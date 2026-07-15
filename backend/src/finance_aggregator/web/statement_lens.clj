(ns finance-aggregator.web.statement-lens
  "The reconcile panel's STATEMENT LENS (see `reconcile-range` in web.pages.transactions) as
   stepped by the transactions navigator's prev/next arrows while it's narrowing the table to
   one account's statement span. Adjacency is ordered by start-date: `adjacent-span` finds the
   account's real next/previous statement — the earliest one starting strictly after (next), or
   the latest one starting strictly before (prev), the current span's own start — so stepping
   walks the account's actual statement history instead of a fixed calendar cadence. When the
   account has no statement in that direction (the lens already sits on the first/last one, or
   the account has none at all), it falls back to shifting the CURRENT span by one calendar
   month on both ends (java.time's day-of-month clamping applies automatically — e.g. a span
   ending Jan 31 lands on Feb 28) — so the arrow always does *something* sensible instead of
   getting stuck with nowhere to go. Pure: LocalDates in, LocalDate out, no I/O — the caller
   (web.pages.transactions/statement-step) supplies the account's statement spans and converts
   the result back to java.util.Dates for the rest of the request."
  (:import [java.time LocalDate]))

(defn- month-shift
  "The fallback span: `current` {:from :to} shifted by `k` calendar months on both ends
   (k is 1 or -1 — the only two directions a step ever needs)."
  [{:keys [^LocalDate from ^LocalDate to]} k]
  {:from (.plusMonths from k) :to (.plusMonths to k)})

(defn adjacent-span
  "The account's real adjacent statement span in `dir` (:next or :prev) from `current`
   {:from :to} — `spans` is the account's statement spans (any order, `current` need not be
   among them: it's the live lens, not necessarily a statement itself). :next is the span with
   the earliest :from strictly AFTER current's :from; :prev is the span with the latest :from
   strictly BEFORE it — a span starting on the SAME day as `current` is never chosen, in either
   direction. Falls back to `month-shift`ing `current` itself when nothing qualifies in that
   direction. Never returns nil."
  [spans current dir]
  (let [^LocalDate cur-from (:from current)
        after? #(.isAfter ^LocalDate (:from %) cur-from)
        before? #(.isBefore ^LocalDate (:from %) cur-from)
        epoch-day #(.toEpochDay ^LocalDate (:from %))
        candidates (filter (case dir :next after? :prev before?) spans)]
    (if (seq candidates)
      (apply (case dir :next min-key :prev max-key) epoch-day candidates)
      (month-shift current (case dir :next 1 :prev -1)))))
