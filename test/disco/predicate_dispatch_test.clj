(ns disco.predicate-dispatch-test
  (:require [disco.predicate-dispatch :refer [defimpl defpred]]
            [clojure.test :refer :all]))

(defpred do-stuff)

(deftest pred-function
  (defimpl do-stuff ([x] (string? x)) ([x] (str "lol a string " x)))
  (defimpl do-stuff ([x] (integer? x)) ([x] (+ x 10)))
  (defimpl do-stuff
    (([x y] true)
     ([x y z] true))
    (([x y] "2 args")
     ([x y z] "3 args")))

  (is (= 13 (do-stuff 3)))
  (is (= "lol a string fof" (do-stuff "fof")))
  (is (= "2 args" (do-stuff 1 2)))
  (is (= "3 args" (do-stuff 1 2 3)))
  (let [h (atom nil)]
    (try
        (= "3 args" (do-stuff 1 2 3 4))
        (is false "shouldn't get here")
        (catch clojure.lang.ExceptionInfo e
          (reset! h (= [1 2 3 4] (:args (ex-data e))))))
    (is @h)))
