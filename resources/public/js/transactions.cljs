(ns finance-aggregator.ui.transactions
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

;; Transaction state management
(rf/reg-event-db
  ::set-transactions
  (fn [db [_ transactions]]
    (assoc db :transactions transactions)))

(rf/reg-event-db
  ::set-page-index
  (fn [db [_ page-index]]
    (assoc-in db [:transactions-table :page-index] page-index)))

(rf/reg-event-db
  ::set-page-size
  (fn [db [_ page-size]]
    (assoc-in db [:transactions-table :page-size] page-size)))

(rf/reg-event-db
  ::set-editing-transaction
  (fn [db [_ tx-id]]
    (assoc-in db [:transactions-table :editing-tx-id] tx-id)))

(rf/reg-event-db
  ::update-transaction-category-optimistic
  (fn [db [_ tx-id category]]
    (let [transactions (:transactions db)
          updated-txs (mapv (fn [tx]
                              (if (= (:db/id tx) tx-id)
                                (assoc tx :transaction/category category)
                                tx))
                            transactions)]
      (assoc db :transactions updated-txs))))

;; Subscriptions
(rf/reg-sub
  ::transactions
  (fn [db _]
    (:transactions db)))

(rf/reg-sub
  ::page-index
  (fn [db _]
    (get-in db [:transactions-table :page-index] 0)))

(rf/reg-sub
  ::page-size
  (fn [db _]
    (get-in db [:transactions-table :page-size] 20)))

(rf/reg-sub
  ::editing-transaction
  (fn [db _]
    (get-in db [:transactions-table :editing-tx-id])))

;; API calls
(defn fetch-json [url on-success on-error]
  (-> (js/fetch url)
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. (str "HTTP error: " (.-status response)))))))
      (.then (fn [js-data]
               (let [clj-data (js->clj js-data :keywordize-keys true)]
                 (on-success clj-data))))
      (.catch on-error)))

(rf/reg-event-fx
  ::fetch-transactions
  (fn [{:keys [db]} _]
    {:db (update db :loading conj :transactions)
     :fx [[:dispatch-later {:ms 0
                            :dispatch [::do-fetch-transactions]}]]}))

