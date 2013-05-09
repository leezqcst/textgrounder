///////////////////////////////////////////////////////////////////////////////
//  Inference.scala
//
//  Copyright (C) 2010-2013 Ben Wing, The University of Texas at Austin
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
package gridlocate

import scala.util.Random
import math._

import util.print.errprint
import util.debug._

import worddist._

/*

This file implements the various strategies used for inference of the
location of a document in a grid -- more specifically, returning a
ranking of the suitability of the cells of the grid for a given
document.

*/

object UnigramStrategy {
  def check_unigram_dist(word_dist: WordDist) = {
    word_dist match {
      case x: UnigramWordDist => x
      case _ => throw new IllegalArgumentException("You must use a unigram word distribution with this strategy")
    }
  }
}

/**
 * Abstract class for grid-locating documents, i.e. computing a ranking of
 * the cells in a grid.
 *
 * @param stratname Name of the strategy, for output purposes
 * @param grid Grid containing the cells
 */
abstract class GridLocateDocStrategy[Co](
  val stratname: String,
  val grid: GeoGrid[Co]
) {
  /**
   * For a given word distribution (describing a test document), return
   * an Iterable of tuples, each listing a particular cell on the Earth
   * and a score of some sort.  The cells given in `include` must be
   * included in the list.  Higher scores are better.  The results should
   * be in sorted order, with better cells earlier.
   */
  def return_ranked_cells(word_dist: WordDist,
      include: Iterable[GeoCell[Co]]):
    Iterable[(GeoCell[Co], Double)]
}

/**
 * Class that implements a very simple baseline strategy -- pick a random
 * cell.
 */

class RandomGridLocateDocStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co]
) extends GridLocateDocStrategy[Co](stratname, grid) {
  def return_ranked_cells(word_dist: WordDist,
      include: Iterable[GeoCell[Co]]) = {
    val cells = grid.iter_nonempty_cells_including(include)
    val shuffled = (new Random()).shuffle(cells)
    (for (cell <- shuffled) yield (cell, 0.0))
  }
}

/**
 * Class that implements a simple baseline strategy -- pick the "most
 * popular" cell (the one either with the largest number of documents, or
 * the most number of links pointing to it, if `internal_link` is true).
 */

class MostPopularGridLocateDocStrategy[Co] (
  stratname: String,
  grid: GeoGrid[Co],
  internal_link: Boolean
) extends GridLocateDocStrategy[Co](stratname, grid) {
  def return_ranked_cells(word_dist: WordDist, include: Iterable[GeoCell[Co]]) = {
    (for (cell <-
        grid.iter_nonempty_cells_including(include))
      yield (cell,
        (if (internal_link)
           cell.combined_dist.incoming_links
         else
           cell.combined_dist.num_docs).toDouble)).
    toIndexedSeq sortWith (_._2 > _._2)
  }
}

/**
 * Abstract class that implements a strategy for grid location that
 * involves directly comparing the document distribution against each cell
 * in turn and computing a score.
 */
