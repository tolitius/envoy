(ns envoy.core
  (:require [cheshire.core :as json]
            [clojure.core.async :refer [go-loop go <! >! >!! alt! chan]]
            [org.httpkit.client :as http]
            [envoy.tools :refer [map->props props->map]])
  (:import [javax.xml.bind DatatypeConverter]))

(defn- recurse [path]
  (str path "?recurse"))

(defn- index-of [resp]
  (-> resp
      :headers
      :x-consul-index))

(defn- read-index [path]
  (-> (http/get path)
      index-of))

(defn- fromBase64 [s]
  (String. (DatatypeConverter/parseBase64Binary s)))

(defn- read-values 
  ([resp]
   (read-values resp true))
  ([{:keys [body]} to-keys?]
   (into {}
         (for [{:keys [Key Value]} (json/parse-string body true)]
           [(if to-keys? (keyword Key) Key)
            (when Value (fromBase64 Value))]))))

(defn put [path v]
  @(http/put path {:body v}))

(defn delete [path]
  @(http/delete (recurse path)))

(defn get-all [path & {:keys [keywordize?]
                       :or {keywordize? true}}]
  (-> @(http/get (recurse path))
      (read-values keywordize?)))

(defn- start-watcher [path fun stop?]
  (let [ch (chan)]
    (go-loop [index (read-index path)]
      (http/get path
                {:query-params {:index index}}
                #(>!! ch %))
      (alt!
        stop? ([_]
               (prn "stopping" path "watcher"))
        ch ([resp] 
            (fun (read-values resp))
            (recur (index-of resp)))))))

(defprotocol Stoppable
  (stop [this]))

(deftype Watcher [ch]
  Stoppable
  (stop [_]
    (>!! ch :done)))

(defn watch-path [path fun]
  (let [stop-ch (chan)]
    (start-watcher path fun stop-ch)
    (Watcher. stop-ch)))

(defn map->consul [kv-path m]
  (doseq [[k v] (map->props m)]
    (put (str kv-path "/" k) (str v))))

(defn consul->map [path]
  (->> (partial get-all path :keywordize? false)
       props->map))
