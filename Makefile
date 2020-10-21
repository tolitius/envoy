.PHONY: clean jar tag outdated install deploy tree repl

clean:
	rm -rf target

jar: pom.xml tag
	clojure -M:jar

outdated:
	clojure -M:outdated

tag:
	clojure -M:tag

pom.xml: deps.edn
	clojure -Spom

install: jar
	clojure -M:install

deploy: jar
	clojure -M:deploy

tree: pom.xml
	mvn dependency:tree

repl:
	clojure -A:dev -A:repl