abstract class PointwiseScoreStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co]
) extends GridLocateDocStrategy[Co](stratname, grid) {
  /**
   * Function to return the score of a document distribution against a
   * cell.
   */
  def score_cell(word_dist: WordDist, cell: GeoCell[Co]): Double

  /**
   * Compare a word distribution (for a document, typically) against all
   * cells. Return a sequence of tuples (cell, score) where 'cell'
   * indicates the cell and 'score' the score.
   */
  def return_ranked_cells_serially(word_dist: WordDist,
    include: Iterable[GeoCell[Co]]) = {
      for (cell <- grid.iter_nonempty_cells_including(include)) yield {
        if (debug("lots")) {
          errprint("Nonempty cell at indices %s = location %s, num_documents = %s",
            cell.describe_indices, cell.describe_location,
            cell.combined_dist.num_docs)
        }
        (cell, score_cell(word_dist, cell))
      }
  }

  /**
   * Compare a word distribution (for a document, typically) against all
   * cells. Return a sequence of tuples (cell, score) where 'cell'
   * indicates the cell and 'score' the score.
   */
  def return_ranked_cells_parallel(word_dist: WordDist,
    include: Iterable[GeoCell[Co]]) = {
    val cells = grid.iter_nonempty_cells_including(include)
    cells.par.map(c => (c, score_cell(word_dist, c)))
  }

  def return_ranked_cells(word_dist: WordDist, include: Iterable[GeoCell[Co]]) = {
    val parallel = !grid.driver.params.no_parallel
    val cell_buf = {
      if (parallel)
        return_ranked_cells_parallel(word_dist, include)
      else
        return_ranked_cells_serially(word_dist, include)
    }

    val retval = cell_buf.toIndexedSeq sortWith (_._2 > _._2)

    /* If doing things parallel, this code applies for debugging
       (serial has the debugging code embedded into it). */
    if (parallel && debug("lots")) {
      for ((cell, score) <- retval)
        errprint("Nonempty cell at indices %s = location %s, num_documents = %s, score = %s",
          cell.describe_indices, cell.describe_location,
          cell.combined_dist.num_docs, score)
    }
    retval
  }
}

/**
 * Class that implements a strategy for document geolocation by computing
 * the KL-divergence between document and cell (approximately, how much
 * the word distributions differ).  Note that the KL-divergence as currently
 * implemented uses the smoothed word distributions.
 *
 * @param partial If true (the default), only do "partial" KL-divergence.
 * This only computes the divergence involving words in the document
 * distribution, rather than considering all words in the vocabulary.
 * @param symmetric If true, do a symmetric KL-divergence by computing
 * the divergence in both directions and averaging the two values.
 * (Not by default; the comparison is fundamentally asymmetric in
 * any case since it's comparing documents against cells.)
 */
class KLDivergenceStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co],
  partial: Boolean = true,
  symmetric: Boolean = false
) extends PointwiseScoreStrategy[Co](stratname, grid) {

  var self_kl_cache: KLDivergenceCache = null
  val slow = false

  def call_kl_divergence(self: WordDist, other: WordDist) =
    self.kl_divergence(self_kl_cache, other, partial = partial)

  def score_cell(word_dist: WordDist, cell: GeoCell[Co]) = {
    val cell_word_dist = cell.combined_dist.word_dist
    var kldiv = call_kl_divergence(word_dist, cell_word_dist)
    if (symmetric) {
      val kldiv2 = cell_word_dist.kl_divergence(null, word_dist,
        partial = partial)
      kldiv = (kldiv + kldiv2) / 2.0
    }
    // Negate so that higher scores are better
    -kldiv
  }

  override def return_ranked_cells(word_dist: WordDist,
      include: Iterable[GeoCell[Co]]) = {
    // This will be used by `score_cell` above.
    self_kl_cache = word_dist.get_kl_divergence_cache()

    val cells = super.return_ranked_cells(word_dist, include)

    if (debug("kldiv") && word_dist.isInstanceOf[FastSlowKLDivergence]) {
      val fast_slow_dist = word_dist.asInstanceOf[FastSlowKLDivergence]
      // Print out the words that contribute most to the KL divergence, for
      // the top-ranked cells
      val num_contrib_cells = 5
      val num_contrib_words = 25
      errprint("")
      errprint("KL-divergence debugging info:")
      for (((cell, _), i) <- cells.take(num_contrib_cells) zipWithIndex) {
        val (_, contribs) =
          fast_slow_dist.slow_kl_divergence_debug(
            cell.combined_dist.word_dist, partial = partial,
            return_contributing_words = true)
        errprint("  At rank #%s, cell %s:", i + 1, cell)
        errprint("    %30s  %s", "Word", "KL-div contribution")
        errprint("    %s", "-" * 50)
        // sort by absolute value of second element of tuple, in reverse order
        val items =
          (contribs.toIndexedSeq sortWith ((x, y) => abs(x._2) > abs(y._2))).
            take(num_contrib_words)
        for ((word, contribval) <- items)
          errprint("    %30s  %s", word, contribval)
        errprint("")
      }
    }

    cells
  }
}

