;;A set of common protocols implemented and used throughout spork.
;;Only protocols that are fairly generic, and see usage through multiple 
;;components will show up here.  Other components may have more localized, 
;;or specific protocols unique to their domain.

;;Optimization note: there may be a problem with prefixing protocols with a 
;;dash, at least in the jvm clojure.  I adopted that convention from 
;;clojurescript, and it seems to cause some reflection issues.  We'll see 
;;if it's actually a sticking point, or if the protocols work just fine.

(ns spork.protocols.core)

;;Common function used throughout Spork.
;;Clojure entries are basically primitive structs, one could think of them as
;;pairs or dotted pairs.  
(defn ^clojure.lang.MapEntry entry [k v] (clojure.lang.MapEntry. k v))
(defn entry? [e] (= (type e) clojure.lang.MapEntry))
(defn nested-entry? [^clojure.lang.MapEntry e] (entry? (val e)))

;;It will be useful to store entries, which normally represent a weight, node
;;pairing, behind an estimated weight.  We will use nested entries to embed 
;;the actual [weight node] pair inside an [estimate [weight node]] pair.

;;We use entries to encode node weights, as well as heuristic estimates for 
;;algorithms like A-star search.  This let us use general entries for the 
;;priority queues these algorithms rely on.
(defn entry-priority [^clojure.lang.MapEntry e] (key e))

(defn entry-weight [^clojure.lang.MapEntry e]
  (key (if (nested-entry? e) (val e) e)))

(defn entry-node [^clojure.lang.MapEntry e]
  (val (if (nested-entry? e) (val e) e)))

(defn maybe 
  ([coll elseval] (if-let [v (first coll)] v elseval))
  ([coll] (maybe coll nil)))

;;Definition for associative containers that can traverse their keys in the 
;;first-in-first-out order, while retaining a constant, or near-constant 
;;lookup time.
(defprotocol IOrderedMap
  (get-ordering [m] "Returns a pair of m's ordering maps [idx->key key->idx]")
  (set-ordering [m idx->key key->idx] 
     "Sets m's key ordering using maps idx->key key->idx"))

(defn swap-order
  "For ordered map om, assumes "
  [om k1 k2]
  (assert (and (contains? om k1) (contains? om k2)) 
          (str "All keys must exist in map: " [k1 k2])) 
  (let [[idx->key key->idx] (get-ordering om) 
        idx1 (get key->idx k1)
        idx2 (get key->idx k2)]    
      (set-ordering om 
          (-> (assoc idx->key idx2 k1) (assoc idx1 k2))
          (-> (assoc key->idx k1 idx2) (assoc k2 idx1)))))

(defn relabel-key
  "For ordered map om, replaces the existing key, k1, with a new key k2, 
   which maps to both the value and the ordering of k1.  Useful for graph
   relabeling."
  [om k1 k2]
  (-> (assoc om k2 (get om k1))
      (swap-order k1 k2)
      (dissoc k1)))

