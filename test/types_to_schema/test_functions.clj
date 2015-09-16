(ns types-to-schema.test-functions
  (:require [clojure.core.typed :as t]))

(t/defalias TestAlias '{:test t/Keyword})

(t/defalias TestRecursiveAlias (t/U nil (t/List TestRecursiveAlias)))

(def function-calls (atom 0))

(t/ann ^:no-check broken-input [TestAlias Long -> Long])
(defn broken-input
  "Used for when the function doesn't require a correct input but will not pass the schema checker"
  [a b]
  (swap! function-calls inc)
  a)

(t/ann ^:no-check broken-required-input [Long Long -> Long])
(defn broken-required-input
  "Used for when the function being used also requires a correct input"
  [a b]
  (swap! function-calls inc)
  (+ a b))

(t/ann ^:no-check broken-output [Long Long -> Number])
(defn broken-output
  "Used when the function gives an output different than what's being used in the type annotation"
  [a b]
  (swap! function-calls inc)
  "Please break")

(t/ann ^:no-check good-fn1 [Long -> Long])
(defn good-fn1
  [a]
  a)

(t/ann ^:no-check good-fn2 [Long Long -> Long])
(defn good-fn2
  [a b]
  b)

(t/ann ^:no-check rest-arg [Long Long * -> Number])
(defn rest-arg [a & rest]
  (let [a (+ 2 3)]
    a)
  (reduce + a rest))

(t/ann ^:no-check multi-arity
       (t/Fn [Long -> Long]
             [Long Long Long * -> Long] ;; putting these out of natural order to be difficult on ourselves.
             [Long Long -> Long]
             ))
(defn multi-arity
  ([a] 1)
  ([a b] 2)
  ([a b & rest] (last rest)))

(t/ann ^:no-check multi-arity2
       (t/Fn [-> Long]
             [Long Long -> Long]))
(defn multi-arity2
  ([] 0)
  ([a b] b))
