//  AnalyzeLocationVariance.scala
//
//  Copyright (C) 2013-2014 Ben Wing, The University of Texas at Austin
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

import org.ddahl.jvmr.RInScala

import util.argparser._
import util.collection._
import util.error._
import util.experiment._
import util.io
import util.json._
import util.math._
import util.print._
import util.spherical._
import util.numeric.min_format_double
import util.textdb._
import util.time._

import util.debug._


class AnalyzeLocationVarianceParameters(ap: ArgParser) {
  var location_histogram = ap.option[String]("location-histogram",
    metavar = "FILE",
    help = """Output a histogram of location-variance information
to the specified file, which should end in .pdf.""")

  var results_histogram = ap.option[String]("results-histogram",
    metavar = "PREFIX",
    help = """Output a set of histograms correlating location-variance
information and geolocation results to a set of files beginning with the
specified prefix. Each file will be named by adding a descriptive suffix
followed by '.pdf'. The geolocation results should be specified using
'--results-file'.""")

  var results_plot = ap.option[String]("results-plot",
    metavar = "FILE",
    help = """Output a table of plots correlating location-variance
information and geolocation results to the specified file, which should
end in .pdf. The geolocation results should be specified using
'--results-file'.""")

  var points_type = ap.option[String]("points-type", "pt",
    default = "b",
    help = """Whether to connect the points, etc. Most useful values are
"p" (points), "l" (lines), "b" (both), "c" (only the lines part of "b", i.e.
broken lines), "n" (no plotting).""")

  var results_file = ap.option[String]("results-file",
    metavar = "FILE",
    help = """Results file to analyze, a textdb database. The value can be
any of the following: Either the data or schema file of the database;
the common prefix of the two; or the directory containing them, provided
there is only one textdb in the directory.""")

  var input = ap.positional[String]("input",
    must = be_specified,
    help = """Corpus to analyze, a textdb database.  This needs to have
a 'positions' field, as generated by ParseTweets with 'positions'
specified as one of the fields in '--output-fields'.

The value of the parameter can be any of the following: Either the data
or schema file of the database; the common prefix of the two; or the
directory containing them, provided there is only one textdb in the
directory.""")
}

object LocationVarianceParams {
  val bin_histogram_base = 5.0
  val num_histogram_bins = 6
  // These values don't have any connection with the tabular width/height
  // in LocationStats other than the fact that they both specify
  // width/height of a table of plots.
  val bin_histogram_width = 3
  val bin_histogram_height = 2
  assert_>=(bin_histogram_width * bin_histogram_height, num_histogram_bins)

  val bin_plot_base = 2.0
  val num_plot_bins = 1000000 // Doesn't really matter

  // In place of strict max, take the quantile at this position. This discards some
  // of the very highest out-of-range numbers.
  val high_quantile_for_limit = 0.98
  // How much to scale the real limit in km down in order to limit the scaled
  // statistics to the lower part of the page.
  val limit_downscale = 2.0
  // Discard bins with fewer than this # of items.
  val min_items_per_bin = 5
}

/**
 * An application to analyze the different positions seen in a given user's
 * tweets. This assumes that ParseTweets has been run with the argument
 * --output-fields 'default positions' so that a field is included that
 * contains all the locations in a user's tweets.
 */
object AnalyzeLocationVariance extends ExperimentApp("AnalyzeLocationVariance") {

  type TParam = AnalyzeLocationVarianceParameters
  type Timestamp = Long

  def create_param_object(ap: ArgParser) = new AnalyzeLocationVarianceParameters(ap)

  def cartesian_product[T1, T2](A: Iterable[T1], B: Iterable[T2]
      ): Iterable[(T1, T2)] = {
    for (a <- A; b <- B) yield (a, b)
  }

  def format_fraction(frac: (Double, Double)) =
    "%.2f%%,%.2f%%" format (frac._1 * 100, frac._2 * 100)

  def fraction_from_bounding_box(sw: SphereCoord, ne: SphereCoord,
      point: SphereCoord) = {
    ((point.lat - sw.lat) / (ne.lat - sw.lat),
     (point.long - sw.long) / (ne.long - sw.long))
  }

  case class Position(
    time: Timestamp,
    coord: SphereCoord
  )

  case class User(
    user: String,
    coord: SphereCoord,
    positions: Iterable[Position]
  )

