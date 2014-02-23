(ns soa.core-test
  (:require [clojure.test :refer :all]
            [org.httpkit.server :as httpkit]
            [clj-http.client :as http]
            [compojure.core :refer (GET)]
            [soa.http :refer :all]  
            [soa.service-discovery :refer :all])
  (:import [org.apache.curator.framework CuratorFramework]
           [org.apache.curator.test TestingServer]))

(defn ring-latency-middleware
  "Middleware for simulating a slow server"
  [h latency]
  (fn [req]
    (try
      (Thread/sleep latency)
      (catch InterruptedException _))
    (h req)))

(defn serve
  [discovery name port latency]
  (let [si (service-instance :name name
                             :address "localhost"
                             :port port)
        server (httpkit/run-server
                 (-> (GET "/test" []
                          "success!")
                     (ring-probe-middleware)
                     (ring-latency-middleware latency))
                 {:port port})]
    (register-service discovery si)
    (fn cleanup []
      (unregister-service discovery si)
      (server))))

(deftest sd-test
  (let [test-service (TestingServer. 2181)
        curator (make-curator (.getConnectString test-service))
        sd (make-service-discovery curator "/sd")
        s1 (serve sd "blammo" 2222 0)
        s2 (serve sd "blammo" 2223 0)
        s3 (serve sd "blammo" 2224 500)
        s4 (serve sd "frob" 2225 0)]

    (try
      (Thread/sleep 250)

      (is (= 3 (count (service-members sd "blammo"))))
      (is (= 1 (count (service-members sd "frob"))))

      (with-probe-middleware
        (is (= "success!" (:body (http/get
                                   "disco://frob/test"
                                   {:discovery sd
                                    :max-concurrent-probes 2}))))

        (is (= "success!" (:body (http/get
                                   "disco://blammo/test"
                                   {:discovery sd
                                    :max-concurrent-probes 2}))))
        
        (let [port (atom {})]
          (dotimes [i 100]
            (swap! port update-in [(-> (http/get
                                         "disco://blammo/test"
                                         {:discovery sd
                                          :max-concurrent-probes 2})
                                       (:chosen-url)
                                       (clj-http.client/parse-url)
                                       (:server-port))]
                   (fnil inc 0)))
          ;; Test load balancing and routing around slow endpoint
          (is (>= (get @port 2222) 20))
          (is (>= (get @port 2223) 20))
          (is (= 0 (get @port 2224 0)))))

      (finally
        (s1)
        (s2)
        (s3)
        (s4)
        (.close ^CuratorFramework curator)
        (.stop test-service)))))
