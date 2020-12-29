(ns envoy.session
  (:require [clojure.set :as sets]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [envoy.tools :as tools]))

(defn- format-resp [{:keys [body error status opts] :as resp} to-keys?]
  ; (clojure.pprint/pprint resp)
  (if (or error (not= status 200))
    (throw (RuntimeException. (str "could not /GET from consul due to: " (tools/dissoc-in resp [:opts :query-params :token] ))))
    (->> (json/parse-string body true)
         (mapv #(tools/fmk % csk/->kebab-case)))))

(defn- with-ops [ops]
  {:query-params (tools/remove-nils ops)})

(defn- hget
  ([path]
   (hget path {}))
  ([path {:keys [keywordize?] :as ops
          :or {keywordize? true}}]
   (-> @(http/get path
                  (with-ops (dissoc ops :keywordize?)))
       (format-resp keywordize?))))

(defn- hput
  ([path v]
   (hput path v {}))
  ([path v ops]
   ;; (println "@(http/put" path (merge {:body v} (with-ops ops)))
   (let [{:keys [status] :as resp} @(http/put path (merge {:body v}
                                                          (with-ops ops)))]
     (if-not (= 200 status)
       (throw (RuntimeException. (str "could not /PUT to consul due to: " (tools/dissoc-in resp [:opts :query-params :token] ))))
       (-> resp
           :body
           (json/parse-string true))))))

(defn- with-version [url]
  ;; it's been v1 since 2014 (inception)
  ;; TODO: fogure out what, if anything can change it later
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
  ([url {:keys [task session-id] :as args} opts]
   (hput (-> url with-version (str "/kv/locks/" task "/.lock?acquire=" session-id))
         (json/generate-string {:acquire session-id})
         opts)))

(defn release-lock
  ([url args]
   (release-lock url args {}))
  ([url {:keys [task session-id] :as args} opts]
   (hput (-> url with-version (str "/kv/locks/" task "/.lock?release=" session-id))
         (json/generate-string {:release session-id})
         opts)))
