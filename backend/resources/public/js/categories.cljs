(ns finance-aggregator.ui.categories
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

;; Category state management
(rf/reg-event-db
  ::set-categories
  (fn [db [_ categories]]
    (assoc db :categories categories)))

(rf/reg-event-db
  ::show-category-form
  (fn [db [_ editing-id]]
    (if editing-id
      (let [category (first (filter #(= (:db/id %) editing-id) (:categories db)))]
        (assoc db :category-form {:show? true
                                  :editing-id editing-id
                                  :name (:category/name category)
                                  :type (:category/type category)}))
      (assoc db :category-form {:show? true :editing-id nil :name "" :type :expense}))))

(rf/reg-event-db
  ::hide-category-form
  (fn [db _]
    (assoc db :category-form {:show? false :editing-id nil :name "" :type :expense})))

(rf/reg-event-db
  ::update-category-form
  (fn [db [_ field value]]
    (assoc-in db [:category-form field] value)))

;; Subscriptions
(rf/reg-sub
  ::categories
  (fn [db _]
    (:categories db)))

(rf/reg-sub
  ::category-form
  (fn [db _]
    (:category-form db)))

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

(defn post-json [url data on-success on-error]
  (-> (js/fetch url
                (clj->js {:method "POST"
                         :headers {"Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js data))}))
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. (str "HTTP error: " (.-status response)))))))
      (.then (fn [js-data]
               (let [clj-data (js->clj js-data :keywordize-keys true)]
                 (on-success clj-data))))
      (.catch on-error)))

(defn put-json [url data on-success on-error]
  (-> (js/fetch url
                (clj->js {:method "PUT"
                         :headers {"Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js data))}))
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. (str "HTTP error: " (.-status response)))))))
      (.then (fn [js-data]
               (let [clj-data (js->clj js-data :keywordize-keys true)]
                 (on-success clj-data))))
      (.catch on-error)))

(defn delete-request [url on-success on-error]
  (-> (js/fetch url (clj->js {:method "DELETE"}))
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. (str "HTTP error: " (.-status response)))))))
      (.then (fn [js-data]
               (let [clj-data (js->clj js-data :keywordize-keys true)]
                 (on-success clj-data))))
      (.catch on-error)))

(rf/reg-event-fx
  ::fetch-categories
  (fn [{:keys [db]} _]
    {:db (update db :loading conj :categories)
     :fx [[:dispatch-later {:ms 0
                            :dispatch [::do-fetch-categories]}]]}))

