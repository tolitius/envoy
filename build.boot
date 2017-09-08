(def +version+ "0.1.5")

(set-env!
  :source-paths #{"src"}
  :dependencies '[[cheshire "5.8.0" :exclusions [org.clojure/clojure]]
                  [org.clojure/core.async "0.3.443" :exclusions [org.clojure/clojure]]
                  [http-kit "2.2.0"]

                  ;; boot clj
                  [boot/core              "2.6.0"           :scope "provided"]
                  [adzerk/bootlaces       "0.1.13"          :scope "test"]])

(require '[adzerk.bootlaces :refer :all])

(bootlaces! +version+)

(task-options!
  push {:ensure-branch nil}
  pom {:project     'tolitius/envoy
       :version     +version+
       :description "a gentle touch of clojure to hashicorp's consul"
       :url         "https://github.com/tolitius/envoy"
       :scm         {:url "https://github.com/tolitius/envoy"}
       :license     {"Eclipse Public License"
                     "http://www.eclipse.org/legal/epl-v10.html"}})
