(ns finance-aggregator.utils.month-epoch-bounds-test
  (:require
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [finance-aggregator.utils :as utils]
   [finance-aggregator.test-utils.setup :as setup]
   [tick.core :as t]))

(def year-gen
  "Generate reasonable years from 1970 to 2100"
  (gen/choose 1970 2100))

(def month-gen
  "Generate valid months 1-12"
  (gen/choose 1 12))

(def year-month-gen
  "Generate [year month] pairs"
  (gen/tuple year-gen month-gen))


(defspec start-date-is-before-end-date setup/test-times
  (prop/for-all [[year month] year-month-gen]
                (let [{:keys [start end]} (utils/month-epoch-bounds year month)]
                  (< start end))))

(defspec start-date-is-first-of-month-utc setup/test-times
  (prop/for-all [[year month] year-month-gen]
                (let [{:keys [start]} (utils/month-epoch-bounds year month)
                      start-datetime (-> start (* 1000) t/instant (t/in "UTC"))
                      day (t/day-of-month start-datetime)
                      hour (t/hour start-datetime)
                      minute (t/minute start-datetime)
                      second (t/second start-datetime)]
                  (and (= day 1)
                       (= hour 0)
                       (= minute 0)
                       (= second 0)))))

(defspec end-date-is-first-of-next-month-utc setup/test-times
  (prop/for-all [[year month] year-month-gen]
                (let [{:keys [start end]} (utils/month-epoch-bounds year month)
                      start-datetime (-> start (* 1000) t/instant (t/in "UTC"))
                      end-datetime (-> end (* 1000) t/instant (t/in "UTC"))
                      start-month (-> start-datetime t/month t/int)
                      end-month (-> end-datetime t/month t/int)
                      day (t/day-of-month end-datetime)
                      hour (t/hour end-datetime)
                      minute (t/minute end-datetime)
                      second (t/second end-datetime)]
                  (and (= end-month (-> start-month (mod 12) inc))
                       (= day 1)
                       (= hour 0)
                       (= minute 0)
                       (= second 0)))))

(defspec duration-matches-month-length setup/test-times
  (prop/for-all [[year month] year-month-gen]
                (let [{:keys [start end]} (utils/month-epoch-bounds year month)
                      start-datetime (-> start (* 1000) t/instant (t/in "UTC"))
                      end-datetime (-> end (* 1000) t/instant (t/in "UTC"))
                      duration-days (t/days (t/between start-datetime end-datetime))
                      days-in-month (t/day-of-month (t/last-day-of-month start-datetime))]
                  (= duration-days days-in-month))))

(defspec consecutive-months-are-contiguous setup/test-times
  (prop/for-all [[year month] year-month-gen]
                (let [{end1 :end} (utils/month-epoch-bounds year month)
                      next-month (-> month (mod 12) inc)
                      next-year (if (= month 12) (inc year) year)
                      {start2 :start} (utils/month-epoch-bounds next-year next-month)]
                  (= end1 start2))))

(defspec handles-leap-years-correctly setup/test-times
  (prop/for-all [year year-gen]
                (let [{:keys [start end]} (utils/month-epoch-bounds year 2)
                      start-datetime (-> start (* 1000) t/instant)
                      end-datetime (-> end (* 1000) t/instant)
                      duration-days (t/days (t/between start-datetime end-datetime))]
                  (if (.isLeap (t/year year))
                    (= duration-days 29)
                    (= duration-days 28)))))