(rf/reg-event-fx
  ::do-fetch-transactions
  (fn [_ _]
    (fetch-json
      "/api/transactions"
      (fn [data]
        (rf/dispatch [:finance-aggregator.ui/set-loading :transactions false])
        (if (:success data)
          (rf/dispatch [::set-transactions (:data data)])
          (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))
      (fn [error]
        (rf/dispatch [:finance-aggregator.ui/set-loading :transactions false])
        (rf/dispatch [:finance-aggregator.ui/set-error (str error)])))
    {}))

(rf/reg-event-fx
  ::update-transaction-category
  (fn [{:keys [db]} [_ tx-id category-id]]
    ;; Find the category object
    (let [category (when category-id
                     (first (filter #(= (:db/id %) category-id)
                                   (get-in db [:categories]))))
          ;; Store original transaction for rollback
          original-tx (first (filter #(= (:db/id %) tx-id) (:transactions db)))]
      ;; Optimistic update
      (rf/dispatch [::update-transaction-category-optimistic tx-id category])
      (rf/dispatch [::set-editing-transaction nil])

      ;; Make API call
      (-> (js/fetch (str "/api/transactions/" tx-id "/category")
                    #js {:method "PUT"
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify #js {:categoryId category-id})})
          (.then (fn [response]
                   (if (.-ok response)
                     (.json response)
                     (throw (js/Error. (str "HTTP error: " (.-status response)))))))
          (.then (fn [js-data]
                   (let [data (js->clj js-data :keywordize-keys true)]
                     (when-not (:success data)
                       ;; Rollback on failure
                       (rf/dispatch [::update-transaction-category-optimistic
                                    tx-id
                                    (:transaction/category original-tx)])
                       (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))))
          (.catch (fn [error]
                    ;; Rollback on error
                    (rf/dispatch [::update-transaction-category-optimistic
                                 tx-id
                                 (:transaction/category original-tx)])
                    (rf/dispatch [:finance-aggregator.ui/set-error (str error)]))))
      {})))

;; Helper functions
(defn format-amount [amount]
  (when amount
    (.toFixed (js/Number. amount) 2)))

(defn format-date [date-str]
  (when date-str
    (try
      (.toLocaleDateString (js/Date. date-str))
      (catch js/Error _
        date-str))))

;; TanStack Table integration
(def React js/React)
(def useState (.-useState React))
(def useReactTable (.-useReactTable (.-ReactTable js/window)))
(def getCoreRowModel (.-getCoreRowModel (.-ReactTable js/window)))
(def getSortedRowModel (.-getSortedRowModel (.-ReactTable js/window)))
(def getFilteredRowModel (.-getFilteredRowModel (.-ReactTable js/window)))
(def flexRender (.-flexRender (.-ReactTable js/window)))

;; Define columns as plain JS objects
(def columns
  #js [#js {:id "posted-date"
            :accessorFn (fn [row] (or (aget row "transaction/posted-date") ""))
            :header "Date"
            :cell (fn [info] (format-date (.getValue info)))}
       #js {:id "payee"
            :accessorFn (fn [row] (or (aget row "transaction/payee") ""))
            :header "Payee"
            :cell (fn [info] (.getValue info))}
       #js {:id "description"
            :accessorFn (fn [row] (or (aget row "transaction/description") ""))
            :header "Description"
            :cell (fn [info] (.getValue info))}
       #js {:id "amount"
            :accessorFn (fn [row] (or (aget row "transaction/amount") 0))
            :header "Amount"
            :cell (fn [info]
                    (let [amount (.getValue info)]
                      (str "$" (format-amount amount))))}
       #js {:id "category"
            :accessorFn (fn [row]
                          (when-let [cat (aget row "transaction/category")]
                            (or (aget cat "category/name") "Uncategorized")))
            :header "Category"
            :cell (fn [info]
                    (let [createElement (.-createElement React)
                          row-data (.-original (.-row info))
                          tx-id (aget row-data "db/id")
                          meta (-> info .-table .-options .-meta)
                          editing-tx-id (when meta (.-editingTxId meta))
                          categories (when meta (.-categories meta))
                          on-edit-click (when meta (.-onEditClick meta))
                          on-category-select (when meta (.-onCategorySelect meta))
                          on-cancel (when meta (.-onCancel meta))
                          is-editing (= tx-id editing-tx-id)
                          current-category (aget row-data "transaction/category")
                          current-category-id (when current-category (aget current-category "db/id"))
                          category-name (or (.getValue info) "Uncategorized")]

                      (if is-editing
                        ;; Show dropdown when editing
                        (createElement "select"
                          #js {:autoFocus true
                               :defaultValue (or current-category-id "")
                               :onChange (fn [e]
                                          (let [val (-> e .-target .-value)]
                                            (when on-category-select
                                              (on-category-select tx-id (if (= val "") nil (js/parseInt val))))))
                               :onBlur (fn [_] (when on-cancel (on-cancel)))
                               :style #js {:width "100%"}}
                          (into-array
                            (concat
                              [(createElement "option" #js {:value "" :key "none"} "Uncategorized")]
                              (when categories
                                (map (fn [cat]
                                       (let [cat-id (aget cat "db/id")
                                             cat-name (aget cat "category/name")]
                                         (createElement "option" #js {:value cat-id :key cat-id} cat-name)))
                                     categories)))))
                        ;; Show category name when not editing
                        (createElement "span"
                          #js {:onClick (fn [_] (when on-edit-click (on-edit-click tx-id)))
                               :style #js {:cursor "pointer"
                                          :textDecoration "underline"
                                          :color "#007aff"}}
                          category-name))))}])

;; Create a pure React component directly in JavaScript
;; This allows us to use React hooks properly
(defonce TransactionsTable
  (let [createElement (.-createElement React)]
    (fn [props]
      (let [data (.-data props)
            page-index (.-pageIndex props)
            page-size (.-pageSize props)
            categories (.-categories props)
            editing-tx-id (.-editingTxId props)
            on-edit-click (.-onEditClick props)
            on-category-select (.-onCategorySelect props)
            on-cancel (.-onCancel props)

            ;; React hooks - these work because this is a real React function component
            sorting-state (useState #js [])
            sorting (aget sorting-state 0)
            set-sorting (aget sorting-state 1)

            ;; Create table instance
            table (useReactTable
                    #js {:data data
                         :columns columns
                         :state #js {:sorting sorting}
                         :onSortingChange set-sorting
                         :getCoreRowModel (getCoreRowModel)
                         :getSortedRowModel (getSortedRowModel)
                         :meta #js {:categories categories
                                    :editingTxId editing-tx-id
                                    :onEditClick on-edit-click
                                    :onCategorySelect on-category-select
                                    :onCancel on-cancel}})

            ;; Get rows and apply pagination
            all-rows (-> table .getRowModel .-rows)
            start-idx (* page-index page-size)
            end-idx (min (+ start-idx page-size) (.-length all-rows))
            page-rows (.slice all-rows start-idx end-idx)]

        ;; Return React elements
        (createElement "table" nil
          (createElement "thead" nil
            (into-array
              (for [header-group (.getHeaderGroups table)]
                (createElement "tr" #js {:key (.-id header-group)}
                  (into-array
                    (for [header (.-headers header-group)]
                      (createElement "th"
                        #js {:key (.-id header)
                             :onClick (when (.getCanSort (.-column header))
                                       (.getToggleSortingHandler (.-column header)))
                             :style #js {:cursor (when (.getCanSort (.-column header)) "pointer")
                                        :userSelect "none"}}
                        (when-not (.-isPlaceholder header)
                          (createElement "div" nil
                            (flexRender (-> header .-column .-columnDef .-header) (.getContext header))
                            (case (.getIsSorted (.-column header))
                              "asc" " ↑"
                              "desc" " ↓"
                              ""))))))))))
          (createElement "tbody" nil
            (into-array
              (for [row page-rows]
                (createElement "tr" #js {:key (.-id row)}
                  (into-array
                    (for [cell (.getVisibleCells row)]
                      (let [col-id (-> cell .-column .-id)
                            is-amount (= col-id "amount")
                            amount-class (when is-amount
                                          (if (pos? (or (js/Number. (.getValue cell)) 0))
                                            "transaction-amount positive"
                                            "transaction-amount negative"))]
                        (createElement "td"
                          #js {:key (.-id cell)
                               :className amount-class}
                          (flexRender (-> cell .-column .-columnDef .-cell) (.getContext cell)))))))))))))))

