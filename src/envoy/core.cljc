(ns envoy.core
  (:require [cheshire.core :as json]
            [clojure.data :refer [diff]]
            [org.httpkit.client :as http]
            [envoy.tools :as tools]
            [clojure.string :as string])
  (:import java.util.Base64))

(defonce ^:private base64-decoder (Base64/getDecoder))

(defn- fromBase64 [^String s]
  (String. (.decode base64-decoder s)))

(defn- timeout-error?
  [error]
  (->> error
       class
       str
       (re-find #"org.httpkit.client.TimeoutException")
       some?))

(defn read-values
  ([resp]
   (read-values resp true))
  ([{:keys [body error status opts]} to-keys?]
   (if (or error (not= status 200))
     (cond
       (= 404 status)
       (throw (ex-info (str "could not find path in consul " (:url opts))
                       {:path (:url opts)}))

       (timeout-error? error)
       (throw (ex-info "connection timed out" {:path (:url opts)
                                               :cause :timeout}
                       error))
       :else
       (throw (ex-info (str "failed to read from consul" (when body (str " (" body ")")))
                       {:path (:url opts)
                        :http-status status}
                       error)))
     (into {}
           (for [{:keys [Key Value]} (json/parse-string body true)]
             [(if to-keys? (keyword Key) Key)
              (when Value (fromBase64 Value))])))))

(defn- find-consul-node [hosts]
     (let [at (atom -1)]
       #(nth hosts (mod (swap! at inc)
                        (count hosts)))))

(defn url-builder
  "Create an envoy kv-path builder"
  [{:keys [hosts port secure?]
    :or {hosts ["localhost"] port 8500 secure? false}}]
  (let [proto (if secure? "https://" "http://")
        consul-node (find-consul-node hosts)]
    (fn [& [path]]
      (let [node (consul-node)]
      (str proto node ":" port "/v1/kv" (when (seq path)
                                          (str "/" (tools/clean-slash path))))))))

(defn put
  ([path v]
   (put path v {}))
  ([path v ops]
   (let [{:keys [error status] :as resp} @(http/put path (merge {:body v}
                                                                (tools/with-ops ops)))]
     (when (or error (not= 200 status))
       (throw (ex-info (str "could not PUT to consul: " path) resp error))))))

(defn delete
  ([path]
   (delete path {}))
  ([path ops]
   @(http/delete (tools/recurse path)
                 (tools/with-ops ops))))

(defn get-all
  ([path]
   (get-all path {}))
  ([path {:keys [keywordize?] :as ops
          :or {keywordize? true}}]
   (-> @(http/get (tools/recurse (tools/without-slash path))
                  (merge (tools/with-ops (dissoc ops :keywordize?))
                         (tools/with-auth ops)))
       (read-values keywordize?))))

(defn strip-offset [xs offset]
  (if-let [stripped (get-in xs (tools/cpath->kpath offset))]
    stripped
    (let [reason (str "this usually happens if both prefix and offset are used, "
                      "for example (envoy/consul->map \"http://localhost:8500/v1/kv/hubble\" {:offset \"mission\"})"
                      "while it should have been (envoy/consul->map \"http://localhost:8500/v1/kv\" {:offset \"/hubble/mission\"})")]
      (throw (ex-info (str "could not remove offset: " reason)
                      {:data xs :offset offset})))))

(defn consul->map
  [path & [{:keys [serializer offset preserve-offset] :or {serializer :edn} :as ops}]]
  (let [full-path (if offset
                    (tools/concat-with-slash path offset)
                    path)
        get-fn    (fn []
                    (get-all
                     full-path
                     (merge
                      (dissoc ops :serializer :offset :preserve-offset)
                      {:keywordize? false})))
        consul-map (tools/props->map get-fn serializer)]
    (if preserve-offset
      consul-map
      (strip-offset consul-map offset))))

(defn- overwrite-with
  [path input-map & [{:keys [offset serializer] :or {serializer :edn} :as ops}]]
  (let [;; make sure we get rid of empty keys (or the diff will be wrong)
        stored-map (tools/remove-empty-keys
                    (try
                      (consul->map path (merge ops {:serializer serializer}))
                      (catch Exception _
                        {})))
        ;;to update correctly seq we need to pre-serialize map
        [to-add to-remove _] (diff (tools/serialize-map input-map serializer)
                                   (tools/serialize-map stored-map serializer))
        side-effect-ops (dissoc ops :serializer :update)]
    ;; Removing has to be done first as "Maps are subdiffed where keys match and values differ" in
    ;; clojure.data/diff. Example:
    ;;
    ;; -- input map   {:kafka {:bootstrap-servers "localhost:9092"}}
    ;; -- store map   {:kafka {:bootstrap-servers "foo"}}
    ;; -- diff        [{:kafka {:bootstrap-servers "localhost:9092"}}
    ;;                 {:kafka {:bootstrap-servers "foo"}}
    ;;                 nil]
    ;; Deleting *after* adding would just remove the key.
    (doseq [[k _] (tools/map->props to-remove serializer)]
      (when (nil? (get-in to-add (tools/cpath->kpath k)))
        (delete (tools/complete-key-path path offset k)
                side-effect-ops)))
    ;; adding
    (doseq [[k v] (tools/map->props to-add serializer)]
      (put (tools/complete-key-path path offset k)
           (str v)
           side-effect-ops))))

(defn map->consul
  [path input-map & [{:keys [offset overwrite? serializer]
                      :or {serializer :edn overwrite? false} :as ops}]]
  (let [path (tools/without-slash path)]
    (if-not overwrite?
      (doseq [[k v] (tools/map->props input-map serializer)]
        (put (tools/complete-key-path path offset k)
             (str v)
             (dissoc ops :serializer :update)))
      (overwrite-with path input-map ops))))

(defn copy
  ([path from to]
   (copy path from to {}))
  ([path from to opts]
   (let [data (consul->map path
                           (merge opts {:offset from}))
         new-map (->> (tools/cpath->kpath to)
                      (tools/nest-map data))]
     (map->consul path
                  new-map
                  opts))))

(defn move
  ([path from to]
   (move path from to {}))
  ([path from to opts]
   (let [dpath (str (tools/with-slash path)
                    (-> (tools/without-slash from {:slash :first})
                        (tools/with-slash)))]
     (copy path from to opts)
     (delete dpath opts))))

(defn merge-with-consul
  ([m path]
   (merge-with-consul m path {}))
  ([m path ops]
   (if-let [consul (consul->map path ops)]
     (tools/merge-maps m consul)
     m)))
