(ns finance-aggregator.web.layout
  "Base HTML document shell for server-rendered (Replicant + Datastar) pages.

   Every page is a full document: the Datastar runtime is loaded once in the
   head (vendored at /js/datastar.js), the page's hiccup is the body, and any
   per-page islands are loaded as ES modules after the body so they run against
   server-rendered markup that already exists."
  (:require
   [finance-aggregator.web.hiccup :as h]))

(defn base-page
  "Render a full HTML page to a string.

   opts:
     :title    <title> text (default \"Finance Aggregator\")
     :signals  Clojure map serialized into the body's data-signals (optional)
     :islands  seq of island bundle names, each loaded as a module from
               /js/islands/<name>.js after the body (optional)
     :head     extra hiccup node(s) appended to <head> (optional)
   body — hiccup node(s) for the page content."
  [{:keys [title signals islands head]} & body]
  (let [island-scripts (for [island islands]
                         [:script {:type "module" :src (str "/js/islands/" island ".js")}])
        body-node (into (if signals
                          [:body (h/a {"data-signals" (h/signals signals)})]
                          [:body])
                        (concat body island-scripts))]
    (h/render-page
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title (or title "Finance Aggregator")]
       [:link {:rel "stylesheet" :href "/css/app.css"}]
       [:script {:type "module" :src "/js/datastar.js"}]
       head]
      body-node])))
