(ns finance-aggregator.web.inline-edit
  "The click-to-edit-in-place interaction grammar shared by two pages — the transactions
   table's description cell and the /setup account-rename cell: resting text in a button;
   clicking it swaps in a hidden sibling input (an `editing` class toggled on the enclosing
   cell, which the CSS uses to pick which one shows); Enter/blur optimistically updates the
   button's text, copies the input's value into a single courier signal, and @put's the
   server — which morphs the row back from the true persisted value, so the optimistic text
   is a guess, never trusted on its own; Escape reverts the input to its last-rendered value
   and closes the editor without a round trip. Two pages want exactly this grammar with
   different endpoints/classes/couriers, so it's factored here once instead of copy-pasted —
   see doc/architecture-and-conventions.md's View layer on shared interaction grammars
   getting their own namespace rather than being duplicated per page. Pure string/hiccup
   builders only: no I/O, no state, no signal-reading — the callers own their courier names
   and put URLs.

   opts (assembled per call — the caller has already resolved the row's id into :put-url and
   its own fallback text into :empty-label):
     :cell-class        — class toggled `editing` on the enclosing cell; also what
                          `el.closest` walks up to from the button/input
     :courier           — the Datastar courier signal name, no leading `$` (e.g. \"editValue\")
     :put-url           — the @put target this row's commit hits
     :empty-label       — text shown (in the resting button, and set optimistically on
                          commit) when the value is blank. The transactions description
                          cell uses a static \"—\"; the account-rename cell falls back to
                          that row's own provider name instead, so unlike the dash it can
                          carry an apostrophe — see js-string below
     :button-class :input-class — the resting button's / editing input's CSS classes
     :input-aria-label  — the input's aria-label
     :add-aria-label    — the button's aria-label when the value is blank (nil = none, same
                          as omitting the attribute entirely)
     :grid?             — true on grid-nav pages (transactions): Enter and Escape also
                          dispatch a `gridedit` CustomEvent (`advance` / `cancel`) so
                          keyboard nav follows the editor — Enter walks focus to the row
                          below, Escape gets focus back onto the cell (grid-nav's keydown
                          listener only fires inside the scroll container — without this,
                          a closed editor stalls focus on a hidden input). false on the
                          setup table, which has no grid-nav: `el.closest('[data-cell]')`
                          would find nothing there and throw."
  (:require
   [clojure.string :as str]))

(defn- js-string
  "Escape a value for embedding as a single-quoted JS string literal inside a `data-on:*`
   attribute. hiccup already HTML-escapes the surrounding attribute quotes; this guards the
   JS layer inside them — an account's provider name can carry an apostrophe, unlike the
   transactions page's static \"—\" fallback, which never needed this."
  [s]
  (str/replace (str s) #"['\\]" "\\\\$0"))

(defn open-js
  "Click-to-open: add `editing` to the enclosing cell, focus + select the sibling input."
  [{:keys [cell-class]}]
  (str "el.closest('." cell-class "').classList.add('editing');"
       " el.nextElementSibling.focus(); el.nextElementSibling.select()"))

(defn commit-js
  "Optimistically set the button's text, copy the input's value into its courier signal,
   @put the server, and close the editor."
  [{:keys [cell-class courier put-url empty-label]}]
  (str "el.previousElementSibling.textContent = el.value || '" (js-string empty-label) "',"
       " $" courier " = el.value, @put('" (js-string put-url) "'),"
       " el.closest('." cell-class "').classList.remove('editing')"))

(defn keydown-js
  "Enter commits; Escape reverts the input to its server value and closes the editor.
   Grid pages additionally report both to keyboard nav via the `gridedit` event (see the
   ns docstring's :grid? note): Enter dispatches `advance` (grid-nav moves focus to the
   row below) and Escape dispatches `cancel` (focus back onto the cell). Each branch
   stopPropagation's before its dispatch: the dispatch moves focus off this input
   synchronously, so the same keystroke bubbling on to grid-nav's navigation handler
   would re-open an editor on the just-landed cell (Enter) or clear the active cell
   (Escape)."
  [{:keys [cell-class grid?] :as opts}]
  (str "evt.key === 'Enter' && (" (commit-js opts)
       (if grid?
         (str ", evt.stopPropagation(), el.closest('[data-cell]').dispatchEvent(new CustomEvent('gridedit',"
              " {detail: {action: 'advance'}, bubbles: true})))")
         ")")
       "; "
       "evt.key === 'Escape' && (evt.stopPropagation(), el.value = el.defaultValue,"
       " el.closest('." cell-class "').classList.remove('editing')"
       (if grid?
         (str ", el.closest('[data-cell]').dispatchEvent(new CustomEvent('gridedit',"
              " {detail: {action: 'cancel'}, bubbles: true})))")
         ")")))

(defn blur-js
  "A genuine click-away commits; Enter/Escape already removed `editing`, so their trailing
   blur is a no-op (guards the double-commit)."
  [{:keys [cell-class] :as opts}]
  (str "el.closest('." cell-class "').classList.contains('editing') && (" (commit-js opts) ")"))

(defn editable-cell
  "The resting button + hidden sibling input pair that share one box. `text` is the current
   server value (blank renders `empty-label` instead); the enclosing cell — its class and
   the `editing` toggle — is the caller's, not built here."
  [{:keys [button-class input-class input-aria-label add-aria-label empty-label] :as opts} text]
  (list
   [(keyword (str "button." button-class))
    {:type "button" :tabindex "-1" "data-on:click" (open-js opts)
     :aria-label (when (str/blank? text) add-aria-label)}
    (if (str/blank? text) empty-label text)]
   [(keyword (str "input." input-class))
    {:type "text" :value text :aria-label input-aria-label
     "data-on:keydown" (keydown-js opts)
     "data-on:blur" (blur-js opts)}]))
