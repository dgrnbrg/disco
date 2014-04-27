(ns disco.predicate-dispatch-test
  (:require [disco.predicate-dispatch :refer [defimpl defpred]]
            [clojure.test :refer :all]))

(defpred do-stuff)

(deftest pred-function
  (defimpl do-stuff ([x] (string? x)) ([x] (str "lol a string " x)))
  (defimpl do-stuff ([x] (integer? x)) ([x] (+ x 10)))
  (defimpl do-stuff
    ([& args] true)
    (([x y] "2 args")
     ([x y z] "3 args")))

  (is (= 13 (do-stuff 3)))
  (is (= "lol a string fof" (do-stuff "fof")))
  (is (= "2 args" (do-stuff 1 2)))
  (is (= "3 args" (do-stuff 1 2 3)))
  )
