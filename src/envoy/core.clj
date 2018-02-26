(ns envoy.core
  (:require [cheshire.core :as json]
            [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop go <! >! >!! alt! chan]]
            [org.httpkit.client :as http]
            [envoy.tools :as tools])
  (:import [javax.xml.bind DatatypeConverter]))

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

(defn- fromBase64 [s]
  (String. (DatatypeConverter/parseBase64Binary s)))

(defn- read-values
  ([resp]
   (read-values resp true))
  ([{:keys [body]} to-keys?]
   ;; (println "body => " body)
   (into {}
         (for [{:keys [Key Value]} (json/parse-string body true)]
           [(if to-keys? (keyword Key) Key)
            (when Value (fromBase64 Value))]))))

(defn put
  ([path v]
   (put path v {}))
  ([path v ops]
   ;; (println "@(http/put" path (merge {:body v} (with-ops ops)))
   @(http/put path (merge {:body v} (with-ops ops)))))

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

(defn- mk-ops
    [ops-1 ops-2]
    (cond
        (map? ops-1) [ops-1 ops-2]
        (map? ops-2) [ops-2 ops-1]
        (keyword? ops-1) [{} ops-1]
        (keyword? ops-2) [{} ops-2]
        :else [{} :edn]))

(defn map->consul
  [kv-path m & [ops-1 ops-2]]
   (let [kv-path (tools/without-slash kv-path)
         [ops serializer] (mk-ops ops-1 ops-2)]
     (doseq [[k v] (tools/map->props m serializer)]
       (put (str kv-path "/" k) (str v) ops))))

(defn consul->map
  [path & [ops-1 ops-2]]
   (let [[{:keys [offset] :as ops} serializer] (mk-ops ops-1 ops-2)]
   (-> (partial get-all path
                        (merge ops {:keywordize? false}))
       (tools/props->map serializer)
       (get-in (tools/cpath->kpath offset)))))

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