;; Transaction table component using TanStack Table
(defn transaction-table []
  (let [transactions @(rf/subscribe [::transactions])
        page-index @(rf/subscribe [::page-index])
        page-size @(rf/subscribe [::page-size])
        loading? @(rf/subscribe [:finance-aggregator.ui/loading? :transactions])
        categories @(rf/subscribe [:finance-aggregator.ui.categories/categories])
        editing-tx-id @(rf/subscribe [::editing-transaction])

        ;; Convert ClojureScript data to JavaScript for TanStack Table
        data (to-array (map #(clj->js % :keyword-fn (fn [k] (str (namespace k) "/" (name k)))) transactions))
        categories-js (to-array (map #(clj->js % :keyword-fn (fn [k] (str (namespace k) "/" (name k)))) categories))

        ;; Pagination
        total-rows (count transactions)
        total-pages (js/Math.ceil (/ total-rows page-size))]

    [:div.section
     [:h2 "Transactions"]
     [:div {:style {:display "flex" :gap "10px" :margin-bottom "15px" :align-items "center"}}
      [:button {:on-click #(rf/dispatch [::fetch-transactions])}
       "Refresh"]]

     (cond
       loading?
       [:div.loading "Loading transactions..."]

       (empty? transactions)
       [:p "No transactions loaded. Click 'Refresh' to fetch data."]

       :else
       [:div
        ;; Use the React component
        [:> TransactionsTable {:data data
                               :pageIndex page-index
                               :pageSize page-size
                               :categories categories-js
                               :editingTxId editing-tx-id
                               :onEditClick (fn [tx-id]
                                             (rf/dispatch [::set-editing-transaction tx-id]))
                               :onCategorySelect (fn [tx-id category-id]
                                                  (rf/dispatch [::update-transaction-category tx-id category-id]))
                               :onCancel (fn []
                                          (rf/dispatch [::set-editing-transaction nil]))}]

        ;; Pagination controls
        [:div {:style {:margin-top "15px" :display "flex" :gap "10px" :align-items "center"}}
         [:button {:on-click #(rf/dispatch [::set-page-index 0])
                   :disabled (= page-index 0)}
          "First"]
         [:button {:on-click #(rf/dispatch [::set-page-index (dec page-index)])
                   :disabled (= page-index 0)}
          "Previous"]
         [:span (str "Page " (inc page-index) " of " total-pages)]
         [:button {:on-click #(rf/dispatch [::set-page-index (inc page-index)])
                   :disabled (>= (inc page-index) total-pages)}
          "Next"]
         [:button {:on-click #(rf/dispatch [::set-page-index (dec total-pages)])
                   :disabled (>= (inc page-index) total-pages)}
          "Last"]
         [:select {:value page-size
                   :on-change #(do
                                (rf/dispatch [::set-page-size (js/Number. (-> % .-target .-value))])
                                (rf/dispatch [::set-page-index 0]))
                   :style {:padding "6px"}}
          [:option {:value 10} "10 per page"]
          [:option {:value 20} "20 per page"]
          [:option {:value 50} "50 per page"]
          [:option {:value 100} "100 per page"]]]])]))
