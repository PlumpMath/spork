;;Utilities for reading/writing GZIP zipfiles using java.io
;;libraries.  Added support for lz4 compression as well.
(ns spork.util.zipfile
  (:use [spork.util.io :only 
			[fname fext fpath relative-path make-folders! make-file! 
             uri-path file->uri list-files folder? with-temp-dir 
             map->folders! folders->map]])
  (:require [clojure.java [io :as io]]
            [clojure.core.reducers :as r])
  (:import [java.util.zip GZIPInputStream GZIPOutputStream]
           [java.io BufferedReader FileInputStream InputStreamReader]
           [net.jpountz.lz4 LZ4BlockOutputStream
            LZ4BlockInputStream]
           ))

;define a simple function to extract files from an archive, a wrapper around
;java.util's zip stuff.

(defn is-zip? 
  "Checks it file's extension is .zip" 
  [file] (= (fext file) "zip"))

(defn newdir
  "aux function to create a newdir if necessary - probably deprecated."
  [d]
  (let [f (io/file d)]
    (cond 
      (not (.exists f))
	      (do (.mkdirs f)
	           (fpath f))
       (.isDirectory f)
        (fpath f)
        :else "nofile")))

(defn derive-zip-path 
  "return a directory path with the same name as the file, minus the extension"
  [filepath]
  (subs filepath 0 (- (count filepath) 4)))

(defn decompress!
  "Given a path to a zip archive, zipfile, and an output path, decompresses a 
   single entry from a zipfile."
  [zipfile outpath ^java.util.zip.ZipEntry entry]
   (let [instream (.getInputStream zipfile entry)
         newpath (relative-path outpath [(.getName entry)])]
     (if  (.isDirectory entry)
              (make-folders! newpath)   
              (let [_ (make-file! newpath) 
                    f (io/file newpath)]
                (io/copy instream f))))) 

(defn zipfile->folders!
  "Given only a filepath to a valid zipfile, decompresses the zipfile, retaining
   internal structure.  If not outpath is provided, a path is derived from 
   inpath.  Returns a file handle for outpath."  
  ([inpath] (zipfile->folders! inpath (derive-zip-path inpath)))
  ([inpath outpath]
    (if (newdir outpath) ;outpath is valid
	      (let [zipfile (java.util.zip.ZipFile. inpath)]
          (doseq [^java.util.zip.ZipEntry e (enumeration-seq 
                                              (.entries zipfile))] 
            (decompress! zipfile outpath e))
           (io/file outpath))))) 

(def unzip-file 
  "Alias for zipfile->folders! used to decompress archives." 
   zipfile->folders!)

(defn- file->zipentry [f] (java.util.zip.ZipEntry. (uri-path (file->uri f))))
(defn pack-file!
  "Given a ZipOutputStream zipstream, a name for an entry, and the entry's 
   content, renders contents to a readable string, and packages its bytes "
  ([^java.util.zip.ZipOutputStream zipstream file name]
    (do (.putNextEntry zipstream (java.util.zip.ZipEntry. name))
        (io/copy (io/file file) zipstream)
        (.closeEntry zipstream)
        zipstream))
  ([^java.util.zip.ZipOutputStream zipstream file]
	   (pack-file! zipstream file (fname file))))
     
(defn child-seq
  "A variation of file-seq.  file-seq prepends the source folder as part of 
   the sequence.  We drop that here. If a filtering predicate, 
   filterf::File->boolean is supplied, then only files/directories passing the 
   filter will appear in the sequence. "
  [folder & [filterf & rest]]
  (if filterf (filterf (list-files folder)) 
              (list-files folder)))
  
(defn pack-folder!
  "Given a ZipOutputStream zipstream, and a starting folder, visits each file
   in a breadth-first manner, packing files relative to the basefolder.
   If a filtering predicate, filterf::File->boolean is supplied, then only 
   files/directories passing the filter will be packed. "
  [^java.util.zip.ZipOutputStream zipstream basefolder & [filterf & rest]]
  (let [basepath (uri-path (file->uri basefolder))
        relativize (fn [path]  (subs path (count basepath)))
        file->uripath (comp uri-path file->uri)
        _ (println ["zipping" basepath])]
	  (loop [folderq (conj clojure.lang.PersistentQueue/EMPTY basefolder)]
	    (if (seq folderq)
		     (let [nextfolder (peek folderq)
               _ (println (child-seq nextfolder filterf))
               nextfolders 
               (->> (child-seq nextfolder filterf)
	                  (reduce (fn [acc f]
		                            (let [entryname (relativize (file->uripath f))
		                                  _ (println entryname)]
									                 (if (folder? f) 
									                     (do (comment (.putNextEntry zipstream 
								                              (java.util.zip.ZipEntry. entryname)))
									                         (conj acc f))
									                     (do (pack-file! zipstream  f entryname)                                            
								                            acc))))  (pop folderq)))]
	         (recur nextfolders))
         zipstream))))            

(defn file->zipstream
  "Returns an open ZipOutputStream on an open OutputStream on file f."
  [f] 
  (java.util.zip.ZipOutputStream. (io/output-stream (io/file f))))

