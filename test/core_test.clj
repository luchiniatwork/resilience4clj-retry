(ns core-test
  (:require [resilience4clj-retry.core :as retry]
            [resilience4clj-retry.interval-functions :as i-fns]
            [clojure.test :refer :all]
            [clojure.string :as string]))

;; mock for an external call
(defn ^:private external-call
  ([n]
   (external-call n nil))
  ([n {:keys [fail? wait]}]
   (when wait
     (Thread/sleep wait))
   (if-not fail?
     (str "Hello " n "!")
     (throw (ex-info "Couldn't say hello" {:extra-info :here})))))

(defn ^:private external-call!
  [a]
  (Thread/sleep 250)
  (swap! a inc))

(deftest create-default-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            retry/create
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with? (str (type interval-function))
                             "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))

(deftest create-custom-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            (retry/create {:interval-function (i-fns/of-randomized 1000)})
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with? (str (type interval-function))
                             "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))