/**
 * Class that implements a strategy for document geolocation by computing
 * the cosine similarity between the distributions of document and cell.
 * FIXME: We really should transform the distributions by TF/IDF before
 * doing this.
 *
 * @param smoothed If true, use the smoothed word distributions. (By default,
 * use unsmoothed distributions.)
 * @param partial If true, only do "partial" cosine similarity.
 * This only computes the similarity involving words in the document
 * distribution, rather than considering all words in the vocabulary.
 */
class CosineSimilarityStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co],
  smoothed: Boolean = false,
  partial: Boolean = false
) extends PointwiseScoreStrategy[Co](stratname, grid) {

  def score_cell(word_dist: WordDist, cell: GeoCell[Co]) = {
    val cossim =
      word_dist.cosine_similarity(cell.combined_dist.word_dist,
        partial = partial, smoothed = smoothed)
    assert(cossim >= 0.0)
    // Just in case of round-off problems
    assert(cossim <= 1.002)
    cossim
  }
}

/** Use a Naive Bayes strategy for comparing document and cell. */
class NaiveBayesDocStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co],
  use_baseline: Boolean = true
) extends PointwiseScoreStrategy[Co](stratname, grid) {

  def score_cell(word_dist: WordDist, cell: GeoCell[Co]) = {
    val params = grid.driver.params
    // Determine respective weightings
    val (word_weight, baseline_weight) = (
      if (use_baseline) {
        if (params.naive_bayes_weighting == "equal") (1.0, 1.0)
        else {
          val bw = params.naive_bayes_baseline_weight.toDouble
          ((1.0 - bw) / word_dist.model.num_tokens, bw)
        }
      } else (1.0, 0.0))

    val word_logprob =
      cell.combined_dist.word_dist.get_nbayes_logprob(word_dist)
    val baseline_logprob =
      log(cell.combined_dist.num_docs.toDouble /
          grid.total_num_docs)
    val logprob = (word_weight * word_logprob +
      baseline_weight * baseline_logprob)
    logprob
  }
}

class AverageCellProbabilityStrategy[Co](
  stratname: String,
  grid: GeoGrid[Co]
) extends GridLocateDocStrategy[Co](stratname, grid) {
  def create_cell_dist_factory(lru_cache_size: Int) =
    new CellDistFactory[Co](lru_cache_size)

  val cdist_factory =
    create_cell_dist_factory(grid.driver.params.lru_cache_size)

  def return_ranked_cells(word_dist: WordDist, include: Iterable[GeoCell[Co]]) = {
    val celldist =
      cdist_factory.get_cell_dist_for_word_dist(grid, word_dist)
    celldist.get_ranked_cells(include)
  }
}

/////////////////////////////////////////////////////////////////////////////
//                                Segmentation                             //
/////////////////////////////////////////////////////////////////////////////

// General idea: Keep track of best possible segmentations up to a maximum
// number of segments.  Either do it using a maximum number of segmentations
// (e.g. 100 or 1000) or all within a given factor of the best score (the
// "beam width", e.g. 10^-4).  Then given the existing best segmentations,
// we search for new segmentations with more segments by looking at all
// possible ways of segmenting each of the existing best segments, and
// finding the best score for each of these.  This is a slow process -- for
// each segmentation, we have to iterate over all segments, and for each
// segment we have to look at all possible ways of splitting it, and for
// each split we have to look at all assignments of cells to the two
// new segments.  It also seems that we're likely to consider the same
// segmentation multiple times.
//
// In the case of per-word cell dists, we can maybe speed things up by
// computing the non-normalized distributions over each paragraph and then
// summing them up as necessary.