;;Retained the original license, although I might simply rewrite docjure...
;;Note -> There are a ton of reflection warnings, although I haven't 
;;noticed a real speed hit yet....

;Copyright (c) 2009-2013 Martin Jul, Ative (www.ative.dk)
;
;Permission is hereby granted, free of charge, to any person
;obtaining a copy of this software and associated documentation
;files (the "Software"), to deal in the Software without
;restriction, including without limitation the rights to use,
;copy, modify, merge, publish, distribute, sublicense, and/or sell
;copies of the Software, and to permit persons to whom the
;Software is furnished to do so, subject to the following
;conditions:
;
;The above copyright notice and this permission notice shall be
;included in all copies or substantial portions of the Software.
;
;THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
;EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
;OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
;NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
;HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
;WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
;FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
;OTHER DEALINGS IN THE SOFTWARE.

;;End Original License

;This is a slight rewrite of docjure to optimize reading large excel files.
;I'm finding that a ton of time is spent in reflection in excel processing 
;in docjure, which I think is due to the multimethod implementation.  
;I believe that protocol-based solution will be faster. -Tom
(ns spork.util.excel.docjure
  (:import
    (java.io FileOutputStream FileInputStream)
    (java.util Date Calendar)
    (org.apache.poi.xssf.usermodel XSSFWorkbook)
    (org.apache.poi.ss.usermodel Workbook Sheet Cell Row WorkbookFactory DateUtil
				 IndexedColors CellStyle Font CellValue)
    (org.apache.poi.ss.util CellReference AreaReference)
    ;;new
    (org.apache.poi.xssf.eventusermodel XSSFReader)  ;;added by tom
    (org.apache.poi.xssf.model SharedStringsTable)
    (org.xml.sax.helpers DefaultHandler)
    (org.apache.poi.openxml4j.opc OPCPackage) ;;added by tom
    ))

