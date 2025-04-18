# 0.1.33

* update auth to support headers (for new'er consul releases [docs](https://developer.hashicorp.com/consul/api-docs/v1.20.x/api-structure#authentication))
* make it babashka compatible

# 0.1.32

* refactor `envoy.watcher` into a separate namespace _([#13](https://github.com/tolitius/envoy/pull/13) thanks to [@VigneshwaranJheyaraman](https://github.com/VigneshwaranJheyaraman))_

# 0.1.31

* update deps versions:

```clojure
 :deps {cheshire/cheshire {:mvn/version "5.12.0"}
        camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}
        org.clojure/core.async {:mvn/version "1.6.681"}
        http-kit/http-kit {:mvn/version "2.7.0"}}
```

# 0.1.29

* remove slash when `?recurse` _([f480eb2](https://github.com/tolitius/envoy/pull/12/commits/f480eb24542c9455fc973bdc904eeffe7e86f27f) thanks to [@sirmspencer](https://github.com/sirmspencer))_

# 0.1.28

* support explicit "`nil`" values _([f18fd5a](https://github.com/tolitius/envoy/commit/f18fd5a6abb7301dade060e43add128f87907f30) thanks to [@VigneshwaranJheyaraman](https://github.com/VigneshwaranJheyaraman))_
