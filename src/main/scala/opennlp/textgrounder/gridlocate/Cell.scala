///////////////////////////////////////////////////////////////////////////////
//  Cell.scala
//
//  Copyright (C) 2010, 2011, 2012 Ben Wing, The University of Texas at Austin
//  Copyright (C) 2011, 2012 Stephen Roller, The University of Texas at Austin
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

import collection.mutable

import util.print.{errprint, warning}
import util.experiment._
import util.textdb.Encoder

import worddist.WordDistFactory

/////////////////////////////////////////////////////////////////////////////
//                             Word distributions                          //
/////////////////////////////////////////////////////////////////////////////

/**
 * Distribution over words resulting from combining the individual
 * distributions of a number of documents.  We track the number of
 * documents making up the distribution, as well as the total incoming link
 * count for all of these documents.  Note that some documents contribute
 * to the link count but not the word distribution; hence, there are two
 * concepts of "empty", depending on whether all contributing documents or
 * only those that contributed to the word distribution are counted.
 * (The primary reason for documents not contributing to the distribution
 * is that they're not in the training set; see comments below.  However,
 * some documents simply don't have distributions defined for them in the
 * document file -- e.g. if there was a problem extracting the document's
 * words in the preprocessing stage.)
 *
 * Note that we embed the actual object describing the word distribution
 * as a field in this object, rather than extending (subclassing) WordDist.
 * The reason for this is that there are multiple types of WordDists, and
 * so subclassing would require creating a different subclass for every
 * such type, along with extra boilerplate functions to create objects of
 * these subclasses.
 */
class CombinedWordDist(factory: WordDistFactory) {
  /** The combined word distribution itself. */
  val word_dist = factory.create_word_dist
  /** Number of documents used to create distribution. */
  var num_docs = 0
  /** Total number of incoming links. */
  var incoming_links = 0

  /** 
   * True if the object is empty.  This means no documents have been
   * added using `add_document`. */
  def is_empty = num_docs == 0

  /**
   *  Add the given document to the total distribution seen so far.
   *  `partial` is a scaling factor (between 0.0 and 1.0) used for
   *  interpolating multiple distributions.
   */
  def add_document(doc: GeoDoc[_], partial: Double = 1.0) {
    /* Formerly, we arranged things so that we were passed in all documents,
       regardless of the split.  The reason for this was that the decision
       was made to accumulate link counts from all documents, even in the
       evaluation set.
       
       Strictly, this is a violation of the "don't train on your evaluation
       set" rule.  The reason motivating this was that

       (1) The links are used only in Naive Bayes, and only in establishing
       a prior probability.  Hence they aren't the main indicator.
       (2) Often, nearly all the link count for a given cell comes from
       a particular document -- e.g. the Wikipedia article for the primary
       city in the cell.  If we pull the link count for this document
       out of the cell because it happens to be in the evaluation set,
       we will totally distort the link count for this cell.  In a "real"
       usage case, we would be testing against an unknown document, not
       against a document in our training set that we've artificially
       removed so as to construct an evaluation set, and this problem
       wouldn't arise, so by doing this we are doing a more realistic
       evaluation.
       
       Note that we do NOT include word counts from dev-set or test-set
       documents in the word distribution for a cell.  This keeps to the
       above rule about only training on your training set, and is OK
       because (1) each document in a cell contributes a similar amount of
       word counts (assuming the documents are somewhat similar in size),
       hence in a cell with multiple documents, each individual document
       only computes a fairly small fraction of the total word counts;
       (2) distributions are normalized in any case, so the exact number
       of documents in a cell does not affect the distribution.
       
       However, once the corpora were separated into sub-corpora based on
       the training/dev/test split, passing in all documents complicated
       things, as it meant having to read all the sub-corpora.  Furthermore,
       passing in non-training documents into the K-d cell grid changes the
       grids in ways that are not easily predictable -- a significantly
       greater effect than simply changing the link counts.  So (for the
       moment at least) we don't do this any more. */
    assert (doc.split == "training")

    /* Add link count of document to cell. */
    doc.incoming_links match {
      // Might be None, for unknown link count
      case Some(x) => incoming_links += x
      case _ =>
    }

    word_dist.add_word_distribution(doc.dist, partial)
    num_docs += 1
  }
}