  /**
   * Case class holding location-variance statistics for a given user.
   */
  case class LocationStats(
    user: User,
    bounding_box_sw: SphereCoord,
    bounding_box_ne: SphereCoord,
    dist_across_bounding_box: Double,
    centroid: SphereCoord,
    avgdist_from_centroid: Double,
    mindist_from_centroid: Double,
    quantile25_dist_from_centroid: Double,
    median_dist_from_centroid: Double,
    quantile75_dist_from_centroid: Double,
    maxdist_from_centroid: Double,
    dist_from_centroid_variance: Double,
    earliest: Position,
    avgdist_from_earliest: Double,
    latest: Position,
    maxdist_between_points: Double
  ) {
    def get_fraction(coord: SphereCoord) =
      fraction_from_bounding_box(bounding_box_sw, bounding_box_ne, coord)

    def get_format_fraction(coord: SphereCoord) =
      format_fraction(get_fraction(coord))

    def output_position(pos: Position, tag: String) {
      errprint("  %s: %s at fraction %s, %.2f km from centroid",
        tag, pos.coord.format, get_format_fraction(pos.coord),
        spheredist(pos.coord, centroid))
      errprint("  %s time: %s", tag, format_time(pos.time * 1000))
    }

    def pretty_print() {
      errprint("User: %s, %s positions", user.user, user.positions.size)
      errprint("  bounding box: %s to %s, dist across: %.2f km",
        bounding_box_sw.format, bounding_box_ne.format,
        dist_across_bounding_box)
      errprint("  centroid: %s at fraction %s",
        centroid.format, get_format_fraction(centroid))
      output_position(earliest, "earliest")
      output_position(latest, "latest")
      errprint("  dist from centroid: avg %.2f km, median %.2f km, std dev %.2f km",
        avgdist_from_centroid, median_dist_from_centroid,
        math.sqrt(dist_from_centroid_variance))
      errprint("  quantiles dist from centroid in km:")
      errprint("    min %.2f, 25%% %.2f, median %.2f, 75%% %.2f, max %.2f",
        mindist_from_centroid, quantile25_dist_from_centroid,
        median_dist_from_centroid, quantile75_dist_from_centroid,
        maxdist_from_centroid)
      errprint("  max dist between points: %.2f km", maxdist_between_points)
    }
  }

  object LocationStats {
    // Set of functions selecting location-variance statistics to plot
    // or otherwise investigate.
    val run_fns = Seq[(String, LocationStats => Double)](
      "num positions" -> { _.user.positions.size },
      "dist across bounding box" -> { _.dist_across_bounding_box },
      "dist earliest from centroid" ->
        { x => spheredist(x.earliest.coord, x.centroid) },
      "max dist from centroid" -> { _.maxdist_from_centroid },
      "avg dist from centroid" -> { _.avgdist_from_centroid },
      "avg dist from earliest" -> { _.avgdist_from_earliest },
      "std dev distance from centroid" ->
        { x => math.sqrt(x.dist_from_centroid_variance) }
    )

    // Width and height of tabular plots corresponding to the above
    // location-variance statistics. There needs to be enough space for
    // all the statistics.
    val tabular_width = 3
    val tabular_height = 3

    assert_>=(tabular_width * tabular_height, run_fns.size)

    /** Create location stats object for a given user. */
    def apply(user: User): LocationStats = {
      val positions = user.positions.toIndexedSeq
      val points = positions.map(_.coord)
      val centroid = SphereCoord.centroid(points)
      val raw_distances = positions.map {
        case Position(ts, coord) => (ts, spheredist(coord, centroid))
      }
      val distances = raw_distances.map(_._2).sorted
      val ts_points_by_time = positions.sortBy(_.time)
      val earliest = ts_points_by_time.head
      val latest = ts_points_by_time.last
      val raw_distances_from_earliest = positions.map {
        case Position(ts, coord) => (ts, spheredist(coord, earliest.coord))
      }
      val distances_from_earliest =
        raw_distances_from_earliest.map(_._2).sorted
      val bounding_box_sw = SphereCoord.bounding_box_sw(points)
      val bounding_box_ne = SphereCoord.bounding_box_ne(points)

      LocationStats(
        user = user,
        bounding_box_sw = bounding_box_sw,
        bounding_box_ne = bounding_box_ne,
        dist_across_bounding_box =
          spheredist(bounding_box_sw, bounding_box_ne),
        centroid = centroid,
        avgdist_from_centroid = mean(distances),
        mindist_from_centroid = distances.head,
        quantile25_dist_from_centroid = quantile_at(distances, 0.25, sorted = true),
        median_dist_from_centroid = quantile_at(distances, 0.5, sorted = true),
        quantile75_dist_from_centroid = quantile_at(distances, 0.75, sorted = true),
        maxdist_from_centroid = distances.last,
        //maxdist_between_points = cartesian_product(points, points).map {
        //  case (a, b) => spheredist(a, b)
        //}.max,
        dist_from_centroid_variance = variance(distances),
        earliest = earliest,
        avgdist_from_earliest = mean(distances_from_earliest),
        latest = latest,
        // FIXME: This is O(N^2)! Seems to me that the maximum distance
        // between any two points should involve two points on the convex
        // hull, so we first should compute the convex hull and eliminate
        // points not on the hull.
        maxdist_between_points = 0.0 // FIXME: Don't calculate for now
      )
    }
  }

