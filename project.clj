(defproject types-to-schema "0.1.5"
  :description "Converts Typed Clojure types to Prismatic Schemas for runtime checking"
  :url "https://github.com/SparkFund/types-to-schema"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]

                 [org.clojure/core.typed "0.3.23"
                  :exclusions [org.clojure/clojure
                               org.clojure/tools.reader]]

                 ;; pull in tools.reader manually as core.typed 0.3.23 asks for
                 ;; conflicting 0.9.2 and 1.0.0-alpha1
                 [org.clojure/tools.reader "0.9.2"]

                 [prismatic/schema "0.2.4"]])
