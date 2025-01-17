(ns liq.tty-output
  (:require [liq.buffer :as buffer]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.java.shell :as shell])
            [liq.tty-shared :as shared]
              ; :cljs [lumo.io :as io]
              
            [clojure.string :as str]))

(def settings (atom {::cursor-draw-hack false})) ;; cursor-draw-hack draws a block when cursors moves to avoid flicker

(def ^:private last-buffer (atom nil))
(def esc "\033[")

(defn rows
  []
  #?(:clj (loop [shellinfo ((shell/sh "/bin/sh" "-c" "stty size </dev/tty") :out) n 0]
            (if (or (re-find #"^\d+" shellinfo) (> n 10))
              (Integer/parseInt (re-find #"^\d+" shellinfo))
              (do
                (shared/tty-println n)
                (Thread/sleep 100)
                (recur ((shell/sh "/bin/sh" "-c" "stty size </dev/tty") :out) (inc n)))))
     :cljs (or 40 (aget (js/process.stdout.getWindowSize) 0)))) 

(defn cols
  []
  #?(:clj (loop [shellinfo ((shell/sh "/bin/sh" "-c" "stty size </dev/tty") :out) n 0]
            (if (or (re-find #"\d+$" shellinfo) (> n 10)) 
             (dec (Integer/parseInt (re-find #"\d+$" shellinfo)))
             (do
               (shared/tty-println n)
               (Thread/sleep 100)
               (recur ((shell/sh "/bin/sh" "-c" "stty size </dev/tty") :out) (inc n)))))
     :cljs (or 120 (aget (js/process.stdout.getWindowSize) 0)))) 


(defn get-dimensions
  []
  {:rows (rows) :cols (cols)})

(defn buffer-footprint
  [buf]
  [(buf ::buffer/window) (buf ::buffer/name) (buf ::buffer/file-name)])

(def theme
  {:string "38;5;131"
   :keyword "38;5;117"
   :comment "38;5;105"
   :special "38;5;11"
   :green   "38;5;40"
   :yellow "38;5;11"
   :red "38;5;196"
   :definition "38;5;40"
   nil "0"})

(def char-cache (atom {}))
(def countdown-cache (atom 0))
(defn- draw-char
  [ch row col color bgcolor]
  (let [k (str row "-" col)
        footprint (str ch row col color bgcolor)]
    (when (not= (@char-cache k) footprint)
      (reset! countdown-cache 9))
    (when (> @countdown-cache 0)
      (swap! countdown-cache dec)
      (shared/tty-print esc color "m")
      (shared/tty-print esc bgcolor "m")
      (shared/tty-print esc row ";" col "H" esc "s" ch)
      (swap! char-cache assoc k footprint))))

(defn invalidate-cache
  []
  (shared/tty-print esc "2J")
  (reset! char-cache {}))

(defn double-width?
  "Not very precise yet!"
  [c]
  (cond (re-matches #"[A-ÿ]" c) false
        (re-matches #"[ぁ-んァ-ン]" c) true
        (re-matches #"[ァ-ン]"c) true
        (re-matches #"[一-龯] "c) true
        :else false))

(defn print-buffer
  [buf]
  (let [cache-id (buffer-footprint buf)
        tw (or (buf ::buffer/tabwidth) 8)
        w (buf ::buffer/window)
        top (w ::buffer/top)   ; Window top margin
        left (w ::buffer/left) ; Window left margin
        rows (w ::buffer/rows) ; Window rows
        cols (w ::buffer/cols) ; Window cols
        tow (buf ::buffer/tow) ; Top of window
        crow (-> buf ::buffer/cursor ::buffer/row)  ; Cursor row
        ccol (-> buf ::buffer/cursor ::buffer/col)] ; Cursor col
   (when (and (@settings ::cursor-draw-hack) (= cache-id @last-buffer))
     (shared/tty-print "█")) ; To make it look like the cursor is still there while drawing.
   (shared/tty-print esc "?25l") ; Hide cursor
   (when-let [statusline (and (not= (buf ::buffer/name) "*minibuffer*") (buf :status-line))]
     (print-buffer statusline))
  ;; Looping over the rows and cols in buffer window in the terminal
   (loop [trow top  ; Terminal row
          tcol left ; Terminal col
          row (tow ::buffer/row)
          col (tow ::buffer/col)
          cursor-row nil
          cursor-col nil
          ccolor "0"]
     (if (< trow (+ rows top))
       (do
       ;; Check if row has changed...
         (let [cm (or (-> buf ::buffer/lines (get (dec row)) (get (dec col))) {}) ; Char map like {::buffer/char \x ::buffer/style :string} 
               c-width (cond (= (cm ::buffer/char) \tab) (- tw (mod (- tcol left) tw))
                             (double-width? (str (cm ::buffer/char))) 2
                             true 1) ; Width of the char
               cursor-match (or (and (= row crow) (= col ccol))
                                (and (= row crow) (not cursor-col) (> col ccol))
                                (and (not cursor-row) (> row crow)))
               c (cond (and (@settings ::cursor-draw-hack) cursor-match (buf :status-line)) "█" 
                       (= (cm ::buffer/char) \tab) (str/join (repeat c-width " "))
                       (= (cm ::buffer/char) \return) (char 633)
                       (cm ::buffer/char) (cm ::buffer/char)
                       (and (= col (inc (buffer/col-count buf row))) (> (buffer/next-visible-row buf row) (+ row 1))) "…"
                       (and (= col 1) (> row (buffer/line-count buf))) (str esc "36m~" esc "0m")
                       true \space)
               new-cursor-row (if cursor-match trow cursor-row)
               new-cursor-col (if cursor-match tcol cursor-col)
               color (theme (cm ::buffer/style))
               bgcolor (if (buffer/selected? buf row col) "48;5;17" "49")
               last-col (+ cols left -1)
               n-trow (if (< last-col tcol) (inc trow) trow)
               ;n-tcol (if (< last-col tcol) left (inc tcol))
               n-tcol (if (< last-col tcol) left (+ tcol c-width))
               n-row (cond (and (< last-col tcol) (> col (buffer/col-count buf row))) (buffer/next-visible-row buf row)
                           true row)
               n-col (cond (and (< last-col tcol) (> col (buffer/col-count buf row))) 1
                           true (inc col))]
           (draw-char c trow tcol color bgcolor)
           (recur n-trow n-tcol n-row n-col new-cursor-row new-cursor-col (if cursor-match color ccolor))))
       (do
         (when-let [c (w ::buffer/bottom-border)]
           (doseq [co (range left (+ left cols))]
             (draw-char c (+ top rows) co "38;5;11" "49")))
         (when (buf :status-line)
           (shared/tty-print esc ccolor "m" esc cursor-row ";" cursor-col "H" esc "s" (or (and (not= (buffer/get-char buf) \tab) (buffer/get-char buf)) \space))
           ;(draw-char (or (and (not= (buffer/get-char buf) \tab) (buffer/get-char buf)) \space) cursor-row cursor-col ccolor "49")
           (shared/tty-print esc "?25h" esc cursor-row ";" cursor-col "H" esc "s")
           (shared/flush-output)
           (reset! last-buffer cache-id)))))))

(def ^:private updater (atom nil))
(def ^:private queue (atom []))

(def ^:private next-buffer (atom nil))
#?(:cljs (js/setInterval
           #(when-let [buf @next-buffer]
              (reset! next-buffer nil)
              (print-buffer buf))
           20))

(defn printer
  [buf]
  #?(:clj (let [fp (buffer-footprint buf)]
            ;; Replace outdated versions of buf 
            (swap! queue
              (fn [q] (conj
                        (filterv #(not= (buffer-footprint %) fp) q)
                        buf)))
            (when (not @updater) (reset! updater (future nil)))
            (when (future-done? @updater)
              (reset! updater
                (future
                  (while (not (empty? @queue))
                    (when-let [b (first @queue)]
                      (swap! queue #(subvec % 1))
                      (print-buffer b)))))))
     :cljs (reset! next-buffer buf)))

(def output-handler
  {:printer printer
   :invalidate invalidate-cache
   :dimensions get-dimensions})
