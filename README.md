# types-to-schema

Converts Typed Clojure types to Prismatic Schemas for runtime checking

## Usage

The main use case of this type to schema checker is in test files. You can assert that all test functions are using the types specified by core.typed like so:
```
(require '[spark.util.types-to-schema :as tts])
(use-fixtures :once (tts/wrap-namespaces-fixture '[spark.logic.funding]))
```
For all namespaces in the vector, types to schema will then assert that these functions are receiving correctly typed input and giving correctly typed output.

Another use would be to create a Prismatic Schema for a core type like so:
```
(schema Number)
;=> java.lang.Number
```
You can then do validation on clojure values and variables like so:
```
(require '[schema.core :as s])
(s/validate (schema Number) 1)
;=> 1
```
## License

Copyright © 2015 

Distributed under the Apache License, Version 2.0.