  /**
   * Output a set of histograms to a specified file, using R. Each
   * histogram is a separate plot, and the entire set of histograms will
   * be displayed in a grid of size `width` by `height`, with each plot
   * labeled with the specified label.
   *
   * @param file File to output to; will be in PDF format.
   * @param runs Data to plot. Should consist of a series of pairs of
   *   label and run, which is a set of numbers.
   * @param width Number of plots to display across.
   * @param height Number of plots to display down.
   */
  def compute_histogram(file: String,
      runs: Iterable[(String, Iterable[Double])],
      width: Int, height: Int) {
    assert_>=(width * height, runs.size)
    val R = RInScala()
    for (((label, run), index) <- runs.zipWithIndex) {
      R.update("data%s" format index, run.toArray)
      R.update("label%s" format index, label)
    }

    val plothist_code = """
plothist = function(vals, title) {
  #vals = read.table(file)
  #vals = vals[,]
  lvals = log(vals)
  h = hist(lvals, breaks=50, freq=FALSE, plot=FALSE)
  d = density(lvals)
  #ylimit = range(0, h$density, d$y)
  #ylimit[2] = min(ylimit[2], 0.5)
  # FIXME! We want the same ylimit for all histograms on a page.
  ylimit = c(0, 0.5)
  xlimit = range(h$breaks, d$x)
  xlimit[1] = 0
  xlabel = sprintf("%s (km)", title)
  # NOTE: Trying to specify the labels here and control the font size
  # using cex doesn't seem to work. You need to use cex.lab for the labels
  # and cex.axis for the axis. Likewise in axis(), cex doesn't work
  # and you have to use cex.axis.
  hist(lvals, breaks=50, freq=FALSE, xlim=xlimit, ylim=ylimit, xlab="",
       ylab="", main="", col="lightgrey", border="darkgrey", xaxt="n")
  myat = xlimit[1]:(xlimit[2]*2)/2
  lines(d$x, d$y)
  axis(1, at=myat, labels=formatC(exp(myat), format="f", digits=2,
                                  drop0trailing=TRUE))
  # cex works here but not the way you expect. cex=1 actually multiplies
  # the default font size by 1.5! You need to use 0.67 to get the expected
  # default font size.
  mtext(xlabel, side=1, line=2.5, cex=1.1)
  mtext("Density", side=2, line=2.5, cex=1.1)
}
"""
    val plothist_calls = (for (index <- 0 until runs.size) yield
      "plothist(data%s, label%s)" format (index, index)) mkString "\n"
    val rcode = plothist_code + s"""
pdf("$file", pointsize=8, width=8.5, height=14)

par(mfrow=c($height,$width))

${plothist_calls}

dev.off()
"""
    R.eval(rcode)
  }

  /**
   * Begin an image displaying some sort of graphics plot in R in a tabular
   * format of the specified width and height.
   *
   * @param file File to output to; will be in PDF format.
   * @param width Number of plots to display across.
   * @param height Number of plots to display down.
   * @return Object representing connection to R.
   */
  def begin_plot_R(file: String, width: Int, height: Int) = {
    val R = RInScala()
    val rcode = s"""
pdf("$file", width=8.5, height=14)

par(mfrow=c($height,$width))
"""
    R.eval(rcode)
    R
  }

  /**
   * End an image displaying some sort of graphics plot in R.
   */
  def end_plot_R(R: RInScala) {
    R.eval("dev.off()")
  }