(defmacro with-new-zipfile
	 "Evaluates body inside a context in which *zip* is bound to a ZipOutputStream
	  derived from a new zipfile located at path.  Archiving functions can use 
	  *zip* as a reference to the file.  Zipfile is safely closed if any errors
	  occur."
   [path & body]
	  `(let [file# (make-file! (if (is-zip? ~path) ~path (str ~path ".zip")))]
	     (with-open [~'*zip*  (file->zipstream file#)] 
	        ~@body)))
      
(defn map->zipfile!
  "Unwraps a hashmap to an archive.  Uses a temp folder to render the map, 
   then zips the map into a zipfile located at rootpath. Same options as for
   map->folders.  Temporary folders are safely deleted after compression."
  [m rootpath & opts]
    (with-temp-dir
      (do (apply map->folders! m *tmpdir* opts)
          (with-new-zipfile rootpath 
            (pack-folder! *zip* (io/file *tmpdir*))))))

(defn zipfile->map
  "Reads a map from a zipfile at rootpath.  Uses a temp folder to render the map 
   then reads the map from the folder structure. Same options as for
   folders->map.  Temporary folders are safely deleted after decompression."
  [zippath]
  (with-temp-dir (folders->map (zipfile->folders! zippath *tmpdir*)))) 


;;__Functions for working with GZIP streams and files.__
;;We'd like to be able to save arbitrary text files
;;in a compressed gzip format.  Thankfully, java already
;;provides facilities for this, we just need to wrap them.

(defn  zip-writer [^String path]
  (io/writer (GZIPOutputStream. (io/output-stream path))))

(defn  lz4-writer [^String path]
  (io/writer (LZ4BlockOutputStream. (io/output-stream path))))
  

(defn  zip-reader [^String path]
  (io/reader (GZIPInputStream. (io/input-stream path))))

(defn  lz4-reader [^String path]
  (io/reader (LZ4BlockInputStream. (io/input-stream path))))

(defn lz4-reader! [^String path]
  (let [fin (FileInputStream. path)
        lz  (LZ4BlockInputStream. fin)
        is  (InputStreamReader. lz "UTF-8")
        rdr (BufferedReader. is)]
    rdr))


(defn zip-reader! [^String path]
  (let [fin (FileInputStream. path)
        lz  (GZIPInputStream. fin)
        is  (InputStreamReader. lz "UTF-8")
        rdr (BufferedReader. is)]
    rdr))
  
;;we can try lz4...
;;GZIP is pretty slow :(
(comment ;testing
  (defn data [k]    (cons [:A :B :C :D]
                          (map (fn [n]
                                 [n n n n])
                               (range k))))
  (defn tabify [coll]
    (clojure.string/join \newline (map (fn [x] (clojure.string/join \tab x)) coll)))
  (defn spit-zip [coll]
    (with-open [^java.io.BufferedWriter w (zip-writer "c:/users/tspoon/blah.gz")]
      (doseq [x coll]
        (.write w (str (clojure.string/join \tab x) \newline)))))
  (defn zip-lines [xs]
    (with-open [^java.io.BufferedWriter w (zip-writer "c:/users/tspoon/blah.gz")]
      (doseq [^String x xs]
        (.write w x ))))

  (defn compress-lines
    ([from to]
     (with-open [^java.io.BufferedWriter w (zip-writer to)
                 ^java.io.BufferedReader r (io/reader from)]
       (doseq [^String l (line-seq r)]
         (.write w (str l \newline)))
        ))
    ([from] (compress-lines from (str from ".gz"))))
      
  (defn unzip-lines [path]
    (with-open [^java.io.BufferedReader r (zip-reader path)]
      (into [] (line-seq r))))

    (defn spit-lz4 [coll]
    (with-open [^java.io.BufferedWriter w (lz4-writer "c:/users/tspoon/blah.lz4")]
      (doseq [x coll]
        (.write w (str (clojure.string/join \tab x) \newline)))))
    
  (defn lz4-lines [xs]
    (with-open [^java.io.BufferedWriter w (lz4-writer "c:/users/tspoon/blah.lz4")]
      (doseq [^String x xs]
        (.write w x ))))

  ;;probably a faster way to do this...
  (defn compress-lines-lz4
    ([from to]
     (with-open [^java.io.BufferedWriter w (lz4-writer to)
                 ^java.io.BufferedReader r (io/reader from)]
       (doseq [^String l (line-seq r)]
         (.write w (str l \newline)))
        ))
    ([from] (compress-lines-lz4 from (str from ".lz4"))))
      
  (defn unzip-lines-lz4 [path]
    (with-open [^java.io.BufferedReader r (lz4-reader path)]
      (into [] (line-seq r))))
      
  (defn count-zipped-lines [path]
    (with-open [^java.io.BufferedReader r (zip-reader path)]
      (reduce (fn [acc _] (unchecked-inc acc)) 0  (line-seq r))))

  (defn count-zipped-lines-lz4 [path]
    (with-open [^java.io.BufferedReader r (lz4-reader path)]
      (reduce (fn [acc _] (unchecked-inc acc)) 0  (line-seq r))))
  (defn count-lines [path]
    (with-open [^java.io.BufferedReader r (io/reader path)]
      (reduce (fn [acc _] (unchecked-inc acc)) 0  (line-seq r))))
    

  
)
