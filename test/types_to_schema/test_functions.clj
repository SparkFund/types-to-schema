(ns types-to-schema.test-functions
  (:require [clojure.core.typed :as t]))

(t/defalias TestAlias '{:test t/Keyword})

(t/defalias TestRecursiveAlias (t/U nil (t/List TestRecursiveAlias)))

(t/ann ^:no-check broken-input [TestAlias Long -> Long])
(defn broken-input
  "Used where the function doesn't require a correct input but will not pass the schema checker"
  [a b]
  a)

