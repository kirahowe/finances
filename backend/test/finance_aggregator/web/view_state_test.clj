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

(deftest splits-value-coercion
  (testing "$splitValue JSON string → set-splits! input shape"
    (is (= [{:amount "-60.00" :category-id 5}
            {:amount "-40.00" :category-id 7 :memo "tip"}]
           (vs/parse-splits-value
            "[{\"amount\":\"-60.00\",\"categoryId\":5},{\"amount\":\"-40.00\",\"categoryId\":7,\"memo\":\"tip\"}]"))
        "categoryId → :category-id (long); memo kept only when present")
    (is (instance? Long (:category-id (first (vs/parse-splits-value "[{\"amount\":\"-1.00\",\"categoryId\":5}]"))))
        "category-id is a long"))
  (testing "an existing part's id passes through as a long; absent when not sent (a fresh row)"
    (is (= {:amount "-60.00" :category-id 5 :id 42}
           (first (vs/parse-splits-value "[{\"amount\":\"-60.00\",\"categoryId\":5,\"id\":42}]"))))
    (is (instance? Long (:id (first (vs/parse-splits-value "[{\"amount\":\"-60.00\",\"categoryId\":5,\"id\":42}]")))))
    (is (not (contains? (first (vs/parse-splits-value "[{\"amount\":\"-60.00\",\"categoryId\":5}]")) :id))))
  (testing "blank / nil / empty array → [] (un-split)"
    (is (= [] (vs/parse-splits-value "")))
    (is (= [] (vs/parse-splits-value nil)))
    (is (= [] (vs/parse-splits-value "[]")))))

;; --- query → view-state -----------------------------------------------------

