(ns envoy.core
  (:require [cheshire.core :as json]
            [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop go <! >! >!! alt! chan]]
            [org.httpkit.client :as http]
            [envoy.tools :as tools]
            [clojure.string :as string])
  (:import [java.util Base64]))

(defn- recurse [path]
  (str path "?recurse"))

(defn- index-of [resp]
  (-> resp
      :headers
      :x-consul-index))

(defn- with-ops [ops]
  {:query-params (tools/remove-nils ops)})

(defn- read-index
  ([path]
   (read-index path {}))
  ([path ops]
  (-> (http/get path (with-ops ops))
      index-of)))

(defn- fromBase64 [^String s]
  (String. (.decode (Base64/getDecoder) s)))

(defn- read-values
  ([resp]
   (read-values resp true))
  ([{:keys [body error status] :as resp} to-keys?]
   (if (or error (not= status 200))
     (throw (ex-info "failed to read from consul" (select-keys resp [:status :error])))
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
    :or {hosts ["localhost"] port 8500 secure? false}
    :as conf}]
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
   ;; (println "@(http/put" path (merge {:body v} (with-ops ops)))
   (let [{:keys [status] :as resp} @(http/put path (merge {:body v}
                                                             (with-ops ops)))]
     (when-not (= 200 status)
       (throw (RuntimeException. (str "could not PUT to consul due to: " resp)))))))

(defn delete
  ([path]
   (delete path {}))
  ([path ops]
   @(http/delete (recurse path)
                 (with-ops ops))))

(defn get-all
  ([path]
   (get-all path {}))
  ([path {:keys [keywordize?] :as ops
          :or {keywordize? true}}]
   (-> @(http/get (recurse (tools/with-slash path))
                  (with-ops (dissoc ops :keywordize?)))
       (read-values keywordize?))))

(defn- start-watcher
  ([path fun stop?]
   (start-watcher path fun stop? {}))
  ([path fun stop? ops]
   (let [ch (chan)]
     (go-loop [index nil current (get-all path)]
              (http/get path
                        (with-ops (merge ops
                                         {:index (or index (read-index path ops))}))
                        #(>!! ch %))
              (alt!
                stop? ([_]
                       (prn "stopping" path "watcher"))
                ch ([resp]
                    (let [new-idx (index-of resp)
                          new-vs (read-values resp)]
                      (when (and index (not= new-idx index))               ;; first time there is no index
                        (when-let [changes (first (diff new-vs current))]
                          (fun changes)))
                      (recur new-idx new-vs))))))))

(defprotocol Stoppable
  (stop [this]))

(deftype Watcher [ch]
  Stoppable
  (stop [_]
    (>!! ch :done)))

(defn watch-path
  ([path fun]
   (watch-path path fun {}))
  ([path fun ops]
  (let [stop-ch (chan)]
    (start-watcher (recurse path) fun stop-ch ops)
    (Watcher. stop-ch))))

(defn consul->map
  [path & [{:keys [serializer offset preserve-offset] :or {serializer :edn} :as ops}]]
  (let [full-path (if offset
                    (tools/concat-with-slash path offset)
                    path)
        consul-map (-> (partial get-all full-path (merge
                                     (dissoc ops :serializer :offset :preserve-offset)
                                     {:keywordize? false}))
                       (tools/props->map serializer))]
       (if preserve-offset
         consul-map
         (get-in consul-map (tools/cpath->kpath offset)))))

(defn- overwrite-with
    [kv-path m & [{:keys [serializer] :or {serializer :edn} :as ops}]]
    (let [[consul-url sub-path]  (string/split kv-path #"kv" 2)
          update-kv-path (str consul-url "kv")
          kpath (tools/cpath->kpath sub-path)
          stored-map (reduce (fn [acc [k v]]
                               (merge acc (consul->map
                                            (str kv-path "/" (name k))
                                            {:serializer serializer})))
                               {} m)
         ;;to update correctly seq we need to pre-serialize map
          [to-add to-remove _] (diff (tools/serialize-map m serializer)
                                    (tools/serialize-map (get-in stored-map kpath) serializer))]
         ;;add
         (doseq [[k v] (tools/map->props to-add serializer)]
             (put (str kv-path "/" k) (str v) (dissoc ops :serializer :update)))
         ;;remove
         (doseq [[k v] (tools/map->props to-remove serializer)]
            (when (nil? (get-in to-add (tools/cpath->kpath k) nil))
                @(http/delete (str kv-path "/" k))))))

(defn map->consul
  [kv-path m & [{:keys [serializer overwrite?] :or {serializer :edn overwrite? false} :as ops}]]
  (let [kv-path (tools/without-slash kv-path)]
    (if-not overwrite?
       (doseq [[k v] (tools/map->props m serializer)]
          (put (str kv-path "/" k) (str v) (dissoc ops :serializer :update)))
       (overwrite-with kv-path m ops))))

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
