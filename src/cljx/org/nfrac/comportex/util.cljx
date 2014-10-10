(ns org.nfrac.comportex.util
  (:require [cemerick.pprng :as rng])
  (:refer-clojure :exclude [rand rand-int rand-nth shuffle]))

(defn abs
  [x]
  (if (neg? x) (- x) x))

(defn round
  ([x]
     (Math/round (double x)))
  ([x n]
     (let [z (Math/pow 10.0 n)]
       (-> x
           (* z)
           (round)
           (/ z)
           (double)))))

(defn mean
  [xs]
  (/ (apply + xs) (double (count xs))))

(def RNG (rng/rng))

(defn set-seed!
  [seed]
  #+cljs
  :not-implemented
  #+clj
  (alter-var-root (var RNG)
                  (fn [_] (rng/rng seed))))

(defn rand
  ([]
     (rand 0 1))
  ([lower upper]
     {:pre [(< lower upper)]}
     (+ lower (* (rng/double RNG)
                 (- upper lower)))))

(defn rand-int
  [lower upper]
  (+ lower (rng/int RNG (- upper lower))))

(defn rand-nth
  [xs]
  (nth xs (rand-int 0 (count xs))))

(defn shuffle
  [xs]
  (let [xrs (map list (repeatedly #(rng/double RNG)) xs)]
    (map second (sort-by first xrs))))

(defn quantile
  [xs p]
  (nth (sort xs) (long (* p (dec (count xs))))))

(defn triangular
  "Returns a function transforming uniform randoms in [0 1] to variates on a
   Triangular distribution. http://en.wikipedia.org/wiki/Triangular_distribution

   * a - lower bound

   * b - upper bound

   * c - peak of probability density (within bounds)"
  [a b c]
  (let [Fc (/ (- c a)
              (- b a))]
    (fn [u]
      (if (< u Fc)
        (+ a (Math/sqrt (* u (- b a) (- c a))))
        (- b (Math/sqrt (* (- 1 u) (- b a) (- b c))))))))

(defn count-filter
  "Same as `(count (filter pred coll))`, but faster."
  [pred coll]
  (reduce (fn [sum x]
            (if (pred x) (inc sum) sum))
          0 coll))

(defn group-by-maps
  "Like the built-in group-by, but taking key-value pairs and building
   maps instead of vectors for the groups. It is tuned for performance
   with many values per key. `f` is a function taking 2 arguments, the
   key and value."
  [f kvs]
  (->> kvs
       ;; create a transient map of transient maps
       (reduce (fn [m [k v]]
                 (let [g (f k v)
                       items (get m g (transient {}))]
                   (assoc! m g (assoc! items k v))))
               (transient {}))
       ;; make the outer map persistent (can't seq it)
       (persistent!)
       ;; make the inner maps persistent within a transient outer map
       (reduce (fn [m [g items]]
                 (assoc! m g (persistent! items)))
               (transient {}))
       ;; make the outer map persistent
       (persistent!)))

(defn group-by-sets
  "Like the built-in group-by, but building sets instead of vectors
   for the groups, and tuned for performance with many values per key."
  [f coll]
  (->> coll
       ;; create a transient map of transient sets
       (reduce (fn [m x]
                 (let [g (f x)
                       items (get m g (transient #{}))]
                   (assoc! m g (conj! items x))))
               (transient {}))
       ;; make the outer map persistent (can't seq it)
       (persistent!)
       ;; make the inner maps persistent within a transient outer map
       (reduce (fn [m [g items]]
                 (assoc! m g (persistent! items)))
               (transient {}))
       ;; make the outer map persistent
       (persistent!)))

(defn update-each
  "Transforms a map or vector `m` applying function `f` to the values
   under keys `ks`."
  [m ks f]
  (if-not (seq ks)
    m
    (->> ks
         (reduce (fn [m k]
                   (assoc! m k (f (get m k))))
                 (transient m))
         (persistent!))))

(defn remap
  "Transforms a map `m` applying function `f` to each value."
  [f m]
  (->> m
       (mapv (fn [[k v]] [k (f v)]))
       (into (or (empty m) {}))))

(defn top-n-keys-by-value
  "Like `(reverse (take n (keys (sort-by val > m))))` but faster."
  [n m]
  (if-not (pos? n)
    ()
    (loop [ms (seq m)
           am (sorted-map-by #(compare [(m %1) %1] [(m %2) %2]))
           curr-min -1.0]
      (if (empty? ms)
        (keys am)
        (let [[k v] (first ms)]
          (cond
           ;; just initialising the set
           (empty? am)
           (recur (next ms)
                  (assoc am k v)
                  (double v))
           ;; filling up the set
           (< (count am) n)
           (recur (next ms)
                  (assoc am k v)
                  (double (min curr-min v)))
           ;; include this one, dominates previous min
           (> v curr-min)
           (let [new-am (-> (dissoc am (first (keys am)))
                            (assoc k v))]
             (recur (next ms)
                    new-am
                    (double (first (vals new-am)))))
           ;; exclude this one
           :else
           (recur (next ms) am curr-min)))))))
