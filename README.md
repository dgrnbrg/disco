# Disco

A Clojure framework for managing the complexitiies of Service Oriented Architectures, including scaling and deployment.

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
