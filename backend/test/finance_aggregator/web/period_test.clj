(ns finance-aggregator.web.period-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.utils :as utils]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.period :as period])
  (:import [java.time LocalDate]))

(defn- ld [y m d] (LocalDate/of y m d))
(defn- rng [y1 m1 d1 y2 m2 d2] {:kind :range :from (ld y1 m1 d1) :to (ld y2 m2 d2)})
(defn- mo [y m] {:kind :month :year y :month m})

(deftest parse-period-test
  (testing "month-only"
    (is (= (mo 2026 7) (period/parse {:month "2026-07"}))))

  (testing "both from/to valid, from <= to -> range"
    (is (= (rng 2026 6 10 2026 7 9) (period/parse {:from "2026-06-10" :to "2026-07-09"}))))

  (testing "from > to falls back to the month path"
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "2026-07-09" :to "2026-06-10"}))
        "month param still honored on fallback")
    (is (= (month/current) (dissoc (period/parse {:from "2026-07-09" :to "2026-06-10"}) :kind))
        "no month param -> current month"))

  (testing "only one of from/to present falls back to the month path"
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "2026-06-10"})))
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :to "2026-07-09"}))))

  (testing "malformed ISO falls back to the month path"
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "2026-6-1" :to "2026-07-09"})))
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "garbage" :to "2026-07-09"})))
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "2026-02-30" :to "2026-07-09"}))))

  (testing "blank strings fall back to the month path"
    (is (= (mo 2026 7) (period/parse {:month "2026-07" :from "" :to ""}))))

  (testing "from = to -> a single-day range"
    (is (= (rng 2026 7 9 2026 7 9) (period/parse {:from "2026-07-09" :to "2026-07-09"}))))

  (testing "all-nil -> the current month"
    (is (= (assoc (month/current) :kind :month) (period/parse {:month nil :from nil :to nil})))))

(deftest canonicalize-test
  (testing "exact single month -> :month"
    (is (= (mo 2026 7) (period/canonicalize (rng 2026 7 1 2026 7 31)))))

  (testing "leap February -> :month"
    (is (= (mo 2024 2) (period/canonicalize (rng 2024 2 1 2024 2 29)))))

  (testing "non-leap February -> :month"
    (is (= (mo 2026 2) (period/canonicalize (rng 2026 2 1 2026 2 28)))))

  (testing "exact two months stays :range"
    (is (= (rng 2026 4 1 2026 5 31) (period/canonicalize (rng 2026 4 1 2026 5 31)))))

  (testing "off-by-one spans stay :range"
    (is (= (rng 2026 7 1 2026 7 30) (period/canonicalize (rng 2026 7 1 2026 7 30))))
    (is (= (rng 2026 7 2 2026 7 31) (period/canonicalize (rng 2026 7 2 2026 7 31)))))

  (testing "a non-range period passes through unchanged"
    (is (= (mo 2026 7) (period/canonicalize (mo 2026 7)))))

  (testing "parse applies canonicalize: a month-shaped from/to pair -> :month"
    (is (= (mo 2026 7) (period/parse {:from "2026-07-01" :to "2026-07-31"})))))

(deftest url-params-and-signal-seed-test
  (testing "month shape"
    (is (= {"month" "2026-07"} (period/url-params (mo 2026 7))))
    (is (= {:month "2026-07" :from "" :to ""} (period/signal-seed (mo 2026 7)))))

  (testing "range shape"
    (let [p (rng 2026 6 10 2026 7 9)]
      (is (= {"from" "2026-06-10" "to" "2026-07-09"} (period/url-params p)))
      (is (= {:month "2026-07" :from "2026-06-10" :to "2026-07-09"} (period/signal-seed p))
          "signal-seed's :month is the containing month (the range's :to month)"))))

(deftest containing-month-test
  (testing "a month period is its own containing month"
    (is (= {:year 2026 :month 7} (period/containing-month (mo 2026 7)))))

  (testing "a range period's containing month is the month of its :to date"
    (is (= {:year 2026 :month 7} (period/containing-month (rng 2026 6 10 2026 7 9))))))

(deftest prev-and-next-month-test
  (testing "mid-year"
    (is (= (mo 2025 5) (period/prev (mo 2025 6))))
    (is (= (mo 2025 7) (period/next (mo 2025 6)))))
  (testing "year boundaries"
    (is (= (mo 2024 12) (period/prev (mo 2025 1))) "Jan -> prev Dec")
    (is (= (mo 2026 1) (period/next (mo 2025 12))) "Dec -> next Jan")))

