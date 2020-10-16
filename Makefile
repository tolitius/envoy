.PHONY: all clean jar tag

clean:
	rm -rf target

jar: pom.xml tag
	clojure -M:jar

outdated:
	clojure -M:outdated

tag:
	clojure -M:tag

pom.xml:
	clojure -Spom

tree: pom.xml
	mvn dependency:tree

repl:
	clojure -A:dev -A:repl