;;Abstract fringes to support generic search operations.
(defprotocol IFringe 
  (conj-fringe [fringe n w] 
   "Add node n with weight w to the fringe.  Some fringes may implement conj 
    in an associative fashion, where existing values of n are effectively 
    overwritten with a new w, ala clojure.core/assoc")
  (next-fringe [fringe] "Get the next node on the fringe")
  (pop-fringe [fringe] "Remove the next node from the fringe"))

(defn conj-fringe-all
  "Add many [node weight] pairs onto the fringe."
  [fringe nws]
  (reduce (partial apply conj-fringe) fringe nws))  
   
(defn fringe-seq
  "Return a sequence of popped {node weight} maps from 
   priorityq q."
  [fringe]
  (if-let [kv (next-fringe fringe)]
    (lazy-seq (cons kv (fringe-seq (pop-fringe fringe))))))
(defn empty-fringe? [fringe] (empty? (fringe-seq fringe)))
(defn fringe? [x] (satisfies? IFringe x))

;;Abstract protocol for operating on shortest path searches.
(defprotocol IGraphSearch
  (new-path     [state source sink w])
  (shorter-path [state source sink wnew wpast])
  (equal-path   [state source sink])
  (conj-visited [state source])
  (best-known-distance [state x]))

(defn relax
  "Given a shortest path map, a distance map, a source node, sink node, 
   and weight(source,sink) = w, update the search state.

   Upon visitation, sources are conjoined to the discovered vector.    

   The implication of a relaxation on sink, relative to source, is that 
   source no longer exists in the fringe (it's permanently labeled).  
   So a relaxation can mean one of three things: 
   1: sink is a newly discovered-node (as a consequence of visiting source);
   2: sink was visited earlier (from a different source), but this visit exposes
      a shorter path to sink, so it should be elevated in consideration in 
      the search fringe.
   3: sink is a node of equal length to the currently shortest-known path from 
      an unnamed startnode.  We want to record this equivalence, which means 
      that we may ultimately end up with multiple shortest* paths."
     
  [state weight-func source sink]  
    (let [relaxed (+ (best-known-distance state source) 
                     (weight-func source sink))]
      (if-let [known (best-known-distance state sink)]
	      (cond 
	        (< relaxed known) (shorter-path state source sink relaxed known)            
	        (= relaxed known) (equal-path state source sink)                         
	        :else state)            
       ;if sink doesn't exist in distance, sink is new...
       (new-path source sink relaxed state))))

;;A protocol for defining various topologies using a graph-based API.  Most
;;of the types used in this library build of a topology encoded by a persistent
;;map.  As a result, we define a slew of operations that are useable by general
;;graphs, as well as the specialized views of graphs.
(defprotocol ITopograph
  (-get-nodes [tg] "Returns a mapping of node keys to node data.")
  (-set-nodes [tg m] "Replaces the existing node map with m.")
  (-conj-node [tg k v] "Adds a node labeled k, mapped to v, to the node map.")
  (-disj-node [tg k] "Removes the node labeled k from the set of nodes.")
  (-has-node? [tg k] "Determines if node k exists in the node map.")
  (-conj-arc  [tg source sink w] 
    "Associates an arc from source to sink of weight w.")
  (-disj-arc  [tg source sink] "Dissociates the arc from source to sink.")
  (-has-arc?  [tg source sink] "Determines is an arc from source to sink exists.")
  (-get-arc   [tg source sink] "Fetches the weight of the arc from source to sink.")
  (-get-sources [tg k] "Returns a sequence of nodes that have arcs to node k.")
  (-get-sinks [tg k] "Returns a sequence of nodes that k has an arc to."))

(defn topograph? [x] (satisfies? ITopograph x))

;;A protocol for allowing us to coerce values into a graph representation.    
;;If we need the underlying topological information, it would be nice to have 
;;access. This protocol allows that.
(defprotocol IGraphable (-get-graph [g]))

(defn graphable? [x] (satisfies? IGraphable x))

;;We often times have great need for tree-like structures, which are GREAT in 
;;functional programming, as long as you don't need bi-directionality or 
;;the ability to traverse from an arbitrary node to a parent.  The IIndexedTree
;;protocol is designed to allow us to use an indexed topography to simulate 
;;tree-like operations, using an ITopograph as a backing structure.
(defprotocol IIndexedTree
  (tree-root [t] "Return the root index of a tree")
  (append [t other-tree] "Append the nodes of an external tree the root.")
  (prune  [t k] "Remove all nodes reachable from k, including k."))

;;We provide a protocol for operating on persistent doubly-linked lists. 
;;Doubly-linked lists represent a very simple graph with bi-directional arcs 
;;between nodes.  Still, we can use a graph-based topology to supply the 
;;information for the list, or other implementations.
(defprotocol ILinked 
  (attach [l parent child] "Attach child to parent")
  (detach [l parent child] "Remove child from parent"))