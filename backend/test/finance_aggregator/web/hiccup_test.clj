(ns finance-aggregator.web.hiccup-test
  "Tests for the Datastar/Replicant hiccup helpers. These cover the sharp edges
   the migration learned the hard way (attribute quoting, signal serialization,
   signal reading)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.hiccup :as h]))

(deftest a-coerces-string-keys
  (testing "string keys become keywords (so colon-bearing Datastar attrs render)"
    (is (= {:data-on:click "$x++"} (h/a {"data-on:click" "$x++"})))
    (is (= {(keyword "data-bind:foo") "bar"} (h/a {"data-bind:foo" "bar"}))))
  (testing "non-string keys pass through untouched"
    (is (= {:class "c" :id "i"} (h/a {:class "c" :id "i"})))
    (is (= {:class "c" :data-text "$n"} (h/a {:class "c" "data-text" "$n"})))))

(deftest js-str-escapes
  (testing "wraps in single quotes"
    (is (= "'hello'" (h/js-str "hello"))))
  (testing "escapes embedded single quotes and backslashes"
    (is (= "'Trader Joe\\'s'" (h/js-str "Trader Joe's")))
    (is (= "'a\\\\b'" (h/js-str "a\\b"))))
  (testing "coerces non-strings"
    (is (= "'42'" (h/js-str 42)))))

(deftest signals-serializes-to-js-object-literal
  (testing "flat map"
    (is (= "{count: 0, search: ''}" (h/signals {:count 0 :search ""}))))
  (testing "nested maps and booleans"
    (is (= "{reviewed: {tx1: false, tx2: true}}"
           (h/signals {:reviewed {:tx1 false :tx2 true}}))))
  (testing "vectors and nil"
    (is (= "{acct: [], x: null}" (h/signals {:acct [] :x nil}))))
  (testing "string values are escaped, not double-quoted"
    (is (= "{q: 'Trader Joe\\'s'}" (h/signals {:q "Trader Joe's"})))))

(deftest render-page-prepends-doctype
  (let [out (h/render-page [:html [:body "hi"]])]
    (is (clojure.string/starts-with? out "<!DOCTYPE html>"))
    (is (clojure.string/includes? out "<body>hi</body>"))))

(deftest read-signals-from-body-params
  (testing "POST/PUT: signals come from middleware-parsed :body-params"
    (is (= {:count 5} (h/read-signals {:request-method :post :body-params {:count 5}})))))

(deftest read-signals-from-query-param
  (testing "GET/DELETE: signals come from the datastar query param"
    (is (= {:count 7}
           (h/read-signals {:request-method :get
                            :query-params {"datastar" "{\"count\":7}"}}))))
  (testing "nil when neither present"
    (is (nil? (h/read-signals {:request-method :get :query-params {}})))))
