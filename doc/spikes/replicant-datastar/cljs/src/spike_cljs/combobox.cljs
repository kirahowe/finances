(ns spike-cljs.combobox
  "Category combobox island in ClojureScript — the mirror of the TS+Zag one.

  Rendering: Replicant (CLJS) renders the markup with the SAME hiccup idiom as the
  server (spike.views). A11y/keyboard/filtering: the vendored <combobox-framework>
  web component (WAI-ARIA combobox pattern + Fuse.js fuzzy search), consumed as a
  plain custom element — no JS prop-getters, clean from CLJS. Shared logic:
  spike.shared (.cljc) supplies the category list, the exact same code the JVM
  server compiles.

  Note the advanced-compilation discipline: DOM string keys go through getAttribute
  / aset with string literals, never renamable property access."
  (:require [replicant.dom :as rd]
            [spike.shared :as shared]))

(defonce ^:private !state (atom nil)) ;; {:root el :cell el}

(defn- combobox-hiccup []
  [:combobox-framework
   [:input {:slot "input" :class "combobox-input" :placeholder "Filter category…"}]
   [:ul {:slot "list" :class "combobox-content"}
    (for [c shared/categories]
      [:li {:data-value c} c])]])

(defn- close! [refocus?]
  (when-let [{:keys [root cell]} @!state]
    (.remove root)
    (reset! !state nil)
    (when refocus? (.focus cell))))

(defn- commit! [cell value]
  (set! (.-textContent (.querySelector cell ".cell-view")) value)
  (let [tx-id (first (.split (.getAttribute cell "data-cell") ":"))]
    (js/fetch (str "/tx/" tx-id "/category")
              #js {:method "PUT"
                   :headers #js {"content-type" "application/json"}
                   :body (js/JSON.stringify #js {:category value})}))
  (close! true))

(defn- open! [cell]
  (close! false)
  (let [root  (js/document.createElement "div")
        r     (.getBoundingClientRect cell)
        style (.-style root)]
    (set! (.-className root) "zag-combobox")
    (set! (.-position style) "fixed")
    (set! (.-left style) (str (.-left r) "px"))
    (set! (.-top style) (str (.-top r) "px"))
    (set! (.-minWidth style) (str (.-width r) "px"))
    (.appendChild js/document.body root)
    (rd/render root (combobox-hiccup))            ;; Replicant renders the custom element
    (reset! !state {:root root :cell cell})
    (let [cbf   (.querySelector root "combobox-framework")
          input (.querySelector root ".combobox-input")]
      ;; combobox-framework moves DOM focus INTO the listbox (aria-selected on the
      ;; <li>), so we can't rely on an input-only handler. It fires `change` on a
      ;; real selection AND sets the input value to the chosen item — whereas its
      ;; autoselect-while-typing leaves the typed prefix in the input. So: commit on
      ;; `change` only when the input value equals the selected value (a real pick).
      (.addEventListener cbf "change"
                         (fn [_] (let [v (.getAttribute cbf "data-value")]
                                   (when (and v (pos? (.-length v)) (= v (.-value input)))
                                     (commit! cell v)))))
      ;; Escape / Tab handled on the element in capture, so they fire whether focus
      ;; is on the input or on a focused option.
      (.addEventListener cbf "keydown"
                         (fn [e]
                           (case (.-key e)
                             "Escape" (do (.preventDefault e) (close! true))
                             "Tab" (let [c cell]
                                     (.preventDefault e)
                                     (close! false)
                                     (.dispatchEvent c (js/KeyboardEvent.
                                                        "keydown" #js {:key "Tab"
                                                                       :shiftKey (.-shiftKey e)
                                                                       :bubbles true})))
                             nil))
                         true)
      (js/requestAnimationFrame (fn [] (.focus input))))))

(let [grid (js/document.getElementById "grid")]
  (.addEventListener grid "click"
                     (fn [e] (when-let [td (.closest (.-target e) ".combo-cell")] (open! td)))))
(.addEventListener js/document "open-combo" (fn [e] (open! (.. e -detail -td))))

(aset js/window "__comboOpen" (fn [] (boolean @!state)))
(js/console.log "cljs combobox island ready —" (count shared/categories) "categories"
                "(shared .cljc fmt:" (shared/cents->str -8423) ")")
