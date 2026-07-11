(ns finance-aggregator.web.inline-edit-test
  "The shared click-to-edit-in-place grammar, exercised with the transactions page's
   grid config (:grid? true) and the /setup account-rename config (:grid? false) — proving
   both the gridedit-dispatch branch and its absence, and that the courier/url/empty-label
   opts actually ride into the generated JS instead of the transactions page's old hardcoded
   strings."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.inline-edit :as ie]
   [finance-aggregator.web.render :as r]))

(def ^:private grid-opts
  ;; Mirrors transactions-view's desc-opts.
  {:cell-class "description-cell" :courier "editValue" :grid? true
   :button-class "description-button" :input-class "description-input"
   :input-aria-label "Edit description" :add-aria-label "Add description"
   :empty-label "—" :put-url "/transactions/41/description"})

(def ^:private plain-opts
  ;; Mirrors setup-view's name-opts.
  {:cell-class "account-name-cell" :courier "nameValue" :grid? false
   :button-class "account-name-button" :input-class "account-name-input"
   :input-aria-label "Rename Chequing" :add-aria-label nil
   :empty-label "Chequing" :put-url "/setup/account/acc-1/name"})

(defn- html [hiccup] (str (r/render hiccup)))

(deftest open-js-toggles-the-configured-cell-class
  (is (= "el.closest('.description-cell').classList.add('editing'); el.nextElementSibling.focus(); el.nextElementSibling.select()"
         (ie/open-js grid-opts)))
  (is (= "el.closest('.account-name-cell').classList.add('editing'); el.nextElementSibling.focus(); el.nextElementSibling.select()"
         (ie/open-js plain-opts))))

(deftest commit-js-carries-the-courier-url-and-empty-label
  (testing "grid config"
    (let [js (ie/commit-js grid-opts)]
      (is (str/includes? js "$editValue = el.value"))
      (is (str/includes? js "@put('/transactions/41/description')"))
      (is (str/includes? js "el.value || '—'"))
      (is (str/includes? js "el.closest('.description-cell').classList.remove('editing')"))))
  (testing "setup config: a different courier, url, and empty-label"
    (let [js (ie/commit-js plain-opts)]
      (is (str/includes? js "$nameValue = el.value"))
      (is (str/includes? js "@put('/setup/account/acc-1/name')"))
      (is (str/includes? js "el.value || 'Chequing'"))
      (is (str/includes? js "el.closest('.account-name-cell').classList.remove('editing')")))))

(deftest commit-js-escapes-an-apostrophe-in-the-empty-label
  ;; An account's provider name can carry an apostrophe (transactions' static "—" never
  ;; needed this) — unescaped, it would close the JS string literal early and break the
  ;; generated data-on:* expression.
  (let [js (ie/commit-js (assoc plain-opts :empty-label "Sam's Club Card"))]
    (is (str/includes? js "el.value || 'Sam\\'s Club Card'"))))

(deftest keydown-js-grid-dispatches-gridedit-non-grid-does-not
  (testing "grid config: Enter advances to the row below, Escape hands focus back — both
            reported to grid-nav via gridedit events, each stopPropagation'd so the same
            keystroke can't also run grid-nav's navigation handler"
    (let [js (ie/keydown-js grid-opts)]
      (is (str/includes? js "evt.key === 'Enter' && ("))
      (is (str/includes? js "evt.key === 'Escape'"))
      (is (str/includes? js "el.closest('[data-cell]').dispatchEvent(new CustomEvent('gridedit'"))
      (is (str/includes? js "{detail: {action: 'advance'}, bubbles: true}"))
      (is (str/includes? js "{detail: {action: 'cancel'}, bubbles: true}"))
      (is (= 2 (count (re-seq #"evt\.stopPropagation\(\)" js))))))
  (testing "setup config: no grid-nav on that page, so no gridedit dispatch"
    (let [js (ie/keydown-js plain-opts)]
      (is (str/includes? js "evt.key === 'Escape'"))
      (is (not (str/includes? js "gridedit")))
      (is (not (str/includes? js "data-cell"))))))

(deftest blur-js-guards-the-double-commit-with-the-configured-cell-class
  (is (str/includes? (ie/blur-js grid-opts) "el.closest('.description-cell').classList.contains('editing')"))
  (is (str/includes? (ie/blur-js plain-opts) "el.closest('.account-name-cell').classList.contains('editing')")))

(deftest editable-cell-renders-the-configured-button-and-input-classes
  (let [h (html (ie/editable-cell grid-opts "weekly shop"))]
    (is (str/includes? h "class=\"description-button\""))
    (is (str/includes? h "class=\"description-input\""))
    (is (str/includes? h ">weekly shop<"))
    (is (str/includes? h "value=\"weekly shop\""))
    (is (str/includes? h "aria-label=\"Edit description\"")))
  (let [h (html (ie/editable-cell plain-opts "My Chequing"))]
    (is (str/includes? h "class=\"account-name-button\""))
    (is (str/includes? h "class=\"account-name-input\""))
    (is (str/includes? h ">My Chequing<"))
    (is (str/includes? h "aria-label=\"Rename Chequing\""))))

(deftest editable-cell-blank-text-shows-empty-label
  (testing "grid config: a blank description reads as a dash + an Add-description affordance"
    (let [h (html (ie/editable-cell grid-opts ""))]
      (is (str/includes? h ">—<"))
      (is (str/includes? h "aria-label=\"Add description\""))))
  (testing "setup config: a blank name falls back to the provider name, no add-affordance"
    (let [h (html (ie/editable-cell plain-opts ""))]
      (is (str/includes? h ">Chequing<"))
      (is (not (str/includes? h "aria-label=\"Add"))))))
