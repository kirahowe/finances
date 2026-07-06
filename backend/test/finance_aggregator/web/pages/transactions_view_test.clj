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

(deftest close-panel-overview-drills-per-account
  (let [h (html (tv/close-panel
                 {:rows mixed-rows
                  :gate {:unreviewed 2 :uncategorized 1 :all-reviewed? false
                         :all-categorized? false :balanced? false :ready? false}
                  :closed? false :closed-at nil :drift nil}))]
    (testing "per-account statuses render"
      (is (re-find #"Chequing" h))
      (is (re-find #"matches" h))
      (is (re-find #"off by" h))
      (is (re-find #"needs balances" h) "a no-snapshot account reads 'needs balances'"))
    (testing "each account row is a button that drills in (filters the table to that eid)"
      (is (re-find #"reconcile-drill" h))
      (is (re-find #"&apos;3&apos;" h) "drilling sets the account funnel to the row's eid"))
    (testing "no statement-balance modal affordances remain"
      (is (not (re-find #"Set balance" h)))
      (is (not (re-find #"Add statement balance" h)))
      (is (not (re-find #"statement-modal" h))))
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
    (testing "closed banner + Reopen; no Close button"
      (is (re-find #"Closed" h))
      (is (re-find #"Reopen" h))
      (is (not (re-find #"Close month" h))))
    (testing "drift note shows the frozen-vs-now change"
      (is (re-find #"Changed since close" h)))))

(deftest close-panel-empty-overview-is-a-hidden-morph-target
  ;; It must NOT return nil — an SSE patch that empties the panel (deleting the last row)
  ;; still needs an #reconciliation element to morph, or the stale panel sticks.
  (let [h (html (tv/close-panel {:rows [] :gate {}}))]
    (is (re-find #"id=\"reconciliation\"" h) "the morph target still exists")
    (is (re-find #"hidden" h) "but it's hidden when there's nothing to show")
    (is (not (re-find #"Reconciliation" h)) "no title/content rendered")))

(deftest close-panel-focused-card
  (let [h (html (tv/close-panel
                 {:rows mixed-rows :gate {} :closed? false
                  :focus {:account-id 2 :name "Visa"
                          :opening (bigdec "1190.00") :closing (bigdec "1240.00")
                          :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                          :expected (bigdec "50.00") :tracked (bigdec "45.00")
                          :difference (bigdec "5.00") :status :drift}}))]
    (testing "the focused account's card renders, not the overview list or gate"
      (is (re-find #"reconcile-focus" h))
      (is (re-find #"Visa" h))
      (is (not (re-find #"reconcile-rows" h)) "the overview list is replaced by the focused card")
      (is (not (re-find #"Close month" h)) "the month gate/Close live in the overview only"))
    (testing "opening/closing fields carry their app-owned end-of-day dates + prefilled values"
      (is (re-find #"Opening" h))
      (is (re-find #"Closing" h))
      (is (re-find #"end of Apr 30, 2026" h))
      (is (re-find #"end of May 31, 2026" h))
      (is (re-find #"1190.00" h) "opening prefill seeded as the raw input value"))
    (testing "the verdict + readout render (drift → off by, with a nudge to fix)"
      (is (re-find #"Expected change" h))
      (is (re-find #"Tracked activity" h))
      (is (re-find #"off by" h))
      (is (re-find #"fix the transactions" h)))
    (testing "Back returns to the overview by clearing the account filter"
      (is (re-find #"reconcile-back" h))
      (is (re-find #"filter\.account = \[\]" h)))
    (testing "Save posts the balances"
      (is (re-find #"reconcile-save" h))
      (is (re-find #"/transactions/reconcile" h)))))

(deftest close-panel-focused-card-verdicts
  (testing "a reconciled focus reads 'checks out'; a no-snapshot focus asks for balances"
    (let [ok (html (tv/close-panel
                    {:rows [] :gate {}
                     :focus {:account-id 1 :name "Chequing" :opening 0M :closing 2000M
                             :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                             :expected 2000M :tracked 2000M :difference 0M :status :reconciled}}))
          none (html (tv/close-panel
                      {:rows [] :gate {}
                       :focus {:account-id 1 :name "Chequing" :opening nil :closing nil
                               :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                               :expected nil :tracked 2000M :difference nil :status :no-snapshot}}))]
      (is (re-find #"checks out" ok))
      (is (re-find #"Enter the opening and closing" none)))))

(deftest close-panel-focused-card-lists-statements
  (let [h (html (tv/close-panel
                 {:rows [] :gate {} :closed? false
                  :focus {:account-id 2 :name "Visa"
                          :opening nil :closing nil
                          :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                          :expected nil :tracked 45M :difference nil :status :no-snapshot
                          :statements [{:id 7 :start-date #inst "2026-04-16" :end-date #inst "2026-05-16"
                                        :start-iso "2026-04-16" :end-iso "2026-05-16"
                                        :start-balance 500M :end-balance 640M
                                        :status :reconciled :difference 0M}
                                       {:id 8 :start-date #inst "2026-05-16" :end-date #inst "2026-06-16"
                                        :start-iso "2026-05-16" :end-iso "2026-06-16"
                                        :start-balance 640M :end-balance 810M
                                        :status :drift :difference 12M}]}}))]
    (testing "each statement renders its span, balances, and verdict"
      (is (re-find #"Statements" h))
      (is (re-find #"Apr 16, 2026" h))
      (is (re-find #"May 16, 2026" h))
      (is (re-find #"matches" h))
      (is (re-find #"off by" h)))
    (testing "a statement narrows the table to its span and offers Edit"
      (is (re-find #"reconcile-statement-span" h))
      (is (re-find #"2026-04-16" h) "the span dates ride the narrow toggle")
      (is (re-find #"statement-modal\?id=7" h)))
    (testing "the add-statement action is present"
      (is (re-find #"Add statement" h)))))

(deftest statement-modal-add-vs-edit
  (let [add  (html (tv/statement-modal false))
        edit (html (tv/statement-modal true))]
    (testing "add mode: titled Add, four fields, posts to /statement, no delete"
      (is (re-find #"Add statement" add))
      (is (re-find #"st-start" add))
      (is (re-find #"st-end-bal" add))
      (is (re-find #"/transactions/statement" add))
      (is (not (re-find #"Delete" add))))
    (testing "edit mode: titled Edit and offers Delete"
      (is (re-find #"Edit statement" edit))
      (is (re-find #"Delete" edit)))))
