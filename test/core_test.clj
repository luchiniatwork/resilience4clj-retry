(ns core-test
  (:require [resilience4clj-retry.core :as retry]
            [resilience4clj-retry.interval-functions :as i-fns]
            [clojure.test :refer :all]
            [clojure.string :as string]))

;; mock factory for a failing an external call
(defn ^:private create-hello-works-after
  [x]
  (let [a (atom x)]
    (fn [n]
      (swap! a dec)
      (if (< @a 0)
        (str "Hello " n "!")
        (throw (ex-info "Couldn't say hello"
                        {:extra-info :here}))))))

(deftest create-default-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            retry/create
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with?
         (str (type interval-function))
         "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))

(deftest create-custom-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            (retry/create {:interval-function (i-fns/of-randomized 1000)})
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with?
         (str (type interval-function))
         "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))

(deftest works-after-3-tries-2-failures
  (let [protected (retry/decorate (create-hello-works-after 2)
                                  (retry/create "my-retry"
                                                {:max-attempts 3}))]
    (is (= "Hello Test!"
           (protected "Test")))))

(deftest works-after-10-tries-5-failures
  (let [protected (retry/decorate (create-hello-works-after 5)
                                  (retry/create "my-retry"
                                                {:max-attempts 10}))]
    (is (= "Hello Test!"
           (protected "Test")))))

(deftest fails-after-2-tries-3-failures
  (let [protected (retry/decorate (create-hello-works-after 3)
                                  (retry/create "my-retry"
                                                {:max-attempts 2}))]
    (is (thrown? clojure.lang.ExceptionInfo (protected "Test")))
    (try
      (protected "Test")
      (catch Throwable e
        (is (= :here)
            (-> e ex-data :extra-info))))))

(deftest default-wait-duration-is-500ms
  (let [works-after 4
        wait-duration 500.0
        error-margin 0.025
        protected (retry/decorate (create-hello-works-after works-after)
                                  (retry/create "my-retry"
                                                {:max-attempts (inc works-after)}))
        start (. System (nanoTime))
        target-end (double (* wait-duration works-after))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))

(deftest wait-duration-is-1000ms-works
  (let [works-after 3
        wait-duration 750.0
        error-margin 0.025
        protected (retry/decorate (create-hello-works-after works-after)
                                  (retry/create "my-retry"
                                                {:max-attempts (inc works-after)
                                                 :wait-duration wait-duration}))
        start (. System (nanoTime))
        target-end (double (* wait-duration works-after))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))

(deftest default-interval-function-should-be-the-same-as-default
  (let [works-after 4
        wait-duration 500.0
        error-margin 0.025
        protected (retry/decorate (create-hello-works-after works-after)
                                  (retry/create "my-retry"
                                                {:max-attempts (inc works-after)
                                                 :interval-function
                                                 (i-fns/of-default)}))
        start (. System (nanoTime))
        target-end (double (* wait-duration works-after))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))

(deftest interval-function-of-millis
  (let [works-after 4
        wait-duration 300.0
        error-margin 0.025
        protected (retry/decorate (create-hello-works-after works-after)
                                  (retry/create "my-retry"
                                                {:max-attempts (inc works-after)
                                                 :interval-function
                                                 (i-fns/of-millis wait-duration)}))
        start (. System (nanoTime))
        target-end (double (* wait-duration works-after))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))


(deftest interval-function-of-exponential-backoff
  (let [works-after 10
        initial-interval 50.0
        multiplier 1.5
        error-margin 0.025
        my-retry (retry/create
                  "my-retry"
                  {:max-attempts (inc works-after)
                   :interval-function (i-fns/of-exponential-backoff
                                       initial-interval)})
        protected (retry/decorate (create-hello-works-after works-after)
                                  my-retry)
        start (. System (nanoTime))
        target-end (reduce-kv
                    (fn [a i v]
                      (+ a (* v (Math/pow multiplier i))))
                    0.0 (vec (repeat works-after initial-interval)))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))


(deftest interval-function-of-exponential-backoff-specified-multiplier
  (let [works-after 10
        initial-interval 50.0
        multiplier 1.2
        error-margin 0.025
        my-retry (retry/create "my-retry"
                               {:max-attempts (inc works-after)
                                :interval-function (i-fns/of-exponential-backoff
                                                    initial-interval multiplier)})
        protected (retry/decorate (create-hello-works-after works-after)
                                  my-retry)
        start (. System (nanoTime))
        target-end (reduce-kv
                    (fn [a i v]
                      (+ a (* v (Math/pow multiplier i))))
                    0.0 (vec (repeat works-after initial-interval)))]
    (is (= "Hello Test!"
           (protected "Test")))
    (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
      (is (> end (* target-end (- 1 error-margin))))
      (is (< end (* target-end (+ 1 error-margin)))))))

(deftest fallback-function
  (testing "non fallback option"
    (let [my-retry (retry/create "MyService" {:max-attempts 1})
          decorated (retry/decorate (create-hello-works-after 2) my-retry)]
      (is (thrown? Throwable (decorated "World!")))
      (try
        (decorated "World!")
        (catch Throwable e
          (is (= :here
                 (-> e ex-data :extra-info)))))))

  (testing "with fallback option"
    (let [fallback-fn (fn [n {:keys [cause]}]
                        (str "It should say Hello " n " but it didn't "
                             "because of a problem " (-> cause ex-data :extra-info name)))
          my-retry (retry/create "MyService" {:max-attempts 1})
          decorated (retry/decorate (create-hello-works-after 2)
                                    my-retry
                                    {:fallback fallback-fn})]
      (is (= "It should say Hello World! but it didn't because of a problem here"
             (decorated "World!"))))))