(deftest prev-and-next-day-range-test
  (testing "a 30-day range slides by 30 days"
    (let [p (rng 2026 6 10 2026 7 9)]
      (is (= (rng 2026 5 11 2026 6 9) (period/prev p)))
      (is (= (rng 2026 7 10 2026 8 8) (period/next p)))))

  (testing "a single-day range slides by one day"
    (let [p (rng 2026 7 9 2026 7 9)]
      (is (= (rng 2026 7 8 2026 7 8) (period/prev p)))
      (is (= (rng 2026 7 10 2026 7 10) (period/next p)))))

  (testing "a slide across a year boundary"
    (let [p (rng 2025 12 25 2026 1 5)] ; 12 inclusive days
      (is (= (rng 2025 12 13 2025 12 24) (period/prev p)))
      (is (= (rng 2026 1 6 2026 1 17) (period/next p)))))

  (testing "a slide that lands exactly on a calendar month canonicalizes to :month"
    (is (= (mo 2026 6) (period/prev (rng 2026 7 1 2026 7 30))))))

(deftest prev-and-next-full-month-range-test
  (testing "an exact 3-month span shifts by 3 whole months, staying aligned"
    (let [p (rng 2026 4 1 2026 6 30)]
      (is (= (rng 2026 1 1 2026 3 31) (period/prev p)))
      (is (= (rng 2026 7 1 2026 9 30) (period/next p)))))

  (testing "calendar-aware, not day-count: Jan 1 - Feb 28 2026 -> next Mar 1 - Apr 30"
    (is (= (rng 2026 3 1 2026 4 30) (period/next (rng 2026 1 1 2026 2 28)))))

  (testing "cross-year"
    (is (= (rng 2025 10 1 2025 12 31) (period/prev (rng 2026 1 1 2026 3 31))))))

(deftest date-range-test
  (testing "a month period's date-range equals utils/month-date-range of the same month"
    (is (= (utils/month-date-range "2026-07") (period/date-range (mo 2026 7)))))

  (testing "a range period's start/end are the exact UTC epoch instants"
    (is (= {:start-date (utils/string->date "2026-06-10")
            :end-date (utils/string->date "2026-07-10")}
           (period/date-range (rng 2026 6 10 2026 7 9))))))

(deftest month?-test
  (is (true? (period/month? (mo 2026 7))))
  (is (false? (period/month? (rng 2026 6 10 2026 7 9)))))

(deftest range-dates-test
  (testing "inclusive both ends"
    (is (= {:from (utils/string->date "2026-06-10") :to (utils/string->date "2026-07-09")}
           (period/range-dates (rng 2026 6 10 2026 7 9)))))

  (testing "nil for a :month period"
    (is (nil? (period/range-dates (mo 2026 7))))))

(deftest quick-links-test
  (testing "a fixed today (2026-07-09): This month, 5 previous months, then two range shortcuts"
    (let [links (period/quick-links (ld 2026 7 9))]
      (is (= 8 (count links)) "This month + 5 previous months + YTD + Last 90 days")
      (is (= [{:label "This month"    :period (mo 2026 7)}
              {:label "June 2026"     :period (mo 2026 6)}
              {:label "May 2026"      :period (mo 2026 5)}
              {:label "April 2026"    :period (mo 2026 4)}
              {:label "March 2026"    :period (mo 2026 3)}
              {:label "February 2026" :period (mo 2026 2)}
              {:label "Year to date"  :period (rng 2026 1 1 2026 7 9)}
              {:label "Last 90 days"  :period (rng 2026 4 11 2026 7 9)}]
             links))))

  (testing "previous months cross a year boundary"
    (let [links (period/quick-links (ld 2026 2 15))
          labels (map :label links)]
      (is (= ["This month" "January 2026" "December 2025" "November 2025"
              "October 2025" "September 2025" "Year to date" "Last 90 days"]
             labels))))

  (testing "on the last day of January, Year to date spans exactly the calendar month and
            canonicalizes to the :month shape — the rail then marks it selected when viewing
            January itself, same as the This month link"
    (let [links (period/quick-links (ld 2026 1 31))
          ytd (some #(when (= "Year to date" (:label %)) (:period %)) links)]
      (is (= (mo 2026 1) ytd)))))

(deftest picker-seed-test
  (testing "a month period seeds its own first/last day"
    (is (= {:picker-from "2026-07-01" :picker-to "2026-07-31"} (period/picker-seed (mo 2026 7)))))

  (testing "leap February"
    (is (= {:picker-from "2024-02-01" :picker-to "2024-02-29"} (period/picker-seed (mo 2024 2)))))

  (testing "non-leap February"
    (is (= {:picker-from "2026-02-01" :picker-to "2026-02-28"} (period/picker-seed (mo 2026 2)))))

  (testing "a range period seeds its own bounds"
    (is (= {:picker-from "2026-06-10" :picker-to "2026-07-09"}
           (period/picker-seed (rng 2026 6 10 2026 7 9))))))
