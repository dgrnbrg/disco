(defproject disco "0.1.0-SNAPSHOT"
  :description "A framework for deploying a cloud-based service discovery architecture"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :warn-on-reflection true
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [http-kit "2.1.6"]
                 [ring "1.2.1"]
                 [compojure "1.1.6"]
                 [clj-http "0.7.9"]
                 [org.flatland/classlojure "0.7.1"]
                 [de.ubercode.clostache/clostache "1.3.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.apache.curator/curator-test "2.4.0"]  
                 [org.apache.curator/curator-x-discovery "2.4.0"]
                 [com.datomic/datomic-free "0.9.4572"]])
