//  AnalyzeResults.scala
//
//  Copyright (C) 2013 Ben Wing, The University of Texas at Austin
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
package postprocess

import collection.mutable

import util.argparser._
import util.collection._
import util.distances._
import util.experiment._
import util.io
import util.math._
import util.print._
import util.Serializer
import util.textdb._

import util.debug._

class AnalyzeResultsParameters(ap: ArgParser) {
  var pred_cell_distribution = ap.option[String]("pred-cell-distribution",
    "pred-cell-distrib", "pcd",
    metavar = "FILE",
    help="""Output Zipfian distribution of predicted cells,
to see the extent to which they are balanced or unbalanced.""")
  var correct_cell_distribution = ap.option[String]("correct-cell-distribution",
    "correct-cell-distrib", "tcd",
    metavar = "FILE",
    help="""Output Zipfian distribution of correct cells,
to see the extent to which they are balanced or unbalanced.""")
  var input = ap.positional[String]("input",
    help = "Results file to analyze.")

  var debug =
    ap.option[String]("d", "debug", metavar = "FLAGS",
      help = """Output debug info of the given types.  Multiple debug
parameters can be specified, indicating different types of info to output.
Separate parameters by spaces, colons or semicolons.  Params can be boolean,
if given alone, or valueful, if given as PARAM=VALUE.  Certain params are
list-valued; multiple values are specified by including the parameter
multiple times, or by separating values by a comma.
""")
}

/**
 * An application to analyze the results from a TextGrounder run,
 * as output using --results.
 */
object AnalyzeResults extends ExperimentApp("classify") {

  type TParam = AnalyzeResultsParameters

  def create_param_object(ap: ArgParser) = new AnalyzeResultsParameters(ap)

  def initialize_parameters() {
    if (params.debug != null)
      parse_debug_spec(params.debug)
  }

  def output_freq_of_freq(filehand: io.FileHandler, file: String,
      map: collection.Map[String, Int]) {
    val numcells = map.values.sum
    var sofar = 0
    val outf = filehand.openw(file)
    for (((cell, count), ind) <-
        map.toSeq.sortWith(_._2 > _._2).zipWithIndex) {
      sofar += count
      outf.println("%s  %s  %s  %.2f%%" format (
        ind + 1, cell, count, sofar.toDouble / numcells * 100))
    }
    outf.close()
  }

  def print_stats(prefix: String, units: String, nums: Seq[Double]) {
    def pr(fmt: String, args: Any*) {
      outprint("%s: %s", prefix, fmt format (args: _*))
    }
    pr("Mean: %.2f%s +/- %.2f%s", mean(nums), units, stddev(nums), units)
    pr("Median: %.2f%s", median(nums), units)
    pr("Mode: %.2f%s", mode(nums), units)
    pr("Range: [%.2f%s to %.2f%s]", nums.min, units, nums.max, units)
  }

  def run_program(args: Array[String]) = {
    val correct_cells = intmap[String]()
    val pred_cells = intmap[String]()
    var numtokens = Vector[Double]()
    var numtypes = Vector[Int]()
    var numcorrect = 0
    var numseen = 0
    var oracle_dist_true_center = Vector[Double]()
    var oracle_dist_centroid = Vector[Double]()
    var oracle_dist_central_point = Vector[Double]()
    var error_dist_true_center = Vector[Double]()
    var error_dist_centroid = Vector[Double]()
    var error_dist_central_point = Vector[Double]()

    val filehand = io.localfh
    val input_file =
      if (params.input contains "/") params.input
      else "./" + params.input
    val (dir, base) = filehand.split_filename(input_file)
    val (schema, field_iter) =
      TextDB.read_textdb_with_schema(filehand, dir, prefix = base)
    for (fieldvals <- field_iter.flatten) {
      def get[T : Serializer](field: String) =
        schema.get_value[T](fieldvals, field)
      def gets(field: String) = schema.get_field(fieldvals, field)
      val correct_cell = gets("correct-cell")
      correct_cells(gets("correct-cell")) += 1
      correct_cells(gets("pred-cell")) += 1
      val correct_coord = get[SphereCoord]("correct-coord")
      def dist_to(field: String) =
        spheredist(correct_coord, get[SphereCoord](field))
      oracle_dist_true_center :+= dist_to("correct-cell-true-center")
      oracle_dist_centroid :+= dist_to("correct-cell-centroid")
      oracle_dist_central_point :+= dist_to("correct-cell-central-point")
      error_dist_true_center :+= dist_to("pred-cell-true-center")
      error_dist_centroid :+= dist_to("pred-cell-centroid")
      error_dist_central_point :+= dist_to("pred-cell-central-point")
      numtypes :+= get[Int]("numtypes")
      numtokens :+= get[Double]("numtokens")
      numseen += 1
      if (get[Int]("correct-rank") == 1)
        numcorrect += 1
    }
    outprint("Accuracy: %.4f (%s/%s)" format
      ((numcorrect.toDouble / numseen), numcorrect, numseen))
    print_stats("Oracle distance to central point", " km", oracle_dist_central_point)
    print_stats("Oracle distance to centroid", " km", oracle_dist_centroid)
    print_stats("Oracle distance to true center", " km", oracle_dist_true_center)
    print_stats("Error distance to central point", " km", error_dist_central_point)
    print_stats("Error distance to centroid", " km", error_dist_centroid)
    print_stats("Error distance to true center", " km", error_dist_true_center)
    print_stats("Word types per document", "", numtypes map { _.toDouble })
    print_stats("Word tokens per document", "", numtokens map { _.toDouble })
    if (params.pred_cell_distribution != null)
      output_freq_of_freq(filehand, params.pred_cell_distribution, pred_cells)
    if (params.correct_cell_distribution != null)
      output_freq_of_freq(filehand, params.correct_cell_distribution, correct_cells)
    0
  }
}