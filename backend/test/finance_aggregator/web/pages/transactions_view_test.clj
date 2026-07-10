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
  ;; :status is the coverage-strict verdict (data.ledger/month-coverage via
  ;; web.view/reconcile-month): Visa is :partial with a :difference — the single-number case
  ;; (a month-boundary balance entered, no statements at all) — which reads exactly like the
  ;; old :drift wording ("off by $X").
  [{:account-id 1 :name "Chequing" :status :reconciled :difference nil}
   {:account-id 2 :name "Visa"     :status :partial    :difference (bigdec "5.00")}
   {:account-id 3 :name "Savings"  :status :no-snapshot :difference nil}])

(deftest close-panel-overview-drills-per-account
  (let [h (html (tv/close-panel
                 {:rows mixed-rows
                  :gate {:unreconciled 2 :uncategorized 1 :all-reconciled? false
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
      (is (re-find #"2 to reconcile" h))
      (is (re-find #"Close month" h))
      (is (re-find #"disabled" h))
      (is (not (re-find #"Reopen" h))))))

(deftest close-panel-ready-enables-close
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "A" :status :reconciled :difference 0M}]
                  :gate {:unreconciled 0 :uncategorized 0 :all-reconciled? true
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
                          :boundary-status :drift :boundary-difference (bigdec "5.00")
                          :coverage {:status :partial :uncovered 1 :first-uncovered #inst "2026-05-20"}
                          :statements []}}))]
    (testing "the focused account's card renders, not the overview list or gate"
      (is (re-find #"reconcile-focus" h))
      (is (re-find #"Visa" h))
      (is (not (re-find #"reconcile-rows" h)) "the overview list is replaced by the focused card")
      (is (not (re-find #"Close month" h)) "the month gate/Close live in the overview only"))
    (testing "the coverage headline leads the card with the account-level verdict"
      (is (re-find #"not yet covered" h)))
    (testing "opening/closing fields carry their app-owned end-of-day dates + prefilled values"
      (is (re-find #"Opening" h))
      (is (re-find #"Closing" h))
      (is (re-find #"end of Apr 30, 2026" h))
      (is (re-find #"end of May 31, 2026" h))
      (is (re-find #"1190.00" h) "opening prefill seeded as the raw input value"))
    (testing "the month-end section's own period verdict + readout render (drift → off by)"
      (is (re-find #"Expected change" h))
      (is (re-find #"Tracked activity" h))
      (is (re-find #"Off by" h) "the boundary period's own verdict, scoped to that one span"))
    (testing "Back returns to the overview by clearing the account filter"
      (is (re-find #"reconcile-back" h))
      (is (re-find #"filter\.account = \[\]" h)))
    (testing "Save posts the balances"
      (is (re-find #"Save balances" h))
      (is (re-find #"/transactions/reconcile" h)))))

(deftest close-panel-focused-card-verdicts
  (testing "a reconciled account: the coverage headline reads Reconciled, the period verdict matches"
    (let [ok (html (tv/close-panel
                    {:rows [] :gate {}
                     :focus {:account-id 1 :name "Chequing" :opening 0M :closing 2000M
                             :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                             :expected 2000M :tracked 2000M
                             :boundary-status :reconciled :boundary-difference 0M
                             :coverage {:status :reconciled :uncovered 0 :first-uncovered nil}
                             :statements []}}))
          none (html (tv/close-panel
                      {:rows [] :gate {}
                       :focus {:account-id 1 :name "Chequing" :opening nil :closing nil
                               :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                               :expected nil :tracked 2000M
                               :boundary-status :no-snapshot :boundary-difference nil
                               :coverage {:status :no-snapshot :uncovered 0 :first-uncovered nil}
                               :statements []}}))]
      (is (re-find #"Reconciled" ok))
      (is (re-find #"This period matches" ok))
      (is (re-find #"Not checked yet" none)))))

(deftest close-panel-focused-card-lists-statements
  (let [h (html (tv/close-panel
                 {:rows [] :gate {} :closed? false
                  :focus {:account-id 2 :name "Visa"
                          :opening nil :closing nil
                          :opening-date #inst "2026-04-30" :closing-date #inst "2026-05-31"
                          :expected nil :tracked 45M
                          :boundary-status :no-snapshot :boundary-difference nil
                          :coverage {:status :partial :uncovered 1 :first-uncovered #inst "2026-05-20"}
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

;; --- Row rendering (splits-as-transactions: a part is a normal row + marker) --

(def ^:private plain-tx
  {:db/id 41 :transaction/payee "Superstore" :transaction/amount -85.00M
   :transaction/effective-description "weekly shop" :transaction/reconciled true
   :transaction/posted-date #inst "2026-05-05"
   :transaction/account {:db/id 100 :account/external-name "Visa"
                         :account/institution {:db/id 1 :institution/name "Bank"}}})

(def ^:private part-tx
  ;; A split part: a normal transaction plus the pulled :transaction/split-parent.
  {:db/id 51 :transaction/payee "Costco" :transaction/amount -60.00M
   :transaction/effective-description "food" :transaction/posted-date #inst "2026-05-06"
   :transaction/split-parent {:db/id 50 :transaction/amount -100.00M
                              :transaction/payee "Costco"}
   :transaction/account {:db/id 100 :account/external-name "Visa"
                         :account/institution {:db/id 1 :institution/name "Bank"}}})

(deftest normal-row-plain-transaction
  (let [h (html (tv/normal-row false plain-tx))]
    (testing "no split affordances on an unsplit row"
      (is (not (re-find #"is-split-part" h)))
      (is (not (re-find #"split-marker" h)))
      (is (not (re-find #"split-drift-badge" h))))
    (testing "the reconciled checkbox is live (server-confirmed toggle)"
      (is (re-find #"reconciled-checkbox" h))
      (is (re-find #"/transactions/41/reconciled/" h))
      (is (not (re-find #"disabled" h))))))

(deftest normal-row-split-part-gets-marker-and-class
  (let [h (html (tv/normal-row false part-tx))]
    (testing "the row is tagged is-split-part"
      (is (re-find #"is-split-part" h)))
    (testing "the payee cell carries a marker button that opens the PARENT's editor"
      (is (re-find #"split-marker" h))
      (is (re-find #"/transactions/50/split-editor" h) "targets the parent id, not the part's")
      (is (re-find #"Part of a split — view or edit" h))
      (is (re-find #"Part of a split of -\$100\.00" h) "title carries the formatted parent amount"))
    (testing "a part's checkbox is live like any row's"
      (is (re-find #"/transactions/51/reconciled/" h)))
    (testing "no drift badge when the family still reconciles"
      (is (not (re-find #"split-drift-badge" h))))))

(deftest normal-row-split-drift-badge
  (let [h (html (tv/normal-row false (assoc part-tx :transaction/split-drift true)))]
    (testing "a drifting part warns in the amount cell"
      (is (re-find #"split-drift-badge" h))
      (is (re-find #"Split no longer adds up" h)))))

(deftest tbody-renders-one-row-per-transaction
  (let [h (html (tv/tbody [plain-tx part-tx]))]
    (testing "every transaction is a single normal row — no parent/child row machinery"
      (is (= 2 (count (re-seq #"<tr" h))))
      (is (not (re-find #"is-split-parent" h)))
      (is (not (re-find #"split-child-row" h))))
    (testing "the stale flag still marks rows"
      (is (re-find #"is-stale" (html (tv/tbody [plain-tx] #{41})))))))

(deftest row-actions-carry-the-split-target
  (testing "a plain row's split menu target is the row itself"
    (let [h (html (tv/normal-row false plain-tx))]
      (is (re-find #"\$_rowMenuSplit = false" h))
      (is (re-find #"\$_rowMenuSplitTarget = 41" h))))
  (testing "a part's split menu target is its PARENT (Edit split opens the family's editor)"
    (let [h (html (tv/normal-row false part-tx))]
      (is (re-find #"\$_rowMenuSplit = true" h))
      (is (re-find #"\$_rowMenuSplitTarget = 50" h))))
  (testing "the shared menu's split item @gets the target, not the raw row id"
    (let [h (html (tv/row-actions-menu))]
      (is (re-find #"\$_rowMenuSplitTarget \+ &apos;/split-editor&apos;" h)))))

(deftest split-editor-modal-split-state-and-hint
  (let [parent {:db/id 50 :transaction/amount -100.00M :transaction/payee "Costco"
                :transaction/_split-parent [{:db/id 51 :transaction/amount -60.00M
                                             :transaction/split-order 0}
                                            {:db/id 52 :transaction/amount -40.00M
                                             :transaction/split-order 1}]}
        split-h (html (tv/split-editor-modal parent [{:id 51 :amount "60.00" :category-id nil
                                                      :memo nil :seed-cents -6000}
                                                     {:id 52 :amount "40.00" :category-id nil
                                                      :memo nil :seed-cents -4000}]))
        unsplit-h (html (tv/split-editor-modal (dissoc parent :transaction/_split-parent) []))]
    (testing "already-split comes from the live parts (:transaction/_split-parent)"
      (is (re-find #"Edit split" split-h))
      (is (re-find #"Un-split" split-h))
      (is (re-find #"Split transaction" unsplit-h))
      (is (not (re-find #"Un-split" unsplit-h))))
    (testing "the hint says parts may be categorized now or later"
      (is (re-find #"categorized now or later" split-h)))))

(deftest date-cell-shows-posted-hint-only-when-effective-differs
  (testing "same-day: a single short date (no year), no posted hint"
    (let [h (html (tv/date-cell {:transaction/date #inst "2025-01-15"
                                 :transaction/posted-date #inst "2025-01-15"
                                 :transaction/effective-posted-date #inst "2025-01-15"}))]
      (is (re-find #"Jan 15" h))
      (is (not (re-find #"posted" h)) "no hint when the effective date == the shown date")
      (is (not (re-find #"2025" h)) "the row date drops the year (the header carries it)")))
  (testing "posted later (no override): transaction date leads, an inline 'posted <date>' follows"
    (let [h (html (tv/date-cell {:transaction/date #inst "2025-01-30"
                                 :transaction/posted-date #inst "2025-02-01"
                                 :transaction/effective-posted-date #inst "2025-02-01"}))]
      (is (re-find #"Jan 30" h) "leads with the transaction date")
      (is (re-find #"posted Feb 1" h) "carries the effective (here, provider posted) date inline")
      (is (re-find #"posted-hint" h))
      (is (not (re-find #"posted-hint--manual" h)) "no override → not the manual class")))
  (testing "legacy row with no authorized date falls back cleanly (posted-date only)"
    (let [h (html (tv/date-cell {:transaction/posted-date #inst "2025-01-20"
                                 :transaction/effective-posted-date #inst "2025-01-20"}))]
      (is (re-find #"Jan 20" h))
      (is (not (re-find #"posted" h)))))
  (testing "manual override: the hint renders the EFFECTIVE (overridden) date and is marked manual"
    (let [h (html (tv/date-cell {:transaction/date #inst "2025-01-15"
                                 :transaction/posted-date #inst "2025-01-15"
                                 :transaction/effective-posted-date #inst "2025-01-20"
                                 :transaction/user-posted-date #inst "2025-01-20"}))]
      (is (re-find #"Jan 15" h) "the transaction date is unchanged")
      (is (re-find #"posted Jan 20" h) "the hint shows the override, not the imported posted-date")
      (is (re-find #"posted-hint--manual" h))
      (is (re-find #"Posted date set manually" h) "carries the explanatory title"))))

(deftest view-menu-carries-posted-toggle-and-columns
  (let [h (html (tv/column-picker))]
    (testing "the toolbar button reads View and opens a Display group"
      (is (re-find #">View<" h) "button label renamed from Columns to View")
      (is (re-find #"Display" h))
      (is (re-find #"Posted dates" h)))
    (testing "the posted-dates toggle binds $showPosted; the column list still binds cols.<id>"
      (is (re-find #"data-bind=\"showPosted\"" h))
      (is (re-find #"data-bind=\"cols.date\"" h)))
    (testing "Columns group + Reset widths footer remain"
      (is (re-find #"Columns" h))
      (is (re-find #"Reset widths" h)))))

(deftest table-hide-class-includes-posted
  (testing "the table's data-class flips hide-posted off !$showPosted alongside the column classes"
    (let [cls (tv/table-hide-class)]
      (is (re-find #"'hide-posted': !\$showPosted" cls))
      (is (re-find #"'hide-date': !\$cols.date" cls) "per-column classes still present"))))

;; --- Two-level sort: header click JS + indicators --------------------------
;; sort-click-js is tested on its RAW string return (not rendered through `html`) — hiccup2
;; escapes attribute values (single quotes → &apos;, & → &amp;; see the &apos; assertions
;; elsewhere in this file), which would make quote-heavy JS regexes fight the escaping instead
;; of the logic. The `th`/`month-navigator`/`active-filters` tests below DO render, so their
;; regexes are written against the escaped form.

(deftest sort-click-js-non-primary-demotes-and-promotes
  (let [js (tv/sort-click-js "payee")]
    (testing "not the current primary → demote it to secondary, clicked column becomes primary asc"
      (is (re-find #"\$sortCol2 = \$sortCol \|\| 'date'" js))
      (is (re-find #"\$sortDir2 = \$sortCol \? \$sortDir : 'asc'" js))
      (is (re-find #"\$sortCol = 'payee', \$sortDir = 'asc'" js)))
    (testing "always resets to page 0 and re-fetches"
      (is (re-find #"\$page = 0; @get\('/transactions/rows'\)" js)))))

(deftest sort-click-js-current-primary-cycles-asc-desc-then-clears
  (let [js (tv/sort-click-js "amount")]
    (testing "asc → desc keeps the column explicit (survives a blank→explicit primary)"
      (is (re-find #"\$sortDir = 'desc', \$sortCol = \$sortCol \|\| 'amount'" js)))
    (testing "desc → promote the secondary, or blank when there's none"
      (is (re-find #"\$sortCol = \$sortCol2, \$sortDir = \$sortDir2, \$sortCol2 = ''" js))
      (is (re-find #"\$sortCol = '', \$sortDir = 'asc', \$sortCol2 = ''" js)))
    (testing "the promotion guard: a secondary of date/asc collapses to blank instead"
      (is (re-find #"!\(\$sortCol2 === 'date' && \$sortDir2 === 'asc'\)" js)))))

(deftest sort-click-js-date-column-treats-blank-as-already-primary
  (let [js (tv/sort-click-js "date")]
    (is (re-find #"\(\$sortCol === 'date' \|\| \$sortCol === ''\)" js)
        "so clicking Date while the default (blank) is active cycles straight to desc")))

(deftest th-date-column-shows-ascending-by-default
  (let [h (html (tv/th {:id "date" :label "Date" :sortable true :min 80 :protected true}))]
    (testing "aria-sort and the primary indicator both treat a blank $sortCol as Date/asc"
      (is (re-find #"aria-sort" h))
      (is (re-find #"\(\$sortCol === &apos;date&apos; \|\| \$sortCol === &apos;&apos;\)" h)))))

(deftest th-non-date-column-has-no-blank-special-case
  (let [h (html (tv/th {:id "payee" :label "Payee" :sortable true :min 100}))]
    (is (re-find #"\$sortCol === &apos;payee&apos;" h))
    (is (not (re-find #"\$sortCol === &apos;&apos;" h))
        "only the Date header gets the default-encoding special case")))

(deftest th-renders-a-muted-secondary-indicator
  (let [h (html (tv/th {:id "payee" :label "Payee" :sortable true :min 100}))]
    (is (re-find #"th-sort-indicator--secondary" h))
    (is (re-find #"\$sortCol2 === &apos;payee&apos;" h))))

;; --- Month navigation preserves view state (Task B) -------------------------

(deftest month-navigator-keeps-href-fallback-and-preserves-state-on-click
  (let [h (html (tv/month-navigator {:year 2025 :month 6}))]
    (testing "hrefs remain the no-JS fallback, unchanged month math"
      (is (re-find #"href=\"/\?month=2025-05\"" h) "previous")
      (is (re-find #"href=\"/\?month=2025-07\"" h) "next"))
    (testing "data-on:click preserves the current query string and resets page, not a bare nav"
      (is (re-find #"evt.preventDefault\(\)" h))
      (is (re-find #"new URLSearchParams\(location.search\)" h))
      (is (re-find #"p.set\(&apos;month&apos;, &apos;2025-05&apos;\)" h))
      (is (re-find #"p.set\(&apos;month&apos;, &apos;2025-07&apos;\)" h))
      (is (re-find #"p.delete\(&apos;page&apos;\)" h))
      (is (re-find #"location.href = &apos;/\?&apos; \+ p" h)))))

;; --- Active-filter chips + Clear all (Task C) --------------------------------

(deftest active-filters-clear-all-visibility-and-reset
  (testing "no filters, clear-all? false → the row is hidden and carries no clear-all button"
    (let [h (html (tv/active-filters [] [] [] {:accounts #{} :institutions #{} :categories #{}} false))]
      (is (re-find #"hidden" h))
      (is (not (re-find #"active-chips-clear" h)))))
  (testing "clear-all? true with ZERO chips (e.g. only a search term is active) still shows the
            row — a search term has no chip of its own, but still needs a way to clear it"
    (let [h (html (tv/active-filters [] [] [] {:accounts #{} :institutions #{} :categories #{}} true))]
      (is (not (re-find #"hidden" h)))
      (is (re-find #"active-chips-clear" h))
      (is (re-find #"Clear all" h))))
  (testing "clear-all resets every filter signal, not sort or scope"
    (let [h (html (tv/active-filters [] [] [] {:accounts #{} :institutions #{} :categories #{}} true))]
      (is (re-find #"\$search = &apos;&apos;" h))
      (is (re-find #"\$uncat = false" h))
      (is (re-find #"\$hideTransfers = false" h))
      (is (re-find #"\$filter\.account = \[\]" h))
      (is (re-find #"\$filter\.institution = \[\]" h))
      (is (re-find #"\$filter\.category = \[\]" h))
      (is (re-find #"\$reconFrom = &apos;&apos;" h))
      (is (re-find #"\$reconTo = &apos;&apos;" h))
      (is (not (re-find #"\$sortCol" h)) "sort untouched")
      (is (not (re-find #"\$scope" h)) "scope (the work-queue mode) untouched")))
  (testing "a chip alone (clear-all? false) still shows the row without the clear-all button"
    (let [h (html (tv/active-filters [{:id 1 :label "Chequing"}] [] []
                                     {:accounts #{1} :institutions #{} :categories #{}} false))]
      (is (not (re-find #"hidden" h)))
      (is (re-find #"active-chip\"" h))
      (is (not (re-find #"active-chips-clear" h))))))

(deftest period-label-month-vs-narrowed-span
  (is (= "January 2025" (tv/period-label {:year 2025 :month 1}))
      "no narrowing → the whole month, with the year for reference")
  (is (= "Dec 28 – Jan 27, 2025"
         (tv/period-label {:year 2025 :month 1}
                          #inst "2025-12-28" #inst "2025-01-27"))
      "narrowed → the actual span shown, not the calendar month"))

(deftest statement-modal-add-vs-edit
  (let [add  (html (tv/statement-modal false {}))
        edit (html (tv/statement-modal true {:start "2026-04-16" :end "2026-05-16"}))]
    (testing "add mode: titled Add, four fields, posts to /statement, no delete"
      (is (re-find #"Add statement" add))
      (is (re-find #"st-start" add))
      (is (re-find #"st-end-bal" add))
      (is (re-find #"/transactions/statement" add))
      (is (not (re-find #"Delete" add))))
    (testing "edit mode: titled Edit and offers Delete"
      (is (re-find #"Edit statement" edit))
      (is (re-find #"Delete" edit)))
    (testing "date inputs are one-way: server-rendered :value + data-on:change, no data-bind
              (data-bind's write-back resets a native date input's segment editing)"
      (is (re-find #"value=\"2026-04-16\"" edit))
      (is (re-find #"value=\"2026-05-16\"" edit))
      (is (re-find #"\$stStart = el.value" add))
      (is (re-find #"\$stEnd = el.value" add))
      (is (not (re-find #"data-bind=\"stStart\"" add)))
      (is (not (re-find #"data-bind=\"stEnd\"" add))))
    (testing "the balance fields stay two-way bound (no segment editing to protect)"
      (is (re-find #"data-bind=\"stStartBal\"" add))
      (is (re-find #"data-bind=\"stEndBal\"" add)))))

(deftest posted-date-modal-no-override-vs-override
  (let [no-override (html (tv/posted-date-modal
                            {:db/id 42 :transaction/payee "Superstore" :transaction/amount -85.00M
                             :transaction/posted-date #inst "2025-01-05"}
                            "2025-01-05"))
        override    (html (tv/posted-date-modal
                            {:db/id 42 :transaction/payee "Superstore" :transaction/amount -85.00M
                             :transaction/posted-date #inst "2025-01-05"
                             :transaction/user-posted-date #inst "2025-01-08"}
                            "2025-01-08"))]
    (testing "title carries the payee; body puts to this row's posted-date route"
      (is (re-find #"Posted date .*Superstore" no-override))
      (is (re-find #"/transactions/42/posted-date" no-override)))
    (testing "no override yet: shows the imported date, no Clear button"
      (is (re-find #"Imported: Jan 5" no-override))
      (is (not (re-find #"Clear override" no-override))))
    (testing "an override exists: still shows the imported (provider) date, plus Clear"
      (is (re-find #"Imported: Jan 5" override))
      (is (re-find #"Clear override" override)))
    (testing "the date input is one-way: the effective date as :value + data-on:change, no data-bind"
      (is (re-find #"value=\"2025-01-08\"" override))
      (is (re-find #"\$postedDateValue = el.value" override))
      (is (not (re-find #"data-bind=\"postedDateValue\"" override)))))
  (testing "no imported posted-date at all reads as a dash (and a nil prefill renders blank)"
    (let [h (html (tv/posted-date-modal {:db/id 7 :transaction/amount 10.00M} nil))]
      (is (re-find #"Imported: —" h))
      (is (re-find #"\(no payee\)" h))
      (is (re-find #"value=\"\"" h)))))

(deftest add-transaction-modal-comboboxes-and-one-way-date
  (let [accounts [{:eid 100 :name "Chequing"} {:eid 200 :name "Visa"}]
        h (html (tv/add-transaction-modal accounts "2025-01-31" 100))]
    (testing "account + category are combobox triggers, not selects"
      (is (not (re-find #"<select" h)))
      (is (re-find #"form-combo-trigger" h))
      (is (re-find #"data-combo=\"account\"" h))
      (is (re-find #"data-combo=\"category\"" h)))
    (testing "the triggers show the current selection and name their courier inputs"
      (is (re-find #"Chequing" h) "the preselected account's name leads")
      (is (re-find #"Uncategorized" h) "no category yet")
      (is (re-find #"data-combo-courier=\"tx-account-courier\"" h))
      (is (re-find #"data-combo-courier=\"tx-category-courier\"" h)))
    (testing "the hidden couriers set the Datastar signals on change"
      (is (re-find #"\$txAccount = el.value" h))
      (is (re-find #"\$txCategory = el.value" h)))
    (testing "the account model travels in the DOM as a hidden #account-options list"
      (is (re-find #"account-options" h))
      (is (re-find #"data-id=\"200\"" h)))
    (testing "the date input is one-way: :value prefill + data-on:change, no data-bind"
      (is (re-find #"value=\"2025-01-31\"" h))
      (is (re-find #"\$txDate = el.value" h))
      (is (not (re-find #"data-bind=\"txDate\"" h))))
    (testing "the Save gate expression still reads the three required signals"
      (is (re-find #"\$txAccount &amp;&amp; \$txAmount &amp;&amp; \$txDate" h)))))
