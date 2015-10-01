(defproject types-to-schema "0.1.3"
  :description "Converts Typed Clojure types to Prismatic Schemas for runtime checking"
  :url "https://github.com/SparkFund/types-to-schema"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.typed "0.2.77"]
                 [prismatic/schema "0.2.4"]])
