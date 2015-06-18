(ns types-to-schema.unwrapped-functions
  (:require [clojure.core.typed :as t]))

(t/ann ^:no-check ignores-arguments [Long Long -> Number])
(defn ignores-arguments
  "A function that outputs a type that disagrees with its annotation"
  [a b]
  "This should break")
