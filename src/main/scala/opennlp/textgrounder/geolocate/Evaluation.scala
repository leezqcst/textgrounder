package opennlp.textgrounder.geolocate

import NlpUtil._
import Distances._
import Debug._
import GeolocateDriver.Opts

import math._
import collection.mutable
import util.control.Breaks._
import java.io._

/////////////////////////////////////////////////////////////////////////////
//                 General statistics on evaluation results                //
/////////////////////////////////////////////////////////////////////////////

// incorrect_reasons is a map from ID's for reasons to strings describing
// them.
class EvalStats(incorrect_reasons: Map[String, String]) {
  // Statistics on the types of instances processed
  // Total number of instances
  var total_instances = 0
  var correct_instances = 0
  var incorrect_instances = 0
  val other_stats = intmap[String]()
  // Map from reason ID's to counts
  var results = intmap[String]()

  def record_result(correct: Boolean, reason: String = null) {
    if (reason != null)
      assert(incorrect_reasons.keySet contains reason)
    total_instances += 1
    if (correct)
      correct_instances += 1
    else {
      incorrect_instances += 1
      if (reason != null)
        results(reason) += 1
    }
  }

  def record_other_stat(othertype: String) {
    other_stats(othertype) += 1
  }

  def output_fraction(header: String, amount: Int, total: Int) {
    if (amount > total) {
      warning("Something wrong: Fractional quantity %s greater than total %s",
        amount, total)
    }
    var percent =
      if (total == 0) "indeterminate percent"
      else "%5.2f%%" format (100 * amount.toDouble / total)
    errprint("%s = %s/%s = %s", header, amount, total, percent)
  }

  def output_correct_results() {
    output_fraction("Percent correct", correct_instances,
      total_instances)
  }

  def output_incorrect_results() {
    output_fraction("Percent incorrect", incorrect_instances,
      total_instances)
    for ((reason, descr) <- incorrect_reasons) {
      output_fraction("  %s" format descr, results(reason), total_instances)
    }
  }

  def output_other_stats() {
    for ((ty, count) <- other_stats)
      errprint("%s = %s", ty, count)
  }

  def output_results() {
    if (total_instances == 0) {
      warning("Strange, no instances found at all; perhaps --eval-format is incorrect?")
      return
    }
    errprint("Number of instances = %s", total_instances)
    output_correct_results()
    output_incorrect_results()
    output_other_stats()
  }
}

class EvalStatsWithRank(
  max_rank_for_credit: Int = 10
) extends EvalStats(Map[String, String]()) {
  val incorrect_by_exact_rank = intmap[Int]()
  val correct_by_up_to_rank = intmap[Int]()
  var incorrect_past_max_rank = 0
  var total_credit = 0

  def record_result(rank: Int) {
    assert(rank >= 1)
    val correct = rank == 1
    super.record_result(correct, reason = null)
    if (rank <= max_rank_for_credit) {
      total_credit += max_rank_for_credit + 1 - rank
      incorrect_by_exact_rank(rank) += 1
      for (i <- rank to max_rank_for_credit)
        correct_by_up_to_rank(i) += 1
    } else
      incorrect_past_max_rank += 1
  }

  override def output_correct_results() {
    super.output_correct_results()
    val possible_credit = max_rank_for_credit * total_instances
    output_fraction("Percent correct with partial credit",
      total_credit, possible_credit)
    for (i <- 2 to max_rank_for_credit) {
      output_fraction("  Correct is at or above rank %s" format i,
        correct_by_up_to_rank(i), total_instances)
    }
  }

  override def output_incorrect_results() {
    super.output_incorrect_results()
    for (i <- 2 to max_rank_for_credit) {
      output_fraction("  Incorrect, with correct at rank %s" format i,
        incorrect_by_exact_rank(i),
        total_instances)
    }
    output_fraction("  Incorrect, with correct not in top %s" format
      max_rank_for_credit,
      incorrect_past_max_rank, total_instances)
  }
}

//////// Statistics for geotagging documents/articles

