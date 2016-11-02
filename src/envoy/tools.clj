(ns envoy.tools
  (:require [clojure.string :as s]
            [clojure.edn :as edn]))

(defn key->prop [k]
  (-> k 
      name 
      (s/replace "-" "_")))

(defn link [connect from [to value]]
  (let [to (key->prop to)]
    [(str from connect to) value]))

(defn- map->flat [m key->x connect]
  (reduce-kv (fn [path k v] 
               (if (map? v)
                 (concat (map (partial link connect (key->x k))
                              (map->flat v key->x connect))
                         path)
                 (conj path [(key->x k) v])))
             [] m))

(defn map->props [m]
  (map->flat m key->prop "/"))

(defn- str->value [v]
  "consul values are strings. str->value will convert:
  the numbers to longs, the alphanumeric values to strings, and will use Clojure reader for the rest
  in case reader can't read OR it reads a symbol, the value will be returned as is (a string)"
  (cond
    (re-matches #"[0-9]+" v) (Long/parseLong v)
    (re-matches #"^(true|false)$" v) (Boolean/parseBoolean v)
    (re-matches #"\w+" v) v
    :else
    (try 
      (let [parsed (edn/read-string v)]
        (if (symbol? parsed)
          v
          parsed))
         (catch Throwable _
           v))))

(defn- key->path [k level]
  (as-> k $
        (s/lower-case $)
        (s/split $ level)
        (map keyword $)))

(defn- sys->map [sys]
  (reduce (fn [m [k-path v]]
            (assoc-in m k-path v)) {} sys))

(defn props->map [read-from-consul]
  (->> (for [[k v] (read-from-consul)]
          [(key->path k #"/")
           (str->value v)])
       sys->map))
