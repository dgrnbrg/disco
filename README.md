# Disco

A Clojure framework for managing the complexitiies of Service Oriented Architectures, including scaling and deployment.

## Microservices

Disco's microservices are easy to use! You can define a microservice using `defservicefn`, which is almost
exactly like `defn`. The different is that a `defservicefn` requires an execution context and returns a
Listenable Future, which you can `deref` or add a listener to, via `disco.microservices/add-listener`. Let's
take a closer look.

### Local calls

```clojure
(require '[disco.microservices :as m])

(m/defservicefn product
 [x y z]
 (* x y z))

(binding [m/*local* true]
  (let [result (product 1 2 3)]
    (m/add-listener result #(println "Got a result!"))
    @result))
```

Here we can see how to define a servicefn (exactly like a regular `fn`), and how to call it. We can
tell it to use the `*local*` execution context via the `binding`. If we do not provide a context, it
will throw an exception.

### Remote calls

Now, what if we want make a servicefn call on another server?

```clojure
(def app (-> (fn [req] {:status 500 :body ""})
             (m/make-ring-handler "/methods" [#'product])))

(binding [m/*remote* "http://uri/to/app/methods/"]
  (let [result (product 1 2 3)]
    (m/add-listener result #(println "Got a result!"))
    @result))
```

`app` is a ring handler, that is automatically generated just by passing a collection of servicefn vars.
In order to call functions remotely, just bind `*remote*` to the uri that the `app` is running on. The
system has built-in retries, so that if your remote uri is a load balancer, it'll retry in case a single
node is down.

### Automatic service discovery

Servicefns are even easier to use than that, however. Discovery and connections can all be automatically handled
by ZooKeeper
with an instance of the Apache Curator `ServiceDiscovery`. This can be created by `disco.service-discovery/make-service-discovery`.
To make a `ServiceDiscovery`, you need a `CuratorFramework`, which can be created by
`disco.curator/make-curator`.

```clojure
(m/serve-ns service-discovery 'ns.with.services)

(binding [m/*remote* service-discovery]
  (let [result (product 1 2 3)]
    (m/add-listener result #(println "Got a result!"))
    @result))
```

Just remember, none of this require additional code!

### Async servicefns

Servicefns can be asynchronous, too. All you need to do is to make them as `^:async`, and make
sure that they return a listenable future. You can create a listenable future than you can deliver
the result to (like a `promise`) by using `disco.microservices/make-listenable-future` and
`disco.microservices/deliver-result`. You can convert a core.async channel into a listenable
future by calling `disco.microservices/async-chan->listenable-future`. You can convert anything
that implements IDeref (such as `future`s and `promise`s) into a listenable future by
calling `disco.microservices/ideref->listenable-future`.

```clojure
(m/defservicefn ^:async product
  [x y z]
  (-> (clojure.core.async/go (* x y z))
      (m/async-chan->listenable-future)))

;; or

(m/defservicefn ^:async product
  [x y z]
  (let [p (m/make-listenable-future)]
    (m/deliver-result p (* x y z))
    p))
```

By using async servicefns, you'll be able to achieve ever higher levels of scalability.

## Architecture

Disco has several interesting features so far:

* `disco.service-discovery` - a wrapper around curator-x-service-discovery, a powerful zookeeper library
* `disco.http` - a pair of ring & clj-http middleware that add a service discovery protocol, `disco://`, to your application. It uses a port of the [sparrow](http://people.csail.mit.edu/matei/papers/2013/sosp_sparrow.pdf) distributed scheduling algorithm to minimize latency without require a centralized queue or broker.
* `disco.nginx` - automatically keeps an nginx server's upstream servers matching exactly what is live in your cluster
* `disco.app-server` - runs all your apps, optionally using `disco.http`, on your cluster according to your desired configuration. Uses classloaders for true isolalation.


All the features include either unit tests or demos. You must install nginx and point disco to it yourself to get `disco.nginx` to work.


## License

Copyright © 2014 David Greenberg

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
