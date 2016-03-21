(ns geo-graph.google
    (:require [google-maps-web-api.core :as g :refer [google-directions google-geocode]])
    (:require [geo-graph.graph :as graph])
    (:require [geo-cache.cache :as c :refer [memoize-geocode memoize-weight]])
    (:require [clojure.math.combinatorics :as combinatorics])
)


(def sleep-max-panic 100000)


(defn directions-get-part
  "get the weights from a google maps api directionsResult object. part can be :distance or :duration"
  [obj part]
  (get-in obj [:routes 0 :legs 0 part :value]))

(defn directions-get-points
  [obj]
  (:points (:overview_polyline (first (:routes obj)))))

(defn gd-api-call
  "get directions between two addresses in a hash map"
  [from to options]
  (if (not=  from to)
	  (loop [sleep 100]
	    (if (> sleep sleep-max-panic) (throw (Exception. (str "GD Timeout Panic - " sleep " from: " from " to: " to))))
	    (let [dir (google-directions (merge {:from from :to to} options))]
	      (cond
	        (= (:status dir) "OK")
	        (assoc  {} :distance (max 1 (directions-get-part dir :distance)) :duration (max 1 (directions-get-part dir :duration)) :points (directions-get-points dir))
	        (= (:status dir) "ZERO_RESULTS")(assoc {} :distance 9999999 :duration 9999999)
	        (= (:status dir) "OVER_QUERY_LIMIT") (do (. Thread (sleep sleep)) (recur (* 2 sleep)))
	        :else (throw (Exception. (str "DIRECTIONS " (:status dir) " " (:label from) " " (:label to) " " from " " to)))))) nil) )

(defn gg-api-call
  "geocode an address from a hashmap"
  [loc]
  (loop [sleep 100]
    (if (> sleep sleep-max-panic) (throw (Exception. (str "GG Timeout Panic -" sleep))))
    (let [gc (google-geocode loc)]
      (cond
        (= (:status gc) "OK") (assoc loc :lat (get-in gc [:results 0 :geometry :location :lat]) :lng (get-in gc [:results 0 :geometry :location :lng]))
        (= (:status gc) "OVER_QUERY_LIMIT") (do (. Thread (sleep sleep)) (recur (* 1 sleep)))
        :else (throw (Throwable. (str "GEOCODE " (:status gc) " " (:id loc) " " (:address loc))))))))

(defn address->latlon
  [addr]
  (if (:address addr)
    (if-let [gobj (gg-api-call addr)] gobj)))

(defn get-weight
  [{:keys [from to options]}]
	  (if-let [wtobj (gd-api-call from to options)] wtobj))

(defn google-graph
  [stops cache & [options]]
  (let [  gfn (if cache (memoize-geocode cache address->latlon) address->latlon)
          wfn (if cache (memoize-weight cache get-weight) get-weight)
          stops (mapv #(gfn (merge % options)) stops)
          tuples (combinatorics/selections (range (count stops)) 2)
          ws        (mapv #(wfn {:from (nth stops (first %)) :to (nth stops (second %)) :options options}) tuples)
          distances (mapv #(:distance %) ws)
          durations (mapv #(:duration %) ws) ]
    (graph/make-graph (count stops) distances durations)))

(defn concurrent-google-graph
  [stops cache & [options]]
  (let [gfn   (if cache (memoize-geocode cache address->latlon) address->latlon)
        wfn   (if cache (memoize-weight  cache get-weight) get-weight)
        depot (gfn (merge (first stops) options))
        stops (vec (cons depot (map #(gfn (merge % options)) (rest stops))))
        t (filter #(let [[x y] %] (not= x y)) (for [x (range (count stops)) y (range (count stops))] [x y]))
        ws (into {} (pmap #(let [[f t] % w (wfn {:from (nth stops f) :to (nth stops t) :options options})] (assoc {} (vec %) {:distance (:distance w) :duration (:duration w)})) t))]
    (graph/make-concurrent-graph (count stops) ws )))