  /**
   * Output a single line plot to R. This assumes that the device has
   * already been opened, and does not close the device afterwards, so
   * that multiple line plots can be included in a single page.
   *
   * This plots a series of lines (points connected by line segments),
   * and draws a smooth curve approximating the points. For each line,
   * there is a set of (x,y) points specified by 'xruns' and 'yruns',
   * a legend (i.e. label) that will be displayed in a "legend" box in
   * the top-left, a shape to use for the points in the line and a color
   * to use for the line and smooth curve. The x- and y-axis labels,
   * y-axis limit, and x-axis tick mark positions and labels can all be
   * specified.
   *
   * @param R Instance of R connector.
   * @param xruns Array of size `NLINES` of array of size `NPOINTS`,
   *   where `NLINES` is the number of lines to plot and `NPOINTS`
   *   is the number of points in each lines, describing the X coordinates.
   * @param yruns Array describing the Y coordinates, same size as `xruns`.
   * @param legends Array of size `NLINES` of legends for each line.
   * @param shapes Array of size `NLINES` of shape ID's for the points in
   *   a given line.
   * @param colors Array of size `NLINES` of colors for the line and
   *   approximating smooth curve.
   * @param pointstype Whether to connect the points, etc. Most useful values are
   *   "p" (points), "l" (lines), "b" (both), "c" (only the lines part of "b", i.e.
   *   broken lines), "n" (no plotting).
   * @param xlab Label for X axis.
   * @param ylab Label for Y axis.
   * @param ylim Limit of numbers plotted on Y axis. This is included so that
   *   multiple plots can have the same Y limit.
   * @param xticks Positions of tick marks across the X axis.
   * @param xticklabs Labelsof tick marks on the X axis, of the same size
   *   as `xticks`.
   */
  def lineplot_R(R: RInScala,
      xruns: Array[Array[Double]], yruns: Array[Array[Double]],
      legends: Array[String], shapes: Array[Int], colors: Array[String],
      pointstype: String, xlab: String, ylab: String, ylim: Double,
      xticks: Array[Double], xticklabs: Array[String]) {
    R.update("xruns", xruns)
    R.update("yruns", yruns)
    R.update("legends", legends)
    R.update("shapes", shapes)
    R.update("colors", colors)
    R.update("pointstype", pointstype)
    R.update("xlab", xlab)
    R.update("ylab", ylab)
    R.update("ylim", ylim)
    R.update("xticks", xticks)
    R.update("xticklabs", xticklabs)

    val plotlines_code = """
  nlines = length(legends)
  npoints = ncol(xruns)
  stopifnot(length(shapes) == nlines)
  stopifnot(length(colors) == nlines)
  stopifnot(nrow(xruns) == nlines)
  stopifnot(nrow(yruns) == nlines)
  stopifnot(ncol(yruns) == npoints)
  stopifnot(length(xticks) == length(xticklabs))
#  df = c(1:nlines)
#  spar = c(1:nlines)
  for (i in 1:nlines) {
    if (i == 1)
      plot(xruns[i,], yruns[i,], type=pointstype, pch=shapes[i], col=colors[i],
           xlab=xlab, ylab=ylab, xaxt="n", ylim=c(0,ylim))
    else
      points(xruns[i,], yruns[i,], type=pointstype, pch=shapes[i], col=colors[i])
    spline.points = smooth.spline(xruns[i,], yruns[i,], df=npoints/3)
#    spar[i] = spline.points$spar
#    df[i] = spline.points$df
    lines(spline.points, col=colors[i])
  }
  axis(1, at=xticks, labels=xticklabs)
  legend("topleft", legend=legends, pch=shapes, col=colors)
"""
    R.eval(plotlines_code)
//    errprint(s"""
//computed spar: ${R.toVector[Double]("spar").toSeq}
//computed df: ${R.toVector[Double]("df").toSeq}
//""")
  }

