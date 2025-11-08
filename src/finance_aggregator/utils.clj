(ns finance-aggregator.utils
  (:require
   [tick.core :as t])
  (:import [java.util Date]))

(defn epoch->date
  "Converts Unix epoch timestamp to a date in UTC."
  [timestamp]
  (-> timestamp (* 1000) t/instant Date/from))

(defn- ->epoch
  "Convert an Instant to epoch."
  [calendar-aware-timestamp]
  (-> calendar-aware-timestamp t/instant t/long))

(defn month-epoch-bounds
  "Given year & month (1–12), return {:start epoch-timestamp :end epoch-timestamp}
  (aligned to UTC month starts) with semantics matching SimpleFIN protocol
  (see https://www.simplefin.org/protocol.html), i.e. start timestamp is
  inclusive, end timestamp is exclusive."
  [year month]
  (let [start (-> (t/new-date year month 1)
                  (t/at (t/midnight))
                  (t/in "UTC"))
        end (t/>> start (t/of-months 1))]
    {:start (->epoch start)
     :end (->epoch end)}))
