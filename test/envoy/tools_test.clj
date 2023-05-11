(ns envoy.tools-test
  (:require [envoy.tools :as tools]
            [clojure.test :refer [deftest testing is]]))

(deftest ^:allow-explicit-nils-values
  allow-explicit-nil-values
  (let [case1 "Remove nil values and include explicit nil values"]
    (println case1)
    (testing case1
      (let [props {"a" nil "b" "I have value"}
            mp    (tools/props->map (constantly props) :edn)]
        (println "Props sent: " props)
        (println "Processed result: " mp) 
        (is (some? (:b mp))
        (is (nil? (:a mp)))))
  (let [case2 "Explicitly have nil values which are case-insensitive"]
    (println case2)
    (testing case2
      (let [props {"a" "some-value" "b" "NIL" "c" "nil" "d" "Nil"}
            mp    (tools/props->map (constantly props) :edn)]
        (println "Props sent: " props)
        (println "Processed result: " mp) 
        (is (some? (:a mp))
        (is (every? nil? (-> (select-keys mp #{:b :c :d})
                              vals))))))))))
