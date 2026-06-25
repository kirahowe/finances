(ns finance-aggregator.provider.retry
  "Capped exponential backoff with jitter for transient sync failures - the
   generic scheduling math, no provider error vocabulary (which codes are
   transient is provider-specific; see e.g. finance-aggregator.plaid.errors).

   Retries are SCHEDULED, not in-process: the caller persists a retry-count and a
   next-retry-at on the connection, and the next resync pass picks the connection
   up when it's due. So this namespace is pure policy - compute the next delay,
   decide when the budget is spent, and where to land after it is.

   Default schedule (base 1m, x2, cap 15m, <=8 retries, 2h ceiling):
     retry #:   1   2   3   4    5    6    7    8
     wait(m):   1   2   4   8   15   15   15   15   -> budget spent at ~1h15m
   Bounded by BOTH attempt count and elapsed wall-clock (whichever hits first).
   Once the fast-backoff budget is spent we don't give up entirely: the
   connection falls back to a slow steady retry (`stale-retry-ms`) so a long
   institution outage still self-heals without hammering the provider."
  (:import
   [java.util Date]))

(def default-policy
  "Tune here; callers may pass an overriding policy map."
  {:base-delay-ms  60000     ; 1 minute
   :multiplier     2
   :max-delay-ms   900000    ; 15-minute per-attempt cap
   :max-retries    8         ; then the fast-backoff budget is spent
   :max-elapsed-ms 7200000   ; 2h hard ceiling, whichever hits first
   :stale-retry-ms 3600000   ; slow steady retry once the budget is spent (1h)
   :jitter         :equal})  ; :equal | :full | :none

(defn- backoff-ms
  "Capped exponential delay (ms) for a 0-indexed retry attempt, before jitter."
  [{:keys [base-delay-ms multiplier max-delay-ms]} attempt]
  (min (long max-delay-ms)
       (long (* base-delay-ms (Math/pow multiplier attempt)))))

(defn jitter-ms
  "Apply the policy's jitter to a raw delay. `rand-fn` returns a value in [0,1)
   (injectable for deterministic tests). :equal = half fixed + half random
   (keeps the backoff shape while de-aligning ticks); :full = uniform [0,raw];
   :none = unchanged."
  [{:keys [jitter]} raw rand-fn]
  (case jitter
    :none  raw
    :full  (long (* (rand-fn) raw))
    :equal (long (+ (/ raw 2) (* (rand-fn) (/ raw 2))))
    raw))

(defn next-delay-ms
  "Delay (ms) before retry number `attempt` (0-indexed): capped exponential plus
   the policy's jitter. Pass `rand-fn` for deterministic tests (defaults to rand)."
  ([policy attempt] (next-delay-ms policy attempt rand))
  ([policy attempt rand-fn]
   (jitter-ms policy (backoff-ms policy attempt) rand-fn)))

(defn next-retry-at
  "`now` (java.util.Date) plus the next delay. now is passed in - no ambient clock."
  (^Date [policy attempt now] (next-retry-at policy attempt now rand))
  (^Date [policy attempt ^Date now rand-fn]
   (Date. (+ (.getTime now) (long (next-delay-ms policy attempt rand-fn))))))

(defn stale-retry-at
  "Where to retry once the fast-backoff budget is spent: `now` plus the policy's
   slow `stale-retry-ms` cadence. Keeps a long-broken connection eligible at a
   bounded interval instead of every pass. now is passed in - no ambient clock."
  ^Date [{:keys [stale-retry-ms]} ^Date now]
  (Date. (+ (.getTime now) (long stale-retry-ms))))

(defn exhausted?
  "True when the fast-backoff budget is spent - by retry count or elapsed
   wall-clock, whichever first. `retry-count` = retries already made; `elapsed-ms`
   = since the first failure (nil = unknown, only the count bounds it)."
  [{:keys [max-retries max-elapsed-ms]} {:keys [retry-count elapsed-ms]}]
  (or (>= (or retry-count 0) max-retries)
      (boolean (and elapsed-ms (>= elapsed-ms max-elapsed-ms)))))
