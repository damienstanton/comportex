(ns org.nfrac.comportex.inhibition
  (:require [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util
             :refer [abs round mean remap]]))

(defn numeric-span
  [xs]
  (- (apply max xs) (apply min xs)))

(defn column-receptive-field-size
  "Returns the span over the input bit array to which this column has
   connected synapses. Takes the maximum span in any one dimension."
  [sg itopo col]
  (let [ids (p/sources-connected-to sg col)
        coords (map (partial p/coordinates-of-index itopo) ids)]
    (if (seq coords)
      (if (number? (first coords))
        (numeric-span coords)
        (let [m (count (p/dimensions itopo))]
          (->> (for [j (range m)]
                 (numeric-span (map #(nth % j) coords)))
               (apply max))))
      0)))

(defn avg-receptive-field-size
  [sg topo itopo]
  (-> (map (partial column-receptive-field-size sg itopo)
           (range (p/size topo)))
      (mean)))

(defn inhibition-radius
  "The radius in column space defining neighbouring columns, based on
   the average receptive field size. Specifically, neighbouring
   columns are defined by sharing at least 30% of their receptive
   fields, on average.

   * `sg` is the synapse graph linking the inputs to targets.

   * `topo` is the topology of the targets (e.g. columns).

   * `itopo` is the topology of the inputs."
  [sg topo itopo]
  (let [shared-frac 0.3
        max-dim (apply max (p/dimensions topo))
        max-idim (apply max (p/dimensions itopo))
        arfs (avg-receptive-field-size sg topo itopo)
        ;; columns in this range will have some overlap of inputs
        cols-diameter (* max-dim (/ arfs max-idim))
        cols-radius (quot cols-diameter 2)]
    ;; to share a given fraction of receptive fields
    (-> (* cols-radius (- 1.0 shared-frac))
        (round)
        (max 1))))

(defn ac-inhibit-globally
  "Returns the set of column ids which should become active given the
   map of column overlap scores `om`, and the target activation rate
   `level`. Global inhibition is applied, i.e. the top N columns by
   overlap score are selected."
  [om level n-cols]
  (let [n-on (max 1 (round (* level n-cols)))]
    (util/top-n-keys-by-value n-on om)))

(defn dominant-overlap-diff
  [dist base-dist speed]
  (-> dist
      (- base-dist) ; within this radius, there can be only one
      (max 0)
      (/ speed))) ; for every `speed` columns away, need one extra overlap to dominate

(defn map->vec
  [n m]
  (mapv m (range n)))

(defn vec->map
  [v]
  (persistent!
   (reduce-kv (fn [m i x]
                (if x
                  (assoc! m i x)
                  m))
              (transient {}) v)))

(defn inhibit-locally
  "Takes a map `exc` of column ids to excitation levels (number of
   active synapses) and the column topology, applies local inhibition
   to remove any columns dominated by their neighbours, returning a
   submap of `exc`."
  [exc topo radius inh-base-dist inh-speed]
  (loop [cols (keys exc)
         mask (transient (map->vec (p/size topo) exc))]
    (if-let [col (first cols)]
      (if-let [o (mask col)]
        (recur (next cols)
         ;; loop through neighbours and mask out any dominated
         (let [coord (p/coordinates-of-index topo col)]
           (loop [nbs (p/neighbours topo coord radius 0)
                  mask mask]
             (if-let [nb-coord (first nbs)]
               (let [nb-col (p/index-of-coordinates topo nb-coord)]
                 (if-let [nb-o (mask nb-col)]
                   (let [dist (p/coord-distance topo coord nb-coord)
                         odom (dominant-overlap-diff dist inh-base-dist inh-speed)]
                     (cond
                      ;; neighbour is dominated
                      (>= o (+ nb-o odom))
                      (recur (next nbs)
                             (assoc! mask nb-col nil))
                      ;; we are dominated by neighbour; abort
                      (<= o (- nb-o odom))
                      (assoc! mask col nil)
                      ;; neither dominates
                      :else
                      (recur (next nbs) mask)))
                   ;; neighbour has no overlap or was eliminated
                   (recur (next nbs) mask)))
               ;; finished with neighbours
               mask))))
        ;; already eliminated, skip
        (recur (next cols) mask))
      ;; finished
      (vec->map (persistent! mask)))))

(defn perturb-overlaps
  [om]
  (remap #(+ % (util/rand 0 0.5)) om))
