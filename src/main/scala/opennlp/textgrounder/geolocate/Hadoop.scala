///////////////////////////////////////////////////////////////////////////////
//  Hadoop.scala
//
//  Copyright (C) 2011-2014 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.textgrounder
package geolocate

import collection.JavaConversions._

import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.fs.Path

import util.argparser._
import util.collection.TransposeIterator
import util.spherical._
import util.hadoop._
import util.io.FileHandler
import util.math.{mean, median}
import util.error.warning
import util.print.errprint
import util.textdb.Schema

import gridlocate._

/* Basic idea for hooking up Geolocate with Hadoop.  Hadoop works in terms
   of key-value pairs, as follows:

   (1) A preprocessor generates key-value pairs, which are passed to hadoop.
       Note that typically at this stage what's passed to Hadoop is not
       in the form of a key-value pair but just some sort of item, e.g. a
       line of text.  This typically becomes the value of the key-value pair,
       while something that most programs ignore becomes the key (e.g. the
       item count of the item that was seen).  Note that these keys and values,
       as for all data passed around by Hadoop, is typed, and the type is
       under the programmer's control.  Hence, although the value is commonly
       text, it may not be.

   (2) Hadoop creates a number of mappers and partitions the input items in
       some way, passing some fraction of the input items to each mapper.

   (3) Each mapper iterates over its input items, and for each in turn,
       generates a possibly-empty set of key-value output items.  Note that
       the types of the output keys and values may be totally different from
       those of the input keys and values.  The output key has much more
       significance than the input key.

   (5) A "shuffle" step happens internally, where all output items are
       grouped according to their keys, and the keys are further sorted.
       (Or equivalently, the entire set of output items is sorted on their
       keys, which will naturally place identical keys next to each other
       as well as ensuring that non-identical keys are sorted, and then
       sets of items with identical keys are transformed into single items
       with the same key and a value consisting of a list of all of items
       grouped together.) What actually happens is that the items are
       sorted and grouped at the end of the map stage on the map node,
       *before* being sent over the network; the overall "shuffle" then
       simply involves merging.

   (4.5) To reduce the amount of data sent over the network, a combiner can
         be defined, which runs on the map node after sorting but before
         sending the data over.  This is optional, and if it exists, does
         a preliminary reduce.  Depending on the task in question, this may
         be exactly the same as the reducer.  For example, if the reducer
         simply adds up all of the items passed to it, the same function
         can be used as a combiner, since a set of number can be added up
         all at once or in parts. (An example of where this can't be done
         is when the reducer needs to find the median of a set of items.
         Computing the median involves selecting one of the items of a
         set rather than mashing them all together, and which item is to
         be selected cannot be known until the entire set is seen.  Given
         a subset, the median could be any value in the subset; hence the
         entire subset must be sent along, and cannot in general be
         "combined" in any way.)

   (6) A set of reducers are created, and the resulting grouped items are
       partitioned based on their keys, with each reducer receiving one
       sorted partition, i.e. a list of all the items whose keys were
       assigned to that reducer, in sorted order, where the value of each
       item (remember, items are key-value pairs) is a list of items
       (all values associated with the same key in the items output by
       the mapper).  Each key is seen only once (assuming no crashes/restarts),
       and only on a single reducer.  The reducer then outputs its own
       output pairs, typically by "reducing" the value list of each key
       into a single item.

   (7) A post-processor might take these final output items and do something
       with them.

   Note about types:

   In general:

   MAP STAGE:

   Mapper input is of type A -> B
   Mapper output is of type C -> D
   Hence map() is of type (A -> B) -> Iterable[(C -> D)]

   Often types B and C are identical or related.

   COMBINE STAGE:

   The combiner is strictly an optimization, and the program must work
   correctly regardless of whether the combiner is run or not -- or, for
   that matter, if run multiple times.  This means that the input and
   output types of the combiner must be the same, and in most cases
   the combiner must be idempotent (i.e. if its input is a previous
   output, it should output is input unchanged; in other words, it
   does nothing if run multiple times on the same input).

   Combiner input is of type C -> Iterable[D]
   Combiner output is of type C -> Iterable[D]
   Hence combine() is of type (C -> Iterable[D]) -> (C -> Iterable[D])

   (The output of the cominber is grouped, just like its input from the
   map output.)

   REDUCE STAGE:

   Reducer input is of type C -> Iterable[D]
   Reducer output is of type E -> F
   Hence reduce() is of type (C -> Iterable[D]) -> (E -> F)

   In our case, we assume that the mappers() do the real work and the
   reducers just collect the stats and combine them.  We can break a
   big job in two ways: Either by partitioning the set of test documents
   and having each mapper do a full evaluation on a limited number of
   test documents, or by partitioning the grid and have each mapper
   compare all test documents against a portion of the grid.  A third
   possibility is to combine both, where a mapper does a portion of
   the test documents against a portion of the grid.

   OUR IMPLEMENTATION:

   Input values to map() are tuples (ranker, document).  Output items
   are have key = (cellgrid-details, ranker), value = result for
   particular document (includes various items, including document,
   predicted cell, true rank, various distances).  No combiner, since
   we have to compute a median, meaning we need all values.  Reducer
   computes mean/median for all values for a given cellgrid/ranker.
   NOTE: For identifying a particular cell, we use indices, since we
   can't pass pointers.  For KD trees and such, we conceivably might have
   to pass in to the reducer some complex details identifying the
   cell grid parameters.  If so, this probably would get passed first
   to all reducers using the trick of creating a custom partitioner
   that ensures the reducer gets this info first.
*/

