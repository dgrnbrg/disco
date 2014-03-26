(ns disco.sample-service)

(defn start
  [cfg]
  (println "Welcome to my cool service!" cfg))

(defn reload 
  [cfg]
  (println "sweet reload brah"))

(defn stop
  []
  (println "bye bye!"))