(rf/reg-event-fx
  ::do-fetch-categories
  (fn [_ _]
    (fetch-json
      "/api/categories"
      (fn [data]
        (rf/dispatch [:finance-aggregator.ui/set-loading :categories false])
        (if (:success data)
          (rf/dispatch [::set-categories (:data data)])
          (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))
      (fn [error]
        (rf/dispatch [:finance-aggregator.ui/set-loading :categories false])
        (rf/dispatch [:finance-aggregator.ui/set-error (str error)])))
    {}))

(rf/reg-event-fx
  ::create-category
  (fn [{:keys [db]} [_ category-data]]
    (post-json
      "/api/categories"
      category-data
      (fn [data]
        (if (:success data)
          (do
            (rf/dispatch [::hide-category-form])
            (rf/dispatch [::fetch-categories]))
          (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))
      (fn [error]
        (rf/dispatch [:finance-aggregator.ui/set-error (str error)])))
    {}))

(rf/reg-event-fx
  ::update-category
  (fn [{:keys [db]} [_ category-id updates]]
    (put-json
      (str "/api/categories/" category-id)
      updates
      (fn [data]
        (if (:success data)
          (do
            (rf/dispatch [::hide-category-form])
            (rf/dispatch [::fetch-categories]))
          (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))
      (fn [error]
        (rf/dispatch [:finance-aggregator.ui/set-error (str error)])))
    {}))

(rf/reg-event-fx
  ::delete-category
  (fn [_ [_ category-id]]
    (when (js/confirm "Are you sure you want to delete this category?")
      (delete-request
        (str "/api/categories/" category-id)
        (fn [data]
          (if (:success data)
            (rf/dispatch [::fetch-categories])
            (rf/dispatch [:finance-aggregator.ui/set-error (:error data)])))
        (fn [error]
          (rf/dispatch [:finance-aggregator.ui/set-error (str error)]))))
    {}))

;; Components
(defn category-form []
  (let [form @(rf/subscribe [::category-form])]
    (when (:show? form)
      [:div
       ;; Backdrop
       [:div {:style {:position "fixed"
                      :top "0"
                      :left "0"
                      :right "0"
                      :bottom "0"
                      :background "rgba(0,0,0,0.5)"
                      :z-index "999"}
              :on-click #(rf/dispatch [::hide-category-form])}]
       ;; Modal
       [:div {:style {:position "fixed"
                      :top "50%"
                      :left "50%"
                      :transform "translate(-50%, -50%)"
                      :background "white"
                      :padding "30px"
                      :border-radius "8px"
                      :box-shadow "0 4px 20px rgba(0,0,0,0.3)"
                      :z-index "1000"
                      :min-width "400px"}}
       [:h3 {:style {:margin-top "0"}}
        (if (:editing-id form) "Edit Category" "New Category")]
       [:div {:style {:margin-bottom "15px"}}
        [:label {:style {:display "block" :margin-bottom "5px" :font-weight "600"}} "Name"]
        [:input {:type "text"
                 :value (:name form)
                 :on-change #(rf/dispatch [::update-category-form :name (-> % .-target .-value)])
                 :placeholder "e.g., Groceries"
                 :style {:width "100%" :padding "8px" :border "1px solid #ddd" :border-radius "4px"}}]]
       [:div {:style {:margin-bottom "20px"}}
        [:label {:style {:display "block" :margin-bottom "5px" :font-weight "600"}} "Type"]
        [:select {:value (name (:type form))
                  :on-change #(rf/dispatch [::update-category-form :type (keyword (-> % .-target .-value))])
                  :style {:width "100%" :padding "8px" :border "1px solid #ddd" :border-radius "4px"}}
         [:option {:value "expense"} "Expense"]
         [:option {:value "income"} "Income"]]]
       [:div {:style {:display "flex" :gap "10px" :justify-content "flex-end"}}
        [:button {:on-click #(rf/dispatch [::hide-category-form])
                  :style {:background "#ccc" :color "#333"}}
         "Cancel"]
        [:button {:on-click #(let [ident-str (str "category/" (clojure.string/lower-case (clojure.string/replace (:name form) #"\s+" "-")))
                                       category-data {:category/name (:name form)
                                                      :category/type (:type form)
                                                      :category/ident ident-str}]
                               (if-let [editing-id (:editing-id form)]
                                 (rf/dispatch [::update-category editing-id category-data])
                                 (rf/dispatch [::create-category category-data])))}
         "Save"]]]])))  ;; Close: actions div, modal div, wrapper div, when form

(defn category-list []
  (let [categories @(rf/subscribe [::categories])
        loading? @(rf/subscribe [:finance-aggregator.ui/loading? :categories])]
    [:div.section
     [:h2 "Categories"]
     [:button {:on-click #(rf/dispatch [::show-category-form nil])}
      "Add Category"]
     [:button {:on-click #(rf/dispatch [::fetch-categories])
               :style {:margin-left "10px"}}
      "Refresh"]
     (cond
       loading?
       [:div.loading "Loading categories..."]

       (empty? categories)
       [:p "No categories yet. Click 'Add Category' to create one."]

       :else
       [:table
        [:thead
         [:tr
          [:th "Name"]
          [:th "Type"]
          [:th "Actions"]]]
        [:tbody
         (for [category categories]
           ^{:key (:db/id category)}
           [:tr
            [:td (:category/name category)]
            [:td (name (:category/type category))]
            [:td
             [:button {:on-click #(rf/dispatch [::show-category-form (:db/id category)])
                       :style {:font-size "12px" :padding "5px 10px" :margin-right "5px"}}
              "Edit"]
             [:button {:on-click #(rf/dispatch [::delete-category (:db/id category)])
                       :style {:font-size "12px" :padding "5px 10px" :background "#d32f2f"}}
              "Delete"]]])]])
     [category-form]]))