/////////////////////////////////////////////////////////////////////////////
//                             Cells in a grid                             //
/////////////////////////////////////////////////////////////////////////////

/**
 * Abstract class for a general cell in a cell grid.
 * 
 * @param grid The Grid object for the grid this cell is in.
 * @tparam Co The type of the coordinate object used to specify a
 *   a point somewhere in the grid.
 */
abstract class GeoCell[Co](
    val grid: GeoGrid[Co]
) {
  val combined_dist =
    new CombinedWordDist(grid.docfact.word_dist_factory)
  var most_popular_document: GeoDoc[Co] = _
  var mostpopdoc_links = 0

  /**
   * Return a string describing the location of the cell in its grid,
   * e.g. by its boundaries or similar.
   */
  def describe_location: String

  /**
   * Return a string describing the indices of the cell in its grid.
   * Only used for debugging.
   */
  def describe_indices: String

  /**
   * Return the coordinate of the true center of the cell.  This is sometimes
   * used in computing certain measures.  If this cannot clearly be defined,
   * then a more or less arbitrarily-placed location can be used as long as
   * it's somewhere central.
   */
  def get_true_center: Co

  /**
   * Return the coordinate of the centroid of the cell. If the cell has no
   * documents in it, return the true center.
   */
  def get_centroid: Co

  /**
   * Return the coordinate of the central point of the cell.  This is the
   * coordinate used in computing distances between arbitrary points and
   * given cells, for evaluation and such.  This may be the true center,
   * or some other measure of central tendency (e.g. the centroid).
   */
  def get_central_point = {
    if (grid.docfact.driver.params.center_method == "center")
      get_true_center
    else
      get_centroid
  }

  /**
   * Return true if we have finished creating and populating the cell.
   */
  def finished = combined_dist.word_dist.finished
  /**
   * Return a string representation of the cell.  Generally does not need
   * to be overridden.
   */
  override def toString = {
    val unfinished = if (finished) "" else ", unfinished"
    val contains =
      if (most_popular_document != null)
        ", most-pop-doc %s(%d links)" format (
          most_popular_document, mostpopdoc_links)
      else ""

    "GeoCell(%s%s%s, %d documents, %s types, %s tokens, %d links)" format (
      describe_location, unfinished, contains,
      combined_dist.num_docs,
      combined_dist.word_dist.model.num_types,
      combined_dist.word_dist.model.num_tokens,
      combined_dist.incoming_links)
  }

  // def __repr__() = {
  //   toString.encode("utf-8")
  // }

  def to_row = Seq(
    "location" -> describe_location,
    "true-center" -> get_true_center,
    "centroid" -> get_centroid,
    "central-point" -> get_central_point,
    "num-documents" -> combined_dist.num_docs,
    "num-word-types" -> combined_dist.word_dist.model.num_types,
    "num-word-tokens" -> combined_dist.word_dist.model.num_tokens,
    "incoming-links" -> combined_dist.incoming_links,
    "most-popular-document" -> (
      if (most_popular_document != null)
        Encoder.string(most_popular_document.title)
      else ""),
    "most-popular-document-incoming-links" -> mostpopdoc_links
  )

  /**
   * Return a shorter string representation of the cell, for
   * logging purposes.
   */
  def shortstr = {
    var str = "Cell %s" format describe_location
    val mostpop = most_popular_document
    if (mostpop != null)
      str += ", most-popular %s" format mostpop.shortstr
    str
  }

  /**
   * Return an XML representation of the cell.  Currently used only for
   * debugging-output purposes, so the exact representation isn't too important.
   */
  def xmldesc =
    <GeoCell>
      <bounds>{ describe_location }</bounds>
      <finished>{ finished }</finished>
      {
        if (most_popular_document != null)
          (<mostPopularDocument>most_popular_document.xmldesc</mostPopularDocument>
           <mostPopularDocumentLinks>mostpopdoc_links</mostPopularDocumentLinks>)
      }
      <numDocuments>{ combined_dist.num_docs }</numDocuments>
      <incomingLinks>{ combined_dist.incoming_links }</incomingLinks>
    </GeoCell>

  /**
   * Add a document to the distribution for the cell.
   */
  def add_document(doc: GeoDoc[Co]) {
    assert(!finished)
    combined_dist.add_document(doc)
    if (doc.incoming_links != None &&
      doc.incoming_links.get > mostpopdoc_links) {
      mostpopdoc_links = doc.incoming_links.get
      most_popular_document = doc
    }
  }

  /**
   * Finish any computations related to the cell's word distribution.
   */
  def finish() {
    assert(!finished)
    combined_dist.word_dist.finish_before_global()
    combined_dist.word_dist.finish_after_global()
  }
}

