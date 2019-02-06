(ns resilience4clj-retry.interval-functions
  (:import
   (io.github.resilience4j.retry IntervalFunction)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-initial-interval []
  (IntervalFunction/DEFAULT_INITIAL_INTERVAL))

(defn default-multiplier []
  (IntervalFunction/DEFAULT_MULTIPLIER))

(defn of-default []
  (IntervalFunction/ofDefaults))

(defn of-millis [interval]
  (IntervalFunction/of ^Long interval))

(defn of-randomized
  ([initial-interval]
   (IntervalFunction/ofRandomized ^Long initial-interval))
  ([initial-interval factor]
   (IntervalFunction/ofRandomized ^Long initial-interval
                                  ^double factor)))

(defn of-exponential-backoff
  ([initial-interval]
   (IntervalFunction/ofExponentialBackoff ^Long initial-interval))
  ([initial-interval multiplier]
   (IntervalFunction/ofExponentialBackoff ^Long initial-interval
                                          ^double multiplier)))

(defn of-exponential-random-backoff
  ([initial-interval]
   (IntervalFunction/ofExponentialRandomBackoff ^Long initial-interval))
  ([initial-interval multiplier]
   (IntervalFunction/ofExponentialRandomBackoff ^Long initial-interval
                                                ^double multiplier))
  ([initial-interval multiplier factor]
   (IntervalFunction/ofExponentialRandomBackoff ^Long initial-interval
                                                ^double multiplier
                                                ^double factor)))