class GeotagDocumentEvalStats(
  max_rank_for_credit: Int = 10
) extends EvalStatsWithRank(max_rank_for_credit) {
  // "True dist" means actual distance in km's or whatever.
  // "Degree dist" is the distance in degrees.
  val true_dists = mutable.Buffer[Double]()
  val degree_dists = mutable.Buffer[Double]()
  val oracle_true_dists = mutable.Buffer[Double]()
  val oracle_degree_dists = mutable.Buffer[Double]()

  def record_result(rank: Int, pred_true_dist: Double,
      pred_degree_dist: Double) {
    super.record_result(rank)
    true_dists += pred_true_dist
    degree_dists += pred_degree_dist
  }

  def record_oracle_result(oracle_true_dist: Double,
      oracle_degree_dist: Double) {
    oracle_true_dists += oracle_true_dist
    oracle_degree_dists += oracle_degree_dist
  }

  override def output_incorrect_results() {
    super.output_incorrect_results()
    def miles_and_km(miledist: Double) = {
      "%.2f miles (%.2f km)" format (miledist, miledist * km_per_mile)
    }
    errprint("  Mean true error distance = %s",
      miles_and_km(mean(true_dists)))
    errprint("  Median true error distance = %s",
      miles_and_km(median(true_dists)))
    errprint("  Mean degree error distance = %.2f degrees",
      mean(degree_dists))
    errprint("  Median degree error distance = %.2f degrees",
      median(degree_dists))
    errprint("  Mean oracle true error distance = %s",
      miles_and_km(mean(oracle_true_dists)))
    errprint("  Median oracle true error distance = %s",
      miles_and_km(median(oracle_true_dists)))
  }
}

/**
 * Class for statistics for geotagging documents/articles, with separate
 * sets of statistics for different intervals of error distances and
 * number of articles in true cell.
 */

class GroupedGeotagDocumentEvalStats(cellgrid: CellGrid) {

  def create_doc() = new GeotagDocumentEvalStats()
  val all_document = create_doc()

  // naitr = "num articles in true cell"
  val docs_by_naitr = new IntTableByRange(Seq(1, 10, 25, 100), create_doc _)

  // Results for documents where the location is at a certain distance
  // from the center of the true statistical cell.  The key is measured in
  // fractions of a tiling cell (determined by 'dist_fraction_increment',
  // e.g. if dist_fraction_increment = 0.25 then values in the range of
  // [0.25, 0.5) go in one bin, [0.5, 0.75) go in another, etc.).  We measure
  // distance is two ways: true distance (in miles or whatever) and "degree
  // distance", as if degrees were a constant length both latitudinally
  // and longitudinally.
  val dist_fraction_increment = 0.25
  def docmap() = defaultmap[Double, GeotagDocumentEvalStats](create_doc())
  val docs_by_degree_dist_to_true_center = docmap()
  val docs_by_true_dist_to_true_center = docmap()

  // Similar, but distance between location and center of top predicted
  // cell.
  val dist_fractions_for_error_dist = Seq(
    0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8,
    12, 16, 24, 32, 48, 64, 96, 128, 192, 256,
    // We're never going to see these
    384, 512, 768, 1024, 1536, 2048)
  val docs_by_degree_dist_to_pred_center =
    new DoubleTableByRange(dist_fractions_for_error_dist, create_doc _)
  val docs_by_true_dist_to_pred_center =
    new DoubleTableByRange(dist_fractions_for_error_dist, create_doc _)