/**
 * Abstract class for a general grid of cells.  The grid is defined over
 * a continuous space (e.g. the surface of the Earth).  The space is indexed
 * by coordinates (of type Co).  Each cell (of type GeoCell[Co]) covers
 * some portion of the space.  There is also a set of documents (of type
 * TDoc), each of which is indexed by a coordinate and which has a
 * distribution describing the contents of the document.  The distributions
 * of all the documents in a cell (i.e. whose coordinate is within the cell)
 * are amalgamated to form the distribution of the cell.
 *
 * One example is the SphereGrid -- a grid of cells covering the Earth.
 * ("Sphere" is used here in its mathematical meaning of the surface of a
 * round ball.) Coordinates, of type SphereCoord, are pairs of latitude and
 * longitude.  Documents are of type SphereDoc and have a SphereCoord
 * as their coordinate.  Cells are of type SphereCell.  Subclasses of
 * SphereGrid refer to particular grid cell shapes.  For example, the
 * MultiRegularGrid consists of a regular tiling of the surface of the
 * Earth into "rectangles" defined by minimum and maximum latitudes and
 * longitudes.  Most commonly, each tile is a cell, but it is possible for
 * a cell to consist of an NxN square of tiles, in which case the cells
 * overlap.  Another subclass is KDTreeGrid, with rectangular cells of
 * variable size so that the number of documents in a given cell stays more
 * or less constant.
 *
 * Another possibility would be a grid indexed by years, where each cell
 * corresponds to a particular range of years.
 *
 * In general, no assumptions are made about the shapes of cells in the grid,
 * the number of dimensions in the grid, or whether the cells are overlapping.
 *
 * The following operations are used to populate a cell grid:
 *
 * (1) Documents are added one-by-one to a grid by calling
 *     `add_document_to_cell`.
 * (2) After all documents have been added, `initialize_cells` is called
 *     to generate the cells and create their distribution.
 * (3) After this, it should be possible to list the cells by calling
 *     `iter_nonempty_cells`.
 */
