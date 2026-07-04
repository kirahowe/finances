(ns finance-aggregator.web.pages.transactions-view-test
  "Render checks for the fragments whose branch logic matters — the monthly-close
   panel's open / ready / closed / drift states. Most rendering is proven in the
   browser (e2e); these cover branches an e2e can't cheaply reach (a closed month,
   drift since close)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.pages.transactions-view :as tv]
   [finance-aggregator.web.render :as r]))

(defn- html [hiccup] (str (r/render hiccup)))

(def ^:private mixed-rows
  [{:account-id 1 :name "Chequing" :status :reconciled  :difference 0M}
   {:account-id 2 :name "Visa"     :status :drift        :difference (bigdec "5.00")}
   {:account-id 3 :name "Savings"  :status :no-snapshot  :difference nil}])

(deftest close-panel-open-month
  (let [h (html (tv/close-panel
                 {:rows mixed-rows
                  :gate {:unreviewed 2 :uncategorized 1 :all-reviewed? false
                         :all-categorized? false :balanced? false :ready? false}
                  :closed? false :closed-at nil :drift nil}))]
    (testing "per-account statuses render"
      (is (re-find #"Chequing" h))
      (is (re-find #"matches" h))
      (is (re-find #"off by" h)))
    (testing "unreconciled accounts get a 'Set balance' affordance on an open month"
      (is (re-find #"reconcile-set-balance" h))
      (is (re-find #"/transactions/statement-modal\?account=3" h) "opens the modal preselected to the account eid"))
    (testing "the panel-level Add statement balance action is present"
      (is (re-find #"Add statement balance" h))
      (is (re-find #"statement-modal&apos;\)" h) "the no-account (any-account) modal open"))
    (testing "gate lines + a disabled Close button (not ready)"
      (is (re-find #"2 to review" h))
      (is (re-find #"Close month" h))
      (is (re-find #"disabled" h))
      (is (not (re-find #"Reopen" h))))))

(deftest close-panel-ready-enables-close
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "A" :status :reconciled :difference 0M}]
                  :gate {:unreviewed 0 :uncategorized 0 :all-reviewed? true
                         :all-categorized? true :balanced? true :ready? true}
                  :closed? false :closed-at nil :drift nil}))]
    (is (re-find #"Close month" h))
    (is (re-find #"Balances match" h))
    (is (not (re-find #"disabled" h)) "ready → Close is enabled")))

(deftest close-panel-closed-month-shows-reopen-and-drift
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "A" :status :reconciled :difference 0M}]
                  :gate {:ready? true}
                  :closed? true :closed-at #inst "2026-03-03"
                  :drift {:frozen (bigdec "1865") :now (bigdec "1900")}}))]
    (testing "closed banner + Reopen; no per-account Set balance; no Close button"
      (is (re-find #"Closed" h))
      (is (re-find #"Reopen" h))
      (is (not (re-find #"Close month" h)))
      (is (not (re-find #"reconcile-set-balance" h))))
    (testing "drift note shows the frozen-vs-now change"
      (is (re-find #"Changed since close" h)))))

(deftest close-panel-lists-statement-balances-with-their-dates
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "Visa" :status :drift :difference (bigdec "5.00")}]
                  :gate {:ready? false}
                  :closed? false
                  :manual-balances [{:id 4207 :account-name "Visa"
                                     :date #inst "2026-07-15" :balance (bigdec "1240.00")}
                                    {:id 4206 :account-name "Visa"
                                     :date #inst "2026-06-15" :balance (bigdec "1190.00")}]}))]
    (testing "each recorded balance shows account, its applied date, and amount"
      (is (re-find #"Statement balances" h))
      (is (re-find #"Jul 15, 2026" h) "the date it's applied on is visible")
      (is (re-find #"Jun 15, 2026" h))
      (is (re-find #"1,240.00" h)))
    (testing "each has a delete that couriers its numeric snapshot id (no string in the JS expr)"
      (is (re-find #"\$stmtDel = 4207" h))
      (is (re-find #"/transactions/statement/delete" h)))))

(deftest close-panel-empty-renders-a-hidden-but-present-morph-target
  ;; It must NOT return nil — an SSE patch that empties the panel (deleting the last
  ;; row/balance) still needs an #reconciliation element to morph, or the stale panel sticks.
  (let [h (html (tv/close-panel {:rows [] :gate {}}))]
    (is (re-find #"id=\"reconciliation\"" h) "the morph target still exists")
    (is (re-find #"hidden" h) "but it's hidden when there's nothing to show")
    (is (not (re-find #"Reconciliation" h)) "no title/content rendered")))

(deftest close-panel-renders-for-manual-balances-without-activity
  (testing "an account with a recorded balance but no activity this month still shows"
    (is (some? (tv/close-panel {:rows [] :gate {}
                                :manual-balances [{:id "x:manual:2026-07-31" :account-name "Savings"
                                                   :date #inst "2026-07-31" :balance (bigdec "500.00")}]})))))
