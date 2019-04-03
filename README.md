[about]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/blob/master/docs/ABOUT.md
[breaker]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/
[circleci-badge]: https://circleci.com/gh/luchiniatwork/resilience4clj-retry.svg?style=shield&circle-token=10db5b67206f6a8351da2e10a783b9667a68b8ac
[circleci]: https://circleci.com/gh/luchiniatwork/resilience4clj-retry
[clojars-badge]: https://img.shields.io/clojars/v/resilience4clj/resilience4clj-retry.svg
[clojars]: http://clojars.org/resilience4clj/resilience4clj-retry
[github-issues]: https://github.com/luchiniatwork/resilience4clj-retry/issues
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[status-badge]: https://img.shields.io/badge/project%20status-alpha-brightgreen.svg

# Resilience4Clj Retry

[![CircleCI][circleci-badge]][circleci]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

Resilience4clj is a lightweight fault tolerance library set built on
top of GitHub's Resilience4j and inspired by Netflix Hystrix. It was
designed for Clojure and functional programming with composability in
mind.

Read more about the [motivation and details of Resilience4clj
here][about].

Resilience4Clj Retry lets you decorate a function call with a
specified number of retry attempts. Once a successful call is reached,
the result is returned. The timing between attempts can be configured
in several different ways.

## Table of Contents

* [Getting Started](#getting-started)
* [Retry Settings](#limiter-settings)
* [Fallback Strategies](#fallback-strategies)
* [Metrics](#metrics)
* [Events](#events)
* [Exception Handling](#exception-handling)
* [Composing Further](#composing-further)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add `resilience4clj/resilience4clj-retry` as a dependency to your
`deps.edn` file:

``` clojure
resilience4clj/resilience4clj-retry {:mvn/version "0.1.0"}
```

If you are using `lein` instead, add it as a dependency to your
`project.clj` file:

``` clojure
[resilience4clj/resilience4clj-retry "0.1.0"]
```

Require the library:

``` clojure
(require '[resilience4clj-retry.core :as r])
```

Then create a retry calling the function `create`:

``` clojure
(def retry (r/create "my-retry"))
```

Now you can decorate any function you have with the retry you just
defined.

For the sake of this example, we will need to use a contrived
approach. In real life you probably have a function that calls an API
via a GET HTTP and, due to its network-based nature, this function
sometimes fails. In our contrived example we will use a function that
returns a function that _we know_ will fail for a certain amount of
times and it will start working:

``` clojure
(defn ^:private create-hello-works-after
  [x]
  (let [a (atom x)]
    (fn [n]
      (swap! a dec)
      (if (< @a 0)
        (str "Hello " n "!")
        (throw (ex-info "Couldn't say hello"
                        {:extra-info :here}))))))
```

You can use this function like this:

``` clojure
(def hello-world (create-hello-works-after 2))
```

Then you can call `(hello-world "John")` and it will fail the first
two times you call it before starting to work normally.

By default, `r/create` will create a retry that has a max of 3
attempts and a waiting period of 500ms between calls. You can now
create a decorated version of your `hello-world` function above and
the `retry` above like this:

``` clojure
(def protected (r/decorate hello-world retry))
```

When you call `protected` it will be successful but take around a full
1000ms to run. That's because retry will wait 500ms between calls and
we know `hello-world` fails twice:

``` clojure
(time (protected "John"))
"Elapsed time: 1002.160511 msecs"
Hello John!
```

## Retry Settings

When creating a retry, you can fine tune three of its settings:

1. `:max-attempts` - the amount of attempts it will try before giving
   up. Default is `3`.
2. `:wait-duration` - the duration in milliseconds between attempts.
   Default is `500`.
3. `:interval-function` - sometimes you need a more advanced strategy
   between attempts. Some systems react better with a progressive
   backoff for instance (you can start retrying faster but increasing
   the waiting time in case the remote system is offline). For these
   cases you can specify an interval function that will control this
   waiting time. See below for more. Default here is a linear function
   of `:wait-duration` intervals.

These three options can be sent to `create` as a map. In the following
example, any function decorated with `retry` will be attempted for 10
times with in 300ms intervals.

``` clojure
(def retry (create {:max-attempts 10
                    :wait-duration 300}))
```

Resilience4clj provides a series of commonly-used interval
functions. They are all in the namespace
`resilience4clj-retry.interval-functions`:

* `of-default` - basic linear function with 500ms intervals
* `of-millis` - linear function with a specified interval in
  milliseconds
* `of-randomized` - starts with an initial, specified interval in
  milliseconds and then randomizes by an optional factor on subsequent
  attempts
* `of-exponential-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier on subsequent calls (default multiplier is 1.5).
* `of-exponential-random-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier and randomizes by an optional factor on subsequent calls.

## Fallback Strategies

TBD: mostly copying from circuit breaker

## Metrics

TBD: {:number-of-successful-calls-without-retry-attempt 0,
 :number-of-failed-calls-without-retry-attempt 0,
 :number-of-successful-calls-with-retry-attempt 0,
 :number-of-failed-calls-with-retry-attempt 0}
 

TBD: reset function needs to be implemnented

## Events

TBD: mostly copy from circuit breaker (events: :SUCCESS :ERROR :RETRY :IGNORED_ERROR)

## Exception Handling

TBD:

When a timeout occurs, an instance of `ExceptionInfo` will be
thrown. Its payload can be extracted with `ex-data` and the key
`:resilience4clj.anomaly/category` will be set with
`:resilience4clj.anomaly/execution-timeout`.

``` clojure
(try
  (slow-hello)
  (catch Throwable e
    (if (= :resilience4clj.anomaly/execution-timeout
           (-> e ex-data :resilience4clj.anomaly/category))
      (println "Call timed out!!!"))))
```

## Composing Further

TBD:

Resilience4clj is composed of [several modules][about] that
easily compose together. For instance, if you are also using the
[circuit breaker module][breaker] and assuming your import and basic
settings look like this:

``` clojure
(ns my-app
  (:require [resilience4clj-circuitbreaker.core :as cb]
            [resilience4clj-timelimiter.core :as tl]))

;; create time limiter with default settings
(def limiter (tl/create))

;; create circuit breaker with default settings
(def breaker (cb/create "HelloService"))

;; slow function you want to limit
(defn slow-hello []
  (Thread/sleep 1500)
  "Hello World!")
```

Then you can create a protected call that combines both the time
limiter and the circuit breaker:

``` clojure
(def protected-hello (-> slow-hello
                         (tl/decorate limiter)
                         (cb/decorate breaker)))
```

The resulting function on `protected-hello` will trigger the breaker
in case of a timeout now.

## Bugs

If you find a bug, submit a [Github issue][github-issues].

## Help

This project is looking for team members who can help this project
succeed!  If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License.