  def run_program(args: Array[String]) = {
    val filehand = io.localfh
    val users = TextDB.read_textdb(filehand, params.input) map { row =>
      val positions = Decoder.string_map_seq(row.gets("positions")).
        map {
          case (timestamp, coord) => Position(
            timestamp.toLong, SphereCoord.deserialize(coord))
        }
      User(row.gets("user"), SphereCoord.deserialize(row.gets("coord")),
        positions)
    }
    val user_stats = users.map { user =>
      val stat = LocationStats(user)
      // pretty_json(stat)
      stat.pretty_print
      (user.user, stat)
    }.toList

    if (params.location_histogram != null) {
      val stats = user_stats.map(_._2)
      val runs =
        for ((label, fn) <- LocationStats.run_fns) yield (label, stats.map(fn))
      compute_histogram(params.location_histogram, runs,
        LocationStats.tabular_width, LocationStats.tabular_height)
    }

    // Load up and correlate results file if requested.
    if (params.results_file != null) {
      val user_results =
        TextDB.read_textdb(filehand, params.results_file).map { row =>
          row.gets("title") -> row
        }.toList

      val merged_stats = user_stats.intersectWith(user_results) {
        (s, r) => (s, r) }.map(_._2)

      def bin_merged_stats(fn: LocationStats => Double, base: Double,
          max_bin: Int) = {
        merged_stats.groupBy {
          case (stat, result) =>
            val bin_by = fn(stat)
            if (bin_by < base) 0 else logn(bin_by, base).toInt min max_bin
        }.toSeq.sortBy(_._1).filter {
          // Filter bins with too few items; params not significant
          case (_, run) => run.size >= LocationVarianceParams.min_items_per_bin
        }
      }

      /**
       * For actual maximum limit and potential limit `potential_lim`,
       * find the multiplication factor to scale the values whose raw
       * limit is `potential_limit` to an amount that will fit within
       * the scale and take up the bottom half or so of the space.
       */
      def compute_mult_factor(limit: Double, potential_lim: Double) = {
        errprint(s"called with $limit, $potential_lim")
        val basic_possible_mult_factors =
          ((1.0 to 5.0 by 0.5) ++ (6.0 to 9.0 by 1.0)).toSeq
        val possible_mult_factors = basic_possible_mult_factors.map { x =>
          Seq(x, x*10, x*100, x*1000, x*10000, x*100000)
        }.flatten
        val possible_factors = possible_mult_factors.map { x =>
          (x, if (x == 1) "" else s" (x${min_format_double(x)})")
        } ++ possible_mult_factors.map { x =>
          (1.0/x, if (x == 1) "" else s" (/${min_format_double(x)})")
        }
        val raw_mult_factor =
          limit/LocationVarianceParams.limit_downscale/potential_lim
        val (closest_factor, disp_factor) = possible_factors.map {
          case (fact, display) =>
            ((1 - raw_mult_factor/fact).abs, (fact, display))
        }.minBy(_._1)._2
        errprint(s"($closest_factor, $disp_factor)")
        (closest_factor, disp_factor)
      }

      // Output plot of mean/median error distances vs. binned
      // location-variance stats.
      if (params.results_plot != null) {
        val base = LocationVarianceParams.bin_plot_base
        val max_bin = LocationVarianceParams.num_plot_bins - 1

        val R = begin_plot_R(params.results_plot,
          LocationStats.tabular_width, LocationStats.tabular_height)
        // `tables` is currently of the following type:
        //
        // Seq[(Array[Array[Double]], Array[Array[Double]], String, String,
        //      Array[Double], Array[String])]
        //
        // Note that there are four dimensions here:
        //
        // 1. The table to display (a plot), corresponding to the
        //    particular location-variance statistic being investigated.
        // 2. Various parameters of the plot. The first and second are
        //    `xruns` and `yruns`, respectively, i.e. X and Y coordinates.
        // 3. The line to draw, corresponding to the particular results
        //    statistic we're correlating against, e.g. mean or median error
        //    distance.
        // 4. The value itself. An (xrun, yrun) pair is a combination of
        //    (bin, binned_value).
        val tables =
          for ((label, fn) <- LocationStats.run_fns.toArray) yield {
            errprint(s"Plot label: $label")
            val binned_merged_stats = bin_merged_stats(fn, base, max_bin)
            val lines = binned_merged_stats.map {
              case (bin, results) => {
                errprint("Bin: %s, range: < %.2f, #items: %s", bin,
                  math.pow(base, bin), results.size)
                val errors = results.map {
                  case (stat, result) => result.get[Double]("error-dist")
                }.toIndexedSeq
                val ranks = results.map {
                  case (stat, result) => result.get[Double]("correct-rank")
                }.toIndexedSeq
                val num_pos = results.map {
                  case (stat, result) => stat.user.positions.size.toDouble
                }.toIndexedSeq
                val rmean = mean(errors)
                val rmedian = median(errors)
                val rmed_rank = median(ranks)
                val accuracy = ranks.count(_ == 1).toDouble/ranks.size*100
                val mean_num_pos = mean(num_pos)
                errprint("Mean: %.2f km, Median: %.2f km", rmean, rmedian)
                errprint("Median rank: %s, Accuracy: %.2f%%, Mean #pos: %.2f",
                  rmed_rank, accuracy, mean_num_pos)
                (bin, Array(results.size, rmed_rank, accuracy, mean_num_pos,
                  rmean, rmedian))
              }
            }.toArray
            val (bins, props) = lines.unzip
            // SCALABUG! unzip() doesn't return static type of Array when
            // applied to arrays.
            val yruns = props.toArray.transpose
            val xruns = Array.fill(yruns.size)(bins.map {_.toDouble}.toArray)
            val xlab = label
            val ylab = "Km or items"
            val xticks = (bins.min to bins.max).map {_.toDouble}.toArray
            val xticklabs = xticks map { bin =>
              val x = if (bin == 0) 0.0 else math.pow(base, bin)
              min_format_double(x)
            }
            (xruns, yruns, xlab, ylab, xticks, xticklabs)
          }
        // Here we assume that the first displayed line (i.e. the first
        // results statistic) is the number of items in the bin, and
        // adjust the items by a constant factor to make them line up with
        // the maximum of the other statistics (measured in km). We first
        // extract `yruns`, which gives us a 3-d array of
        // (table by line by bin), the transpose the first two dimensions
        // to give us (line by table by bin), then flatten to get
        // (line by table+bin), then take the max to get the maximum for
        // each line.
        val line_runs = tables.map(_._2).transpose.map(_.flatten)
        // val ylims = line_runs.map(_.max)
        val ylims = line_runs.map { x =>
          quantile_at(x, LocationVarianceParams.high_quantile_for_limit) }
        val num_adjust = 4
        val ylim = ylims.drop(num_adjust).max
        val (mults, disp_factors) = ylims.take(num_adjust).map {
          potential_lim => compute_mult_factor(ylim, potential_lim)
        }.unzip
        val mod_tables = tables map {
          case (xruns, yruns, xlab, ylab, xticks, xticklabs) =>
            (0 until num_adjust).map { i =>
              val run = yruns(i)
              for (j <- 0 until run.size)
                run(j) *= mults(i)
            }
        }
        val shapes = Array(23,21,22,3,20,25)
        val colors = Array("green", "slategray", "blue", "gold", "black", "red")
        val legends = Array(s"#Items${disp_factors(0)}",
          s"Median rank${disp_factors(1)}",
          s"%Accuracy${disp_factors(2)}",
          s"Mean #pos${disp_factors(3)}",
          "Mean error (km)",
          "Median error (km)")
        for ((xruns, yruns, xlab, ylab, xticks, xticklabs) <- tables) {
          lineplot_R(R, xruns, yruns, legends, shapes, colors,
            params.points_type, xlab, ylab, ylim, xticks, xticklabs)
        }
        end_plot_R(R)
      }

      // Output histogram of error distances vs. binned location-variance stats.
      if (params.results_histogram != null) {
        val base = LocationVarianceParams.bin_histogram_base
        val max_bin = LocationVarianceParams.num_histogram_bins - 1

        for ((file_label, fn) <- LocationStats.run_fns) {
          errprint("\nFile label: %s", file_label)

          val binned_merged_stats = bin_merged_stats(fn, base, max_bin)

          val runs = binned_merged_stats.map {
            case (bin, results) => {
              val minval = if (bin == 0) 0.0 else math.pow(base, bin)
              val maxval = math.pow(base, bin+1)
              val bin_label = "[%s - %s)" format (minval, maxval)
              errprint("Bin: %s, label: %s, #items: %s", bin, bin_label,
                results.size)
              val run = results.map {
                case (stat, result) => result.get[Double]("error-dist")
              }.toIndexedSeq
              (bin_label, run)
            }
          }.filter {
            // R's histogram function is unhappy when given only a single item.
            case (_, run) => run.size >= 2
          }

          val file_suffix = file_label.replace(" ", "-")
          val file = params.results_histogram + "." + file_suffix + ".pdf"
          compute_histogram(file, runs,
            LocationVarianceParams.bin_histogram_width,
            LocationVarianceParams.bin_histogram_height)
        }
      }
    }

    0
  }
}