  def record_result(res: ArticleEvaluationResult) {
    all_document.record_result(res.true_rank,
      res.pred_truedist, res.pred_degdist)
    val naitr = docs_by_naitr.get_collector(res.num_arts_in_true_cell)
    naitr.record_result(res.true_rank, res.pred_truedist, res.pred_degdist)

    /* FIXME: This code specific to MultiRegularCellGrid is kind of ugly.
       Perhaps it should go elsewhere.

       FIXME: Also note that we don't actually make use of the info we
       record here. See below.
     */
    if (cellgrid.isInstanceOf[MultiRegularCellGrid]) {
      val multigrid = cellgrid.asInstanceOf[MultiRegularCellGrid]

      /* For distance to center of true cell, which will be small (no more
         than width_of_multi_cell * size-of-tiling-cell); we convert to
         fractions of tiling-cell size and record in ranges corresponding
         to increments of 0.25 (see above). */
      /* True distance (in both miles and degrees) as a fraction of
         cell size */
      val frac_true_truedist = res.true_truedist / multigrid.miles_per_cell
      val frac_true_degdist = res.true_degdist / multigrid.degrees_per_cell
      /* Round the fractional distances to multiples of
         dist_fraction_increment */
      val fracinc = dist_fraction_increment
      val rounded_frac_true_truedist =
        fracinc * floor(frac_true_degdist / fracinc)
      val rounded_frac_true_degdist =
        fracinc * floor(frac_true_degdist / fracinc)
      all_document.record_oracle_result(res.true_truedist, res.true_degdist)
      docs_by_true_dist_to_true_center(rounded_frac_true_truedist).
        record_result(res.true_rank, res.pred_truedist, res.pred_degdist)
      docs_by_degree_dist_to_true_center(rounded_frac_true_degdist).
        record_result(res.true_rank, res.pred_truedist, res.pred_degdist)

      /* For distance to center of predicted cell, which may be large, since
         predicted cell may be nowhere near the true cell.  Again we convert
         to fractions of tiling-cell size and record in the ranges listed in
         dist_fractions_for_error_dist (see above). */
      /* Predicted distance (in both miles and degrees) as a fraction of
         cell size */
      val frac_pred_truedist = res.pred_truedist / multigrid.miles_per_cell
      val frac_pred_degdist = res.pred_degdist / multigrid.degrees_per_cell
      docs_by_true_dist_to_pred_center.get_collector(frac_pred_truedist).
        record_result(res.true_rank, res.pred_truedist, res.pred_degdist)
      docs_by_degree_dist_to_pred_center.get_collector(frac_pred_degdist).
        record_result(res.true_rank, res.pred_truedist, res.pred_degdist)
    }
  }

  def record_other_stat(othertype: String) {
    all_document.record_other_stat(othertype)
  }

  def output_results(all_results: Boolean = false) {
    errprint("")
    errprint("Results for all documents/articles:")
    all_document.output_results()
    /* FIXME: This code specific to MultiRegularCellGrid is kind of ugly.
       Perhaps it should go elsewhere.

       FIXME: Also note that we don't actually do anything here, because of
       the 'if (false)'.  See above.
     */
    //if (all_results)
    if (false) {
      errprint("")
      for ((lower, upper, obj) <- docs_by_naitr.iter_ranges()) {
        errprint("")
        errprint("Results for documents/articles where number of articles")
        errprint("  in true cell is in the range [%s,%s]:",
          lower, upper - 1)
        obj.output_results()
      }
      errprint("")

      if (cellgrid.isInstanceOf[MultiRegularCellGrid]) {
        val multigrid = cellgrid.asInstanceOf[MultiRegularCellGrid]

        for (
          (frac_truedist, obj) <-
            docs_by_true_dist_to_true_center.toSeq sortBy (_._1)
        ) {
          val lowrange = frac_truedist * multigrid.miles_per_cell
          val highrange = ((frac_truedist + dist_fraction_increment) *
            multigrid.miles_per_cell)
          errprint("")
          errprint("Results for documents/articles where distance to center")
          errprint("  of true cell in miles is in the range [%.2f,%.2f):",
            lowrange, highrange)
          obj.output_results()
        }
        errprint("")
        for (
          (frac_degdist, obj) <-
            docs_by_degree_dist_to_true_center.toSeq sortBy (_._1)
        ) {
          val lowrange = frac_degdist * multigrid.degrees_per_cell
          val highrange = ((frac_degdist + dist_fraction_increment) *
            multigrid.degrees_per_cell)
          errprint("")
          errprint("Results for documents/articles where distance to center")
          errprint("  of true cell in degrees is in the range [%.2f,%.2f):",
            lowrange, highrange)
          obj.output_results()
        }
      }
    }
    // FIXME: Output median and mean of true and degree error dists; also
    // maybe move this info info EvalByRank so that we can output the values
    // for each category
    errprint("")
    output_resource_usage()
  }
}

/////////////////////////////////////////////////////////////////////////////
//                             Main evaluation code                        //
/////////////////////////////////////////////////////////////////////////////

/**
 * General trait for classes representing documents to evaluate.
 */
trait EvaluationDocument {
}

/**
 * General trait for classes representing result of evaluating a document.
 */
