(ns finance-aggregator.web.pages.transactions-test
  "Integration tests for GET / (web.pages.transactions/page) against a real temp db — the ONE
   handler in this namespace callable directly with no SSE machinery (a plain ring response, not
   handle-edit/sse-response; every edit action here is an SSE handler http-kit's async channel
   makes untestable without a real server). That makes it the seam that can prove
   close-model-for's real DB wiring end to end — in particular phase 3's quiet accounts (an
   account with an entered period but no transactions this month), which every other test here
   only exercises through the pure ledger/view layers in isolation."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.test-utils.setup :as setup]
   [finance-aggregator.web.pages.transactions :as transactions]))

(use-fixtures :each setup/with-empty-db)

(defn- put-account! [ext-id name]
  (d/transact! setup/*test-conn*
               [{:account/external-id ext-id :account/external-name name :account/provider :plaid}]))

(defn- snapshot! [ext-id ^java.util.Date date balance]
  (d/transact! setup/*test-conn*
               [{:snapshot/id (str ext-id ":" date)
                 :snapshot/account [:account/external-id ext-id]
                 :snapshot/date date
                 :snapshot/balance balance
                 :snapshot/source :reported}]))

(defn- txn! [ext-id ^java.util.Date posted-date amount]
  (d/transact! setup/*test-conn*
               [{:transaction/external-id (str ext-id "-" posted-date)
                 :transaction/account [:account/external-id ext-id]
                 :transaction/posted-date posted-date
                 :transaction/amount amount
                 :transaction/payee "Test"}]))

(defn- page-body [month]
  (:body ((transactions/page {:db-conn setup/*test-conn*}) {:query-params {"month" month}})))

(deftest quiet-account-reconciled-appears-and-matches
  (testing "an account with an entered boundary pair but ZERO June transactions still shows up
            as a row, and reconciles when the bank reports no movement"
    (put-account! "cheq" "Chequing")
    (put-account! "mortgage" "Mortgage")
    ;; Chequing: normal ACTIVE account (a June txn + a matching pair) — the control.
    (txn! "cheq" #inst "2025-06-05" 100.00M)
    (snapshot! "cheq" #inst "2025-05-31" 1000.00M)
    (snapshot! "cheq" #inst "2025-06-30" 1100.00M)
    ;; Mortgage: QUIET — an entered pair, no June activity at all, and the balance didn't move.
    (snapshot! "mortgage" #inst "2025-05-31" -100000.00M)
    (snapshot! "mortgage" #inst "2025-06-30" -100000.00M)
    (let [body (page-body "2025-06")]
      (is (str/includes? body "Chequing"))
      (is (str/includes? body "Mortgage") "the quiet account still surfaces as a reconcile row")
      (is (= 2 (count (re-seq #"reconcile-tick\">✓</span>matches" body)))
          "both read matches — Chequing via activity, Mortgage via an unmoved reported balance")
      (is (not (str/includes? body "off by")))
      (is (not (str/includes? body "needs balances"))))))

(deftest quiet-account-drifting-blocks-the-close-gate
  (testing "a quiet account whose reported balance moved anyway (nothing tracked to explain it)
            reads off-by and blocks Close — evidence of a missing transaction"
    (put-account! "cheq" "Chequing")
    (put-account! "mortgage" "Mortgage")
    (txn! "cheq" #inst "2025-06-05" 100.00M)
    (snapshot! "cheq" #inst "2025-05-31" 1000.00M)
    (snapshot! "cheq" #inst "2025-06-30" 1100.00M)
    ;; Mortgage moved by $1000 in June with zero tracked transactions.
    (snapshot! "mortgage" #inst "2025-05-31" -100000.00M)
    (snapshot! "mortgage" #inst "2025-06-30" -99000.00M)
    (let [body (page-body "2025-06")]
      (is (str/includes? body "Mortgage"))
      (is (str/includes? body "off by $1,000.00")
          "computed is 0 (no activity), so the difference IS the reported-delta")
      (is (str/includes? body "disabled")
          "Close month stays blocked — a quiet account drifting gates same as an active one"))))

(deftest quiet-account-via-a-statement-with-no-boundary-pair
  (testing "a quiet account with ONLY a statement on file (no month-boundary pair at all — no
            snapshots) still surfaces and reconciles per that statement's own verdict"
    (put-account! "visa" "Visa")
    ;; A trivial statement (0 -> 0, no movement) that ties out with zero transactions — so Visa
    ;; stays genuinely quiet (no June activity at all) while still having a period on file.
    (d/transact! setup/*test-conn*
                 [{:statement/account [:account/external-id "visa"]
                   :statement/start-date #inst "2025-06-01"
                   :statement/start-balance 0M
                   :statement/end-date #inst "2025-06-15"
                   :statement/end-balance 0M}])
    (let [body (page-body "2025-06")]
      (is (str/includes? body "Visa") "surfaces via account-eids-overlapping, not a boundary pair")
      (is (str/includes? body "matches")))))
