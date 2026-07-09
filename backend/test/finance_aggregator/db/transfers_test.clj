(ns finance-aggregator.db.transfers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.transactions :as db-transactions]
            [finance-aggregator.db.transfers :as transfers]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- day
  "A java.util.Date at the given epoch-day (UTC midnight)."
  [n]
  (java.util.Date. (* (long n) 86400000)))

(defn- make-account! [external-id name]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id)
                                   :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id external-id
                                   :account/external-name name
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:account/external-id external-id])))

(defn- make-tx!
  "Create a transaction on the given account. Returns its :db/id."
  [external-id account-id amount day-n & {:as overrides}]
  (d/transact! setup/*test-conn*
               [(merge {:transaction/external-id external-id
                        :transaction/account account-id
                        :transaction/amount amount
                        :transaction/payee "Transfer"
                        :transaction/posted-date (day day-n)}
                       overrides)])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- make-category! [name ident type]
  (:db/id (categories/create! setup/*test-conn*
                              {:category/name name :category/type type :category/ident ident})))

(defn- pair-ids [result] (set (map (juxt #(:db/id (:outflow %)) #(:db/id (:inflow %))) result)))

(defn- split!
  "Split `tx-id` into uncategorized parts (amount strings), returning the part ids
   ordered by :transaction/split-order. Parts inherit the parent's account and
   posted-date, so they window/pair like any leg."
  [tx-id & amounts]
  (db-transactions/set-splits! setup/*test-conn* tx-id (mapv (fn [a] {:amount a}) amounts))
  (->> (d/q '[:find [(pull ?p [:db/id :transaction/split-order]) ...]
              :in $ ?tx :where [?p :transaction/split-parent ?tx]]
            (d/db setup/*test-conn*) tx-id)
       (sort-by :transaction/split-order)
       (mapv :db/id)))

;; NOTE: with-empty-db is :each per deftest (not per testing block), and
;; suggest-matches / match-candidates query ALL transactions — so each global
;; scenario gets its own deftest to avoid cross-contamination.

(deftest suggest-matches-basic-test
  (testing "an inverse-amount pair across accounts within the window is suggested with both legs pulled"
    (let [checking (make-account! "checking" "Checking")
          savings (make-account! "savings" "Savings")
          out (make-tx! "out-1" checking -100.00M 10)
          in (make-tx! "in-1" savings 100.00M 11)
          [match :as result] (transfers/suggest-matches setup/*test-conn*)]
      (is (= 1 (count result)))
      (is (= out (:db/id (:outflow match))))
      (is (= in (:db/id (:inflow match))))
      (is (== 100.00M (:amount match)))
      (is (= "Savings" (get-in match [:inflow :transaction/account :account/external-name]))))))

(deftest suggest-matches-skips-real-expense-test
  (testing "a leg categorized as a real expense is not auto-suggested"
    (let [checking (make-account! "c2" "Checking")
          mortgage (make-account! "m2" "Mortgage")
          housing (make-category! "Housing" :category/housing :expense)]
      (make-tx! "out-2" checking -100.00M 10 :transaction/category housing)
      (make-tx! "in-2" mortgage 100.00M 10)
      (is (empty? (transfers/suggest-matches setup/*test-conn*))))))

(deftest suggest-matches-skips-already-matched-test
  (testing "already-matched legs are not re-suggested"
    (let [a (make-account! "a3" "A")
          b (make-account! "b3" "B")
          out (make-tx! "out-3" a -100.00M 10)
          in (make-tx! "in-3" b 100.00M 10)]
      (transfers/confirm-match! setup/*test-conn* out in)
      (is (empty? (transfers/suggest-matches setup/*test-conn*))))))

(deftest suggest-matches-excludes-split-parent-includes-parts-test
  (testing "a split parent never appears in suggestions; its parts are normal legs"
    (let [checking (make-account! "csp" "Checking")
          savings (make-account! "ssp" "Savings")
          parent (make-tx! "parent-sp" checking -100.00M 10)
          ;; Two inflows: +100 would pair with the (hidden) parent, +60 with a part.
          in-100 (make-tx! "in100-sp" savings 100.00M 10)
          in-60 (make-tx! "in60-sp" savings 60.00M 10)
          [part-60 _part-40] (split! parent "-60.00" "-40.00")
          result (transfers/suggest-matches setup/*test-conn*)]
      (is (= #{[part-60 in-60]} (pair-ids result))
          "the -60 part pairs with the +60 inflow — and that is the ONLY pair: the
           hidden parent never pairs with the +100 inflow")
      (is (empty? (filter #(contains? #{parent in-100} %)
                          (mapcat (juxt #(:db/id (:outflow %)) #(:db/id (:inflow %))) result)))
          "neither the split parent nor its would-be +100 partner appears in any pair"))))

(deftest match-candidates-exclude-split-parent-include-parts-test
  (testing "a split parent is never offered as a manual-match candidate; its parts are"
    (let [checking (make-account! "cmc" "Checking")
          savings (make-account! "smc" "Savings")
          parent (make-tx! "parent-mc" checking -100.00M 10)
          plain (make-tx! "plain-mc" checking -100.00M 11)
          src-100 (make-tx! "src100-mc" savings 100.00M 10)
          src-60 (make-tx! "src60-mc" savings 60.00M 10)
          [part-60 _part-40] (split! parent "-60.00" "-40.00")]
      (is (= [plain] (map :db/id (transfers/match-candidates setup/*test-conn* src-100)))
          "only the unsplit -100 is offered — the split parent is hidden")
      (is (= [part-60] (map :db/id (transfers/match-candidates setup/*test-conn* src-60)))
          "a part is a normal counterpart candidate"))))

(deftest confirm-match-rejects-split-parent-test
  (testing "confirming a match against a split parent is rejected (defensive — the PUT
            route takes raw ids, and a hidden parent must never become a matched leg)"
    (let [checking (make-account! "crj" "Checking")
          savings (make-account! "srj" "Savings")
          parent (make-tx! "parent-rj" checking -100.00M 10)
          in (make-tx! "in-rj" savings 100.00M 10)]
      (split! parent "-60.00" "-40.00")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"match one of its parts"
                            (transfers/confirm-match! setup/*test-conn* parent in)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"match one of its parts"
                            (transfers/confirm-match! setup/*test-conn* in parent))
          "either argument order")
      (is (nil? (:transaction/transfer-pair
                 (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] in)))
          "nothing was written"))))

(deftest confirm-and-unmatch-test
  (testing "confirm sets transfer-pair on both legs; unmatch retracts both"
    (let [a (make-account! "a4" "A")
          b (make-account! "b4" "B")
          out (make-tx! "out-4" a -100.00M 10)
          in (make-tx! "in-4" b 100.00M 10)]
      (transfers/confirm-match! setup/*test-conn* out in)
      (let [db (d/db setup/*test-conn*)]
        (is (= in (get-in (d/pull db '[{:transaction/transfer-pair [:db/id]}] out)
                          [:transaction/transfer-pair :db/id])))
        (is (= out (get-in (d/pull db '[{:transaction/transfer-pair [:db/id]}] in)
                           [:transaction/transfer-pair :db/id]))))
      (let [r (transfers/unmatch! setup/*test-conn* out)]
        (is (true? (:unmatched r)))
        (is (= in (:partner r))))
      (let [db (d/db setup/*test-conn*)]
        (is (nil? (:transaction/transfer-pair (d/pull db '[{:transaction/transfer-pair [:db/id]}] out))))
        (is (nil? (:transaction/transfer-pair (d/pull db '[{:transaction/transfer-pair [:db/id]}] in)))))))

  (testing "confirming a transaction with itself is rejected"
    (let [a (make-account! "a5" "A")
          out (make-tx! "out-5" a -100.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"itself"
                            (transfers/confirm-match! setup/*test-conn* out out)))))

  (testing "confirming a missing transaction throws :not-found"
    (let [a (make-account! "a6" "A")
          out (make-tx! "out-6" a -100.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (transfers/confirm-match! setup/*test-conn* out 999999))))))

(deftest reject-match-test
  (testing "a rejected pair is recorded on both legs and not re-suggested"
    (let [a (make-account! "a7" "A")
          b (make-account! "b7" "B")
          out (make-tx! "out-7" a -100.00M 10)
          in (make-tx! "in-7" b 100.00M 10)]
      (is (seq (transfers/suggest-matches setup/*test-conn*)))
      (transfers/reject-match! setup/*test-conn* out in)
      (let [db (d/db setup/*test-conn*)]
        (is (= #{in} (set (map :db/id (:transaction/transfer-rejected
                                       (d/pull db '[{:transaction/transfer-rejected [:db/id]}] out))))))
        (is (= #{out} (set (map :db/id (:transaction/transfer-rejected
                                        (d/pull db '[{:transaction/transfer-rejected [:db/id]}] in)))))))
      (is (empty? (transfers/suggest-matches setup/*test-conn*))))))

(deftest match-candidates-real-expense-test
  (testing "returns the inverse counterpart in a wider window, even for a real-expense leg"
    (let [checking (make-account! "c8" "Checking")
          mortgage (make-account! "m8" "Mortgage")
          housing (make-category! "Housing" :category/housing8 :expense)
          payment (make-tx! "pay-8" checking -100.00M 10 :transaction/category housing)
          counterpart (make-tx! "cp-8" mortgage 100.00M 25)        ; 15 days away
          candidates (transfers/match-candidates setup/*test-conn* payment)]
      (is (= [counterpart] (map :db/id candidates))))))

(deftest match-candidates-exclusions-test
  (testing "excludes already-paired and same-account candidates"
    (let [checking (make-account! "c9" "Checking")
          savings (make-account! "s9" "Savings")
          src (make-tx! "src-9" checking -100.00M 10)
          _same-acct (make-tx! "same-9" checking 100.00M 10)       ; same account, excluded
          taken (make-tx! "taken-9" savings 100.00M 10)
          other (make-tx! "other-9" savings 100.00M 11)]
      (transfers/confirm-match! setup/*test-conn* taken (make-tx! "x-9" checking -100.00M 10))
      (is (= [other] (map :db/id (transfers/match-candidates setup/*test-conn* src)))))))

(deftest confirm-match-invariant-test
  (testing "confirming a leg that is already paired is rejected (no silent orphan)"
    (let [a (make-account! "ai" "A")
          b (make-account! "bi" "B")
          c (make-account! "ci" "C")
          out (make-tx! "out-i" a -100.00M 10)
          in (make-tx! "in-i" b 100.00M 10)
          other-in (make-tx! "other-in-i" c 100.00M 10)]
      (transfers/confirm-match! setup/*test-conn* out in)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already part"
                            (transfers/confirm-match! setup/*test-conn* out other-in)))
      ;; the original pair is intact and the partner was not orphaned
      (let [db (d/db setup/*test-conn*)]
        (is (= in (get-in (d/pull db '[{:transaction/transfer-pair [:db/id]}] out)
                          [:transaction/transfer-pair :db/id])))
        (is (nil? (:transaction/transfer-pair
                   (d/pull db '[{:transaction/transfer-pair [:db/id]}] other-in)))))))

  (testing "confirming legs on the same account is rejected"
    (let [a (make-account! "aj" "A")
          out (make-tx! "out-j" a -100.00M 10)
          in (make-tx! "in-j" a 100.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different accounts"
                            (transfers/confirm-match! setup/*test-conn* out in)))))

  (testing "confirming legs whose amounts are not equal and opposite is rejected"
    (let [a (make-account! "ak" "A")
          b (make-account! "bk" "B")
          out (make-tx! "out-k" a -100.00M 10)
          same-sign (make-tx! "same-sign-k" b -100.00M 10)
          unequal (make-tx! "unequal-k" b 99.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"equal and opposite"
                            (transfers/confirm-match! setup/*test-conn* out same-sign)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"equal and opposite"
                            (transfers/confirm-match! setup/*test-conn* out unequal)))))

  (testing "confirming two zero-amount legs is rejected"
    (let [a (make-account! "al" "A")
          b (make-account! "bl" "B")
          z1 (make-tx! "z1-l" a 0.00M 10)
          z2 (make-tx! "z2-l" b 0.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"equal and opposite"
                            (transfers/confirm-match! setup/*test-conn* z1 z2))))))

(deftest confirm-clears-rejection-test
  (testing "an explicit confirm clears a prior rejection between the two legs"
    (let [a (make-account! "am" "A")
          b (make-account! "bm" "B")
          out (make-tx! "out-m" a -100.00M 10)
          in (make-tx! "in-m" b 100.00M 10)]
      (transfers/reject-match! setup/*test-conn* out in)
      (transfers/confirm-match! setup/*test-conn* out in)
      (let [db (d/db setup/*test-conn*)]
        (is (= in (get-in (d/pull db '[{:transaction/transfer-pair [:db/id]}] out)
                          [:transaction/transfer-pair :db/id])))
        (is (empty? (:transaction/transfer-rejected
                     (d/pull db '[{:transaction/transfer-rejected [:db/id]}] out))))
        (is (empty? (:transaction/transfer-rejected
                     (d/pull db '[{:transaction/transfer-rejected [:db/id]}] in))))))))

(deftest reject-match-guards-test
  (testing "rejecting a missing transaction throws :not-found"
    (let [a (make-account! "an" "A")
          out (make-tx! "out-n" a -100.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (transfers/reject-match! setup/*test-conn* out 999999)))))

  (testing "rejecting a currently-linked pair unlinks it (never both linked and rejected)"
    (let [a (make-account! "ao" "A")
          b (make-account! "bo" "B")
          out (make-tx! "out-o" a -100.00M 10)
          in (make-tx! "in-o" b 100.00M 10)]
      (transfers/confirm-match! setup/*test-conn* out in)
      (transfers/reject-match! setup/*test-conn* out in)
      (let [db (d/db setup/*test-conn*)]
        (is (nil? (:transaction/transfer-pair
                   (d/pull db '[{:transaction/transfer-pair [:db/id]}] out))))
        (is (nil? (:transaction/transfer-pair
                   (d/pull db '[{:transaction/transfer-pair [:db/id]}] in))))
        (is (= #{in} (set (map :db/id (:transaction/transfer-rejected
                                       (d/pull db '[{:transaction/transfer-rejected [:db/id]}] out))))))))))

;; match-candidates / suggest-matches scan ALL transactions, so each global
;; scenario needs its own deftest (the empty-db fixture is :each per deftest).

(deftest match-candidates-zero-amount-test
  (testing "a zero-amount transaction has no candidates"
    (let [a (make-account! "ap" "A")
          b (make-account! "bp" "B")
          zero (make-tx! "zero-p" a 0.00M 10)
          _other-zero (make-tx! "other-zero-p" b 0.00M 10)]
      (is (empty? (transfers/match-candidates setup/*test-conn* zero))))))

(deftest match-candidates-no-date-test
  (testing "a self with no posted-date still returns amount/account matches instead of nothing"
    (let [a (make-account! "aq" "A")
          b (make-account! "bq" "B")
          _ (d/transact! setup/*test-conn* [{:transaction/external-id "nodate-q"
                                             :transaction/account a
                                             :transaction/amount -100.00M
                                             :transaction/payee "Transfer"}])
          self (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "nodate-q"]))
          cp (make-tx! "cp-q" b 100.00M 10)]
      (is (= [cp] (map :db/id (transfers/match-candidates setup/*test-conn* self)))))))

(deftest match-candidates-tiebreak-test
  (testing "candidates with an equal day-diff are ordered deterministically by id"
    (let [a (make-account! "ar" "A")
          b (make-account! "br" "B")
          self (make-tx! "self-r" a -100.00M 10)
          cp1 (make-tx! "cp1-r" b 100.00M 9)   ; 1 day before
          cp2 (make-tx! "cp2-r" b 100.00M 11)] ; 1 day after — same day-diff
      (is (= (sort [cp1 cp2])
             (map :db/id (transfers/match-candidates setup/*test-conn* self)))))))

(deftest match-candidates-recovery-test
  (testing "a previously-rejected counterpart is still offered for manual matching (recovery)"
    (let [a (make-account! "as" "A")
          b (make-account! "bs" "B")
          out (make-tx! "out-s" a -100.00M 10)
          in (make-tx! "in-s" b 100.00M 10)]
      (transfers/reject-match! setup/*test-conn* out in)
      (is (= [in] (map :db/id (transfers/match-candidates setup/*test-conn* out)))))))

(deftest suggest-matches-honors-manual-posted-date-override-test
  (testing "day-matching windows on the EFFECTIVE posted date — a manual override can pull
            a pair that the raw imported posted-date would place outside the window"
    (let [checking (make-account! "cov" "Checking")
          savings (make-account! "sov" "Savings")
          out (make-tx! "out-ov" checking -100.00M 10)
          ;; Imported far outside the default suggestion window; the user's override
          ;; corrects it to line up with `out`.
          in (make-tx! "in-ov" savings 100.00M 90)]
      (d/transact! setup/*test-conn* [{:db/id in :transaction/user-posted-date (day 11)}])
      (let [result (transfers/suggest-matches setup/*test-conn*)]
        (is (= #{[out in]} (pair-ids result))
            "the override — not the raw imported posted-date — decides the day-diff")))))

(deftest match-candidates-honors-manual-posted-date-override-test
  (testing "match-candidates windows the SELF transaction's day on its effective posted date too"
    (let [checking (make-account! "cmov" "Checking")
          savings (make-account! "smov" "Savings")
          self (make-tx! "self-ov" checking -100.00M 90)
          candidate (make-tx! "cand-ov" savings 100.00M 11)]
      (d/transact! setup/*test-conn* [{:db/id self :transaction/user-posted-date (day 10)}])
      (is (= [candidate] (map :db/id (transfers/match-candidates setup/*test-conn* self {:window-days 5})))
          "with the override, self's effective day (10) is within 5 days of the candidate's (11);
           the raw imported day (90) would have excluded it"))))

(deftest suggest-matches-tolerates-missing-date-test
  (testing "a transfer-eligible leg with no posted-date doesn't crash suggestions"
    (let [a (make-account! "amd" "A")
          b (make-account! "bmd" "B")
          out1 (make-tx! "out-md1" a -100.00M 10)
          in1 (make-tx! "in-md1" b 100.00M 10)
          ;; an undated inverse pair that would NPE the matcher before the nil guard
          _ (d/transact! setup/*test-conn* [{:transaction/external-id "out-md2"
                                             :transaction/account a
                                             :transaction/amount -50.00M
                                             :transaction/payee "Transfer"}])
          _in2 (make-tx! "in-md2" b 50.00M 10)
          result (transfers/suggest-matches setup/*test-conn*)]
      ;; the dated pair is still suggested; the undated one is simply skipped
      (is (= #{[out1 in1]} (pair-ids result))))))

(deftest reject-invalid-pair-test
  (testing "reject validates the pair the same way confirm does (no junk rejections)"
    (let [a (make-account! "arj" "A")
          b (make-account! "brj" "B")
          out (make-tx! "out-rj" a -100.00M 10)
          same-acct (make-tx! "same-rj" a 100.00M 10)
          unequal (make-tx! "uneq-rj" b 99.00M 10)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"itself"
                            (transfers/reject-match! setup/*test-conn* out out)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different accounts"
                            (transfers/reject-match! setup/*test-conn* out same-acct)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"equal and opposite"
                            (transfers/reject-match! setup/*test-conn* out unequal))))))

(deftest unmatch-missing-and-unpaired-test
  (testing "unmatching a missing transaction throws :not-found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (transfers/unmatch! setup/*test-conn* 999999))))
  (testing "unmatching an existing but unpaired transaction is a no-op"
    (let [a (make-account! "aum" "A")
          tx (make-tx! "tx-um" a -100.00M 10)
          r (transfers/unmatch! setup/*test-conn* tx)]
      (is (false? (:unmatched r)))
      (is (nil? (:partner r))))))

(deftest with-transfer-hidden-test
  (testing "flags a matched pair whose legs are both non-real"
    (is (true? (:transaction/transfer-hidden
                (transfers/with-transfer-hidden {:transaction/transfer-pair {:db/id 2}})))))
  (testing "does not flag an unmatched transaction"
    (is (nil? (:transaction/transfer-hidden (transfers/with-transfer-hidden {})))))
  (testing "keeps a matched pair visible when this leg is real expense/income"
    (is (nil? (:transaction/transfer-hidden
               (transfers/with-transfer-hidden
                {:transaction/category {:category/type :expense}
                 :transaction/transfer-pair {:db/id 2}})))))
  (testing "keeps a matched pair visible when the partner leg is real activity"
    (is (nil? (:transaction/transfer-hidden
               (transfers/with-transfer-hidden
                {:transaction/transfer-pair {:db/id 2 :transaction/category {:category/type :income}}}))))))