/************************************************************************/
/*                  General Hadoop code for Geolocate app               */
/************************************************************************/

trait HadoopGeolocateTextDBMixin extends HadoopTextDBMixin {
  type TDriver <: HadoopGeolocateDriver
  val driver: TDriver

  def corpus_suffix = driver.document_textdb_suffix
  def corpus_dirs = driver.params.input ++ driver.params.train
}

abstract class HadoopGeolocateApp(
  progname: String
) extends GeolocateApp(progname)
    with HadoopTextDBApp
    with HadoopGeolocateTextDBMixin {
  override def initialize_hadoop_input(job: Job) {
    super.initialize_hadoop_input(job)
    FileOutputFormat.setOutputPath(job, new Path(params.outfile))
  }
}

class HadoopGeolocateParameters(
  parser: ArgParser
) extends GeolocateParameters(parser) {
  var textgrounder_dir =
    ap.option[String]("textgrounder-dir",
      must = be_specified,
      help = """Directory to use in place of TEXTGROUNDER_DIR environment
variable (e.g. in Hadoop).""")

  if (ap.parsedValues)
    TextGrounderInfo.set_textgrounder_dir(textgrounder_dir)

  var outfile =
    ap.positional[String]("outfile",
      must = be_specified,
      help = """File to store evaluation results in.""")

}

/**
 * Base mix-in for a Geolocate application using Hadoop.
 *
 * @see GeolocateDriver
 */

trait HadoopGeolocateDriver extends
    GeolocateDriver with HadoopExperimentDriver {
  override type TParam <: HadoopGeolocateParameters
}

/**
 * Class for a geolocate-document application using Hadoop.
 *
 * @see HadoopGeolocateDriver
 */

class HadoopGeolocateDocumentDriver extends
    GeolocateDocumentDriver with HadoopGeolocateDriver {
  override type TParam = HadoopGeolocateParameters
}

/************************************************************************/
/*                Hadoop implementation of geolocate-document           */
/************************************************************************/

