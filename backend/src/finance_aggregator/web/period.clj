(ns finance-aggregator.web.period
  "The transactions page's viewed timespan. Months stay the canonical unit — the
   monthly-close workflow (doc/plans/monthly-close-handoff.md) lives there — and a
   range is an analysis lens layered on top of it. A range whose bounds exactly equal
   one calendar month ALWAYS canonicalizes to the :month shape (see `canonicalize`),
   so the month shape stays the one true representation of that span; nothing
   downstream ever has to treat an equivalent range and month period as different."
  (:refer-clojure :exclude [next])
  (:require [clojure.string :as str]
            [finance-aggregator.utils :as utils]
            [finance-aggregator.web.month :as month])
  (:import [java.time LocalDate YearMonth]))

(defn- parse-iso-date
  "Parse an ISO yyyy-MM-dd string to a LocalDate, or nil for blank/malformed input.
   Never throws — callers fall through to the month path on nil."
  ^LocalDate [s]
  (when-not (str/blank? s)
    (try (LocalDate/parse s) (catch Exception _ nil))))

(defn canonicalize
  "A :range whose bounds exactly span one calendar month (:from is the 1st, :to the
   last day, of that SAME month) collapses to the :month shape. A multi-month exact
   span (e.g. a quarter, Apr 1 - Jun 30) is a genuinely different shape and stays
   :range. Everything else passes through unchanged."
  [p]
  (if (= :range (:kind p))
    (let [^LocalDate from (:from p)
          ^LocalDate to (:to p)]
      (if (and (= (.getYear from) (.getYear to))
               (= (.getMonthValue from) (.getMonthValue to))
               (= 1 (.getDayOfMonth from))
               (= (.getDayOfMonth to) (.lengthOfMonth to)))
        {:kind :month :year (.getYear from) :month (.getMonthValue from)}
        p))
    p))

(defn parse
  "Parse {:month :from :to} — all strings-or-nil, straight off query params or Datastar
   signals — into a period. A valid from<=to ISO pair wins the range shape (canonicalized
   — see `canonicalize`); anything else (malformed dates, from > to, only one of the
   pair present, blank/nil) falls through to the month shape via `month/parse`, which
   already defaults nil/blank/invalid input to the current month. Never throws."
  [{:keys [month from to]}]
  (or (when (and from to)
        (let [f (parse-iso-date from)
              t (parse-iso-date to)]
          (when (and f t (not (.isAfter f t)))
            (canonicalize {:kind :range :from f :to t}))))
      (assoc (month/parse month) :kind :month)))

(defn url-params
  "The query-param map naming `p`. The two shapes are mutually exclusive in a URL —
   {\"month\" ...} for a month, {\"from\" ... \"to\" ...} for a range — so the caller
   deletes the other shape's keys when writing the address bar."
  [p]
  (case (:kind p)
    :month {"month" (month/serialize p)}
    :range {"from" (str (:from p)) "to" (str (:to p))}))

(defn containing-month
  "The {:year :month} `p` is anchored to for month-bound affordances/handlers: the
   month itself, or — for a range — the month of its :to date (the most recent month
   the range touches, where the × affordance and month-bound handlers land)."
  [p]
  (case (:kind p)
    :month (select-keys p [:year :month])
    :range (let [^LocalDate to (:to p)]
             {:year (.getYear to) :month (.getMonthValue to)})))

(defn signal-seed
  "The Datastar signal-map slice seeding the client for `p`. :month is ALWAYS the
   containing-month string, even in range view, so month-bound handlers keep working
   without special-casing range mode; :from/:to are the ISO strings for a range, blank
   for a month."
  [p]
  (let [month-str (month/serialize (containing-month p))]
    (case (:kind p)
      :month {:month month-str :from "" :to ""}
      :range {:month month-str :from (str (:from p)) :to (str (:to p))})))

(defn- full-month-span?
  "True when [from, to] exactly spans one or more whole calendar months: from is the
   1st of its month, to is the last day of its month."
  [^LocalDate from ^LocalDate to]
  (and (= 1 (.getDayOfMonth from))
       (= (.getDayOfMonth to) (.lengthOfMonth to))))

(defn- month-span-length
  "The count of whole calendar months [from, to] covers, inclusive (e.g. Apr 1 - Jun
   30 -> 3). Only meaningful when `full-month-span?` is true."
  [^LocalDate from ^LocalDate to]
  (inc (- (+ (* 12 (.getYear to)) (.getMonthValue to))
          (+ (* 12 (.getYear from)) (.getMonthValue from)))))

(defn- shift-full-month-range
  "Shift a full-month-aligned [from, to] by k whole calendar months (k may be
   negative), staying aligned to month boundaries on both ends — so a quarter-ish
   window keeps its full length across short months instead of drifting by day count."
  [^LocalDate from ^LocalDate to k]
  (let [^YearMonth from-ym0 (YearMonth/from from)
        ^YearMonth to-ym0 (YearMonth/from to)
        ^YearMonth from-ym (.plusMonths from-ym0 k)
        ^YearMonth to-ym (.plusMonths to-ym0 k)]
    {:kind :range :from (.atDay from-ym 1) :to (.atEndOfMonth to-ym)}))

