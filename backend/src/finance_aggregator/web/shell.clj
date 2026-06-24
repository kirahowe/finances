(ns finance-aggregator.web.shell
  "Shared page chrome (the masthead) for server-rendered pages."
  (:require
   [clojure.string :as str]
   [finance-aggregator.web.format :as fmt]))

(def ^:private nav-tabs
  [{:href "/"      :label "Transactions" :key :transactions}
   {:href "/setup" :label "Setup"        :key :setup}])

(defn masthead
  "The top chrome: wordmark, primary nav tabs, and the live stats bar.

   opts:
     :active — the active tab key (:transactions/:setup)
     :stats  — {:institutions :accounts :transactions} (optional)"
  [{:keys [active stats]}]
  [:header.masthead
   [:div.masthead-bar
    [:a.wordmark {:href "/"}
     [:span.wordmark-mark {:aria-hidden "true"}
      [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
             :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}
       [:polyline {:points "22 7 13.5 15.5 8.5 10.5 2 17"}]
       [:polyline {:points "16 7 22 7 22 13"}]]]
     [:h1.wordmark-text "Finance Aggregator"]]
    [:nav.view-tabs {:aria-label "Primary"}
     (for [{:keys [href label key quiet]} nav-tabs]
       [:a {:href href
            :class (str/join " " (cond-> ["view-tab"]
                                   quiet (conj "view-tab--quiet")
                                   (= key active) (conj "is-active")))}
        label])]
    (when stats
      [:div.masthead-stats
       [:span [:b (fmt/integer (:institutions stats))] " inst."]
       [:span.dot "·"]
       [:span [:b (fmt/integer (:accounts stats))] " acct."]
       [:span.dot "·"]
       [:span [:b (fmt/integer (:transactions stats))] " txns"]])]])
