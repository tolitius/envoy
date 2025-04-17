(ns envoy.session
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [envoy.tools :as tools]
            [org.httpkit.client :as http]))

(defn error-message [{:keys [error status]}]
  (or (some-> error (.getMessage))
      (str "response status was " status)))

(defn- hget
  ([path]
   (hget path {}))
  ([path ops]
   (let [{:keys [body error status] :as resp} @(http/get path
                                                         (tools/with-bearer-auth ops))]
     (if (or error (not= status 200))
       (throw (ex-info (str "could not GET from consul due to: " (error-message resp))
                       (tools/dissoc-in resp [:opts :query-params :token])
                       error))
       (->> (json/parse-string body true)
            (mapv #(tools/fmk % csk/->kebab-case)))))))

(defn- hput
  ([path v]
   (hput path v {}))
  ([path v ops]
   (let [{:keys [error status] :as resp} @(http/put path (merge {:body v}
                                                                (tools/with-bearer-auth ops)))]
     (if (or error (not= 200 status))
       (throw (ex-info (str "could not PUT to consul due to: " (error-message resp))
                       (tools/dissoc-in resp [:opts :query-params :token])
                       error))
       (-> resp
           :body
           (json/parse-string true))))))

(defn- with-version [url]
  ;; it's been v1 since 2014 (inception)
  ;; TODO: figure out what, if anything can change it later
  (tools/concat-with-slash url "v1"))


;; session /PUTs

(defn create-session
  ([url args]
   (create-session url args {}))
  ([url args opts]
   (-> (hput (-> url with-version (str "/session/create"))
            (-> args
                (tools/fmk csk/->camelCase)
                json/generate-string)
            opts)
       (tools/fmk csk/->kebab-case))))

(defn delete-session
  ([url args]
   (delete-session url args {}))
  ([url {:keys [uuid] :as args} opts]
   (hput (-> url with-version (str "/session/destroy/" uuid))
         (json/generate-string args)
         opts)))

(defn renew-session
  ([url args]
   (renew-session url args {}))
  ([url {:keys [uuid] :as args} opts]
   (->> (hput (-> url with-version (str "/session/renew/" uuid))
              (json/generate-string args)
              opts)
        (mapv #(tools/fmk % csk/->kebab-case)))))


;; session /GETs

(defn read-session
  ([url args]
   (read-session url args {}))
  ([url {:keys [uuid] :as args} opts]
   (->> (hget (-> url with-version (str "/session/info/" uuid))
              (merge args opts)))))

(defn list-node-session
  ([url args]
   (list-node-session url args {}))
  ([url {:keys [node] :as args} opts]
   (->> (hget (-> url with-version (str "/session/node/" node))
              (merge args opts)))))

(defn list-sessions
  ([url args]
   (list-sessions url args {}))
  ([url args opts]
   (->> (hget (-> url with-version (str "/session/list"))
              (merge args opts)))))


;; lock

(defn acquire-lock
  ([url args]
   (acquire-lock url args {}))
  ([url {:keys [path task session-id]
         :or {path "locks"}} opts]
   (hput (-> url with-version (str "/kv/" path "/" task "/.lock?acquire=" session-id))
         (json/generate-string {:acquire session-id})
         opts)))

(defn release-lock
  ([url args]
   (release-lock url args {}))
  ([url {:keys [path task session-id]
         :or {path "locks"}} opts]
   (hput (-> url with-version (str "/kv/" path "/" task "/.lock?release=" session-id))
         (json/generate-string {:release session-id})
         opts)))
