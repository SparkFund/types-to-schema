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