(defn- day-count
  "The inclusive day-length of [from, to]."
  [^LocalDate from ^LocalDate to]
  (inc (- (.toEpochDay to) (.toEpochDay from))))

(defn- prev-day-range
  "Slide an n-day [from, to] backward by n days, keeping its length: [from-n, from-1]."
  [^LocalDate from ^LocalDate to]
  (let [n (day-count from to)]
    {:kind :range :from (.minusDays from n) :to (.minusDays from 1)}))

(defn- next-day-range
  "Slide an n-day [from, to] forward by n days, keeping its length: [to+1, to+n]."
  [^LocalDate from ^LocalDate to]
  (let [n (day-count from to)]
    {:kind :range :from (.plusDays to 1) :to (.plusDays to n)}))

(defn prev
  "The period one step earlier, canonicalized. A month steps to the previous calendar
   month. A range that's an exact k-month calendar span (k >= 2) steps back by k whole
   months, staying full-month aligned (so a quarter-ish window keeps its length across
   short months instead of drifting by day count). Any other range of n inclusive days
   slides back by n days, keeping the same length. Landing exactly on a calendar month
   canonicalizes the result away (see `canonicalize`) — deliberate: the month shape is
   that span's one true form."
  [p]
  (case (:kind p)
    :month (assoc (month/prev-month p) :kind :month)
    :range (let [^LocalDate from (:from p)
                 ^LocalDate to (:to p)]
             (canonicalize
              (if (and (full-month-span? from to) (>= (month-span-length from to) 2))
                (shift-full-month-range from to (- (month-span-length from to)))
                (prev-day-range from to))))))

(defn next
  "The period one step later, canonicalized — the mirror of `prev` (see its docstring)."
  [p]
  (case (:kind p)
    :month (assoc (month/next-month p) :kind :month)
    :range (let [^LocalDate from (:from p)
                 ^LocalDate to (:to p)]
             (canonicalize
              (if (and (full-month-span? from to) (>= (month-span-length from to) 2))
                (shift-full-month-range from to (month-span-length from to))
                (next-day-range from to))))))

(defn date-range
  "{:start-date :end-date} Dates spanning `p`, end EXCLUSIVE — exactly the contract
   `utils/month-date-range` already establishes for months (delegated to directly). A
   range's bounds are UTC day-granular: :from at 00:00:00 UTC, :to's next day at
   00:00:00 UTC, so the inclusive :to day itself is captured."
  [p]
  (case (:kind p)
    :month (utils/month-date-range (month/serialize p))
    :range (let [^LocalDate to (:to p)]
             {:start-date (utils/string->date (str (:from p)))
              :end-date (utils/string->date (str (.plusDays to 1)))})))

(defn month?
  "True when `p` is the :month shape."
  [p]
  (= :month (:kind p)))

(defn range-dates
  "{:from :to} Dates (inclusive BOTH ends, UTC midnight) for a :range period, or nil for
   a :month. The view layer feeds this to its own date-span formatter
   (web.format/date-span) — this ns stays formatting-free, it never requires web.format."
  [p]
  (when (= :range (:kind p))
    {:from (utils/string->date (str (:from p)))
     :to (utils/string->date (str (:to p)))}))

;; ---------------------------------------------------------------------------
;; Period picker (the popover under the dateline)
;; ---------------------------------------------------------------------------

(defn quick-links
  "The picker popover's LEFT RAIL: quick-jump links, each `{:label :period}` — exactly what
   the popover view renders as a period-href/period-nav-js anchor (identical state-preserving
   navigation to the prev/next arrows). Built once from `today` (a LocalDate) so the page's
   one clock read (the handler) feeds this, like every other today-dependent affordance; views
   never call `month/current` themselves. Fixed link order: this month, the five calendar
   months before it (most recent first), then two range shortcuts anchored to today — a range
   whose bounds exactly span one calendar month canonicalizes away (see `canonicalize`), which
   is why an on-the-last-day-of-January YTD reads as the January month link itself, not a
   distinct range."
  [^LocalDate today]
  (let [this-month (assoc {:year (.getYear today) :month (.getMonthValue today)} :kind :month)
        prev-months (take 5 (rest (iterate month/prev-month this-month)))
        month-link (fn [m] {:label (month/display m) :period (assoc m :kind :month)})
        year-start (LocalDate/of (.getYear today) 1 1)]
    (into [{:label "This month" :period this-month}]
          (concat
           (map month-link prev-months)
           [{:label "Year to date" :period (canonicalize {:kind :range :from year-start :to today})}
            {:label "Last 90 days" :period (canonicalize {:kind :range :from (.minusDays today 89) :to today})}]))))

(defn picker-seed
  "The custom-range footer's seed — {:picker-from :picker-to} ISO strings spanning `p`: a
   range's own bounds, or a month's first/last day. Kept distinct from `signal-seed`'s
   :from/:to (blank in month view — the client's own cue for which lens is active): the
   footer inputs always show SOMETHING, even in month view, so Apply starts from the span
   on screen instead of forcing the user to pick both ends from scratch."
  [p]
  (case (:kind p)
    :month (let [ym (YearMonth/of (:year p) (:month p))]
             {:picker-from (str (.atDay ym 1)) :picker-to (str (.atEndOfMonth ym))})
    :range {:picker-from (str (:from p)) :picker-to (str (:to p))}))
