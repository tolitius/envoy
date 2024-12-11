(ns envoy.tools
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :as walk]))

(defn key->prop [k]
  (-> k
      name
      ;; (s/replace "-" "_")  ;; TODO: think about whether it is best to simply leave dashes alone
      ))

(defn link [connect from [to value]]
  (let [to (key->prop to)]
    [(str from connect to) value]))

(defn- serialize
    [v serializer]
    (condp = serializer
        :edn (str (vec v))
        :json (json/generate-string (vec v))
        (serialize v :edn)))

(defn serialize-map
    [m & [serializer]]
    (reduce-kv (fn [acc k v]
        (cond
            (map? v) (assoc acc k (serialize-map v serializer))
            (sequential? v) (assoc acc k (serialize v serializer))
            :else (assoc acc k (str v))))
        {} m))

(defn- map->flat [m key->x connect & [serializer]]
  (reduce-kv (fn [path k v]
               (cond
                 (map? v)
                 (concat (map (partial link connect (key->x k))
                              (map->flat v key->x connect serializer))
                         path)
                 (sequential? v) (conj path [(key->x k)
                                             (serialize v serializer)])
                 :else (conj path [(key->x k) v])))
             [] m))

(defn map->props [m & [serializer]]
  (map->flat m key->prop "/" serializer))

(defn- deserialize
  [v serializer]
  (condp = serializer
      :edn (try
            (let [parsed (edn/read-string v)]
                (if (symbol? parsed)
                    v
                    parsed))
            (catch Throwable _ v))
      :json (try
                (json/parse-string-strict v true)
                (catch Throwable _ v))
      (deserialize v :edn)))

(defn- str->value
  "consul values are strings. str->value will convert:
  the numbers to longs, the alphanumeric values to strings, and will use Clojure reader for the rest
  in case reader can't read OR it reads a symbol, the value will be returned as is (a string)"
  [v & [deserializer]]
  (cond
    (nil? v)                         v
    (re-matches #"[0-9]+" v)         (Long/parseLong v)
    (re-matches #"^(true|false)$" v) (Boolean/parseBoolean v)
    (re-matches #"\w+" v)            v
    :else                            (deserialize v deserializer)))

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

(defn remove-empty-keys
  "Recursively remove keys with empty values.

  We use the empty? predicate only, therefore not checking for blank strings."
  [m]
  ;; adapted from https://stackoverflow.com/a/34221816/1888507
  (let [f (fn [x]
            (cond (map? x)
                  (let [kvs (filter (comp not nil? second) x)]
                    (if (empty? kvs) nil (into {} kvs)))
                  (or (set? x) (sequential? x)) (if (empty? x) nil x)
                  :else x))]
    (walk/postwalk f m)))

(defn include-explicit-nils
  "There are certain scnearios, where Configuration from CONSUL should support explicit `nil` values
   Replace explicit `nil` [case insensitive] value with nil"
  [map-with-nils]
  (let [explicit-nil? (fn [val] (some-> val s/lower-case (= "nil")))]
    (into {}
      (map (fn [[ki val-u]]
             [ki (if (explicit-nil? val-u)
	           nil
		   val-u)])
           map-with-nils))))

(defn props->map [read-from-consul & [deserializer]]
  (->> (for [[k v] (-> (read-from-consul)
                       remove-nils
		       include-explicit-nils)]
          [(key->path k #"/")
           (str->value v deserializer)])
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

(defn clean-slash
    [path]
    (s/join "/" (remove #{""} (s/split path #"/"))))

(defn without-slash
  "removes slash from either ':first' or ':last' (default) position
   in case it is there"
  ([path]
   (without-slash path {}))
  ([path {:keys [slash]
          :or {slash :last}}]
    (if-not (= :both slash)
        (let [[find-slash no-slash] (case slash
                                 :last [last drop-last]
                                 :first [first rest]
                                 :not-first-or-last-might-need-to-implement)]
          (if (= (find-slash path) \/)
            (apply str (no-slash path))
            path))
       (clean-slash path))))

(defn concat-with-slash [s1 s2]
  (str (without-slash s1)
       "/"
       (without-slash s2 {:slash :first})))

(defn fmk
  "apply f to each key k of map m"
  [m f]
  (into {}
        (for [[k v] m]
          [(f k) v])))

;; author of "keys-in" is Alex Miller: https://stackoverflow.com/a/21769786/211277
(defn keys-in [m]
  (if (map? m)
    (vec
     (mapcat (fn [[k v]]
               (let [sub (keys-in v)
                     nested (map #(into [k] %) (filter (comp not empty?) sub))]
                 (if (seq nested)
                   nested
                   [[k]])))
             m))
    []))

(defn dissoc-in
  "from https://github.com/clojure/core.incubator
   dissociates an entry from a nested associative structure returning a new
   nested structure. keys is a sequence of keys. Any empty maps that result
   will not be present in the new structure."
  [m [k & ks]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn with-ops [ops]
  (some->> (remove-nils ops)
           not-empty
           (hash-map :query-params)))

(defn recurse [path]
  (str path "?recurse"))

(defn with-auth [{:keys [token]}]
  (when token
    {:headers {"authorization" token}}))

(defn complete-key-path [path offset k]
  (cond-> path
    offset (concat-with-slash offset)
    :always (concat-with-slash (name k))))
