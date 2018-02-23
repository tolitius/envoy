(ns envoy.tools
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.edn :as edn]))

(defn key->prop [k]
  (-> k
      name
      ;; (s/replace "-" "_")  ;; TODO: think about whether it is best to simply leave dashes alone
      ))

(defn link [connect from [to value]]
  (let [to (key->prop to)]
    [(str from connect to) value]))

(defn- map->flat [m key->x connect]
  (reduce-kv (fn [path k v]
               (cond
                 (map? v)
                 (concat (map (partial link connect (key->x k))
                              (map->flat v key->x connect))
                         path)
                 (or (sequential? v)) (conj path
                                                 [(key->x k) (try
                                                                (json/generate-string (vec v))
                                                                (catch Throwable _ v))])
                 :else (conj path [(key->x k) v])))
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
      ;;default json no symbol
      (json/parse-string-strict v true)
         (catch Throwable _
         ;;fallback to edn for compatibility
         (try
           (let [parsed (edn/read-string v)]
             (if (symbol? parsed)
               v
               parsed))
          (catch Throwable _
                v))))))

(defn- key->path [k level]
  (as-> k $
        ;; (s/lower-case $)
        (s/split $ level)
        (remove #{""} $)   ;; in case "/foo/bar" remove the result for the first slash
        (map keyword $)))

(defn- sys->map [sys]
  (reduce (fn [m [k-path v]]
            (assoc-in m k-path v)) {} sys))

(defn cpath->kpath
  "consul path to key path: i.e. \"/foo/bar/baz\" to [:foo :bar :baz]"
  [cpath]
  (if (seq cpath)
    (key->path cpath #"/")
    []))

(defn remove-nils [m]
  (let [remove? (fn [v]
                  (or (nil? v)
                      (= "null" v)))]
    (into {}
          (remove
            (comp remove? second)
            m))))

(defn props->map [read-from-consul]
  (->> (for [[k v] (-> (read-from-consul)
                       remove-nils)]
          [(key->path k #"/")
           (str->value v)])
       sys->map))

;; author of "deep-merge-with" is Chris Chouser: https://github.com/clojure/clojure-contrib/commit/19613025d233b5f445b1dd3460c4128f39218741
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, appling the given fn
  only when there's a non-map at a particular level.
  (deepmerge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
               {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    maps))

(defn merge-maps [& m]
  (apply deep-merge-with
         (fn [_ v] v) m))

(defn nest-map
  "given a prefix in a form of [:a :b :c] and a map, nests this map under
  {:a {:b {:c m}}}"
  [m prefix]
  (reduce (fn [nested level]
            {level nested})
          m
          (reverse prefix)))

(defn with-slash
  "adds a slash to the last position if it is not there"
  [path]
  (let [c (last path)]
    (if (not= c \/)
      (str path "/")
      path)))

(defn without-slash
  "removes slash from either ':first' or ':last' (default) position
   in case it is there"
  ([path]
   (without-slash path {}))
  ([path {:keys [slash]
          :or {slash :last}}]
   (let [[find-slash no-slash] (case slash
                                 :last [last drop-last]
                                 :first [first rest]
                                 :not-first-or-last-might-need-to-implement)]
     (if (= (find-slash path) \/)
       (apply str (no-slash path))
       path))))
