(ns finance-aggregator.web.statement-lens-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.statement-lens :as lens])
  (:import [java.time LocalDate]))

(defn- ld [y m d] (LocalDate/of y m d))
(defn- span [y1 m1 d1 y2 m2 d2] {:from (ld y1 m1 d1) :to (ld y2 m2 d2)})

(deftest adjacent-span-real-statement-test
  (testing "next: the earliest statement starting strictly after the current span"
    (let [spans [(span 2026 4 16 2026 5 16)   ; current
                 (span 2026 5 16 2026 6 16)
                 (span 2026 6 16 2026 7 16)]
          current (span 2026 4 16 2026 5 16)]
      (is (= (span 2026 5 16 2026 6 16) (lens/adjacent-span spans current :next)))))

  (testing "prev: the latest statement starting strictly before the current span"
    (let [spans [(span 2026 4 16 2026 5 16)
                 (span 2026 5 16 2026 6 16)
                 (span 2026 6 16 2026 7 16)]  ; current
          current (span 2026 6 16 2026 7 16)]
      (is (= (span 2026 5 16 2026 6 16) (lens/adjacent-span spans current :prev))))))

(deftest adjacent-span-no-statement-in-that-direction-falls-back-to-a-month-shift-test
  (testing "no next statement -> +1 calendar month on both ends"
    (let [spans [(span 2026 4 16 2026 5 16)]
          current (span 2026 4 16 2026 5 16)]
      (is (= (span 2026 5 16 2026 6 16) (lens/adjacent-span spans current :next)))))

  (testing "no prev statement -> -1 calendar month on both ends"
    (let [spans [(span 2026 4 16 2026 5 16)]
          current (span 2026 4 16 2026 5 16)]
      (is (= (span 2026 3 16 2026 4 16) (lens/adjacent-span spans current :prev)))))

  (testing "no statements at all -> still shifts the current span"
    (let [current (span 2026 4 16 2026 5 16)]
      (is (= (span 2026 5 16 2026 6 16) (lens/adjacent-span [] current :next)))
      (is (= (span 2026 3 16 2026 4 16) (lens/adjacent-span [] current :prev))))))

(deftest adjacent-span-month-end-clamp-test
  (testing "a span ending Jan 31 shifts to Feb 28 (day-of-month clamping is automatic)"
    (let [current (span 2026 1 3 2026 1 31)]
      (is (= (span 2026 2 3 2026 2 28) (lens/adjacent-span [] current :next)))))

  (testing "shifting backward off a Mar 31 end lands on Feb 28"
    (let [current (span 2026 3 3 2026 3 31)]
      (is (= (span 2026 2 3 2026 2 28) (lens/adjacent-span [] current :prev))))))

(deftest adjacent-span-current-sits-between-statements-test
  (testing "current isn't itself in `spans` (it's the live lens, not a statement) -- the nearest
            real statement in each direction is still found correctly around it"
    (let [spans [(span 2026 1 1 2026 1 31)
                 (span 2026 6 1 2026 6 30)]
          current (span 2026 3 1 2026 3 31)]
      (is (= (span 2026 6 1 2026 6 30) (lens/adjacent-span spans current :next)))
      (is (= (span 2026 1 1 2026 1 31) (lens/adjacent-span spans current :prev))))))

(deftest adjacent-span-same-start-day-not-chosen-test
  (testing "a statement starting the SAME day as current is never chosen, even as :next"
    (let [same-start (span 2026 4 16 2026 4 20)   ; different :to, same :from as current
          later (span 2026 5 1 2026 5 31)
          current (span 2026 4 16 2026 5 16)]
      (is (= later (lens/adjacent-span [same-start later] current :next)))))

  (testing "...nor as :prev"
    (let [same-start (span 2026 4 16 2026 4 20)
          earlier (span 2026 3 1 2026 3 31)
          current (span 2026 4 16 2026 5 16)]
      (is (= earlier (lens/adjacent-span [same-start earlier] current :prev))))))