trait EvaluationResult {
}

/**
 * Abstract class for reading documents from a test file and evaluating
 * on them.
 */
abstract class TestFileEvaluator(stratname: String) {
  var documents_processed = 0

  type Document <: EvaluationDocument

  /**
   * Return an Iterable listing the documents retrievable from the given
   * filename.
   */
  def iter_documents(filename: String): Iterable[Document]

  /**
   * Return true if document would be skipped; false if processed and
   * evaluated.
   */
  def would_skip_document(doc: Document, doctag: String) = false

  /**
   * Return true if document was actually processed and evaluated; false
   * if skipped.
   */
  def evaluate_document(doc: Document, doctag: String):
    EvaluationResult

  /**
   * Output results so far.  If 'isfinal', this is the last call, so
   * output more results.
   */
  def output_results(isfinal: Boolean = false): Unit
}

abstract class GeotagDocumentEvaluator(
  strategy: GeotagDocumentStrategy,
  stratname: String
) extends TestFileEvaluator(stratname) {
  val evalstats = new GroupedGeotagDocumentEvalStats(strategy.cellgrid)

  def output_results(isfinal: Boolean = false) {
    evalstats.output_results(all_results = isfinal)
  }
}

case class ArticleEvaluationResult(
  article: StatArticle,
  pred_cell: StatCell,
  true_rank: Int
) extends EvaluationResult {
  val true_cell = pred_cell.cellgrid.find_best_cell_for_coord(article.coord)
  val num_arts_in_true_cell = true_cell.worddist.num_arts_for_word_dist
  val true_center = true_cell.get_center_coord()
  val true_truedist = spheredist(article.coord, true_center)
  val true_degdist = degree_dist(article.coord, true_center)
  val pred_center = pred_cell.get_center_coord()
  val pred_truedist = spheredist(article.coord, pred_center)
  val pred_degdist = degree_dist(article.coord, pred_center)
}

/**
  Class to do document geotagging on articles from the article data, in
  the dev or test set.
 */
