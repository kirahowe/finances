(ns finance-aggregator.web.accounts
  "Pure presentation-data helpers for account rows on /setup — kept out of the view so the
   display rules (source label, provider-native type, ordering) are unit-testable rather than
   buried in hiccup."
  (:require
   [clojure.string :as str]))

(defn provider-label
  "Capitalized provider label for an account's source badge, or \"Unknown\"."
  [provider]
  (if provider (str/capitalize (name provider)) "Unknown"))

(defn display-type
  "The type to show for an account: the provider-native type[/subtype] when present, else the
   internal account type, else a dash."
  [{:account/keys [provider-type provider-subtype type]}]
  (cond
    provider-type (if provider-subtype (str provider-type " / " provider-subtype) provider-type)
    type          (name type)
    :else         "—"))

(defn sort-accounts
  "Accounts ordered for display (by external name)."
  [accounts]
  (sort-by :account/external-name accounts))
