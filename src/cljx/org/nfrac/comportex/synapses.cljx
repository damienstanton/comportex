(ns org.nfrac.comportex.synapses
  (:require [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util
             :refer [remap]]))

(defrecord SynapseGraph
    [syns-by-target targets-by-source pcon max-syns cull-zeros?]
  p/PSynapseGraph
  (in-synapses
    [this target-id]
    (syns-by-target target-id))
  (sources-connected-to
    [this target-id]
    (->> (p/in-synapses this target-id)
         (keep (fn [[k p]] (when (>= p pcon) k)))))
  (targets-connected-from
    [this source-id]
    (targets-by-source source-id))
  (reinforce-in-synapses
    [this target-id skip? reinforce? pinc pdec]
    (let [syns (p/in-synapses this target-id)
          sg (util/group-by-maps
              (fn [id2 p]
                (if (skip? id2)
                  :skip
                  (if (reinforce? id2)
                    (cond
                     (== p 1.0) :skip
                     (and (< p pcon)
                          (>= p (- pcon pinc))) :promote
                     :else :up)
                    (cond
                     (<= p 0.0) (if cull-zeros? :cull :skip)
                     (and (>= p pcon)
                          (< p (+ pcon pdec))) :demote
                     :else :down))))
              syns)
          new-syns (merge (:skip sg)
                          (remap #(min (+ % pinc) 1.0) (:up sg))
                          (remap #(min (+ % pinc) 1.0) (:promote sg))
                          (remap #(max (- % pdec) 0.0) (:down sg))
                          (remap #(- % pdec) (:demote sg)))]
      (-> this
          (assoc-in [:syns-by-target target-id] new-syns)
          (update-in [:targets-by-source]
                     util/update-each (keys (:promote sg)) #(conj % target-id))
          (update-in [:targets-by-source]
                     util/update-each (keys (:demote sg)) #(disj % target-id)))))
  (conj-synapses
    [this target-id syn-source-ids p]
    (let [osyns (p/in-synapses this target-id)
          nsyns (zipmap syn-source-ids (repeat p))
          syns (merge osyns nsyns)]
      (cond->
       (assoc-in this [:syns-by-target target-id] syns)
       ;; record connection if initially connected
       (>= p pcon)
       (update-in [:targets-by-source]
                  util/update-each syn-source-ids #(conj % target-id))
       ;; if too many synapses, remove those with lowest permanence
       (> (count syns) max-syns)
       (p/disj-synapses target-id
                        (->> (sort-by val syns)
                             (keys)
                             (take (- (count syns) max-syns)))))))
  (disj-synapses
    [this target-id syn-source-ids]
    (-> this
        (update-in [:syns-by-target target-id]
                   (fn [syns] (apply dissoc syns syn-source-ids)))
        (update-in [:targets-by-source]
                   util/update-each syn-source-ids #(disj % target-id)))))

(defn empty-synapse-graph
  [n-targets n-sources pcon max-syns cull-zeros?]
  (map->SynapseGraph
   {:syns-by-target (vec (repeat n-targets {}))
    :targets-by-source (vec (repeat n-sources #{}))
    :pcon pcon
    :max-syns max-syns
    :cull-zeros? cull-zeros?}))

(defn synapse-graph
  [syns-by-target n-sources pcon max-syns cull-zeros?]
  (let [target-sets
        (reduce-kv (fn [v tid syns]
                     (let [sids (keep (fn [[k p]]
                                        (when (>= p pcon) k)) syns)]
                       (util/update-each v sids #(conj % tid))))
                   (vec (repeat n-sources #{}))
                   syns-by-target)]
    (map->SynapseGraph
     {:syns-by-target syns-by-target
      :targets-by-source target-sets
      :pcon pcon
      :max-syns max-syns
      :cull-zeros? cull-zeros?})))

(defn excitations
  "Computes a map of target ids to their degree of excitation: the
   number of sources in `active-sources` they are connected to."
  [syns active-sources]
  (->> active-sources
       (reduce (fn [exc ai]
                 (reduce (fn [exc id]
                           (assoc! exc id (inc (get exc id 0))))
                         exc
                         (p/targets-connected-from syns ai)))
               (transient {}))
       (persistent!)))

;;; ## Dendrite segments

(defn cell-uidx
  [depth [col ci]]
  (+ (* col depth)
     ci))

(defn cell-path
  [depth uidx]
  [(quot uidx depth)
   (rem uidx depth)])

(defn seg-uidx
  [depth max-segs [col ci si]]
  (+ (* col depth max-segs)
     (* ci max-segs)
     si))

(defn seg-path
  [depth max-segs uidx]
  (let [col (quot uidx (* depth max-segs))
        col-rem (rem uidx (* depth max-segs))]
    [col
    (quot col-rem max-segs)
    (rem col-rem max-segs)]))

(defn remap-keys
  [f m]
  (zipmap (map f (keys m))
          (vals m)))

(defrecord SynapseGraphBySegments
    [raw-sg depth max-segs
     src->i i->src tgt->i i->tgt]
  p/PSynapseGraph
  (in-synapses
    [_ target-id]
    (->> (p/in-synapses raw-sg (tgt->i target-id))
         (remap-keys i->src)))
  (sources-connected-to
    [_ target-id]
    (->> (p/sources-connected-to raw-sg (tgt->i target-id))
         (map src->i)))
  (targets-connected-from
    [_ source-id]
    (->> (p/targets-connected-from raw-sg (src->i source-id))
         (map i->tgt)))
  (reinforce-in-synapses
    [this target-id skip? reinforce? pinc pdec]
    (-> this
        (update-in [:raw-sg] p/reinforce-in-synapses (tgt->i target-id)
                   (comp skip? i->src) (comp reinforce? i->src)
                   pinc pdec)))
  (conj-synapses
    [this target-id syn-source-ids p]
    (-> this
        (update-in [:raw-sg] p/conj-synapses (tgt->i target-id)
                   (map src->i syn-source-ids) p)))
  (disj-synapses
    [this target-id syn-source-ids]
    (-> this
        (update-in [:raw-sg] p/disj-synapses (tgt->i target-id)
                   (map src->i syn-source-ids))))
  p/PSegments
  (cell-segments
    [this cell-id]
    (let [[col ci] cell-id]
      (mapv #(p/in-synapses this [col ci %])
            (range max-segs)))))

(defn synapse-graph-by-segments
  [n-cols depth max-segs pcon max-syns cull-zeros?]
  (let [n-targets (* n-cols depth max-segs)
        n-sources (* n-cols depth)
        raw-sg (empty-synapse-graph n-targets n-sources pcon max-syns cull-zeros?)
        tgt->i (partial seg-uidx depth max-segs)
        i->tgt (partial seg-path depth max-segs)
        src->i (partial cell-uidx depth)
        i->src (partial cell-path depth)]
    (map->SynapseGraphBySegments
     {:raw-sg raw-sg
      :max-segs max-segs
      :tgt->i tgt->i
      :i->tgt i->tgt
      :src->i src->i
      :i->src i->src
      })))