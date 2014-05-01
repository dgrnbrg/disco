(ns disco.microservices-test
  (:use [clojure.test])
  (:require [disco.microservices :as ms]
            [clojure.core.async :as async]
            [disco.curator :as curator]
            [disco.service-discovery :as sd]
            [org.httpkit.server :as server])
  (import [org.apache.curator.test TestingServer]))  

(ms/defservicefn sf-sync
  [x y z]
  (* x y z))

(ms/defservicefn ^:async sf-future
  [x y z]
  (future (* x y z)))

(ms/defservicefn ^:async sf-async
  [x y z]
  (async/go (* x y z)))

(ms/defservicefn ^:async sf-listenable-future
  [x y z]
  (let [lf (ms/make-listenable-future)]
    (ms/deliver-result lf (* x y z))
    lf))

(deftest sync-service-fn
  (let [app (-> (fn [h] {:body "oops" :status 500})
                (ms/make-ring-handler "/methods" [#'sf-sync]))
        http-kit (server/run-server app {:port 8099})]
    (binding [ms/*remote* "http://localhost:8099/methods/"]
      (is (= 14 (deref (sf-sync 1 2 7) 1000 :fail))))
    (http-kit)))

(deftest async-service-fn
  (let [app (-> (fn [h] {:body "oops" :status 500})
                (ms/make-ring-handler "/methods" [#'sf-future
                                                  #'sf-async
                                                  #'sf-listenable-future]))
        http-kit (server/run-server app {:port 8099})]
    (binding [ms/*remote* "http://localhost:8099/methods/"]
      (is (= 14 (deref (sf-future 1 2 7) 1000 :fail)))
      (is (= 14 (deref (sf-async 1 2 7) 1000 :fail))) 
      (is (= 14 (deref (sf-listenable-future 1 2 7) 1000 :fail)))) 
    (http-kit)))

(deftest callbacks
  (let [app (-> (fn [h] {:body "oops" :status 500})
                (ms/make-ring-handler "/methods" [#'sf-sync]))
        http-kit (server/run-server app {:port 8099})]
    (binding [ms/*remote* "http://localhost:8099/methods/"]
      (let [r (sf-sync 2 2 3)
            reads (atom 0)]
        (Thread/sleep 100)
        (ms/add-listener r (fn [] (swap! reads inc)))
        (is (= @reads 1))
        (is (= (deref r 1000 :fail) 12))
        (ms/add-listener r (fn [] (swap! reads inc)))
        (is (= @reads 2))))
    (http-kit)))

(deftest automatic-exporting
  (let [zk (TestingServer. 2181)
        curator (curator/make-curator (.getConnectString zk))
        sd (sd/make-service-discovery curator "/sd")
        server (ms/serve-ns sd 'disco.microservices-test)]
    (binding [ms/*remote* sd]
      (is (= 14 (deref (sf-sync 1 2 7) 1000 :fail))) 
      (is (= 14 (deref (sf-future 1 2 7) 1000 :fail)))
      (is (= 14 (deref (sf-async 1 2 7) 1000 :fail))) 
      (is (= 14 (deref (sf-listenable-future 1 2 7) 1000 :fail))))
    (curator/close curator)
    (curator/close zk)))