(deftest query->view-state-defaults
  (testing "an empty query yields the documented defaults"
    (let [v (vs/query->view-state {})]
      (is (= "" (:search v)))
      (is (= :all (:scope v)) "scope defaults to :all")
      (is (false? (:hide-transfers v)))
      (is (false? (:uncat v)))
      (is (= {:col :date :dir :asc} (:sort v))
          "no sortCol param → the resolved default (date asc), not nil — a blank sortCol is
           the canonical ENCODING of this default, resolved here so web.view always sees a
           primary sort")
      (is (nil? (:sort2 v)) "no secondary sort by default")
      (is (= 0 (:page v)) "page defaults to 0")
      (is (= 25 (:page-size v)) "page-size defaults to 25")
      (is (= #{} (:accounts v)))
      (is (= #{} (:institutions v)))
      (is (= #{} (:categories v))))))

(deftest query->view-state-two-level-sort
  (testing "sortCol2/sortDir2 parse into :sort2"
    (let [v (vs/query->view-state {"sortCol" "amount" "sortDir" "desc"
                                   "sortCol2" "payee" "sortDir2" "asc"})]
      (is (= {:col :amount :dir :desc} (:sort v)))
      (is (= {:col :payee :dir :asc} (:sort2 v)))))
  (testing "blank sortCol2 → no secondary"
    (is (nil? (:sort2 (vs/query->view-state {"sortCol" "amount"})))))
  (testing "sortCol2 naming the SAME column as the (possibly default) primary collapses to nil"
    (is (nil? (:sort2 (vs/query->view-state {"sortCol2" "date" "sortDir2" "asc"})))
        "primary defaults to date — a 'date' secondary is redundant")
    (is (nil? (:sort2 (vs/query->view-state {"sortCol" "amount" "sortCol2" "amount"})))
        "an explicit primary matching its own secondary is redundant too")))

(deftest query->view-state-full
  (testing "every param flows into the view-state with the right coercions"
    (let [v (vs/query->view-state {"q" "coffee" "scope" "to-reconcile"
                                   "ht" "1" "uncat" "1"
                                   "sortCol" "amount" "sortDir" "desc"
                                   "page" "2" "pageSize" "50"
                                   "fa" "100,101" "fi" "1000" "fc" "10,11"})]
      (is (= "coffee" (:search v)))
      (is (= :to-reconcile (:scope v)))
      (is (true? (:hide-transfers v)) "ht=1 → true")
      (is (true? (:uncat v)) "uncat=1 → true")
      (is (= {:col :amount :dir :desc} (:sort v)) "sort col keywordized, dir parsed")
      (is (= 2 (:page v)))
      (is (= 50 (:page-size v)))
      (is (= #{100 101} (:accounts v)))
      (is (= #{1000} (:institutions v)))
      (is (= #{10 11} (:categories v)))))
  (testing "sort dir other than 'desc' → :asc; scope other than to-reconcile → :all"
    (let [v (vs/query->view-state {"sortCol" "payee" "sortDir" "asc" "scope" "everything"})]
      (is (= {:col :payee :dir :asc} (:sort v)))
      (is (= :all (:scope v)))))
  (testing "the pre-rename scope token still parses (stale bookmarked URLs keep working)"
    (is (= :to-reconcile (:scope (vs/query->view-state {"scope" "needs-review"}))))))

;; --- signals → view-state ---------------------------------------------------

(deftest signals->view-state-mapping
  (testing "the live signals map (camelCase + nested :filter) → view-state"
    (let [v (vs/signals->view-state
             {:search "rent" :scope "to-reconcile" :hideTransfers true :uncat false
              :sortCol "date" :sortDir "desc" :sortCol2 "payee" :sortDir2 "asc"
              :page 1 :pageSize 100
              :filter {:account ["100"] :institution [] :category ["10" "11"]}})]
      (is (= "rent" (:search v)))
      (is (= :to-reconcile (:scope v)))
      (is (true? (:hide-transfers v)))
      (is (false? (:uncat v)))
      (is (= {:col :date :dir :desc} (:sort v)))
      (is (= {:col :payee :dir :asc} (:sort2 v)))
      (is (= 1 (:page v)))
      (is (= 100 (:page-size v)))
      (is (= #{100} (:accounts v)))
      (is (= #{} (:institutions v)))
      (is (= #{10 11} (:categories v))))))

;; --- view-state → signals (initial seed) ------------------------------------

(def ^:private month-seed
  "A period-signals seed for month view (web.period/signal-seed's :month shape) — blank from/to."
  {:month "2025-01" :from "" :to ""})

(deftest vs->signals-mapping
  (testing "page/page-size come from the clamped view result, not the requested view-state"
    (let [v      (vs/query->view-state {"q" "x" "scope" "to-reconcile"
                                        "sortCol" "amount" "sortDir" "desc"
                                        "page" "9" "pageSize" "50"})
          result {:page 2 :page-size 50}   ; clamped by the view engine
          s      (vs/vs->signals v month-seed result)]
      (is (= "x" (:search s)))
      (is (= "to-reconcile" (:scope s)) "scope stringified")
      (is (= "amount" (:sortCol s)) "sort col → name string")
      (is (= "desc" (:sortDir s)))
      (is (= 2 (:page s)) "page taken from clamped result, not the requested 9")
      (is (= 50 (:pageSize s)))
      (is (= "2025-01" (:month s)))
      (is (= "" (:editValue s)) "courier signals start blank")
      (is (= "" (:catValue s)))))
  (testing "no sort param → the resolved default (date asc) signals back as BLANK sortCol/asc
            dir — the canonical encoding, not the literal 'date'/'asc' strings"
    (let [s (vs/vs->signals (vs/query->view-state {}) month-seed {:page 0 :page-size 25})]
      (is (= "" (:sortCol s)))
      (is (= "asc" (:sortDir s)))
      (is (= "" (:sortCol2 s)) "no secondary sort by default")
      (is (= "asc" (:sortDir2 s)))
      (is (= "all" (:scope s)))))
  (testing "an explicit non-default sort signals back literally"
    (let [s (vs/vs->signals (vs/query->view-state {"sortCol" "amount" "sortDir" "desc"
                                                    "sortCol2" "payee" "sortDir2" "asc"})
                            month-seed {:page 0 :page-size 25})]
      (is (= "amount" (:sortCol s)))
      (is (= "desc" (:sortDir s)))
      (is (= "payee" (:sortCol2 s)))
      (is (= "asc" (:sortDir2 s)))))
  (testing "an EXPLICIT date-asc primary still signals back blank — same resolved sort as the
            default, so the same canonical encoding applies either way"
    (let [s (vs/vs->signals (vs/query->view-state {"sortCol" "date" "sortDir" "asc"})
                            month-seed {:page 0 :page-size 25})]
      (is (= "" (:sortCol s)))))
  (testing "a RANGE seed lands its from/to in the signals, and :month is the containing month
            (not blank) — month-bound handlers keep working even in range view"
    (let [s (vs/vs->signals (vs/query->view-state {})
                            {:month "2026-07" :from "2026-06-10" :to "2026-07-09"}
                            {:page 0 :page-size 25})]
      (is (= "2026-07" (:month s)))
      (is (= "2026-06-10" (:from s)))
      (is (= "2026-07-09" (:to s)))))
  (testing "a MONTH seed lands blank from/to"
    (let [s (vs/vs->signals (vs/query->view-state {}) month-seed {:page 0 :page-size 25})]
      (is (= "" (:from s)))
      (is (= "" (:to s))))))

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

(deftest posted-hint-display-signal
  (testing "showPosted defaults true (hint shown), flips false only when posted=0 is in the URL"
    (let [seed (fn [qp] (:showPosted (vs/client-signals (vs/query->view-state qp)
                                                        month-seed {:page 0 :page-size 25} qp)))]
      (is (true? (seed {})) "no param → shown")
      (is (false? (seed {"posted" "0"})) "posted=0 → hidden")
      (is (true? (seed {"posted" "1"})) "any other value → shown (0 is the only hide token)"))))

;; --- full client-signals seed -----------------------------------------------

(deftest client-signals-shape
  (testing "the full seed = view-state signals + cols + filter arrays + ephemeral _ signals"
    (let [v      (vs/query->view-state {"fa" "100" "fc" "10,11" "hidecols" "payee"})
          qp     {"fa" "100" "fc" "10,11" "hidecols" "payee"}
          s      (vs/client-signals v month-seed {:page 0 :page-size 25} qp)]
      (is (= "2025-01" (:month s)) "carries the vs->signals base")
      (is (false? (get-in s [:cols :payee])) "column visibility folded in")
      (is (true? (:showPosted s)) "posted-date hint shows by default (no posted param)")
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
    (let [qp     {"q" "groceries" "scope" "to-reconcile" "ht" "1" "uncat" "1"
                  "sortCol" "category" "sortDir" "desc" "sortCol2" "payee" "sortDir2" "asc"
                  "page" "0" "pageSize" "100"
                  "fa" "100" "fi" "1000" "fc" "10"}
          v      (vs/query->view-state qp)
          result {:page 0 :page-size 100}
          s      (vs/client-signals v {:month "2025-04" :from "" :to ""} result qp)]
      ;; The signal map a fresh load ships should describe the same view the URL asked for.
      (is (= "groceries" (:search s)))
      (is (= "to-reconcile" (:scope s)))
      (is (true? (:hideTransfers s)))
      (is (true? (:uncat s)))
      (is (= "category" (:sortCol s)))
      (is (= "desc" (:sortDir s)))
      (is (= "payee" (:sortCol2 s)))
      (is (= "asc" (:sortDir2 s)))
      (is (= 100 (:pageSize s)))
      (is (= ["100"] (get-in s [:filter :account])))
      (is (= ["1000"] (get-in s [:filter :institution])))
      (is (= ["10"] (get-in s [:filter :category])))
      ;; And feeding those signals back through signals->view-state lands on the same view-state.
      (is (= v (vs/signals->view-state
                (assoc s :filter {:account ["100"] :institution ["1000"] :category ["10"]})))
          "signals → view-state reproduces the original view-state")))
  (testing "the DEFAULT sort round-trips too: blank params → blank signals → back to the same
            resolved default view-state"
    (let [v (vs/query->view-state {})
          s (vs/client-signals v {:month "2025-04" :from "" :to ""} {:page 0 :page-size 25} {})]
      (is (= "" (:sortCol s)))
      (is (= "" (:sortCol2 s)))
      (is (= v (vs/signals->view-state (assoc s :filter {:account [] :institution [] :category []})))))))

;; --- Clear-all activation ----------------------------------------------------

(deftest clear-all-active-predicate
  (testing "no filter active, no lens → false"
    (is (false? (vs/clear-all-active? (vs/query->view-state {}) false))))
  (testing "each filter axis independently activates it"
    (is (true? (vs/clear-all-active? (vs/query->view-state {"q" "coffee"}) false)) "search")
    (is (true? (vs/clear-all-active? (vs/query->view-state {"uncat" "1"}) false)) "uncat chip")
    (is (true? (vs/clear-all-active? (vs/query->view-state {"ht" "1"}) false)) "hide-transfers")
    (is (true? (vs/clear-all-active? (vs/query->view-state {"fa" "1"}) false)) "account funnel")
    (is (true? (vs/clear-all-active? (vs/query->view-state {"fi" "1"}) false)) "institution funnel")
    (is (true? (vs/clear-all-active? (vs/query->view-state {"fc" "1"}) false)) "category funnel"))
  (testing "the statement lens alone activates it, even with no other filter"
    (is (true? (vs/clear-all-active? (vs/query->view-state {}) true))))
  (testing "sort and scope are NOT filters — neither alone activates it"
    (is (false? (vs/clear-all-active? (vs/query->view-state {"sortCol" "amount" "sortDir" "desc"}) false)))
    (is (false? (vs/clear-all-active? (vs/query->view-state {"scope" "to-reconcile"}) false)))))