abstract class GeoGrid[Co](
    val docfact: GeoDocFactory[Co]
) {
  def driver = docfact.driver

  /**
   * Total number of cells in the grid.
   */
  var total_num_cells: Int

  /**
   * Find the correct cell for the given document, based on the document's
   * coordinates and other properties.  If no such cell exists, return None
   * if `create` is false.  Else, create an empty cell to hold the
   * coordinates -- but do *NOT* record the cell or otherwise alter the
   * existing cell configuration.  This situation where such a cell is needed
   * is during evaluation.  The cell is needed purely for comparing it against
   * existing cells and determining its center.  The reason for not recording
   * such cells is to make sure that future evaluation results aren't affected.
   */
  def find_best_cell_for_document(doc: GeoDoc[Co], create_non_recorded: Boolean):
    Option[GeoCell[Co]]

  /**
   * Add the given training documents to the cell grid.
   *
   * @param get_rawdocs Function to read raw documents, given a string
   *   indicating the nature of the operation (displayed in status updates
   *   during reading).
   */
  def add_training_documents_to_grid(
      get_rawdocs: String => Iterator[DocStatus[RawDocument]])

  /**
   * Generate all non-empty cells.  This will be called once (and only once),
   * after all documents have been added to the cell grid by calling
   * `add_document_to_cell`.  The generation happens internally; but after
   * this, `iter_nonempty_cells` should work properly.  This is not meant
   * to be called externally.
   */
  protected def initialize_cells()

  /**
   * Iterate over all non-empty cells.
   */
  def iter_nonempty_cells: Iterable[GeoCell[Co]]
  
  /*********************** Not meant to be overridden *********************/

  /* Sum of `num_docs` for each cell. */
  var total_num_docs = 0
  /* Set once finish() is called. */
  var all_cells_computed = false
  /* Number of non-empty cells. */
  var num_non_empty_cells = 0
  
  /**
   * Iterate over all non-empty cells, making sure to include the given cells
   *  even if empty.
   */
  def iter_nonempty_cells_including(include: Iterable[GeoCell[Co]]) = {
    val cells = iter_nonempty_cells
    if (include.size == 0)
      cells
    else {
      val not_included = include.filter(cell => cells.find(_ == cell) == None)
      cells ++ not_included
    }
  }

  /**
   * Standard implementation of `add_training_documents_to_grid`.
   *
   * @param add_document_to_grid Function to add a document to the grid.
   */
  protected def default_add_training_documents_to_grid(
    get_rawdocs: String => Iterator[DocStatus[RawDocument]],
    add_document_to_grid: GeoDoc[Co] => Unit
  ) {
    // FIXME: The "finish_globally" flag needs to be tied into the
    // recording of global statistics in the word dists.
    // In reality, all the glop handled by finish_before_global() and
    // note_dist_globally() (as well as record_in_subfactory) and such
    // should be handled by separate mapping stages onto the documents.
    for (doc <- docfact.raw_documents_to_documents(get_rawdocs("reading"),
           record_in_subfactory = true,
           note_globally = true,
           finish_globally = false)) {
      add_document_to_grid(doc)
    }
    // Compute overall distribution values (e.g. back-off statistics).
    errprint("Finishing global dist...")
    docfact.word_dist_factory.finish_global_distribution()
    docfact.finish_document_loading()
  }

  /**
   * This function is called externally to initialize the cells.  It is a
   * wrapper around `initialize_cells()`, which is not meant to be called
   * externally.  Normally this does not need to be overridden.
   */
  def finish() {
    assert(!all_cells_computed)

    initialize_cells()

    all_cells_computed = true

    total_num_docs = 0

    driver.show_progress("computing statistics of", "non-empty cell").
    foreach(iter_nonempty_cells) { cell =>
      total_num_docs +=
        cell.combined_dist.num_docs
    }

    driver.note_print_result("number-of-non-empty-cells", num_non_empty_cells,
      "Number of non-empty cells")
    driver.note_print_result("total-number-of-cells", total_num_cells,
      "Total number of cells")
    driver.note_print_result("percent-non-empty-cells",
      "%g" format (num_non_empty_cells.toDouble / total_num_cells),
      "Percent non-empty cells"
    )
    val recorded_training_docs_with_coordinates =
      docfact.num_recorded_documents_with_coordinates_by_split("training").value
    driver.note_print_result("training-documents-per-non-empty-cell",
      "%g" format (recorded_training_docs_with_coordinates.toDouble /
        num_non_empty_cells),
      "Training documents per non-empty cell")
    driver.heartbeat
  }
}
