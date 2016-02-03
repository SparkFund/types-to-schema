(ns types-to-schema.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.typed :as t]
            [schema.core :as s]
            [types-to-schema.core :refer :all :as tts]
            [types-to-schema.test-functions :refer :all]
            [types-to-schema.unwrapped-functions :refer :all]))

(use-fixtures :once (wrap-namespaces-fixture '[types-to-schema.test-functions]))

(deftest test-schema
  (is (= 3 (s/validate (schema Long) 3))
      "tests that schema works on longs")
  (is (= "Test" (s/validate (schema String) "Test"))
      "tests that schema works for strings")
  (is (= '{:test :testing-once} (s/validate (schema TestAlias) '{:test :testing-once}))
      "tests that schema works for aliases")
  (is (= (list (list nil)) (s/validate (schema TestRecursiveAlias) (list (list nil))))
      "tests that recursive types work")
  (is (= '{:test :testing-hmap} (s/validate (schema '{:test t/Keyword}) '{:test :testing-hmap})))
  (is (= '["hey" 12] (s/validate (schema '[String Number]) '["hey" 12])))
  (is (thrown? Exception (s/validate (schema '[String Number]) '("hey" 12))))
  (is (= '["hey" 12] (s/validate (schema (t/HSequential [t/Str t/Int]))
                                 '["hey" 12])))
  (is (= '["hey" 12] (s/validate (schema (t/HSeq [t/Str t/Int]))
                                 '["hey" 12])))
  (is (nil? (s/check (schema (t/NonEmptyLazySeq t/Int)) (map inc (range)))))
  (is (nil? (s/check (schema (t/HMap :absent-keys #{:foo :bar})) {:not "foo"})))
  (is (not (nil? (s/check (schema (t/HMap :absent-keys #{:foo :bar})) {:foo "foo"})))))

(deftest test-wrapped-functions
  (let [counter @function-calls]
    (is (thrown? clojure.lang.ExceptionInfo (broken-input 5 6)))
    (is (= counter @function-calls))
    (is (thrown? clojure.lang.ExceptionInfo (broken-required-input "Break Please" 6)))
    (is (= counter @function-calls))
    (is (thrown? clojure.lang.ExceptionInfo (broken-output 1 6)))
    (is (= (inc counter) @function-calls))))

(deftest test-arity
  (is (= (good-fn1 1)   1))
  (is (= (good-fn2 1 2) 2))
  (is (thrown? clojure.lang.ExceptionInfo (good-fn1 1 2)))
  (is (thrown? clojure.lang.ExceptionInfo (good-fn2 1)))
  (is (thrown? clojure.lang.ExceptionInfo (good-fn2 1 2 3)))
  (is (thrown? clojure.lang.ExceptionInfo (rest-arg))))

(deftest test-rest-args
  (is (= 6 (rest-arg 0 1 2 3)) "wrapped rest args give expected output")
  (is (thrown? clojure.lang.ExceptionInfo (rest-arg 0 1 "2")) "wrapped rest args check schema"))

(deftest test-multi-arity
  (is (= 1 (multi-arity 0)))
  (is (= 2 (multi-arity 0 0)))
  (is (= 3 (multi-arity 1 2 3)))
  (is (= 0 (multi-arity2)) "Works without a rest vararg too")
  (is (= 2 (multi-arity2 1 2)) "Works without a rest vararg too")
  (is (thrown? clojure.lang.ExceptionInfo (multi-arity 1 "string")))
  (is (thrown? clojure.lang.ExceptionInfo (multi-arity 1 2 "string")))
  (is (thrown? clojure.lang.ExceptionInfo (multi-arity))))

(deftest wrap-and-unwrap
  (let [_ (wrap-namespaces! '[types-to-schema.unwrapped-functions])]
    (is (thrown? clojure.lang.ExceptionInfo (ignores-arguments 5 6))
        "tests that it won't allow you to use a function that goes against its type")
    (is (do (unwrap-namespaces! '[types-to-schema.unwrapped-functions])
            (= "This should break" (ignores-arguments 5 6)))
        "tests that after unwrapping the function, you can now use this function")))