;;Tom's code..
(def #^:dynamic *date-checking* true) 
(defmacro ignoring-dates [& body]
  `(binding [*date-checking* false]
     ~@body))

;(set! *warn-on-reflection* true)
(defmacro assert-type [value expected-type]
  `(when-not (isa? (class ~value) ~expected-type)
     (throw (IllegalArgumentException.
             (format "%s is invalid. Expected %s. Actual type %s, value: %s"
                     (str '~value) ~expected-type (class ~value) ~value)))))

;faster...
(def cell-types
  {'Cell/CELL_TYPE_BOOLEAN Cell/CELL_TYPE_BOOLEAN
   'Cell/CELL_TYPE_STRING  Cell/CELL_TYPE_STRING 
   'Cell/CELL_TYPE_NUMERIC Cell/CELL_TYPE_NUMERIC
   'Cell/CELL_TYPE_BLANK   Cell/CELL_TYPE_BLANK 
   'Cell/CELL_TYPE_FORMULA Cell/CELL_TYPE_FORMULA}) 

(defmacro case-cell [pred & cases]
  (let [tl (when (odd? (count cases)) (last cases))]
  `(case ~pred
     ~@(mapcat (fn [[s expr]]
                 [(or (get cell-types s) (throw (Exception. (str [:unknown-celltype s]))))
                  expr])
               (partition 2 cases))
     ~@tl)))
    
(defn read-cell-value [^CellValue cv date-format?]
  (case-cell  (int (.getCellType cv))
    Cell/CELL_TYPE_BOOLEAN   (.getBooleanValue cv)
    Cell/CELL_TYPE_STRING    (.getStringValue cv)
    Cell/CELL_TYPE_NUMERIC  
    (if date-format? 
	     (DateUtil/getJavaDate (.getNumberValue cv))
	     (.getNumberValue cv))
    (throw (Exception. (str [:unknown :celltype (.getCellType cv)])))))
     
;;again, get away from multimethods and go faster.....
(defn read-cell [^Cell cell]
  (case-cell  (int (.getCellType cell))
    Cell/CELL_TYPE_BLANK       nil
    Cell/CELL_TYPE_STRING     (.getStringCellValue cell)
    Cell/CELL_TYPE_FORMULA   
      (let [evaluator (.. cell getSheet getWorkbook
                          getCreationHelper createFormulaEvaluator)
            cv         (.evaluate evaluator cell)]
        (read-cell-value cv false))
    Cell/CELL_TYPE_BOOLEAN     (.getBooleanCellValue cell)
    Cell/CELL_TYPE_NUMERIC     
      (if (and *date-checking* (DateUtil/isCellDateFormatted cell))
        (.getDateCellValue cell)
        (.getNumericCellValue cell))
      (throw (Exception. (str [:unknown-cell-type  (.getCellType cell)])))
    ))


(defn load-workbook 
  "Load an Excel .xls or .xlsx workbook from a file."
  [filename]
  (with-open [stream (FileInputStream. filename)]
    (WorkbookFactory/create stream)))

;; (defn load-workbook 
;;   "Load an Excel .xls or .xlsx workbook from a file."
;;   [filename]
;;   (let [f   (java.io.File. filename)
;;         ^OPCPackage opc (OPCPackage/open f)]
;;     (XSSFWorkbook. opc)))

;;holy wow...
;; (defn sheet-handler [sst]
;;   (proxy [org.xml.sax.helpers.DefaultHandler]
      
;; (defn read-workbook
;;   [filename]
;;   (let [^OPCPackage pkg (OPCPacakge/open filename)
;;         ^XSSFReader r   (XSSFReader. pkg)
;;         ^SharedStringsTable sst (.getSharedStringsTable r)
;;         ^XMLReader parser (let [p (XMLReaderFactory/createXMLReader
;;                                    "org.apache.xerces.parsers.SAXParser")
;;                                 handler (SheetHandler

(defn save-workbook! 
  "Save the workbook into a file."
  [filename ^Workbook workbook]
  (assert-type workbook Workbook)
  (with-open [file-out (FileOutputStream. filename)]
    (.write workbook file-out)))

(defn sheet-seq 
  "Return a lazy seq of the sheets in a workbook."
  [^Workbook workbook]
  (assert-type workbook Workbook)
  (for [idx (range (.getNumberOfSheets workbook))]
    (.getSheetAt workbook idx)))

(defn sheet-name
  "Return the name of a sheet."
  [^Sheet sheet]
  (assert-type sheet Sheet)
  (.getSheetName sheet))

(defn select-sheet 
  "Select a sheet from the workbook by name."
  [name ^Workbook workbook]
  (assert-type workbook Workbook)
  (->> (sheet-seq workbook)
       (filter #(= name (sheet-name %)))
       first))

(defn row-seq 
  "Return a lazy sequence of the rows in a sheet."
  [^Sheet sheet]
  (assert-type sheet Sheet)
  (iterator-seq (.iterator sheet)))

(defn- cell-seq-dispatch [x]
  (cond
   (isa? (class x) Row) :row
   (isa? (class x) Sheet) :sheet
   (seq? x) :coll
   :else :default))
  
(defmulti cell-seq
  "Return a seq of the cells in the input which can be a sheet, a row, or a collection
   of one of these. The seq is ordered ordered by sheet, row and column."
  cell-seq-dispatch)
(defmethod cell-seq :row  [row] (iterator-seq (.iterator row)))
(defmethod cell-seq :sheet [sheet] (for [row (row-seq sheet)
					 cell (cell-seq row)]
				     cell))
(defmethod cell-seq :coll [coll] (for [x coll,
				       cell (cell-seq x)]
				   cell))


(defn into-seq
  [sheet-or-row]
  (vec (for [item (iterator-seq (.iterator sheet-or-row))] item)))

(defn- project-cell [column-map ^Cell cell]
  (let [colname (-> cell
		    .getColumnIndex 
		    org.apache.poi.ss.util.CellReference/convertNumToColString
		    keyword)
	new-key (column-map colname)]
    (when new-key
      {new-key (read-cell cell)})))

(defn select-columns [column-map ^Sheet sheet]
  "Takes two arguments: column hashmap where the keys are the
   spreadsheet column names as keys and the values represent the names they are mapped to, 
   and a sheet.

   For example, to select columns A and C as :first and :third from the sheet
   
   (select-columns {:A :first, :C :third} sheet)
   => [{:first \"Value in cell A1\", :third \"Value in cell C1\"} ...] "
  (assert-type sheet Sheet)
  (vec
   (for [row (into-seq sheet)]
     (->> (map #(project-cell column-map %) row)
	  (apply merge)))))

(defn string-cell? [^Cell cell]
  (= Cell/CELL_TYPE_STRING (.getCellType cell)))

(defn- date-or-calendar? [value]
  (let [cls (class value)]
    (or (isa? cls Date) (isa? cls Calendar))))

(defn apply-date-format! [^Cell cell format]
  (let [workbook (.. cell getSheet getWorkbook)
        date-style (.createCellStyle workbook)
        format-helper (.getCreationHelper workbook)]
    (.setDataFormat date-style
		    (.. format-helper createDataFormat (getFormat format)))
    (.setCellStyle cell date-style)))

(defn set-cell! [^Cell cell value]
  (if (nil? value)
    (let [^String null nil]
      (.setCellValue cell null)) ;do not call setCellValue(Date) with null
    (let [converted-value (cond (number? value) (double value)
                                true value)]
      (.setCellValue cell converted-value)
      (if (date-or-calendar? value)
        (apply-date-format! cell "m/d/yy")))))

(defn add-row! [^Sheet sheet values]
  (assert-type sheet Sheet)
  (let [row-num (if (= 0 (.getPhysicalNumberOfRows sheet)) 
		  0 
		  (inc (.getLastRowNum sheet)))
	row (.createRow sheet row-num)]
    (doseq [[column-index value] (partition 2 (interleave (iterate inc 0) values))]
      (set-cell! (.createCell row column-index) value))
    row))

(defn add-rows! [^Sheet sheet rows]
  "Add rows to the sheet. The rows is a sequence of row-data, where
   each row-data is a sequence of values for the columns in increasing
   order on that row."
  (assert-type sheet Sheet)
  (doseq [row rows]
    (add-row! sheet row)))

(defn add-sheet! 
  "Add a new sheet to the workbook."
  [^Workbook workbook name]
  (assert-type workbook Workbook)
  (.createSheet workbook name))


(defn create-workbook
  "Create a new workbook with a single sheet and the data specified.
   The data is given a vector of vectors, representing the rows
   and the cells of the rows.

   For example, to create a workbook with a sheet with
   two rows of each three columns:

   (create-workbook \"Sheet 1\" 
                    [[\"Name\" \"Quantity\" \"Price\"]
                     [\"Foo Widget\" 2 42]])             
   "
  [sheet-name data]
  (let [workbook (XSSFWorkbook.)
	sheet    (add-sheet! workbook sheet-name)]
    (add-rows! sheet data)
    workbook))

(defn create-font!
  "Create a new font in the workbook.

   Options are

       :bold    true/false   bold or normal font

   Example:

      (create-font! wb {:bold true})
   "
  [^Workbook workbook options]
  (let [defaults {:bold false}
	cfg (merge defaults options)]
    (assert-type workbook Workbook)
    (let [f (.createFont workbook)]
      (doto f
	(.setBoldweight (if (:bold cfg) Font/BOLDWEIGHT_BOLD Font/BOLDWEIGHT_NORMAL)))
      f)))
  

(defn create-cell-style!
  "Create a new cell-style.
   Options is a map with the cell style configuration:

      :background     the name of the background colour (as keyword)

   Valid keywords are the colour names defined in
   org.apache.ss.usermodel.IndexedColors as lowercase keywords, eg.

     :black, :white, :red, :blue, :green, :yellow, ...

   Example:

   (create-cell-style! wb {:background :yellow})
  "
  ([^Workbook workbook] (create-cell-style! workbook {}))
  
  ([^Workbook workbook styles]
     (assert-type workbook Workbook)
     (let [cs (.createCellStyle workbook)
	   {background :background, font-style :font} styles
	   font (create-font! workbook font-style)]
       (do
	 (.setFont cs font)
	 (when background
	   (let [bg-idx (.getIndex (IndexedColors/valueOf
				    (.toUpperCase (name background))))]
	     (.setFillForegroundColor cs bg-idx)
	     (.setFillPattern cs CellStyle/SOLID_FOREGROUND)))
	 cs))))

(defn set-cell-style!
  "Apply a style to a cell.
   See also: create-cell-style!.
  "
  [^Cell cell ^CellStyle style]
  (assert-type cell Cell)
  (assert-type style CellStyle)
  (.setCellStyle cell style)
  cell)

(defn set-row-style!
  "Apply a style to all the cells in a row.
   Returns the row."
  [^Row row ^CellStyle style]
  (assert-type row Row)
  (assert-type style CellStyle)
  (dorun (map #(.setCellStyle % style) (cell-seq row)))
  row)

(defn get-row-styles
  "Returns a seq of the row's CellStyles."
  [#^Row row]
  (map #(.getCellStyle %) (cell-seq row)))

(defn set-row-styles!
  "Apply a seq of styles to the cells in a row."
  [#^Row row styles]
  (let [pairs (partition 2 (interleave (cell-seq row) styles))]
    (doseq [[c s] pairs]
      (.setCellStyle c s))))

(defn row-vec 
  "Transform the row struct (hash-map) to a row vector according to the column order.
   Example:

     (row-vec [:foo :bar] {:foo \"Foo text\", :bar \"Bar text\"})
     > [\"Foo text\" \"Bar text\"]
  "
  [column-order row]
  (vec (map row column-order)))

(defn remove-row!
  "Remove a row from the sheet."
  [sheet row]
  (do
    (assert-type sheet Sheet)
    (assert-type row Row)
    (.removeRow sheet row)
    sheet))

(defn remove-all-rows!
  "Remove all the rows from the sheet."
  [sheet]
  (doall
   (for [row (doall (row-seq sheet))]
     (remove-row! sheet row)))
  sheet)

(defn- named-area-ref [#^Workbook workbook n]
  (let [index (.getNameIndex workbook (name n))]
    (if (>= index 0)
      (->> index
        (.getNameAt workbook)
        (.getRefersToFormula)
        (AreaReference.))
      nil)))

(defn- cell-from-ref [#^Workbook workbook #^CellReference cref]
  (let [row (.getRow cref)
        col (-> cref .getCol .intValue)
        sheet (->> cref (.getSheetName) (.getSheet workbook))]
    (-> sheet (.getRow row) (.getCell col))))

(defn select-name
  "Given a workbook and name (string or keyword) of a named range, select-name returns a seq of cells or nil if the name could not be found."
  [#^Workbook workbook n]
  (if-let [aref (named-area-ref workbook n)]
      (map (partial cell-from-ref workbook) (.getAllReferencedCells aref))
    nil))

(defn add-name! [#^Workbook workbook n string-ref]
  (let [the-name (.createName workbook)]
    (.setNameName the-name (name n))
    (.setRefersToFormula the-name string-ref)))

;;OBE stuff, old multimethod implementation
(comment 
(defn cell-reference [^Cell cell]
  (.formatAsString (CellReference. (.getRowIndex cell) (.getColumnIndex cell))))

;;Note: we can spoeed this up a lot if we get away from multimethods....
(defmulti read-cell-value (fn [^CellValue cv date-format?] (.getCellType cv)))
(defmethod read-cell-value Cell/CELL_TYPE_BOOLEAN  [^CellValue cv _]  (.getBooleanValue cv))
(defmethod read-cell-value Cell/CELL_TYPE_STRING   [^CellValue cv _]  (.getStringValue cv))
(defmethod read-cell-value Cell/CELL_TYPE_NUMERIC  [^CellValue cv date-format?]
	   (if date-format? 
	     (DateUtil/getJavaDate (.getNumberValue cv))
	     (.getNumberValue cv)))
)

(comment 
;modification.....we spend a ton of time checking for dates.
(defmulti  read-cell (fn [^Cell cell] (.getCellType cell)))
(defmethod read-cell Cell/CELL_TYPE_BLANK     [_]     nil)
(defmethod read-cell Cell/CELL_TYPE_STRING    [^Cell cell]  (.getStringCellValue cell))
(defmethod read-cell Cell/CELL_TYPE_FORMULA   [^Cell cell]
	   (let [evaluator (.. cell getSheet getWorkbook
			       getCreationHelper createFormulaEvaluator)
		 cv (.evaluate evaluator cell)]
	     (read-cell-value cv false)))
(defmethod read-cell Cell/CELL_TYPE_BOOLEAN   [^Cell cell]  (.getBooleanCellValue cell))
(defmethod read-cell Cell/CELL_TYPE_NUMERIC   [^Cell cell]  
  (if (and *date-checking* (DateUtil/isCellDateFormatted cell))
    (.getDateCellValue cell)
    (.getNumericCellValue cell)))
)
