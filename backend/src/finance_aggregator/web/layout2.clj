(ns finance-aggregator.web.layout2
  "hiccup2 document shell for the server-authoritative pages — the replacement for the
   Replicant web.layout (deleted/renamed at Phase R4). Loads the Datastar runtime once
   and any per-page islands as modules after the body."
  (:require
   [finance-aggregator.web.render :as r]))

(defn document
  "Render a full HTML page to a string.

   opts: :title, :signals (Clojure map → data-signals JSON), :islands (module names
   loaded from /js/islands/<name>.js), :head (extra hiccup in <head>).
   body — hiccup node(s)."
  [{:keys [title signals islands head]} & body]
  (let [island-scripts (for [island islands]
                         [:script {:type "module" :src (str "/js/islands/" island ".js")}])]
    (r/render-page
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:meta {:name "theme-color" :content "#f6f3ec" :media "(prefers-color-scheme: light)"}]
       [:meta {:name "theme-color" :content "#15130d" :media "(prefers-color-scheme: dark)"}]
       [:title (or title "Finance Aggregator")]
       [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
       [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin "anonymous"}]
       [:link {:rel "stylesheet"
               :href (str "https://fonts.googleapis.com/css2?"
                          "family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,500;0,9..144,600;1,9..144,400"
                          "&family=Hanken+Grotesk:wght@400;500;600;700"
                          "&family=IBM+Plex+Mono:wght@400;500;600&display=swap")}]
       [:link {:rel "stylesheet" :href "/css/app.css"}]
       [:script {:type "module" :src "/js/datastar.js"}]
       head]
      (into (if signals
              [:body {"data-signals" (r/signals signals)}]
              [:body])
            (concat body island-scripts))])))
