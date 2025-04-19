(ns envoy.test.tools
  (:require [clojure.test :refer :all]
            [envoy.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as s]))

(deftest test-key->prop
  (testing "key->prop function"
    (is (= "foo" (tools/key->prop :foo)))
    (is (= "foo-bar" (tools/key->prop :foo-bar)))
    (is (= "foo-bar" (tools/key->prop 'foo-bar)))))

(deftest test-link
  (testing "link function"
    (is (= ["foo/bar" "value"] (tools/link "/" "foo" [:bar "value"])))
    (is (= ["foo.bar" "value"] (tools/link "." "foo" [:bar "value"])))))

(deftest test-map->props
  (testing "map->props with simple values"
    (is (= [["foo" "bar"]] (tools/map->props {:foo "bar"}))))

  (testing "map->props with nested maps"
    (is (= [["foo/bar" "baz"]]
           (tools/map->props {:foo {:bar "baz"}}))))

  (testing "map->props with multiple keys"
    (is (= #{["foo" "bar"] ["baz" "qux"]}
           (set (tools/map->props {:foo "bar" :baz "qux"})))))

  (testing "map->props with sequences"
    (let [result (tools/map->props {:items [1 2 3]} :edn)]
      (is (= 1 (count result)))
      (is (= "items" (ffirst result)))
      (is (= "[1 2 3]" (second (first result)))))))

(deftest test-str->value
  (testing "str->value conversion"
    (let [convert #'tools/str->value]
      (is (= 42 (convert "42")))
      (is (= true (convert "true")))
      (is (= false (convert "false")))
      (is (= "foo" (convert "foo")))
      (is (= [1 2 3] (convert "[1 2 3]" :edn)))
      (is (= {:a 1} (convert "{:a 1}" :edn))))))

(deftest test-cpath->kpath
  (testing "consul path to key path conversion"
    (is (= [] (tools/cpath->kpath "")))
    (is (= [:foo] (tools/cpath->kpath "foo")))
    (is (= [:foo] (tools/cpath->kpath "/foo")))
    (is (= [:foo :bar] (tools/cpath->kpath "foo/bar")))
    (is (= [:foo :bar] (tools/cpath->kpath "/foo/bar")))
    (is (= [:foo :bar] (tools/cpath->kpath "foo/bar/")))))

(deftest test-remove-nils
  (testing "remove nil values from map"
    (is (= {} (tools/remove-nils {"foo" nil})))
    (is (= {"foo" "bar"} (tools/remove-nils {"foo" "bar" "baz" nil})))
    (is (= {} (tools/remove-nils {"foo" "null"})))
    (is (= {"foo" "bar"} (tools/remove-nils {"foo" "bar" "baz" "null"})))))

(deftest test-include-explicit-nils
  (testing "explicit nil values"
    (is (= {"foo" nil} (tools/include-explicit-nils {"foo" "nil"})))
    (is (= {"foo" nil} (tools/include-explicit-nils {"foo" "NIL"})))
    (is (= {"foo" nil} (tools/include-explicit-nils {"foo" "Nil"})))
    (is (= {"foo" "bar"} (tools/include-explicit-nils {"foo" "bar"})))))

(deftest test-merge-maps
  (testing "simple merge"
    (is (= {:a 1 :b 2} (tools/merge-maps {:a 1} {:b 2}))))

  (testing "overwrite value"
    (is (= {:a 2} (tools/merge-maps {:a 1} {:a 2}))))

  (testing "deep merge"
    (is (= {:a {:b 1 :c 2}}
           (tools/merge-maps {:a {:b 1}} {:a {:c 2}}))))

  (testing "nested override"
    (is (= {:a {:b 2 :c 3}}
           (tools/merge-maps {:a {:b 1 :c 3}} {:a {:b 2}})))))

(deftest test-nest-map
  (testing "nest map under prefix"
    (is (= {:a {:b {:c "value"}}}
           (tools/nest-map "value" [:a :b :c])))
    (is (= {:a "value"}
           (tools/nest-map "value" [:a])))
    (is (= "value"
           (tools/nest-map "value" [])))))

(deftest test-with-slash
  (testing "add slash to path"
    (is (= "foo/" (tools/with-slash "foo")))
    (is (= "foo/" (tools/with-slash "foo/")))
    (is (= "/" (tools/with-slash "")))))

(deftest test-without-slash
  (testing "remove slash from end of path"
    (is (= "foo" (tools/without-slash "foo/")))
    (is (= "foo" (tools/without-slash "foo"))))

  (testing "remove slash from beginning of path"
    (is (= "foo" (tools/without-slash "/foo" {:slash :first})))
    (is (= "foo" (tools/without-slash "foo" {:slash :first}))))

  (testing "clean path with slashes"
    (is (= "foo/bar" (tools/without-slash "/foo/bar/" {:slash :both})))
    (is (= "foo/bar" (tools/without-slash "foo/bar" {:slash :both})))))

(deftest test-concat-with-slash
  (testing "concatenate paths with slash"
    (is (= "foo/bar" (tools/concat-with-slash "foo" "bar")))
    (is (= "foo/bar" (tools/concat-with-slash "foo/" "bar")))
    (is (= "foo/bar" (tools/concat-with-slash "foo" "/bar")))
    (is (= "foo/bar" (tools/concat-with-slash "foo/" "/bar")))))

(deftest test-with-ops
  (testing "basic options"
    (is (= {:query-params {:dc "us-west"}}
           (tools/with-ops {:dc "us-west"}))))

  (testing "token with default auth-as (auto)"
    (let [result (tools/with-ops {:token "secret"})]
      (is (map? result))
      (is (= {"X-Consul-Token" "secret"
              "authorization" "secret"}
             (:headers result)))))

  (testing "token with auth-as :header"
    (let [result (tools/with-ops {:token "secret" :auth-as :header})]
      (is (map? result))
      (is (= {"X-Consul-Token" "secret"}
             (:headers result)))))

  (testing "token with auth-as :query-param"
    (let [result (tools/with-ops {:token "secret" :auth-as :query-param})]
      (is (map? result))
      (is (= {:token "secret"} (:query-params result)))))

  (testing "nil values are removed"
    (is (nil? (:query-params (tools/with-ops {:dc nil}))))))

(deftest test-recurse
  (testing "add recurse parameter to path"
    (is (= "foo?recurse" (tools/recurse "foo")))))

(deftest test-complete-key-path
  (testing "complete key path without offset"
    (is (= "base/key"
           (tools/complete-key-path "base" nil "key"))))

  (testing "complete key path with offset"
    (is (= "base/prefix/key"
           (tools/complete-key-path "base" "prefix" "key")))))
