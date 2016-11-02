(ns envoy.tools
  (:require [clojure.string :as s]))

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