class DocEvalMapper
    extends Mapper[Object, Text, Text, DoubleWritable]
       with HadoopExperimentMapReducer
       with HadoopGeolocateTextDBMixin {
  def progname = HadoopGeolocateDocument.progname
  type TContext = Mapper[Object, Text, Text, DoubleWritable]#Context
  type TDriver = HadoopGeolocateDocumentDriver
  // more type erasure crap
  def create_param_object(ap: ArgParser) = new TParam(ap)
  def create_driver = new TDriver

  lazy val filehand = driver.getfh

  override def run(context: TContext) {
    super.init(context)
    if (driver.params.eval_format != "textdb")
      driver.params.parser.error(
        "For Hadoop, '--eval-format' must be 'textdb'")
    else {
      if (corpus_dirs.size != 1) {
        driver.params.parser.error(
          "FIXME: For Hadoop, currently need exactly one corpus")
      }
    }
    val ranker = driver.create_ranker
    context.progress
    val schema = Schema.read_schema_from_textdb(filehand,
      corpus_dirs.head, suffix_re = corpus_suffix)
    context.progress

    class HadoopIterator extends Iterator[(FileHandler, String, String, Long)] {
      def hasNext = context.nextKeyValue
      def next = {
        // FIXME: Is this actually the line no or character offset???
        val lineno = context.getCurrentKey.toString.toLong
        val line = context.getCurrentValue.toString
        (filehand, driver.get_configuration.get("mapred.input.dir"),
          line, lineno)
      }
    }

    // FIXME! This won't work. Can't iterate multiple times over the input
    // except with multiple map-reduce sessions. Need to scrap this whole
    // Hadoop code and use Scoobi.
    val get_docstats = () => {
      val lines = new HadoopIterator
      lines.map {
        case (filehand, file, line, lineno) => {
          val rawdocstat = GridDocFactory.line_to_raw_document(filehand, file,
            // FIXME: Set domain and importance
            line, lineno, "", 1.0, schema)
          ranker.grid.docfact.raw_document_to_document_status(rawdocstat,
            skip_no_coord = true, note_globally = false)
        }
      } map { stat =>
        stat.foreach {
          case (rawdoc, doc) => doc.lang_model.finish_after_global() }
        stat
      }
    }
    val evalobj = driver.create_cell_evaluator(ranker)
    for (result <- evalobj.evaluate_documents(get_docstats)) {
      context.write(new Text(ranker.ranker_name),
        // FIXME! This will crash if we try to use Hadoop when the
        // document's correct coord is unknown. Either do something sensible
        // or issue an error up front when incompatible combination of
        // parameters is detected.
        new DoubleWritable(result.correct.get.pred_truedist))
    }
  }
}

class DocResultReducer extends
    Reducer[Text, DoubleWritable, Text, DoubleWritable] {

  type TContext = Reducer[Text, DoubleWritable, Text, DoubleWritable]#Context

  var driver: HadoopGeolocateDocumentDriver = _

  override def setup(context: TContext) {
    driver = new HadoopGeolocateDocumentDriver
    driver.set_task_context(context)
  }

  override def reduce(key: Text, values: java.lang.Iterable[DoubleWritable],
      context: TContext) {
    val errordists = (for (v <- values) yield v.get).toIndexedSeq
    val mean_dist = mean(errordists)
    val median_dist = median(errordists)
    context.write(new Text(key.toString + " mean"), new DoubleWritable(mean_dist))
    context.write(new Text(key.toString + " median"), new DoubleWritable(median_dist))
  }
}

object HadoopGeolocateDocument extends
    HadoopGeolocateApp("HadoopGeolocateDocument") {
  type TDriver = HadoopGeolocateDocumentDriver
  // FUCKING TYPE ERASURE
  def create_param_object(ap: ArgParser) = new TParam(ap)
  def create_driver = new TDriver

  def initialize_hadoop_classes(job: Job) {
    job.setJarByClass(classOf[DocEvalMapper])
    job.setMapperClass(classOf[DocEvalMapper])
    job.setReducerClass(classOf[DocResultReducer])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[DoubleWritable])
  }
}
