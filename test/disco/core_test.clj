(ns disco.core-test
  (:require [clojure.test :refer :all]
            [org.httpkit.server :as httpkit]
            [clojure.java.io :as io]
            [disco.nginx]
            [clj-http.client :as http]
            [compojure.core :refer (GET)]
            [disco.http :refer :all]  
            [disco.service-discovery :refer :all]
            [disco.curator :refer :all])
  (:import [org.apache.curator.framework CuratorFramework]
           [org.apache.curator.test TestingServer]))

(defn nginx-bin
  "Tries to find the nginx binary"
  []
  (some (fn [p]
          (when (.exists (io/file p))
            p))
        ["/usr/local/bin/nginx"
         "/usr/sbin/nginx"]))

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
                          (str "success! [" name " on " port "]") )
                     (ring-probe-middleware)
                     (ring-latency-middleware latency))
                 {:port port})]
    (register-service discovery si)
    (fn cleanup []
      (unregister-service discovery si)
      (server))))

(defn create-test-sd
  []
  (let [test-service (TestingServer. 2181)
        curator (make-curator (.getConnectString test-service))
        sd (make-service-discovery curator "/sd")
        s1 (serve sd "blammo" 2222 0)
        s2 (serve sd "blammo" 2223 0)
        s3 (serve sd "blammo" 2224 500)
        s4 (serve sd "frob" 2225 0)]
    {:zk test-service
     :curator curator
     :sd sd
     :services [s1 s2 s3 s4]}))

(defn close-test-sd
  [{:keys [curator services sd zk]}]
  (doseq [s services] (s))
  (close curator)
  (close zk))

;;;
;;; This comment provides all you need to use the repl to control disco
;
(comment
  (let [s (create-test-sd)]
    (def sd (:sd s))
    (def services s))

  (def frob2 (serve sd "frob" 2226 0))
  (frob2)

  (close-test-sd services)

  (def nginx-conf (.getAbsolutePath (io/file "nginx.conf")))

  (spit nginx-conf (doto (render default-template
                                 {:service
                                  [{:name "frobulator"
                                    :path "/frob"
                                    :server [{:address "localhost" :port 2222}
                                             {:address "localhost" :port 2223}]}]}) println))

  (def nginx
    (disco.nginx/run-nginx
      sd
      disco.nginx/default-template
      {:frob {:path "/"}}
      nginx-conf
      (nginx-bin)))

  (nginx)

  )

(deftest sd-test
  (let [{:keys [sd] :as services} (create-test-sd)]

    (try
      (Thread/sleep 250)

      (is (= 3 (count (service-members sd "blammo"))))
      (is (= 1 (count (service-members sd "frob"))))

      (with-probe-middleware
        (is (re-find #"^success!" (:body (http/get
                                           "disco://frob/test"
                                           {:discovery sd
                                            :max-concurrent-probes 2}))))

        (is (re-find #"^success!" (:body (http/get
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
          (is (= 0 (get @port 2224 0))))

        (let [nginx-conf (.getAbsolutePath (io/file "nginx.conf"))
              nginx
              (disco.nginx/run-nginx
                sd
                disco.nginx/default-template
                {:frob {:path "/"}}
                nginx-conf
                (nginx-bin))
              proc-builder (doto (ProcessBuilder. [(nginx-bin) "-c" nginx-conf])
                             (.redirectErrorStream true))
              proc (.start proc-builder)
              num-uniq (fn []
                         (let [a (atom #{})]
                           (dotimes [i 10]
                             (swap! a conj
                                    (:body (http/get
                                             "http://localhost:10000/test"))))
                           @a))
              bin (java.io.BufferedReader.
                    (java.io.InputStreamReader.
                      (.getInputStream proc)))]
          (doto (Thread.
                  (fn []
                    (while true
                      (when-let [l (.readLine bin)]
                        (println l))
                      (Thread/sleep 100))))
            (.setDaemon true)
            (.start))
          (try
            (Thread/sleep 250)
            (is (= 1 (count (num-uniq))))
            (let [s' (serve sd "frob" 2226 0)]
              (Thread/sleep 50)
              (is (= 2 (count (num-uniq))))
              (s'))
            (Thread/sleep 50)
            (is (= 1 (count (num-uniq))))
            (finally
              (.destroy proc)
              (nginx)))))
      (finally
        (close-test-sd services)))))