class ArticleGeotagDocumentEvaluator(
  strategy: GeotagDocumentStrategy,
  stratname: String
) extends GeotagDocumentEvaluator(strategy, stratname) {

  type Document = StatArticle
  type DocumentResult = ArticleEvaluationResult

  // Debug flags:
  //
  //  gridrank: For the given test article number (starting at 1), output
  //            a grid of the predicted rank for cells around the true
  //            cell.  Multiple articles can have the rank output, e.g.
  //
  //            --debug 'gridrank=45,58'
  //
  //            (This will output info for articles 45 and 58.)
  //
  //  gridranksize: Size of the grid, in numbers of articles on a side.
  //                This is a single number, and the grid will be a square
  //                centered on the true cell.
  register_list_debug_param("gridrank")
  debugval("gridranksize") = "11"

  def iter_documents(filename: String) = {
    assert(filename == null)
    for (art <- StatArticleTable.table.articles_by_split(Opts.eval_set))
      yield art
  }

  //title = None
  //words = []
  //for line in openr(filename, errors="replace"):
  //  if (rematch("Article title: (.*)$", line))
  //    if (title != null)
  //      yield (title, words)
  //    title = m_[1]
  //    words = []
  //  else if (rematch("Link: (.*)$", line))
  //    args = m_[1].split('|')
  //    trueart = args[0]
  //    linkword = trueart
  //    if (len(args) > 1)
  //      linkword = args[1]
  //    words.append(linkword)
  //  else:
  //    words.append(line)
  //if (title != null)
  //  yield (title, words)

  override def would_skip_document(article: StatArticle, doctag: String) = {
    if (article.dist == null) {
      // This can (and does) happen when --max-time-per-stage is set,
      // so that the counts for many articles don't get read in.
      if (Opts.max_time_per_stage == 0.0 && Opts.num_training_docs == 0)
        warning("Can't evaluate article %s without distribution", article)
      evalstats.record_other_stat("Skipped articles")
      true
    } else false
  }

  def evaluate_document(article: StatArticle, doctag: String):
      EvaluationResult = {
    if (would_skip_document(article, doctag))
      return null
    assert(article.dist.finished)
    val true_cell =
      strategy.cellgrid.find_best_cell_for_coord(article.coord)
    if (debug("lots") || debug("commontop")) {
      val naitr = true_cell.worddist.num_arts_for_word_dist
      errprint("Evaluating article %s with %s word-dist articles in true cell",
        article, naitr)
    }

    /* That is:

       pred_cells = List of predicted cells, from best to worst; each list
          entry is actually a tuple of (cell, score) where lower scores
          are better
       true_rank = Rank of true cell among predicted cells
     */
    val (pred_cells, true_rank) =
      if (Opts.oracle_results)
        (Array((true_cell, 0.0)), 1)
      else {
        def get_computed_results() = {
          val cells = strategy.return_ranked_cells(article.dist).toArray
          var rank = 1
          var broken = false
          breakable {
            for ((cell, value) <- cells) {
              if (cell eq true_cell) {
                broken = true
                break
              }
              rank += 1
            }
          }
          if (!broken)
            rank = 1000000000
          (cells, rank)
        }

        get_computed_results()
      }
    val result =
      new ArticleEvaluationResult(article, pred_cells(0)._1, true_rank)

    val want_indiv_results =
      !Opts.oracle_results && !Opts.no_individual_results
    evalstats.record_result(result)
    if (result.num_arts_in_true_cell == 0) {
      evalstats.record_other_stat(
        "Articles with no training articles in cell")
    }
    if (want_indiv_results) {
      errprint("%s:Article %s:", doctag, article)
      errprint("%s:  %d types, %d tokens",
        doctag, article.dist.counts.size, article.dist.total_tokens)
      errprint("%s:  true cell at rank: %s", doctag, true_rank)
      errprint("%s:  true cell: %s", doctag, result.true_cell)
      for (i <- 0 until 5) {
        errprint("%s:  Predicted cell (at rank %s): %s",
          doctag, i + 1, pred_cells(i)._1)
      }
      errprint("%s:  Distance %.2f miles to true cell center at %s",
        doctag, result.true_truedist, result.true_center)
      errprint("%s:  Distance %.2f miles to predicted cell center at %s",
        doctag, result.pred_truedist, result.pred_center)
      assert(doctag(0) == '#')
      if (debug("gridrank") ||
        (debuglist("gridrank") contains doctag.drop(1))) {
        val grsize = debugval("gridranksize").toInt
        if (!true_cell.isInstanceOf[MultiRegularCell])
          warning("Can't output ranking grid, cell not of right type")
        else {
          strategy.cellgrid.asInstanceOf[MultiRegularCellGrid].
            output_ranking_grid(
              pred_cells.asInstanceOf[Seq[(MultiRegularCell, Double)]],
              true_cell.asInstanceOf[MultiRegularCell], grsize)
        }
      }
    }

    return result
  }
}

class TitledDocumentResult extends EvaluationResult {
}

class PCLTravelGeotagDocumentEvaluator(
  strategy: GeotagDocumentStrategy,
  stratname: String
) extends GeotagDocumentEvaluator(strategy, stratname) {
  case class TitledDocument(
    title: String, text: String) extends EvaluationDocument 
  type Document = TitledDocument
  type DocumentResult = TitledDocumentResult

  def iter_documents(filename: String) = {

    val dom = try {
      // On error, just return, so that we don't have problems when called
      // on the whole PCL corpus dir (which includes non-XML files).
      xml.XML.loadFile(filename)
    } catch {
      case _ => {
        warning("Unable to parse XML filename: %s", filename)
        null
      }
    }

    if (dom == null) Seq[TitledDocument]()
    else for {
      chapter <- dom \\ "div" if (chapter \ "@type").text == "chapter"
      val (heads, nonheads) = chapter.child.partition(_.label == "head")
      val headtext = (for (x <- heads) yield x.text) mkString ""
      val text = (for (x <- nonheads) yield x.text) mkString ""
      //errprint("Head text: %s", headtext)
      //errprint("Non-head text: %s", text)
    } yield TitledDocument(headtext, text)
  }

  def evaluate_document(doc: TitledDocument, doctag: String) = {
    val dist = WordDist()
    val the_stopwords =
      if (Opts.include_stopwords_in_article_dists) Set[String]()
      else Stopwords.stopwords
    for (text <- Seq(doc.title, doc.text)) {
      dist.add_words(split_text_into_words(text, ignore_punc = true),
        ignore_case = !Opts.preserve_case_words,
        stopwords = the_stopwords)
    }
    dist.finish(minimum_word_count = Opts.minimum_word_count)
    val cells = strategy.return_ranked_cells(dist)
    errprint("")
    errprint("Article with title: %s", doc.title)
    val num_cells_to_show = 5
    for ((rank, cellval) <- (1 to num_cells_to_show) zip cells) {
      val (cell, vall) = cellval
      if (debug("pcl-travel")) {
        errprint("  Rank %d, goodness %g:", rank, vall)
        errprint(cell.struct().toString) // indent=4
      } else
        errprint("  Rank %d, goodness %g: %s", rank, vall, cell.shortstr())
    }

    new TitledDocumentResult()
  }
}

