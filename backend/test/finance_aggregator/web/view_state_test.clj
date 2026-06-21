(ns finance-aggregator.web.view-state-test
  "Tests for the pure view-state codec: query-params ↔ view-state ↔ initial Datastar signals,
   plus the funnel id-set / column-visibility parsing and the $catValue coercion. These map→map
   transforms were previously private view fns where a real lingering bug once hid and escaped
   the suite — they live in a tested namespace now."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.view-state :as vs]))

;; --- Primitive parsers ------------------------------------------------------

(deftest id-set-parsing
  (testing "funnel id strings → set of longs, dropping blanks / non-numerics"
    (is (= #{1 2 3} (vs/->id-set ["1" "2" "3"])))
    (is (= #{7} (vs/->id-set ["" "7" nil])) "blank + nil dropped")
    (is (= #{} (vs/->id-set [])) "empty in, empty out")
    (is (= #{42} (vs/->id-set [42])) "already-numeric coerced too")
    (is (every? #(instance? Long %) (vs/->id-set ["5"])) "elements are longs")))

(deftest csv-param-parsing
  (is (= [] (vs/csv-param {} "fa")) "missing key → empty vector")
  (is (= [] (vs/csv-param {"fa" ""} "fa")) "blank value → empty vector")
  (is (= ["1" "2" "3"] (vs/csv-param {"fa" "1,2,3"} "fa")))
  (is (= ["7"] (vs/csv-param {"fa" "7"} "fa")) "single value"))

(deftest category-value-coercion
  (testing "$catValue (number/string/blank/nil) → long id or nil"
    (is (nil? (vs/parse-category-value "")) "empty string → nil (clear)")
    (is (nil? (vs/parse-category-value nil)) "nil → nil")
    (is (= 7 (vs/parse-category-value "7")) "id string → long")
    (is (= 7 (vs/parse-category-value 7)) "number → long")
    (is (instance? Long (vs/parse-category-value 7)) "result is a long")
    (is (instance? Long (vs/parse-category-value "7")) "string result is a long too")
    (is (nil? (vs/parse-category-value "not-a-number")) "non-numeric string → nil")))

;; --- query → view-state -----------------------------------------------------

(deftest query->view-state-defaults
  (testing "an empty query yields the documented defaults"
    (let [v (vs/query->view-state {})]
      (is (= "" (:search v)))
      (is (= :all (:scope v)) "scope defaults to :all")
      (is (false? (:hide-transfers v)))
      (is (false? (:uncat v)))
      (is (nil? (:sort v)) "no sort param → no sort")
      (is (= 0 (:page v)) "page defaults to 0")
      (is (= 25 (:page-size v)) "page-size defaults to 25")
      (is (= #{} (:accounts v)))
      (is (= #{} (:institutions v)))
      (is (= #{} (:categories v))))))

(deftest query->view-state-full
  (testing "every param flows into the view-state with the right coercions"
    (let [v (vs/query->view-state {"q" "coffee" "scope" "needs-review"
                                   "ht" "1" "uncat" "1"
                                   "sortCol" "amount" "sortDir" "desc"
                                   "page" "2" "pageSize" "50"
                                   "fa" "100,101" "fi" "1000" "fc" "10,11"})]
      (is (= "coffee" (:search v)))
      (is (= :needs-review (:scope v)))
      (is (true? (:hide-transfers v)) "ht=1 → true")
      (is (true? (:uncat v)) "uncat=1 → true")
      (is (= {:col :amount :dir :desc} (:sort v)) "sort col keywordized, dir parsed")
      (is (= 2 (:page v)))
      (is (= 50 (:page-size v)))
      (is (= #{100 101} (:accounts v)))
      (is (= #{1000} (:institutions v)))
      (is (= #{10 11} (:categories v)))))
  (testing "sort dir other than 'desc' → :asc; scope other than needs-review → :all"
    (let [v (vs/query->view-state {"sortCol" "payee" "sortDir" "asc" "scope" "everything"})]
      (is (= {:col :payee :dir :asc} (:sort v)))
      (is (= :all (:scope v))))))

;; --- signals → view-state ---------------------------------------------------

(deftest signals->view-state-mapping
  (testing "the live signals map (camelCase + nested :filter) → view-state"
    (let [v (vs/signals->view-state
             {:search "rent" :scope "needs-review" :hideTransfers true :uncat false
              :sortCol "date" :sortDir "desc" :page 1 :pageSize 100
              :filter {:account ["100"] :institution [] :category ["10" "11"]}})]
      (is (= "rent" (:search v)))
      (is (= :needs-review (:scope v)))
      (is (true? (:hide-transfers v)))
      (is (false? (:uncat v)))
      (is (= {:col :date :dir :desc} (:sort v)))
      (is (= 1 (:page v)))
      (is (= 100 (:page-size v)))
      (is (= #{100} (:accounts v)))
      (is (= #{} (:institutions v)))
      (is (= #{10 11} (:categories v))))))

;; --- view-state → signals (initial seed) ------------------------------------

(deftest vs->signals-mapping
  (testing "page/page-size come from the clamped view result, not the requested view-state"
    (let [v      (vs/query->view-state {"q" "x" "scope" "needs-review"
                                        "sortCol" "amount" "sortDir" "desc"
                                        "page" "9" "pageSize" "50"})
          result {:page 2 :page-size 50}   ; clamped by the view engine
          s      (vs/vs->signals v "2025-01" result)]
      (is (= "x" (:search s)))
      (is (= "needs-review" (:scope s)) "scope stringified")
      (is (= "amount" (:sortCol s)) "sort col → name string")
      (is (= "desc" (:sortDir s)))
      (is (= 2 (:page s)) "page taken from clamped result, not the requested 9")
      (is (= 50 (:pageSize s)))
      (is (= "2025-01" (:month s)))
      (is (= "" (:editValue s)) "courier signals start blank")
      (is (= "" (:catValue s)))))
  (testing "no sort → empty sortCol and default 'asc' sortDir"
    (let [s (vs/vs->signals (vs/query->view-state {}) "2025-02" {:page 0 :page-size 25})]
      (is (= "" (:sortCol s)))
      (is (= "asc" (:sortDir s)))
      (is (= "all" (:scope s))))))

;; --- column visibility ------------------------------------------------------

(deftest parse-cols-visibility
  (testing "no hidecols → every column visible (true)"
    (let [cols (vs/parse-cols {})]
      (is (every? true? (vals cols)))
      (is (= (set (map (comp keyword first) vs/hideable-columns)) (set (keys cols)))
          "one entry per hideable column")))
  (testing "hidecols csv flips those columns to hidden (false), rest stay visible"
    (let [cols (vs/parse-cols {"hidecols" "payee,institution"})]
      (is (false? (:payee cols)))
      (is (false? (:institution cols)))
      (is (true? (:date cols)))
      (is (true? (:amount cols))))))

;; --- full client-signals seed -----------------------------------------------

(deftest client-signals-shape
  (testing "the full seed = view-state signals + cols + filter arrays + ephemeral _ signals"
    (let [v      (vs/query->view-state {"fa" "100" "fc" "10,11" "hidecols" "payee"})
          qp     {"fa" "100" "fc" "10,11" "hidecols" "payee"}
          s      (vs/client-signals v "2025-03" {:page 0 :page-size 25} qp)]
      (is (= "2025-03" (:month s)) "carries the vs->signals base")
      (is (false? (get-in s [:cols :payee])) "column visibility folded in")
      (is (= ["100"] (get-in s [:filter :account])) "filter arrays are raw csv tokens")
      (is (= [] (get-in s [:filter :institution])))
      (is (= ["10" "11"] (get-in s [:filter :category])))
      (is (false? (:_colsOpen s)) "ephemeral UI signals seeded")
      (is (= "" (:_openFunnel s)))
      (is (= "" (:_funnelQuery s)))
      (is (= 0 (:_funnelX s)))
      (is (= 0 (:_funnelY s))))))

;; --- round-trip -------------------------------------------------------------

(deftest query-roundtrips-through-signals
  (testing "query → view-state → signals preserves the persistent view fields"
    (let [qp     {"q" "groceries" "scope" "needs-review" "ht" "1" "uncat" "1"
                  "sortCol" "category" "sortDir" "desc" "page" "0" "pageSize" "100"
                  "fa" "100" "fi" "1000" "fc" "10"}
          v      (vs/query->view-state qp)
          result {:page 0 :page-size 100}
          s      (vs/client-signals v "2025-04" result qp)]
      ;; The signal map a fresh load ships should describe the same view the URL asked for.
      (is (= "groceries" (:search s)))
      (is (= "needs-review" (:scope s)))
      (is (true? (:hideTransfers s)))
      (is (true? (:uncat s)))
      (is (= "category" (:sortCol s)))
      (is (= "desc" (:sortDir s)))
      (is (= 100 (:pageSize s)))
      (is (= ["100"] (get-in s [:filter :account])))
      (is (= ["1000"] (get-in s [:filter :institution])))
      (is (= ["10"] (get-in s [:filter :category])))
      ;; And feeding those signals back through signals->view-state lands on the same view-state.
      (is (= v (vs/signals->view-state
                (assoc s :filter {:account ["100"] :institution ["1000"] :category ["10"]})))
          "signals → view-state reproduces the original view-state"))))