abstract class EvaluationOutputter {
  def evaluate_and_output_results(files: Iterable[String]): Unit
}

class DefaultEvaluationOutputter(stratname: String, evalobj: TestFileEvaluator
    ) extends EvaluationOutputter {
  val results = mutable.Map[EvaluationDocument, EvaluationResult]()
  /**
    Evaluate on all of the given files, outputting periodic results and
    results after all files are done.  If the evaluator uses articles as
    documents (so that it doesn't need any external test files), the value
    of 'files' should be a sequence of one item, which is null. (If an
    empty sequence is passed in, no evaluation will happen.)

    Also returns an object containing the results.
   */
  def evaluate_and_output_results(files: Iterable[String]) {
    val task = new MeteredTask("document", "evaluating")
    var last_elapsed = 0.0
    var last_processed = 0
    var skip_initial = Opts.skip_initial_test_docs
    var skip_n = 0

    class EvaluationFileProcessor extends FileProcessor {
      override def begin_process_directory(dir: File) {
        errprint("Processing evaluation directory %s...", dir)
      }

      /* Process all documents in a given file.  If return value is false,
         processing was interrupted due to a limit being reached, and
         no more files should be processed. */
      def process_file(filename: String): Boolean = {
        if (filename != null)
          errprint("Processing evaluation file %s...", filename)
        for (doc <- evalobj.iter_documents(filename)) {
          // errprint("Processing document: %s", doc)
          val num_processed = task.num_processed
          val doctag = "#%d" format (1 + num_processed)
          if (evalobj.would_skip_document(doc, doctag))
            errprint("Skipped document %s", doc)
          else {
            var do_skip = false
            if (skip_initial != 0) {
              skip_initial -= 1
              do_skip = true
            } else if (skip_n != 0) {
              skip_n -= 1
              do_skip = true
            } else
              skip_n = Opts.every_nth_test_doc - 1
            if (do_skip)
              errprint("Passed over document %s", doctag)
            else {
              // Don't put side-effecting code inside of an assert!
              val result = evalobj.evaluate_document(doc, doctag)
              assert(result != null)
              results(doc) = result
            }
            task.item_processed()
            val new_elapsed = task.elapsed_time
            val new_processed = task.num_processed

            // If max # of docs reached, stop
            if ((Opts.num_test_docs > 0 &&
              new_processed >= Opts.num_test_docs)) {
              errprint("")
              errprint("Stopping because limit of %s documents reached",
                Opts.num_test_docs)
              task.finish()
              return false
            }

            // If five minutes and ten documents have gone by, print out results
            if ((new_elapsed - last_elapsed >= 300 &&
              new_processed - last_processed >= 10)) {
              errprint("Results after %d documents (strategy %s):",
                task.num_processed, stratname)
              evalobj.output_results(isfinal = false)
              errprint("End of results after %d documents (strategy %s):",
                task.num_processed, stratname)
              last_elapsed = new_elapsed
              last_processed = new_processed
            }
          }
        }

        return true
      }
    }

    new EvaluationFileProcessor().process_files(files)

    task.finish()

    errprint("")
    errprint("Final results for strategy %s: All %d documents processed:",
      stratname, task.num_processed)
    errprint("Ending operation at %s", curtimehuman())
    evalobj.output_results(isfinal = true)
    errprint("Ending final results for strategy %s", stratname)
  }
}

