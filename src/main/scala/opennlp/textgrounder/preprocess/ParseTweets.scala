///////////////////////////////////////////////////////////////////////////////
//  ParseTweets.scala
//
//  Copyright (C) 2012 Stephen Roller, The University of Texas at Austin
//  Copyright (C) 2012-2014 Ben Wing, The University of Texas at Austin
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
package preprocess

/*
 * This program reads in tweets and processes them, optionally filtering
 * and/or grouping them.  Normally, tweets are read in JSON format, as
 * directly pulled from the twitter API, and output in "textdb" format,
 * i.e. as a simple TAB-separated database with one record per line and
 * a separate schema file specifying the column names.  However, other
 * input and output formats can be used.  The output normally includes the
 * text of the tweets as well as unigrams and/or n-grams.
 *
 * See README.txt in the top-level directory for more info.
 */

import collection.JavaConversions._
import collection.mutable

import java.io._
import java.lang.Double.isNaN
import Double.NaN
import java.text.{SimpleDateFormat, ParseException}
import java.util.Date

import net.liftweb
import org.apache.commons.logging
import org.apache.hadoop.fs.{FileSystem=>HFileSystem,_}

import com.nicta.scoobi.Scoobi._

import util.Twokenize
import util.argparser._
import util.collection._
import util.error._
import util.textdb._
import util.io.FileHandler
import util.hadoop.HadoopFileHandler
import util.print._
import util.spherical.SphereCoord
import util.numeric.pretty_long
import util.time._

class ParseTweetsParams(ap: ArgParser) extends
    ScoobiProcessFilesParams(ap) {
  var grouping = ap.option[String]("grouping", "g",
    default = "none",
    choices = Seq("user", "time", "file", "none"),
    help="""Mode for grouping tweets in the output.  There are currently
    four methods of grouping: `user`, `time` (i.e. all tweets within a given
    timeslice, specified with `--timeslice`), `file` (all tweets within a
    given input file) and `none` (no grouping; tweets are passed through
    directly, after duplicated tweets have been removed).  Default is
    `%default`.  See also `--filter-grouping`.""")
  var filter_grouping = ap.option[String]("filter-grouping", "fg",
    default = "default",
    choices = Seq("user", "time", "file", "none", "default"),
    help="""Mode for grouping tweets for filtering by group (using
    `--filter-groups` or `--cfilter-groups`).  The possible modes are the
    same as in `--grouping`.  The default is the same as `--grouping`, but
    it is possible to specify a different type of grouping when applying the
    group-level filters.""")
  var input_format = ap.option[String]("input-format", "if",
    choices = Seq("json", "textdb", "raw-lines"),
    default = "json",
    help="""Format for input of tweets.  Possibilities are

    -- `json` (Read in JSON-formatted tweets.)

    -- `textdb` (Read in data in textdb format, i.e. as a simple database
    with one record per line, fields separated by TAB characters, and a
    separate schema file indicating the names of the columns.  Typically this
    will result from a previous run of ParseTweets using the default textdb
    output format.)

    -- `raw-lines` (Read in lines of raw text and treat them as "tweets".  The
    tweets will have no useful information in them except for the text and
    file name, but this can still be useful for things like generating n-grams.)
    """)
  var output_format = ap.option[String]("output-format", "of",
    choices = Seq("textdb", "stats", "json"),
    default = "textdb",
    help="""Format for output of tweets or tweet groups.  Possibilities are

    -- `textdb` (Store in textdb format, i.e. as a simple database with one
    record per line, fields separated by TAB characters, and a separate schema
    file indicating the names of the columns.)

    -- `json` (Simply output JSON-formatted tweets directly, exactly as
    received.)

    -- `stats` (Textdb-style output with statistics on the tweets, users, etc.
    rather than outputting the tweets themselves.)""")
  var corpus_name = ap.option[String]("corpus-name",
    help="""Name of output corpus; for identification purposes.
    Default to name taken from input directory.""")
  var split = ap.option[String]("split", default = "training",
    help="""Split (training, dev, test) to place data in.  Default %default.""")
  var timeslice_float = ap.option[Double]("timeslice", "time-slice",
    default = 6.0,
    must = be_>(0.0),
    help="""Number of seconds per timeslice when `--grouping=time`.
    Can be a fractional number.  Default %default.""")
  var timeslice = (timeslice_float * 1000).toLong
  var filter_tweets = ap.option[String]("filter-tweets",
    help="""Boolean expression used to filter tweets to be output.
Expression consists of one or more expressions, joined by the operators
AND, OR and NOT (which must be written all-caps to be recognized).  The
order of precedence (from high to low) is

-- comparison operators (<, <=, >, >=, WITHIN)
-- NOT
-- AND
-- OR

Parentheses can be used for grouping or precedence.  Expressions consist of
one of the following:

-- A sequence of words, which matches a tweet if and only if that exact
   sequence is found in the tweet.  Matching happens on the word-by-word
   level, after a tweet has been tokenized.  Matching is case-insensitive;
   use '--cfilter-tweets' for case-sensitive matching.  Note that the use of
   '--preserve-case' has no effect on the case sensitivity of filtering;
   it rather affects whether the output is converted to lowercase or
   left as-is.  Any word that is quoted is treated as a literal
   regardless of the characters in it; this can be used to treat words such
   as "AND" literally.

-- An expression specifying a one-sided restriction on the time of the tweet,
   such as 'TIME < 20100802180502PDT' (earlier than August 2, 2010, 18:05:02
   Pacific Daylight Time) or 'TIME >= 2011:12:25:0905pm (at least as late as
   December 25, 2011, 9:05pm local time). The operators can be <, <=, > or >=.
   As for the time, either 12-hour or 24-hour time can be given, colons can
   optionally be inserted anywhere for readability, the time zone can be
   omitted or specified, and part or all of the time of day (hours, minutes,
   seconds) can be omitted.  Years must always be full (i.e. 4 digits).

-- An expression specifying a two-sided restriction on the time of the tweet
   (i.e. the tweet's time must be within a given interval).  Either of the
   following forms are allowed:

   -- 'TIME WITHIN 2010:08:02:1805PDT/2h'
   -- 'TIME WITHIN (2010:08:02:0500pmPDT 2010:08:03:0930amPDT)'

   That is, the interval of time can be given either as a point of time plus
   an offset, or as two points of time.  The offset can be specified in
   various ways, e.g.

   -- '1h' or '+1h' (1 hour)
   -- '3m2s' or '3m+2s' or '+3m+2s' (3 minutes, 2 seconds)
   -- '2.5h' (2.5 hours, i.e. 2 hours 30 minutes)
   -- '5d2h30m' (5 days, 2 hours, 30 minutes)
   -- '-3h' (-3 hours, i.e. 3 hours backwards from a given point of time)
   -- '5d-1s' (5 days less 1 second)

   That is, an offset is a combination of individual components, each of
   which is a number (possibly fractional or negative or with a prefixed
   plus sign, which is ignored) plus a unit: 'd' = days, 'h' = hours,
   'm' = minutes, 's' = seconds.  Negative offsets are allowed, to indicate
   an interval backwards from a reference point.

-- An expression specifying a restriction on the location of the tweet.
   This can either be 'LOCATION EXISTS', indicating that the tweet must
   have a location, or a more specific restriction requiring both that
   the location exist and be within a given bounding box, e.g. for
   North America:

   -- 'LOCATION WITHIN (25.0,-126.0,49.0,-60.0)'

   The format is (MINLAT, MINLONG, MAXLAT, MAXLONG).

-- An expression specifying a restriction on which tweets are selected by
   the index of the tweet (where the first tweet is numbered 1, the second
   2, etc.). This is a comparison, e.g. 'TWEETINDEX <= 5'.

Examples:

--filter-tweets "mitt romney OR obama"

Look for any tweets containing the sequence "mitt romney" (in any case) or
"Obama".

--filter-tweets "mitt AND romney OR barack AND obama"

Look for any tweets containing either the words "mitt" and "romney" (in any
case and anywhere in the tweet) or the words "barack" and "obama".

--filter-tweets "hillary OR bill AND clinton"

Look for any tweets containing either the word "hillary" or both the words
"bill" and "clinton" (anywhere in the tweet).

--filter-tweets "(hillary OR bill) AND clinton"

Look for any tweets containing the word "clinton" as well as either the words
"bill" or "hillary".""")
  var cfilter_tweets = ap.option[String]("cfilter-tweets",
    help="""Boolean expression used to filter tweets to be output, with
    case-sensitive matching.  Format is identical to `--filter-tweets`.""")
  var grouped_location_method = ap.option[String]("grouped-location-method",
    "glm",
    default = "centroid",
    choices = Seq("earliest", "centroid"),
    help="""Method for choosing the location to assign to a group of tweets
    (e.g. for a given user). The possible values are `earliest` (choose the
    earliest one) and `centroid` (choose the centroid).""")
  var output_fields = ap.option[String]("output-fields",
    default="default",
    help="""Fields to output in textdb format.  This should consist of one or
    more directives, separated by spaces or commas.  Directives are processed
    sequentially.  Each directive should be one of

    1. A field name, meaning to include that field

    2. A field set, meaning to include those fields; currently the recognized
       sets are 'big-fields' (fields that may become arbitrarily large,
       including 'positions', 'user-mentions', 'retweets', 'hashtags', 'urls',
       'text', 'counts'), 'small-fields' (all remaining fields except for
       'unigram-counts', 'ngram-counts' and 'mapquest-place') and
       'inherent-fields' (both 'big-fields' and 'small-fields').

    3. A field name or field set with a preceding + sign, same as if the
       + sign were omitted.

    4. A field name or field set with a preceding - sign, meaning to exclude
       the respective field(s).

    5. The directive 'all', meaning to include all fields, canceling any
       previous directives.

    6. The directive 'none', meaning to include no fields, canceling any
       previous directives.

    7. The directive 'default', meaning to set the current fields to output
       to the default (which may vary depending on other settings), canceling
       any previous directives.

    Currently recognized fields:

    'path': Path of file that the tweet came from

    'index': 1-based index of the tweet (= line number of tweet in file
      it was taken from)

    'user': User name

    'id': Tweet ID

    'type': Either "tweet", "delete" or "limit"

    'offset': In the case of deletion or limit notices, a non-zero offset
      to ensure that the notices remain in the same relative position when
      sorting

    'min-timestamp': Earliest timestamp

    'max-timestamp': Latest timestamp

    'geo-timestamp': Earliest timestamp of tweet with corresponding location

    'coord': Best latitude/longitude pair (corresponding to earliest tweet),
      separated by a comma

    'followers': Max followers

    'following': Max following

    'lang': Language used

    'location': Declared location

    'timezone': Declared timezone

    'utc-offset': Timezone offset from UTC, in seconds

    'numtweets': Number of tweets merged

    'positions': List of all lat/long positions of users, along with
      timestamps

    'num-positions': Number of items listed in 'positions'

    'user-mentions': List of @-mentions of users, along with counts

    'retweets': List of users from which tweets were retweeted, with counts

    'hashtags': List of hashtags, with counts

    'urls': List of URL's, with counts

    'text': Actual text of all tweets, separate by >> signs

    'unigram-counts': All words, with counts

    'ngram-counts': All n-grams, with counts

    'counts': 'ngram-counts' if --max-ngram > 1, else 'unigram-counts'

    'mapquest-place': The nearest "place" for the lat/long position,
      found using MapQuest. Empty if no position exists or or if no
      place can be found.

    The default value is the directive 'default', which is defined as follows:

    1. For --input-format=raw-lines, include only 'path', 'numtweets',
       'text' and 'counts'. (Most other fields are not meaningful.)
    2. Else, for --grouping=file, all small fields.
    3. Else all small and big fieldsg.

    Note that specifying a value for this parameter will override the
    'default' directive, so that only the specified field names will be
    output, unless the 'default' directive is included at the beginning
    of the parameter's value.
""")
  var filter_groups = ap.option[String]("filter-groups",
    help="""Boolean expression used to filter on the grouped-tweet level.
  This is like `--filter-tweets` but filters groups of tweets (grouped
  according to `--grouping`), such that groups of tweets will be accepted
  if *any* tweet matches the filter.""")
  var cfilter_groups = ap.option[String]("cfilter-groups",
    help="""Same as `--filter-groups` but does case-sensitive matching.""")

  var preserve_case = ap.flag("preserve-case",
    help="""Don't lowercase words.  This preserves the difference
    between e.g. the name "Mark" and the word "mark".""")
  var include_invalid = ap.flag("include-invalid",
    help="""Don't skip invalid tweets, deletion notices and such. All tweets
    and tweet notices will be included.""")
  var max_ngram = ap.option[Int]("max-ngram", "max-n-gram", "ngram", "n-gram",
    default = 1,
    must = be_>(0),
    help="""Largest size of n-grams to create.  Default 1, i.e. language model
    only contains unigrams.""")

  // FIXME!! The following should really be controllable using the
  // normal filter mechanism, rather than the special-case hacks below.
  var filter_spammers = ap.flag("filter-spammers", "fs",
    help="""Filter out tweets that don't have number of tweets within
    [10, 1000], or number of followers >= 10, or number of people
    following within [5, 1000].  This is an attempt to filter out
    "spammers", i.e. accounts not associated with normal users.""")

  var override_coord = ap.option[String]("override-coord",
    default = "",
    help="""Override the latitude/longitude coordinates with the specified
    values, which should be separated by a comma.""")
  val (override_lat, override_long) =
    if (override_coord == "") (0.0, 0.0)
    else {
      val Array(lat, long) = override_coord.split(",")
      (lat.toDouble, long.toDouble)
    }

  /* Whether we are doing tweet-level filtering.  To check whether doing
     group-level filtering, check whether filter_grouping == "none". */
  val has_tweet_filtering = filter_tweets != null || cfilter_tweets != null
  val has_group_filtering = filter_groups != null || cfilter_groups != null

  if (filter_grouping == "default") {
    if (has_group_filtering)
      filter_grouping = grouping
    else
      filter_grouping = "none"
  }
  if (has_group_filtering && filter_grouping == "none")
    ap.usageError("group-level filtering not possible when `--filter-grouping=none`")
  if (!has_group_filtering && filter_grouping != "none")
    ap.usageError("when not doing group-level filtering, `--filter-grouping` must be `none`")
  if (output_format == "json" && grouping != "none")
    ap.usageError("output grouping (--grouping) not possible when output format is JSON")

  import ParseTweets.Tweet

  private def match_field(field: String) =
    if (Tweet.possible_fields contains field)
      Some(Seq(field))
    else if (field == "big-fields")
      Some(Tweet.big_fields)
    else if (field == "small-fields")
      Some(Tweet.small_fields)
    else
      None

  private object Field {
    def unapply(spec: String) = {
      if (spec.length > 0 && spec.head == '+')
        match_field(spec.tail)
      else
        match_field(spec)
    }
  }

  private object NegField {
    def unapply(spec: String) = {
      if (spec.length > 0 && spec.head == '-')
        match_field(spec.tail)
      else
        None
    }
  }

  def parse_output_fields(fieldspec: String) = {
    val directives = fieldspec.split("[ ,]")
    val incfields = mutable.LinkedHashSet[String]()
    for (direc <- directives) {
      direc match {
        case "default" => {
          incfields.clear()
          incfields ++= ParseTweets.Tweet.default_fields(this)
        }
        case "all" => {
          incfields.clear()
          incfields ++= ParseTweets.Tweet.all_fields
        }
        case "none" => {
          incfields.clear()
        }
        case Field(fields) => {
          incfields ++= fields
        }
        case NegField(fields) => {
          incfields --= fields
        }
        case x => { ap.usageError(
          "Unrecognized directive '%s' in --output-fields" format x)
        }
      }
    }
    incfields.toSeq map {
      case "counts" => if (max_ngram > 1) "ngram-counts" else "unigram-counts"
      case x => x
    }
  }

  val included_fields = parse_output_fields(output_fields)
  var input_schema: Schema = _

  /** Return whether we need to compute the given field. This is only
    * accurate for fields that consist of sequences or maps.
    */
  def need_to_compute_field(field: String) = {
    field match {
      case "text" =>
        (included_fields contains "text") ||
        (included_fields contains "unigram-counts") ||
        (included_fields contains "ngram-counts")
      case "positions"  =>
        (included_fields contains "positions") ||
        grouped_location_method == "centroid"
      case "user-mentions" | "retweets" | "hashtags" =>
        included_fields contains field
      case _ => true
    }
  }
}

object ParseTweets extends ScoobiProcessFilesApp[ParseTweetsParams] {

  // TweetID = Twitter's numeric ID used to uniquely identify a tweet.
  type TweetID = Long

  // Count of tweets; also used for tweet indices (= count of tweets
  // seen so far)
  type TweetCount = Long

  type Timestamp = Long

  def empty_map = Map[String, Int]()

  implicit def SphereCoordFmt = new WireFormat[SphereCoord] {
    def toWire(x: SphereCoord, out: DataOutput) {
      out.writeDouble(x.lat)
      out.writeDouble(x.long)
    }
    def fromWire(in: DataInput): SphereCoord = {
      val lat = in.readDouble()
      val long = in.readDouble()
      SphereCoord(lat, long)
    }
  }

  /**
   * Additional tweet fields that would go into Tweet except that there's
   * a 22-field limit in case classes. (Technically, this isn't the case
   * any more with Scala 2.10, but case classes with more than 22 fields
   * are crippled in certain ways and so we can't use them.)
   *
   * @param lang Language used
   * @param location Declared location in profile
   * @param timezone Declared timezone
   * @param utc_offset Timezone offset from UTC, in seconds. Treated as a
   *   string so we can handle null (blank) and multiple.
   */
  case class TweetA(
    lang: String,
    location: String,
    timezone: String,
    utc_offset: String
  )

  /**
   * Data for a tweet or grouping of tweets.
   *
   * @param json Raw JSON for tweet; only stored when --output-format=json
   * @param path Path of file that the tweet came from
   * @param index 1-based index of tweet (= line number); 0 if multiple
   * @param ty Tweet message type: "tweet", "delete" or "limit"
   * @param text Text for tweet or tweets (a Seq in case of multiple tweets)
   * @param user User name (FIXME: or one of them, when going by time; should
   *    do something smarter)
   * @param id Tweet ID
   * @param min_timestamp Earliest timestamp
   * @param max_timestamp Latest timestamp
   * @param geo_timestamp Earliest timestamp of tweet with corresponding
   *    location
   * @param lat Best latitude (corresponding to the earliest tweet)
   * @param long Best longitude (corresponding to the earliest tweet)
   * @param followers Max followers
   * @param following Max following
   * @param numtweets Number of tweets merged
   * @param positions Map of timestamps and positions
   * @param user_mentions Item-count map of all @-mentions
   * @param retweets Like `user_mentions` but only for retweet mentions
   * @param hashtags Item-count map of hashtags
   * @param urls Item-count map of URL's
   * @param a Extra data
   */
  case class Tweet(
    json: String,
    path: String,
    // FIXME!!! Currently this counts from the first tweet we've seen in
    // a file, but if a file was split into multiple chunks and each
    // chunk processed by a different mapper, our indices will begin
    // from the start of the chunk rather than reflecting the actual
    // line number in the file. This can be fixed by calling `zipWithIndex`
    // on the DList before operating on it, but this function only exists
    // starting in Scoobi 0.7.
    index: TweetCount,
    // Tweet type ("tweet", "delete")
    ty: String,
    var offset: Int,
    text: Iterable[String],
    user: String,
    var id: TweetID,
    min_timestamp: Timestamp,
    max_timestamp: Timestamp,
    geo_timestamp: Timestamp,
    lat: Double,
    long: Double,
    followers: Int,
    following: Int,
    numtweets: TweetCount,
    positions: Map[Timestamp, SphereCoord],
    user_mentions: Map[String, Int],
    retweets: Map[String, Int],
    hashtags: Map[String, Int],
    urls: Map[String, Int],
    a: TweetA
    /* NOTE: If you add a field here, you need to update a bunch of places,
       including (of course) wherever a Tweet is created, but also
       some less obvious places. (HOWEVER, there are currently 22 fields,
       and adding another one will cause problems at least with WireFormat.
       As a result, fields should be added to the TweetA substructure. If
       that ever fills up with 22 values, TweetB should be created, etc.)
       
       In all:

       -- the doc string just above
       -- the help string for parameter --output-fields (output_fields)
       -- the definition of to_row() and either Tweet.small_fields or
          Tweet.big_fields (or some variant)
       -- Tweet.from_row(), Tweet.from_raw_text(), Tweet.to_row()
       -- parse_json_lift()
       -- is_valid_tweet()
       -- merge_records()
       -- TweetFilterParser.main()
    */
  ) {
    def lookup_mapquest_place: String = {
      errout("Looking up %s,%s in MapQuest... ", lat, long)
      val place = if (!has_latlong) "" else {
        try {
          SphereCoord.lookup_lat_long_mapquest(lat, long) getOrElse ""
        } catch {
          case e:Exception =>
            errprint("%s", e)
            return ""
        }
      }
      errprint(place)
      place
    }

    def to_row(tokenize_act: TokenizeCountAndFormat, opts: ParseTweetsParams
    ): Iterable[String] = {
      import Encoder.{long => elong, _}
      opts.included_fields map { field =>
        field match {
          case "user" => string(user)
          case "id" => elong(id)
          case "path" => string(path)
          case "index" => elong(index)
          case "type" => string(ty)
          case "offset" => int(offset)
          case "min-timestamp" => timestamp(min_timestamp)
          case "max-timestamp" => timestamp(max_timestamp)
          case "geo-timestamp" => timestamp(geo_timestamp)
          case "coord" => {
            if (opts.grouped_location_method == "earliest") {
              // Latitude/longitude need to be combined into a single field,
              // but only if both actually exist.
              if (!isNaN(lat) && !isNaN(long)) "%s,%s" format (lat, long)
              else ""
            } else {
              if (positions.size == 0) ""
              else SphereCoord.serialize(
                SphereCoord.centroid(positions.map(_._2)))
            }
          }
          case "followers" => int(followers)
          case "following" => int(following)
          case "lang" => string(a.lang)
          case "location" => string(a.location)
          case "timezone" => string(a.timezone)
          case "utc-offset" => string(a.utc_offset)
          case "numtweets" => elong(numtweets)
          case "positions" => {
            val strmap =
              positions.toSeq sortWith (_._1 < _._1) map {
                case (timestamp, coord) => (timestamp.toString,
                  SphereCoord.serialize(coord))
              }
            string_map_seq(strmap)
          }
          case "num-positions" => int(positions.size)
          case "user-mentions" => count_map(user_mentions)
          case "retweets" => count_map(retweets)
          case "hashtags" => count_map(hashtags)
          case "urls" => count_map(urls)
          case "text" => string_seq(text)
          case "unigram-counts" => tokenize_act.emit_unigrams(text)
          case "ngram-counts" => tokenize_act.emit_ngrams(text)
          case "mapquest-place" => lookup_mapquest_place
        }
      }
    }

    /**
     * Return true if tweet has a fully-specified latitude and longitude.
     */
    def has_latlong = !isNaN(lat) && !isNaN(long)
  }

  object Tweet extends ParseTweetsAction {
    val operation_category = "Tweet"

    val small_fields =
      Seq("user", "id", "type", "offset", "path", "index", "min-timestamp",
        "max-timestamp", "geo-timestamp", "coord", "followers", "following",
        "lang", "location", "timezone", "utc-offset", "numtweets",
        "num-positions")

    val big_fields =
      Seq("positions", "user-mentions", "retweets", "hashtags", "urls",
        "text", "counts")

    val all_fields = small_fields ++ big_fields

    val possible_fields =
      all_fields ++ Seq("unigram-counts", "ngram-counts", "mapquest-place")

    // Keep track of unknown fields we've already warned about so we don't
    // warn on every single row. This may happen multiple times in different
    // task servers, but not a problem, we just want to avoid huge numbers
    // of warnings.
    var warned_fields = mutable.Set[String]()

    def default_fields(opts: ParseTweetsParams) = {
      if (opts.input_format == "raw-lines")
        Seq("path", "numtweets", "text", "counts")
      else if (opts.grouping == "file")
        small_fields
      else
        small_fields ++ big_fields
    }

    def row_fields(opts: ParseTweetsParams) = opts.included_fields

    def from_row(schema: Schema, fields: IndexedSeq[String],
        index: TweetCount, opts: ParseTweetsParams) = {
      var json = ""
      var path = ""
      var ty = ""
      var offset = 0
      var text = Seq[String]()
      var user = ""
      var id: TweetID = 0L
      var min_timestamp: Timestamp = 0L
      var max_timestamp: Timestamp = 0L
      var geo_timestamp: Timestamp = 0L
      var lat = NaN
      var long = NaN
      var followers = 0
      var following = 0
      var lang = ""
      var location = ""
      var timezone = ""
      var utc_offset = ""
      var numtweets = 1L
      var positions = Map[Timestamp, SphereCoord]()
      var user_mentions = empty_map
      var retweets = empty_map
      var hashtags = empty_map
      var urls = empty_map

      import Decoder.{long => dlong,_}
      schema.check_values_fit_schema(fields)
      for ((name, x) <- schema.fieldnames zip fields) {
        name match {
          case "json"          => json = string(x)
          case "path"          => path = string(x)
          case "type"          => ty = string(x)
          case "offset"        => offset = int(x)
          case "text"          => text = string_seq(x)
          case "user"          => user = string(x)
          case "id"            => id = dlong(x)
          case "min-timestamp" => min_timestamp = dlong(x)
          case "max-timestamp" => max_timestamp = dlong(x)
          case "geo-timestamp" => geo_timestamp = dlong(x)
          case "timestamp"     => {
            min_timestamp = dlong(x)
            max_timestamp = min_timestamp
            geo_timestamp = min_timestamp
          }
          case "coord"         => {
            if (x == "")
              { lat = NaN; long = NaN }
            else {
              val Array(xlat, xlong) = x.split(",", -1)
              lat = double(xlat)
              long = double(xlong)
            }
          }
          case "followers"     => followers = int(x)
          case "following"     => following = int(x)
          case "lang"          => lang = string(x)
          case "location"      => location = string(x)
          case "timezone"      => timezone = string(x)
          case "utc-offset"    => utc_offset = string(x)
          case "numtweets"     => numtweets = dlong(x)
          case "positions"     => {
            val strmap = string_map(x)
            strmap map {
              case (ts, coord) => (timestamp(x),
                SphereCoord.deserialize(coord))
            }
          }
          case "num-positions" =>
            { } // We don't record as it duplicates info in 'positions'
          case "user-mentions" => user_mentions = count_map(x)
          case "retweets"      => retweets = count_map(x)
          case "hashtags"      => hashtags = count_map(x)
          case "urls"          => urls = count_map(x)
          case "counts"        =>
            { } // We don't record counts as they're built from text
          case _               => {
            // Ignore index because we take it from the line number
            if (name != "index" && !(warned_fields contains name)) {
              logger.warn("Unrecognized field %s with value %s" format
                (name, x))
              warned_fields += name
            }
          }
        }
      }

      if (opts.override_coord != "") {
        lat = opts.override_lat
        long = opts.override_long
      }

      /* Synthesize missing timestamps and position list from other
       * properties.
       */
      val timestamps =
        List(min_timestamp, max_timestamp, geo_timestamp).filter(_ != 0)
      if (timestamps.size > 0) {
        val synth_timestamp = timestamps.min
        if (min_timestamp == 0) min_timestamp = synth_timestamp
        if (max_timestamp == 0) max_timestamp = synth_timestamp
        if (geo_timestamp == 0) geo_timestamp = synth_timestamp
      }
      if (positions.size == 0 && !lat.isNaN && !long.isNaN)
        positions = Map(geo_timestamp -> SphereCoord(lat, long))

      Tweet(json, path, index, ty, offset, text, user, id, min_timestamp,
        max_timestamp, geo_timestamp, lat, long, followers, following,
        numtweets, positions, user_mentions, retweets, hashtags, urls,
        TweetA(lang, location, timezone, utc_offset))
    }

    def from_raw_text(path: String, text: String, index: TweetCount,
        opts: ParseTweetsParams) = {
      val (lat, long) =
        if (opts.override_coord != "") (opts.override_lat, opts.override_long)
        else (NaN, NaN)
      Tweet("", path, index, "tweet", 0, Seq(text), "", 0L, 0L, 0L, 0L,
        lat, long, 0, 0, 1, Map[Timestamp, SphereCoord](),
        empty_map, empty_map, empty_map, empty_map, TweetA("", "", "", ""))
    }
  }
  implicit val tweetAWire = mkCaseWireFormat(TweetA.apply _, TweetA.unapply _)
  implicit val tweetWire = mkCaseWireFormat(Tweet.apply _, Tweet.unapply _)

  /**
   * A tweet along with ancillary data used for merging and filtering.
   *
   * @param output_key Key used for output grouping (--grouping).
   * @param filter_key Key used for filter grouping (--filter-grouping).
   * @param matches Whether the tweet matches the group-level boolean filters
   *   (if any).
   * @param tweet The tweet itself.
   */
  case class Record(
    output_key: String,
    filter_key: String,
    matches: Boolean,
    tweet: Tweet
  )
  implicit val recordWire = mkCaseWireFormat(Record.apply _, Record.unapply _)

  /**
   * A generic action in the ParseTweets app.
   */
  trait ParseTweetsAction extends ScoobiProcessFilesAction {
    val progname = "ParseTweets"

    def create_parser(expr: String, foldcase: Boolean) = {
      if (expr == null) null
      else new TweetFilterParser(foldcase).parse(expr)
    }
  }

  import scala.util.parsing.combinator.lexical.StdLexical
  import scala.util.parsing.combinator.syntactical._
  import scala.util.parsing.input.CharArrayReader.EofCh

  /**
   * A class used for filtering tweets using a boolean expression.
   * Parsing of the boolean expression uses Scala parsing combinators.
   *
   * To use, create an instance of this class; then call `parse` to
   * parse an expression into an abstract syntax tree object.  Then use
   * the `matches` method on this object to match against a tweet.
   */
  class TweetFilterParser(foldcase: Boolean) extends StandardTokenParsers {
    sealed abstract class Expr {
      /**
       * Check if this expression matches the given sequence of words.
       */
      def matches(tweet: Tweet): Boolean = {
        // FIXME: When a filter is present, we may end up calling Twokenize
        // 2 or 3 times (once when generating words or n-grams, once when
        // implementing tweet-level filters, and once when implementing
        // user-level filters).  But the alternative is to pass around the
        // tokenized text, which might not be any faster in a Hadoop env.
        val tokenized = tweet.text flatMap (Twokenize(_))
        if (foldcase)
          matches(tweet, tokenized map (_.toLowerCase))
        else
          matches(tweet, tokenized)
      }

      // Not meant to be called externally.  Actually implement the matching,
      // with the text explicitly given (so it can be downcased to implement
      // case-insensitive matching).
      def matches(tweet: Tweet, text: Iterable[String]): Boolean
    }

    case class EConst(value: Iterable[String]) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) =
        text.toSeq containsSlice value.toSeq
    }

    case class EAnd(left:Expr, right:Expr) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) =
        left.matches(tweet, text) && right.matches(tweet, text)
    }

    case class EOr(left:Expr, right:Expr) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) =
        left.matches(tweet, text) || right.matches(tweet, text)
    }

    case class ENot(e:Expr) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) =
        !e.matches(tweet, text)
    }

    def long_compare(val1: Timestamp, op: String, time2: Timestamp) = {
      op match {
        case "<" => val1 < time2
        case "<=" => val1 <= time2
        case ">" => val1 > time2
        case ">=" => val1 >= time2
      }
    }

    def time_compare(tw: Tweet, op: String, time: Timestamp): Boolean = {
      assert_==(tw.min_timestamp, tw.max_timestamp)
      long_compare(tw.min_timestamp, op, time)
    }

    case class TimeCompare(op: String, time: Timestamp) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) =
        time_compare(tweet, op, time)
    }

    case class TweetIndexCompare(op: String, index: Long) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) = {
        assert_>(tweet.index, 0L) // No merged tweets!
        long_compare(tweet.index, op, index)
      }
    }

    case class TimeWithin(interval: (Timestamp, Timestamp)) extends Expr {
      def matches(tweet: Tweet, text: Iterable[String]) = {
        val (start, end) = interval
        time_compare(tweet, ">=", start) &&
        time_compare(tweet, "<", end)
      }
    }

    case class LocationWithin(bbox: (Double, Double, Double, Double)
        ) extends Expr {
      def matches(tw: Tweet, text: Iterable[String]) = {
        val (minlat, minlong, maxlat, maxlong) = bbox
        (tw.has_latlong &&
         tw.lat >= minlat && tw.lat <= maxlat &&
         tw.long >= minlong && tw.long <= maxlong)
      }
    }

    case class LocationExists() extends Expr {
      def matches(tw: Tweet, text: Iterable[String]) = {
        tw.has_latlong
      }
    }

    // NOT CURRENTLY USED, but potentially useful as an indicator of how to
    // implement a parser for numbers. Instead we currently handle floating-
    // point numbers using parse_double().
//    class ExprLexical extends StdLexical {
//      override def token: Parser[Token] = floatingToken | super.token
//
//      def floatingToken: Parser[Token] =
//        rep1(digit) ~ optFraction ~ optExponent ^^
//          { case intPart ~ frac ~ exp => NumericLit(
//              (intPart mkString "") :: frac :: exp :: Nil mkString "")}
//
//      def chr(c:Char) = elem("", ch => ch==c )
//      def sign = chr('+') | chr('-')
//      def optSign = opt(sign) ^^ {
//        case None => ""
//        case Some(sign) => sign
//      }
//      def fraction = '.' ~ rep(digit) ^^ {
//        case dot ~ ff => dot :: (ff mkString "") :: Nil mkString ""
//      }
//      def optFraction = opt(fraction) ^^ {
//        case None => ""
//        case Some(fraction) => fraction
//      }
//      def exponent = (chr('e') | chr('E')) ~ optSign ~ rep1(digit) ^^ {
//        case e ~ optSign ~ exp =>
//          e :: optSign :: (exp mkString "") :: Nil mkString ""
//      }
//      def optExponent = opt(exponent) ^^ {
//        case None => ""
//        case Some(exponent) => exponent
//      }
//    }

    class FilterLexical extends StdLexical {
      // see `token` in `Scanners`
      override def token: Parser[Token] =
        ( delim
        | unquotedWordChar ~ rep( unquotedWordChar )  ^^
           { case first ~ rest => processIdent(first :: rest mkString "") }
        | '\"' ~ rep( quotedWordChar ) ~ '\"' ^^
           { case '\"' ~ chars ~ '\"' => StringLit(chars mkString "") }
        | EofCh ^^^ EOF
        | '\"' ~> failure("unclosed string literal")
        | failure("illegal character")
        )

      def isPrintable(ch: Char) =
         !ch.isControl && !ch.isSpaceChar && !ch.isWhitespace && ch != EofCh
      def isPrintableNonDelim(ch: Char) =
         isPrintable(ch) && ch != '(' && ch != ')' && ch != ','
      def unquotedWordChar = elem("unquoted word char",
         ch => ch != '"' && isPrintableNonDelim(ch))
      def quotedWordChar = elem("quoted word char",
         ch => ch != '"' && ch != '\n' && ch != EofCh)

     // // see `whitespace in `Scanners`
     // def whitespace: Parser[Any] = rep(
     //     whitespaceChar
     // //  | '/' ~ '/' ~ rep( chrExcept(EofCh, '\n') )
     //   )

      override protected def processIdent(name: String) =
        if (reserved contains name) Keyword(name) else StringLit(name)
    }

    override val lexical = new FilterLexical
    lexical.reserved ++= List("AND", "OR", "NOT", "TIME", "LOCATION",
      "TWEETINDEX", "WITHIN", "EXISTS")
    // FIXME! This is partly duplicated above in isPrintableNonDelim, and
    // the function above is required to handle parens and commas not
    // delimited by whitespace. Do we need this defn here at all?
    lexical.delimiters ++= List("(", ")", "<", "<=", ">", ">=", ",")

    def word = stringLit ^^ {
      s => EConst(Seq(if (foldcase) s.toLowerCase else s))
    }

    def words = word.+ ^^ {
      x => EConst(x.flatMap(_ match { case EConst(y) => y }))
    }

    def compare_op = ( "<=" | "<" | ">=" | ">" )

    def parse_long(x: String) = {
      try {
        Some(x.toLong)
      } catch {
        case _: NumberFormatException => None
      }
    }

    def parse_double(x: String) = {
      try {
        Some(x.toDouble)
      } catch {
        case _: NumberFormatException => None
      }
    }

    /**
     * Return a parser for objects of human-readable type `tag`, which parses
     * strings using `parse_fn`.
     */
    def parse_string[T](tag: String, parse_fn: String => Option[T]) = {
      // stringLit is a Parser[String] that parses a string literal into a
      // string. The ^^ function is function application, transforming the
      // parser into a Parser[(String, Option[T])] which parses a string
      // literal into an object of some type, returning the untransformed
      // string and the parsed object wrapped in an Option[]. The ^?
      // function is partial function application, transforming the parser
      // into a Parser[T] but throwing an error if the string could not
      // be parsed. (It takes two arguments, the partial function itself
      // and an error function to be called when the partial function
      // isn't applicable. The error function returns a string, which is
      // included in the thrown error.
      stringLit ^^ { s => (s, parse_fn(s)) } ^? (
      { case (_, Some(x)) => x },
      { case (s, None) => "Unable to parse %s %s" format (tag, s)
        case _ => internal_error("Should not get here") } )
    }

    def time = parse_string("date", parse_date)

    /**
     * Parse a string literal into a time interval of type (Long, Long).
     * This parses "short intervals" e.g. "2010:08:02:1805PDT/2h".
     * "Full intervals", which consist of two separate dates, are handled
     * with `full_interval`. This parser works essentially the same way as
     * the parser created in parse_string.
     */
    def short_interval = stringLit ^^ { parse_date_interval(_) } ^? (
      { case (Some((from, to)), "") => (from, to) },
      { case (None, errmess) => errmess
        case _ => internal_error("Should not get here") } )

    def full_interval = "(" ~> time ~ time <~ ")" ^^ {
      case from ~ to => (from, to) }

    def interval = (short_interval | full_interval)

    def latlong = parse_string("coordinate", parse_double)

    def bounding_box = ("(" ~> latlong ~ ("," ~> latlong) ~ ("," ~> latlong) ~
        ("," ~> latlong) <~ ")") ^^ {
      case minlat ~ minlong ~ maxlat ~ maxlong => (
        minlat, minlong, maxlat, maxlong) }

    def time_compare = "TIME" ~> compare_op ~ time ^^ {
      case op ~ time => TimeCompare(op, time)
      // case op~time if parse_time(time) => TimeCompare(op, time)
    }

    def time_within = "TIME" ~> "WITHIN" ~> interval ^^ {
      interval => TimeWithin(interval)
    }

    def location_within = "LOCATION" ~> "WITHIN" ~> bounding_box ^^ {
      bbox => LocationWithin(bbox)
    }

    def index = parse_string("index", parse_long)

    def tweetindex_compare = "TWEETINDEX" ~> compare_op ~ index ^^ {
      case op ~ index => TweetIndexCompare(op, index)
    }

    def location_exists = "LOCATION" ~> "EXISTS" ^^ {
      _ => LocationExists()
    }

    def parens: Parser[Expr] = "(" ~> expr <~ ")"

    def not: Parser[ENot] = "NOT" ~> term ^^ { ENot(_) }

    def term = ( words | parens | not | time_compare | time_within |
      location_within | location_exists)

    def andexpr = term * (
      "AND" ^^^ { (a:Expr, b:Expr) => EAnd(a,b) } )

    def orexpr = andexpr * (
      "OR" ^^^ { (a:Expr, b:Expr) => EOr(a,b) } )

    def expr = ( orexpr | term )

    def maybe_parse(s: String) = {
      val tokens = new lexical.Scanner(s)
      phrase(expr)(tokens)
    }

    def parse(s: String): Expr = {
      maybe_parse(s) match {
        case Success(tree, _) => tree
        case e: NoSuccess =>
          throw new
            IllegalArgumentException("Bad syntax: %s: %s" format (s, e))
      }
    }

    def test(exprstr: String, tweet: Tweet) = {
      maybe_parse(exprstr) match {
        case Success(tree, _) =>
          println("Tree: "+tree)
          val v = tree.matches(tweet)
          println("Eval: "+v)
        case e: NoSuccess => errprint("%s\n" format e)
      }
    }

    //A main method for testing
    def main(args: Array[String]) = {
      val text = args(2)
      val timestamp = parse_date(args(1)) match {
        case Some(time) => time
        case None => throw new IllegalArgumentException(
          "Unable to parse date %s" format args(1))
      }
      val tweet =
        Tweet("", "", 1, "tweet", 0, Seq(text), "user", 0, timestamp,
          timestamp, timestamp, NaN, NaN, 0, 0, 1,
          Map[Timestamp, SphereCoord](), empty_map, empty_map,
          empty_map, empty_map, TweetA("", "", "", ""))
      test(args(0), tweet)
    }
  }

  class ParseAndUniquifyTweets(opts: ParseTweetsParams) extends
    ParseTweetsAction {

    val operation_category = "Parse"

    // Used internally to force an exit when a problem in parse_json_lift
    // occurs.
    private class ParseJSonExit extends Exception { }

    var tweet_offset = 0

    /**
     * Parse a JSON line into a tweet, using Lift.
     *
     * @return status and tweet.
     */
    def parse_json_lift(path: String, line: String, index: TweetCount
        ): Tweet = {

      /**
       * Convert a Twitter timestamp, e.g. "Tue Jun 05 14:31:21 +0000 2012",
       * into a time in milliseconds since the Epoch (Jan 1 1970, or so).
       */
      def parse_time(timestring: String): Timestamp = {
        val sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss ZZZZZ yyyy")
        try {
          sdf.parse(timestring)
          sdf.getCalendar.getTimeInMillis
        } catch {
          case pe: ParseException => {
            bump_counter("unparsable date")
            logger.warn("Error parsing date %s on line %s: %s\n%s" format (
              timestring, lineno, line, pe))
            0
          }
        }
      }

      def parse_problem(e: Exception) = {
        logger.warn("Error parsing line %s: %s\n%s" format (
          lineno, line, stack_trace_as_string(e)))
        null
      }

      /**
       * Retrieve a string along a path, checking to make sure the path
       * exists.
       */
      def force_string(value: liftweb.json.JValue, fields: String*) = {
        var fieldval = value
        var path = List[String]()
        for (field <- fields) {
          path :+= field
          fieldval \= field
          if (fieldval == liftweb.json.JNothing) {
            val fieldpath = path mkString "."
            bump_counter("ERROR: tweet with missing field %s" format fieldpath)
            warning(line, "Can't find field path %s in tweet", fieldpath)
            throw new ParseJSonExit
          }
        }
        if (fieldval.values == null)
          ""
        else
          fieldval.values.toString
      }

      /**
       * Retrieve the list of entities of a particular type from a tweet,
       * along with the indices referring to the entity.
       *
       * @param key the key referring to the type of entity
       * @param subkey the subkey within the entity to return as the "value"
       *   of the entity
       */
      def retrieve_entities_with_indices(parsed: liftweb.json.JValue,
          key: String, subkey: String) = {
        // Retrieve the raw list of entities based on the key.
        val entities_raw =
          (parsed \ "entities" \ key values).
          asInstanceOf[List[Map[String, Any]]]
        // For each entity, fetch the user actually mentioned. Sort and
        // convert into a map counting mentions.  Do it this way to count
        // multiple mentions properly.
        for { ent <- entities_raw
              rawvalue = ent(subkey)
              if rawvalue != null
              value = rawvalue.toString
              indices = ent("indices").asInstanceOf[List[Number]]
              start = indices(0).intValue
              end = indices(1).intValue
              if {
                if (value.length == 0) {
                  bump_counter("zero length %s/%s seen" format (key, subkey))
                  warning(line,
                    "Zero-length %s/%s in interval [%s,%s], skipped",
                    key, subkey, start, end)
                  false
                } else true
              }
            }
          yield (value, start, end)
      }

      /**
       * Retrieve the list of entities of a particular type from a tweet,
       * as a map with counts (so as to count multiple identical entities
       * properly).
       *
       * @param key the key referring to the type of entity
       * @param subkey the subkey within the entity to return as the "value"
       *   of the entity
       */
      def retrieve_entities(parsed: liftweb.json.JValue,
          key: String, subkey: String) = {
        list_to_item_count_map(
          for { (value, start, end) <-
                retrieve_entities_with_indices(parsed, key, subkey) }
            yield value
        )
      }

      try {
        /* The result of parsing is a JValue, which is an abstract type with
           subtypes for all of the types of objects found in JSON: maps, arrays,
           strings, integers, doubles, booleans.  Corresponding to the atomic
           types are JString, JInt, JDouble, JBool.  Corresponding to arrays is
           JArray (which underlyingly holds something like a List[JValue]), and
           corresponding to maps is JObject.  JObject underlyingly holds
           something like a List[JField], and a JField is a structure holding a
           'name' (a string) and a 'value' (a JValue).  The following
           operations can be done on these objects:

           1. For JArray, JObject and JField, the method 'children' yields
              a List[JValue] of their children.  For JObject, as mentioned
              above, this will be a list of JField objects.  For JField
              objects, this will be a one-element list of whatever was
              in 'value'.
           2. JArray, JObject and JField can be indexed like an array.
              Indexing JArray is obvious.  Indexing JObject gives a JField
              object, and indexing JField tries to index the object in the
              field's 'value' element.
           3. You can directly fetch the components of a JField using the
              accessors 'name' and 'value'.

           4. You can retrieve the underlying Scala form of any object using
              'values'.  This returns JArrays as a List, JObject as a Map, and
              JField as a tuple of (name, value).  The conversion is deep, in
              that subobjects are recursively converted.  However, the result
              type isn't necessarily useful -- for atoms you get more or less
              what you expect (although BigInt instead of Int), and for JField
              it's just a tuple, but for JArray and JObject the type of the
              expression is a path-dependent type ending in 'Value'.  So you
              will probably have to cast it using asInstanceOf[]. (For Maps,
              consider using Map[String, Any], since you don't necessarily know
              the type of the different elements, which may vary from element
              to element.

           5. For JObject, you can do a field lookup using the "\" operator,
              as shown below.  This yields a JValue, onto which you can do
              another "\" if it happens to be another JObject. (That's because
              "\", like 'apply', 'values' and 'children' is defined on the
              JValue itself.  When "\" is given a non-existent field name,
              or run on a JInt or other atom, the result is JNothing.
           6. You can also pass a class (one of the subclasses of JValue) to
              "\" instead of a string, e.g. classOf[JInt], to find children
              with this type.  BEWARE: It looks only at the children returned
              by the 'children' method, which (for objects) are all of type
              JField, so looking for JInt won't help even if you have some
              key-value pairs where the value is an integer.
           7. You can also use "\\" to do multi-level lookup, i.e. this
              recursively traipses down 'children' and 'children' of 'children'
              and does the equivalent of "\" on each.  The return value is a
              List, with 'values' applied to each element of the List to
              convert to Scala objects.  Beware that you only get the items
              themselves, without the field names. That is, if you search for
              e.g. a JInt, you'll get back a List[Int] with a lot of numbers
              in it -- but no indication of where they came from or what the
              associated field name was.
           8. There's also 'extract' for getting a JObject as a case class,
              as well as 'map', 'filter', '++', and other standard methods for
              collections.
        */
        val parsed = liftweb.json.parse(line)
        val json = if (opts.output_format == "json") line else ""
        if ((parsed \ "delete" values) != None) {
          bump_counter("tweet deletion notices")
          tweet_offset += 1
          Tweet(json, path, index, "delete", tweet_offset, Seq(), "",
            0L, 0L, 0L, 0L, NaN, NaN, 0, 0, 1,
            Map[Timestamp, SphereCoord](),
            empty_map, empty_map, empty_map, empty_map,
            TweetA("", "", "", ""))
        } else if ((parsed \ "limit" values) != None) {
          bump_counter("tweet limit notices")
          tweet_offset += 1
          Tweet(json, path, index, "limit", tweet_offset, Seq(), "",
            0L, 0L, 0L, 0L, NaN, NaN, 0, 0, 1,
            Map[Timestamp, SphereCoord](),
            empty_map, empty_map, empty_map, empty_map,
            TweetA("", "", "", ""))
        } else {
          tweet_offset = 0
          val user = force_string(parsed, "user", "screen_name")
          val timestamp = parse_time(force_string(parsed, "created_at"))
          val raw_text = force_string(parsed, "text")
          val text = raw_text.replaceAll("\\s+", " ")
          val followers = force_string(parsed, "user", "followers_count").toInt
          val following = force_string(parsed, "user", "friends_count").toInt
          val tweet_id = force_string(parsed, "id_str").toLong
          val lang = force_string(parsed, "user", "lang")
          val location = force_string(parsed, "user", "location")
          val timezone = force_string(parsed, "user", "time_zone")
          val utc_offset = force_string(parsed, "user", "utc_offset")
          val (lat, long) =
            if (opts.override_coord != "")
              (opts.override_lat, opts.override_long)
            else if ((parsed \ "coordinates" \ "type" values).toString != "Point") {
              (NaN, NaN)
            } else {
              val latlong: List[Number] =
                (parsed \ "coordinates" \ "coordinates" values).
                  asInstanceOf[List[Number]]
              (latlong(1).doubleValue, latlong(0).doubleValue)
            }

          val positions = if (lat.isNaN || long.isNaN)
            Map[Timestamp, SphereCoord]()
          else
            Map(timestamp -> SphereCoord(lat, long))

          /////////////// HANDLE ENTITIES

          /* Entity types:

           user_mentions: @-mentions in the text; subkeys are
             screen_name = Username of user
             name = Display name of user
             id_str = ID of user, as a string
             id = ID of user, as a number
             indices = indices in text of @-mention, including the @

           urls: URLs mentioned in the text; subkeys are
             url = raw URL (probably a shortened reference to bit.ly etc.)
             expanded_url = actual URL
             display_url = display form of URL (without initial http://, cut
               off after a point with \u2026 (Unicode ...)
             indices = indices in text of raw URL
             (NOTE: all URL's in the JSON text have /'s quoted as \/, and
              display_url may not be present)

           hashtags: Hashtags mentioned in the text; subkeys are
             text = text of the hashtag
             indices = indices in text of hashtag, including the #

           media: Embedded objects
             type = "photo" for images
             indices = indices of URL for image
             url, expanded_url, display_url = similar to URL mentions
             media_url = photo on p.twimg.com?
             media_url_https = https:// alias for media_url
             id_str, id = some sort of tweet ID or similar?
             sizes = map with keys "small", "medium", "large", "thumb";
               each has subkeys:
                 resize = "crop" or "fit"
                 h = height (as number)
                 w = width (as number)

             Example of URL's for photo:
  url = http:\/\/t.co\/AO3mRYaG
  expanded_url = http:\/\/twitter.com\/alejandraoraa\/status\/215758169589284864\/photo\/1
  display_url = pic.twitter.com\/AO3mRYaG
  media_url = http:\/\/p.twimg.com\/Av6G6YBCAAAwD7J.jpg
  media_url_https = https:\/\/p.twimg.com\/Av6G6YBCAAAwD7J.jpg

"id_str":"215758169597673472"
           */

          // Retrieve "user_mentions", which is a list of mentions, each
          // listing the span of text, the user actually mentioned, etc. --
          // along with whether the mention is a retweet (by looking for
          // "RT" before the mention)
          val user_mentions_raw = retrieve_entities_with_indices(
            parsed, "user_mentions", "screen_name")
          val user_mentions_retweets =
            for { (screen_name, start, end) <- user_mentions_raw
                  namelen = screen_name.length
                  retweet = (start >= 3 &&
                             raw_text.slice(start - 3, start) == "RT ")
                } yield {
              // Subtract one because of the initial @ in the index reference
              if (end - start - 1 != namelen) {
                bump_counter("wrong length interval for screen name seen")
                warning(line, "Strange indices [%s,%s] for screen name %s, length %s != %s, text context is '%s'",
                  start, end, screen_name, end - start - 1, namelen,
                  raw_text.slice(start, end))
              }
              (screen_name, retweet)
            }
          val user_mentions_list =
            for { (screen_name, retweet) <- user_mentions_retweets }
              yield screen_name
          val retweets_list =
            for { (screen_name, retweet) <- user_mentions_retweets if retweet }
              yield screen_name
          val user_mentions = list_to_item_count_map(user_mentions_list)
          val retweets = list_to_item_count_map(retweets_list)

          val hashtags = retrieve_entities(parsed, "hashtags", "text")
          val urls = retrieve_entities(parsed, "urls", "expanded_url")
          // map
          //  { case (url, count) => (url.replace("\\/", "/"), count) }

          Tweet(json, path, index, "tweet", 0, Seq(text), user, tweet_id,
            timestamp, timestamp, timestamp, lat, long, followers,
            following, 1, positions, user_mentions, retweets, hashtags,
            urls, TweetA(lang, location, timezone, utc_offset))
        }
      } catch {
        case jpe: liftweb.json.JsonParser.ParseException => {
          bump_counter("ERROR: lift-json parsing error")
          parse_problem(jpe)
        }
        case npe: NullPointerException => {
          bump_counter("ERROR: NullPointerException when parsing")
          parse_problem(npe)
        }
        case nfe: NumberFormatException => {
          bump_counter("ERROR: NumberFormatException when parsing")
          parse_problem(nfe)
        }
        case _: ParseJSonExit => null
        case e: Exception => {
          bump_counter("ERROR: %s when parsing" format e.getClass.getName)
          parse_problem(e); throw e
        }
      }
    }

    def get_string(value: Map[String, Any], l1: String) = {
      value(l1).asInstanceOf[String]
    }

    def get_2nd_level_value[T](value: Map[String, Any], l1: String,
        l2: String) = {
      value(l1).asInstanceOf[java.util.LinkedHashMap[String,Any]](l2).
        asInstanceOf[T]
    }

    /*
     * Parse a line (JSON or textdb) into a tweet.  Return `null` if
     * unable to parse.
     */
    def parse_line_1(pathline: (String, String)): Tweet = {
      val (path, line) = pathline
      bump_counter("total lines")
      lineno += 1
      // For testing
      if (opts.debug)
        logger.debug("parsing JSON: %s" format line)
      if (line.trim == "") {
        bump_counter("blank lines skipped")
        null
      }
      else {
        bump_counter("total tweets parsed")
        val tweet = opts.input_format match {
          case "raw-lines" =>
            Tweet.from_raw_text(path, line, lineno, opts)
          case "json" => parse_json_lift(path, line, lineno)
          case "textdb" =>
            error_wrap(line, null: Tweet) { line =>
              Tweet.from_row(opts.input_schema, line.split("\t", -1), lineno,
                opts)
            }
        }
        if (tweet == null) {
          bump_counter("total tweets unsuccessfully parsed")
        } else {
          bump_counter("total tweets successfully parsed")
        }
        tweet
      }
    }

    val stored_notices = mutable.Buffer[Tweet]()
    var last_id: Long = _

    def parse_line(pathline: (String, String)): Seq[Tweet] = {
      val tweet = parse_line_1(pathline)
      /* Normally, we assign a delete message the same ID as the previous
         tweet so it will sort after it. But we can't do that for delete
         messages at the beginning of a split so instead we cache them
         until we encounter a tweet, then set the ID to that tweet and
         use negative offsets to ensure that they sort before that tweet.
         In the process we handle nulls (errors).
       */
      if (tweet == null)
        Seq()
      else if (tweet.id == 0) {
        tweet.id = last_id
        if (tweet.id == 0) {
          stored_notices += tweet
          Seq()
        } else
          Seq(tweet)
      } else {
        last_id = tweet.id
        if (stored_notices.size > 0) {
          var negoffset = -stored_notices.size
          for (tw <- stored_notices) {
            tw.id = last_id
            tw.offset = negoffset
            negoffset += 1
          }
          val retval = stored_notices.toList ++ Seq(tweet)
          stored_notices.clear()
          retval
        } else
          Seq(tweet)
      }
    }

    /**
     * Return true if this tweet is "valid" in that it doesn't have any
     * out-of-range values (blank strings or 0-valued quantities).  Note
     * that we treat a case where both latitude and longitude are 0 as
     * invalid even though technically such a place could exist. (FIXME,
     * use NaN or something to indicate a missing latitude or longitude).
     */
    def is_valid_tweet(tw: Tweet): Boolean = {
      val valid =
      // filters out invalid tweets, as well as trivial spam
        tw.ty == "tweet" && tw.id != 0 && tw.min_timestamp != 0 &&
          tw.max_timestamp != 0 && tw.user != "" &&
          !(tw.lat == 0.0 && tw.long == 0.0)
      if (!valid) {
        if (tw.ty != "tweet")
          bump_counter("tweet-related notices skipped")
        else
          bump_counter("tweets skipped due to invalid fields")
      }
      valid
    }

    /**
     * Select the first tweet with the same ID.  For various reasons we may
     * have duplicates of the same tweet among our data.  E.g. it seems that
     * Twitter itself sometimes streams duplicates through its Streaming API,
     * and data from different sources will almost certainly have duplicates.
     * Furthermore, sometimes we want to get all the tweets even in the
     * presence of flakiness that causes Twitter to sometimes bomb out in a
     * Streaming session and take a while to restart, so we have two or three
     * simultaneous streams going recording the same stuff, hoping that Twitter
     * bombs out at different points in the different sessions (which is
     * generally true). Then, all or almost all the tweets are available in
     * the different streams, but there is a lot of duplication that needs to
     * be tossed aside.
     */
    def tweet_once(id_tweets: ((TweetID, Int), Iterable[Tweet])) = {
      val (id, tweets) = id_tweets
      val head = tweets.head
      val skipped = tweets.tail.toSeq.length
      if (skipped > 0)
        bump_counter("duplicate tweets skipped", skipped)
      head
    }

    lazy val filter_tweets_ast =
      create_parser(opts.filter_tweets, foldcase = true)
    lazy val cfilter_tweets_ast =
      create_parser(opts.cfilter_tweets, foldcase = false)

    /**
     * Apply any boolean filters given in `--filter-tweets` or
     * `--cfilter-tweets`.
     */
    def filter_tweet_by_tweet_filters(tweet: Tweet) = {
      val good =
        (filter_tweets_ast == null || (filter_tweets_ast matches tweet)) &&
         (cfilter_tweets_ast == null || (cfilter_tweets_ast matches tweet))
      if (!good)
        bump_counter("tweets skipped due to non-matching tweet-level filter")
      good
    }

    // Filter out duplicate tweets -- group by tweet ID and then take the
    // first tweet for a given ID.  Duplicate tweets occur for various
    // reasons -- they are common even in a single Twitter stream, not to
    // mention when two or more streams covering the same time period and
    // source are combined to deal with the inevitable gaps in coverage
    // resulting from brief Twitter failures or hiccups, periods when
    // one of the scraping machines goes down, etc.
    def filter_duplicates(values: DList[Tweet]) = {
      values.groupBy { tweet => (tweet.id, tweet.offset)
      }.map(tweet_once)
    }

    def filter_tweets(values: DList[Tweet]) = {
      // It's necessary to filter for duplicates when reading JSON tweets
      // directly (see comment above), but a bad idea when reading other
      // formats because tweet ID's may not be unique (particularly when
      // reading "tweets" that actually stem from multiple grouped tweets,
      // raw text, etc. where the ID field may simply have -1 in it).
      //
      // Likewise it may be a good idea to filter out JSON tweets with
      // invalid fields in them, but not a good idea otherwise.
      val valid =
        if (opts.include_invalid) values
        else values.filter(is_valid_tweet)
      val deduplicated =
        if (opts.input_format == "json") filter_duplicates(valid)
        else values
      deduplicated.filter(x => filter_tweet_by_tweet_filters(x))
    }

    def note_remaining_tweets(tweet: Tweet) = {
      bump_counter("tweets remaining after uniquifying and tweet-level filtering")
      true
    }

    /**
     * Parse a set of JSON-formatted tweets.  Input is (path, JSON).
     */
    def apply(lines: DList[(String, String)]) = {

      // Parse JSON into tweet records.
      val values_extracted = lines.flatMap(parse_line)

      /* Filter duplicates, invalid tweets, tweets not matching any
         tweet-level boolean filters. (User-level boolean filters get
         applied later.) */
      val good_tweets = filter_tweets(values_extracted)

      good_tweets.filter(note_remaining_tweets)
    }
  }

  class GroupTweets(opts: ParseTweetsParams)
      extends ParseTweetsAction {

    val operation_category = "Group"

    lazy val filter_groups_ast =
      create_parser(opts.filter_groups, foldcase = true)
    lazy val cfilter_groups_ast =
      create_parser(opts.cfilter_groups, foldcase = false)

    /**
     * Apply any boolean filters given in `--filter-groups` or
     * `--cfilter-groups`.
     */
    private def filter_tweet_by_group_filters(tweet: Tweet) = {
      (filter_groups_ast == null || (filter_groups_ast matches tweet)) &&
       (cfilter_groups_ast == null || (cfilter_groups_ast matches tweet))
    }

    private def tweet_key(tweet: Tweet, keytype: String) = {
      keytype match {
        case "user" => tweet.user
        case "file" => tweet.path
        case "time" =>
          ((tweet.min_timestamp / opts.timeslice) * opts.timeslice).toString
        case "none" => ""
      }
    }

    private def tweet_to_record(tweet: Tweet) = {
      Record(tweet_key(tweet, opts.grouping),
             tweet_key(tweet, opts.filter_grouping),
             filter_tweet_by_group_filters(tweet),
             tweet)
    }

    // Do something sensible when merging two strings that are normally the
    // same for both tweets. FIXME: Perhaps we should keep a set of all
    // values.
    def combine_strings(a: String, b: String) = {
      if (a == "") b
      else if (b == "") a
      else if (a != b) "[multiple]"
      else a
    }

    /**
     * Merge the data associated with two tweets or tweet combinations
     * into a single tweet combination.  Concatenate text.  Find maximum
     * numbers of followers and followees.  Add number of tweets in each.
     * For latitude and longitude, take the earliest provided values
     * ("earliest" by timestamp and "provided" meaning not missing).
     */
    private def merge_records(tw1: Record, tw2: Record): Record = {
      assert_==(tw1.output_key, tw2.output_key)
      val t1 = tw1.tweet
      val t2 = tw2.tweet
      val id = if (t1.id != t2.id) -1L else t1.id
      val ty = combine_strings(t1.ty, t2.ty)
      val lang = combine_strings(t1.a.lang, t2.a.lang)
      val location = combine_strings(t1.a.location, t2.a.location)
      val timezone = combine_strings(t1.a.timezone, t2.a.timezone)
      val utc_offset = combine_strings(t1.a.utc_offset, t2.a.utc_offset)
      val path = combine_strings(t1.path, t2.path)
      val (followers, following) =
        (math.max(t1.followers, t2.followers),
         math.max(t1.following, t2.following))
      val numtweets = t1.numtweets + t2.numtweets
      // Avoid computing stuff we will never use
      val text =
        if (opts.need_to_compute_field("text"))
          t1.text ++ t2.text
        else Seq[String]()
      val positions =
        if (opts.need_to_compute_field("positions"))
          t1.positions ++ t2.positions
        else Map[Timestamp,SphereCoord]()
      val user_mentions =
        if (opts.need_to_compute_field("user-mentions"))
          combine_maps(t1.user_mentions, t2.user_mentions)
        else empty_map
      val retweets =
        if (opts.need_to_compute_field("retweets"))
          combine_maps(t1.retweets, t2.retweets)
        else empty_map
      val hashtags =
        if (opts.need_to_compute_field("hashtags"))
          combine_maps(t1.hashtags, t2.hashtags)
        else empty_map
      val urls =
        if (opts.need_to_compute_field("urls"))
          combine_maps(t1.urls, t2.urls)
        else empty_map

      val (lat, long, geo_timestamp) =
        if (isNaN(t1.lat) && isNaN(t2.lat)) {
          (t1.lat, t1.long, math.min(t1.geo_timestamp, t2.geo_timestamp))
        } else if (isNaN(t2.lat)) {
          (t1.lat, t1.long, t1.geo_timestamp)
        } else if (isNaN(t1.lat)) {
          (t2.lat, t2.long, t2.geo_timestamp)
        } else if (t1.geo_timestamp < t2.geo_timestamp) {
          (t1.lat, t1.long, t1.geo_timestamp)
        } else {
          (t2.lat, t2.long, t2.geo_timestamp)
        }
      val min_timestamp = math.min(t1.min_timestamp, t2.min_timestamp)
      val max_timestamp = math.max(t1.max_timestamp, t2.max_timestamp)

      // FIXME maybe want to track the different users
      val tweet =
        Tweet("", path, 0, ty, 0, text, t1.user, id, min_timestamp,
          max_timestamp, geo_timestamp, lat, long, followers, following,
          numtweets, positions, user_mentions, retweets, hashtags, urls,
          TweetA(lang, location, timezone, utc_offset))
      Record(tw1.output_key, "", tw1.matches || tw2.matches, tweet)
    }

    /**
     * Return true if tweet (combination) has a fully-specified latitude
     * and longitude.
     */
    private def has_latlong(tw: Tweet) = {
      val good = tw.has_latlong
      if (!good)
        bump_counter("grouped tweets filtered due to missing lat/long")
      good
    }

    val MAX_NUMBER_FOLLOWING = 1000
    val MIN_NUMBER_FOLLOWING = 5
    val MIN_NUMBER_FOLLOWERS = 10
    val MAX_NUMBER_TWEETS = 1000
    val MIN_NUMBER_TWEETS = 10
    /**
     * Return true if this tweet combination (tweets for a given user)
     * appears to reflect a "spammer" user or some other user with
     * sufficiently nonstandard behavior that we want to exclude them (e.g.
     * a celebrity or an inactive user): Having too few or too many tweets,
     * following too many or too few, or having too few followers.  A spam
     * account is likely to have too many tweets -- and even more, to send
     * tweets to too many people (although we don't track this).  A spam
     * account sends much more than it receives, and may have no followers.
     * A celebrity account receives much more than it sends, and tends to have
     * a lot of followers.  People who send too few tweets simply don't
     * provide enough data.
     *
     * FIXME: We don't check for too many followers of a given account, but
     * instead too many people that a given account is following.  Perhaps
     * this is backwards?
     */
    private def is_nonspammer(tw: Tweet): Boolean = {
      val good =
        (tw.following >= MIN_NUMBER_FOLLOWING &&
           tw.following <= MAX_NUMBER_FOLLOWING) &&
        (tw.followers >= MIN_NUMBER_FOLLOWERS) &&
        (tw.numtweets >= MIN_NUMBER_TWEETS &&
         tw.numtweets <= MAX_NUMBER_TWEETS)
      if (!good)
        bump_counter("grouped tweets filtered due to failing following, followers, or min/max tweets restrictions")
      good
    }

    /**
     * Return true if this tweet (combination) matches all of the
     * misc. filters (--filter-spammers).
     *
     * FIXME: Eliminate misc. filters, make them possible using
     * normal filter mechanism.
     */
    private def matches_misc_filters(tw: Tweet): Boolean = {
      (!opts.filter_spammers || is_nonspammer(tw))
    }

    /**
     * Return true if a grouped set of tweets matches group-level filters
     * (i.e. if any of them individually matches the filters).
     * We've already checked each individual tweet against the
     * group-level filters, and grouped tweets based on the group-filtering
     * key.  Note that we only do things this way if we're not also
     * grouping output on the same key; see below.
     */
    private def matches_group_filters(records: Iterable[Record]) = {
      val good = records.exists(_.matches)
      if (!good)
        bump_counter("grouped tweets filtered by group-level filters")
      good
    }

    /**
     * Apply group filtering (--filter-grouping, --filter-groups,
     * --cfilter-groups) in the absence of output grouping on the same key.
     */
    private def do_group_filtering(records: DList[Record]) = {
      records
        .groupBy(_.filter_key)
        .map(_._2) // throw away key
        .filter(matches_group_filters)
        .flatten
    }

    /**
     * Group output according to --grouping.
     */
    private def do_output_grouping(records: DList[Record]) = {
      records
        .groupBy(_.output_key)
        .combine(merge_records)
        .map(_._2) // throw away key
    }

    private def note_remaining_tweets(tweet: Tweet) = {
      bump_counter("grouped tweets remaining after output grouping and group-level filtering")
      bump_counter("ungrouped tweets remaining after output grouping and group-level filtering",
        tweet.numtweets)
      true
    }

    def apply(tweets: DList[Tweet]) = {
      /* Here we implement output grouping and group filtering.

      Possibilities:

      1. No output grouping, no group filtering:
         -- Do nothing.

      2. Output grouping, no group filtering:
         -- Group by output key;
         -- Merge.

      3. No output grouping, group filtering:
         -- Group by filter key;
         -- Filter to see if any tweet in group matches group filter
            (_.exists(_.matches));
         -- Flatten.

      4. Output grouping, filter grouping, same keys:
         -- Group by output key;
         -- Merge, merging the 'matches' values (a.matches || b.matches);
         -- Filter results based on _.matches.

      5. Output grouping, filter grouping, different keys: Combine 3+2:
         -- Group by filter key;
         -- Filter to see if any tweet in group matches group filter
            (_.exists(_.matches))
         -- Flatten;
         -- Group by output key;
         -- Merge.

      Note that in the cases of (3) and (5), we need to iterate over
      the result of grouping.  Currently there are some issues doing
      this in Scoobi.  We do have a fix in our private version, but it
      potentially can lead to memory errors.  In general, the problem
      is that we need to iterate twice through the list of grouped
      tweets (once to check to see if any match, again to do the
      flattening).  Hadoop doesn't really have support for this, so
      we have to buffer everything, which can lead to memory errors.
      (Note that Hadoop 0.21+/2.0+ has a feature to allow multiple
      iteration in this case, but it also does buffering.  The only
      difference is that it buffers only a certain amount in memory
      and then spills the remainder to disk.  Scoobi should probably do
      the same.)
      */

      val grouped_tweets =
        if (opts.grouping == "none" && opts.filter_grouping == "none")
          tweets
        else {
          // convert to Record (which contains grouping keys and group-level
          // filter results)
          val records = tweets.map(tweet_to_record)
          val grouped_records =
            (opts.grouping, opts.filter_grouping) match {
              case (_, "none") => do_output_grouping(records)
              case ("none", _) => do_group_filtering(records)
              case (x, y) if x == y =>
                do_output_grouping(records).filter(_.matches)
              case (_, _) =>
                do_output_grouping(do_group_filtering(records))
            }
          // convert back to Tweet
          grouped_records.map(_.tweet)
        }

      // Apply misc. filters, and note whatever remains.
      // FIXME: Misc. filters should be doable using normal filter
      // mechanism, rather than special-cased.
      grouped_tweets
        .filter(matches_misc_filters)
        .filter(note_remaining_tweets)
    }
  }

  class TokenizeCountAndFormat(opts: ParseTweetsParams)
      extends ParseTweetsAction {

    val operation_category = "Tokenize"

    /**
     * Convert a word to lowercase.
     */
    def normalize_word(orig_word: String) = {
      val word =
        if (opts.preserve_case)
          orig_word
        else
          orig_word.toLowerCase
      // word.startsWith("@")
      if (word.contains("http://") || word.contains("https://"))
        "-LINK-"
      else
        word
    }

    /**
     * Return true if word should be filtered out (post-normalization).
     */
    def reject_word(word: String) = {
      word == "-LINK-"
    }

    /**
     * Return true if ngram should be filtered out (post-normalization).
     * Here we filter out things where every word should be filtered, or
     * where the first or last word should be filtered (in such a case, all
     * the rest will be contained in a one-size-down n-gram).
     */
    def reject_ngram(ngram: Iterable[String]) = {
      ngram.forall(reject_word) || reject_word(ngram.head) ||
        reject_word(ngram.last)
    }

    /**
     * Use Twokenize to break up a tweet into tokens and separate into ngrams.
     */
    def break_tweet_into_ngrams(text: String, max_ngram: Int):
        Iterable[Iterable[String]] = {
      val words = Twokenize(text)
      val normwords = words.map(normalize_word)

      // Then, generate all possible ngrams up to a specified maximum length,
      // where each ngram is a sequence of words.  `sliding` overlays a sliding
      // window of a given size on a sequence to generate successive
      // subsequences -- exactly what we want to generate ngrams.  So we
      // generate all 1-grams, then all 2-grams, etc. up to the maximum size,
      // and then concatenate the separate lists together (that's what `flatMap`
      // does).
      (1 to max_ngram).
        flatMap(normwords.sliding(_)).filter(!reject_ngram(_))
    }

    /**
     * Tokenize a tweet text string into ngrams up to a maximum specified
     * size and count them, emitting the word-count pairs encoded into a
     * string.
     */
    def emit_ngrams(tweet_text: Iterable[String], max_ngram: Int): String = {
      val ngrams =
        tweet_text.flatMap(break_tweet_into_ngrams(_, max_ngram)).toSeq.
          map(encode_ngram_for_map_field)
      shallow_encode_count_map(list_to_item_count_map(ngrams).toSeq)
    }

    /**
     * Tokenize a tweet text string into ngrams up to the size given in
     * --max-ngram and count them, emitting the word-count pairs encoded
     * into a string.
     */
    def emit_ngrams(tweet_text: Iterable[String]): String =
      emit_ngrams(tweet_text, opts.max_ngram)

    /**
     * Tokenize a tweet text string into unigrams and count them, emitting
     * the word-count pairs encoded into a string.
     */
    def emit_unigrams(tweet_text: Iterable[String]): String =
      emit_ngrams(tweet_text, 1)

    /**
     * Given a tweet, tokenize the text into ngrams, count words and format
     * the result as a field; then convert the whole into a record (list of
     * field values) to be written out.
     */
    def tokenize_count_and_format(tweet: Tweet) = tweet.to_row(this, opts)
  }

  /**
   * @param ty Type of value
   * @param key2 Second-level key to group on; Typically, either we want the
   *   individual value to appear in the overall stats (at 2nd level) or not;
   *   when not, put the individual value in `value`, and put the value of
   *   `ty` in `key2`; otherwise, put the individual value in `key2`, and
   *   usually put a constant value (e.g. "") in `value` so all relevant
   *   tweets get grouped together.
   * @param value See above.
   */
  case class PropertyValueStats(
    ty: String,
    key2: String,
    value: String,
    num_tweets: Int,
    min_timestamp: Timestamp,
    max_timestamp: Timestamp
  ) {
    def to_row(opts: ParseTweetsParams) = {
      import Encoder._
      Seq(
        string(ty),
        string(key2),
        string(value),
        int(num_tweets),
        timestamp(min_timestamp),
        timestamp(max_timestamp)
      )
    }
  }

  implicit val propertyValueStatsWire =
    mkCaseWireFormat(PropertyValueStats.apply _, PropertyValueStats.unapply _)

  object PropertyValueStats {
    def row_fields =
      Seq(
        "type",
        "key2",
        "value",
        "num-tweets",
        "min-timestamp",
        "max-timestamp")

    def from_row(row: String, opts: ParseTweetsParams) = {
      import Decoder._
      val Array(ty, key2, value, num_tweets, min_timestamp, max_timestamp) =
        row.split("\t", -1)
      PropertyValueStats(
        string(ty),
        string(key2),
        string(value),
        int(num_tweets),
        timestamp(min_timestamp),
        timestamp(max_timestamp)
      )
    }

    def from_tweet(tweet: Tweet, ty: String, key2: String, value: String) = {
      PropertyValueStats(ty, key2, value, 1, tweet.min_timestamp,
        tweet.max_timestamp)
    }

    def merge_stats(x1: PropertyValueStats, x2: PropertyValueStats) = {
      assert_==(x1.ty, x2.ty)
      assert_==(x1.key2, x2.key2)
      assert_==(x1.value, x2.value)
      PropertyValueStats(x1.ty, x1.key2, x1.value,
        x1.num_tweets + x2.num_tweets,
        math.min(x1.min_timestamp, x2.min_timestamp),
        math.max(x1.max_timestamp, x2.max_timestamp))
    }
  }

  /**
   * Statistics on any tweet property (e.g. user, language) that can be
   * identified by a value of some type (e.g. string, number) and has an
   * associated map of occurrences of values of the property.
   */
  case class PropertyStats(
    ty: String,
    key2: String,
    lowest_value_by_sort: String,
    highest_value_by_sort: String,
    most_common_value: String,
    most_common_count: Int,
    least_common_value: String,
    least_common_count: Int,
    num_value_types: Int,
    num_value_occurrences: Int
  ) extends Ordered[PropertyStats] {
    def compare(that: PropertyStats) = {
      (ty compare that.ty) match {
        case 0 => key2 compare that.key2
        case x => x
      }
    }

    def to_row(opts: ParseTweetsParams) = {
      import Encoder._
      Seq(
        string(ty),
        string(key2),
        string(lowest_value_by_sort),
        string(highest_value_by_sort),
        string(most_common_value),
        int(most_common_count),
        string(least_common_value),
        int(least_common_count),
        int(num_value_types),
        int(num_value_occurrences),
        double(num_value_occurrences.toDouble/num_value_types)
      )
    }
  }

  implicit val propertyStatsWire =
    mkCaseWireFormat(PropertyStats.apply _, PropertyStats.unapply _)

  object PropertyStats {
    def row_fields =
      Seq(
        "type",
        "key2",
        "lowest-value-by-sort",
        "highest-value-by-sort",
        "most-common-value",
        "most-common-count",
        "least-common-value",
        "least-common-count",
        "num-value-types",
        "num-value-occurrences",
        "avg-value-occurrences"
      )

    def from_row(row: String) = {
      import Decoder._
      val Array(ty, key2, lowest_value_by_sort, highest_value_by_sort,
        most_common_value, most_common_count,
        least_common_value, least_common_count,
        num_value_types, num_value_occurrences, avo) =
        row.split("\t", -1)
      PropertyStats(
        string(ty),
        string(key2),
        string(lowest_value_by_sort),
        string(highest_value_by_sort),
        string(most_common_value),
        int(most_common_count),
        string(least_common_value),
        int(least_common_count),
        int(num_value_types),
        int(num_value_occurrences)
      )
    }

    def from_value_stats(vs: PropertyValueStats) =
      PropertyStats(vs.ty, vs. key2, vs.value, vs.value, vs.value, vs.num_tweets,
      vs.value, vs.num_tweets, 1, vs.num_tweets)

    def merge_stats(x1: PropertyStats, x2: PropertyStats) = {
      assert_==(x1.ty, x2.ty)
      assert_==(x1.key2, x2.key2)
      val (most_common_value, most_common_count) =
        if (x1.most_common_count > x2.most_common_count)
          (x1.most_common_value, x1.most_common_count)
        else
          (x2.most_common_value, x2.most_common_count)
      val (least_common_value, least_common_count) =
        if (x1.least_common_count < x2.least_common_count)
          (x1.least_common_value, x1.least_common_count)
        else
          (x2.least_common_value, x2.least_common_count)
      PropertyStats(x1.ty, x1.key2,
        if (x1.lowest_value_by_sort < x2.lowest_value_by_sort)
          x1.lowest_value_by_sort
        else x2.lowest_value_by_sort,
        if (x1.highest_value_by_sort > x2.highest_value_by_sort)
          x1.highest_value_by_sort
        else x2.highest_value_by_sort,
        most_common_value, most_common_count,
        least_common_value, least_common_count,
        x1.num_value_types + x2.num_value_types,
        x1.num_value_occurrences + x2.num_value_occurrences)
    }
  }

  class GetStats(opts: ParseTweetsParams)
      extends ParseTweetsAction {

    import java.util.Calendar

    val operation_category = "GetStats"

    val date_fmts = Seq(
      ("year", "yyyy"),                   // Ex: "2012"
      ("year/month", "yyyy-MM (MMM)"),    // Ex. "2012-07 (Jul)"
      ("year/month/day",
        "yyyy-MM-dd (MMM d)"),            // Ex. "2012-07-05 (Jul 5)"
      ("month", "'month' MM (MMM)"),      // Ex. "month 07 (Jul)"
      ("month/week",
        "'month' MM (MMM), 'week' W"),    // Ex. "month 07 (Jul), week 2"
      ("month/day", "MM-dd (MMM d)"),     // Ex. "07-05 (Jul 5)"
      ("weekday", "'weekday' 'QQ' (EEE)"),// Ex. "weekday 2 (Mon)"
      ("hour", "HHaa"),                   // Ex. "09am"
      ("weekday/hour",
         "'weekday' 'QQ' (EEE), HHaa"),   // Ex. "weekday 2 (Mon), 23pm"
      ("hour/weekday",
        "HHaa, 'weekday' 'QQ' (EEE)"),    // Ex. "23pm, weekday 2 (Mon)"
      ("weekday/month", "'weekday' 'QQ' (EEE), 'month' MM (MMM)"),
                                    // Ex. "weekday 5 (Thu), month 07 (Jul)"
      ("month/weekday", "'month' MM (MMM), 'weekday' 'QQ' (EEE)")
                                   // Ex. "month 07 (Jul), weekday 5 (Thu)"
    )
    lazy val calinst = Calendar.getInstance
    lazy val sdfs =
      for ((engl, fmt) <- date_fmts) yield (engl, new SimpleDateFormat(fmt))

    /**
     * Format a timestamp according to a date format.  This would be easy if
     * not for the fact that SimpleDateFormat provides no way of inserting
     * the numeric equivalent of a day of the week, which we want in order
     * to make sorting turn out correctly.  So we have to retrieve it using
     * the `Calendar` class and shoehorn it in wherever the non-code QQ
     * was inserted.
     *
     * NOTE, a quick guide to Java date-related classes:
     *
     * java.util.Date: A simple wrapper around a timestamp in "Epoch time",
     *   i.e. milliseconds after the Unix Epoch of Jan 1, 1970, 00:00:00 GMT.
     *   Formerly also used for converting timestamps into human-style
     *   dates, but all that stuff is long deprecated because of lack of
     *   internationalization support.
     * java.util.Calendar: A class that supports conversion between timestamps
     *   and human-style dates, e.g. to figure out the year, month and day of
     *   a given timestamp.  Supports time zones, daylight savings oddities,
     *   etc.  Subclasses are supposed to represent different calendar systems
     *   but in reality there's only one, named GregorianCalendar but which
     *   actually supports both Gregorian (modern) and Julian (old-style,
     *   with no leap-year special-casing of years divisible by 100) calendars,
     *   with a configurable cross-over point.  Support for other calendars
     *   (Islamic, Jewish, etc.) is provided by third-party libraries (e.g.
     *   JodaTime), which typically discard the java.util.Calendar framework
     *   and create their own.
     * java.util.DateFormat: A class that supports conversion between
     *   timestamps and human-style dates nicely formatted into a string, e.g.
     *   generating strings like "Jul 23, 2012 08:05pm".  Again, theoretically
     *   there are particular subclasses to support different formatting
     *   mechanisms but in reality there's only one, SimpleDateFormat.
     *   Readable dates are formatted using a template, and there's a good
     *   deal of localization-specific stuff under the hood that theoretically
     *   the programmer doesn't need to worry about.
     */
    def format_date(time: Timestamp, fmt: SimpleDateFormat) = {
      val output = fmt.format(new Date(time))
      calinst.setTimeInMillis(time)
      val weekday = calinst.get(Calendar.DAY_OF_WEEK)
      output.replace("QQ", weekday.toString)
    }

    def stats_for_tweet(tweet: Tweet) = {
      Seq(PropertyValueStats.from_tweet(tweet, "user", "user", tweet.user),
          // Get a summary for all languages plus a summary for each lang
          PropertyValueStats.from_tweet(tweet, "lang", tweet.a.lang, ""),
          PropertyValueStats.from_tweet(tweet, "lang", "lang", tweet.a.lang)) ++
        sdfs.map { case (engl, fmt) =>
          PropertyValueStats.from_tweet(
            tweet, engl, format_date(tweet.min_timestamp, fmt), "") }
    }

    /**
     * Compute statistics on a DList of tweets.
     */
    def get_by_value(tweets: DList[Tweet]) = {
      /* Operations:

         1. For each tweet, and for each property we're interested in getting
            stats on (e.g. users, languages, etc.), generate a tuple
            (keytype, key, value) that has the type of property as `keytype`
            (e.g. "user"), the value of the property in `key` (e.g. the user
            name), and some sort of stats object (e.g. `PropertyStats`),
            giving statistics on that user (etc.) derived from the individual
            tweet.

         2. Take the resulting DList and group by grouping key.  Combine the
            resulting `PropertyStats` together by adding their values or
            taking max/min or whatever. (If there are multiple property types,
            we might have multiple classes involved, so we need to condition
            on the property type.)

         3. The resulting DList has one entry per property value, giving
            stats on all tweets corresponding to that property value.  We
            want to aggregate again of property type, to get statistics on
            the whole type (e.g. how many different property values, how
            often they occur).
       */
      tweets.flatMap(x => stats_for_tweet(x)).
      groupBy(stats => (stats.ty, stats.key2, stats.value)).
      combine(PropertyValueStats.merge_stats).
      map(_._2)
    }

    def get_by_type(values: DList[PropertyValueStats]) = {
      values.map(PropertyStats.from_value_stats(_)).
      groupBy(stats => (stats.ty, stats.key2)).
      combine(PropertyStats.merge_stats).
      map(_._2)
    }
  }

  class ParseTweetsDriver(opts: ParseTweetsParams)
      extends ParseTweetsAction {

    val operation_category = "Driver"

    /**
     * Create a schema for the data to be output.
     */
    def create_schema = {
      // We add the counts data to what to_row() normally outputs so we
      // have to add the same field here
      val fields = Tweet.row_fields(opts)
      val fixed_fields = Map(
          "corpus-name" -> opts.corpus_name,
          "generating-app" -> "ParseTweets",
          "textdb-type" -> "textgrounder-corpus",
          "corpus-type" -> ("twitter-%s" format
            (if (opts.grouping == "none") "tweets" else opts.grouping))) ++
        opts.non_default_params_string.toMap ++
        Map(
          "grouping" -> opts.grouping,
          "output-format" -> opts.output_format,
          "split" -> opts.split
        ) ++ (
        if (opts.grouping == "time")
          Map("corpus-timeslice" -> opts.timeslice.toString)
        else
          Map[String, String]()
      )
      new Schema(fields, fixed_fields)
    }
  }

  def create_params(ap: ArgParser) = new ParseTweetsParams(ap)
  val progname = "ParseTweets"

  private def grouping_type_english(opts: ParseTweetsParams,
      grouptype: String) = {
    grouptype match {
      case "time" =>
        "time, with slices of %g seconds".format(opts.timeslice_float)
      case "user" =>
        "user"
      case "file" =>
        "file"
      case "none" =>
        "not grouping"
    }
  }

  def run() {
    val opts = init_scoobi_app()
    val filehand = new HadoopFileHandler(configuration)
    if (opts.corpus_name == null) {
      val (_, tail) = filehand.split_filename(opts.input)
      opts.corpus_name = tail.replace("*", "_")
    }
    errprint("ParseTweets: " + (opts.grouping match {
      case "none" => "not grouping output"
      case x => "grouping output by " + grouping_type_english(opts, x)
    }))
    errprint("ParseTweets: " + (opts.filter_grouping match {
      case "none" => "no group-level filtering"
      case x => "group-level filtering by " + grouping_type_english(opts, x)
    }))
    errprint("ParseTweets: " + (opts.has_tweet_filtering match {
      case false => "no tweet-level filtering"
      case true => "doing tweet-level filtering"
    }))
    errprint("ParseTweets: " + (opts.output_format match {
      case "textdb" => "outputting in textdb format"
      case "json" => "outputting as raw JSON"
      case "stats" => "outputting statistics on tweets"
    }))
    val ptd = new ParseTweetsDriver(opts)
    // Firstly we load up all the (new-line-separated) JSON or textdb lines.
    val lines: DList[(String, String)] = {
      opts.input_format match {
        case "textdb" => {
          opts.input_schema = Schema.read_schema_from_textdb(
            filehand, opts.input)
          val matching_patterns = TextDB.data_file_matching_patterns(
            filehand, opts.input)
          TextInput.fromTextFileWithPath(matching_patterns: _*)
        }
        case "json" | "raw-lines"  => TextInput.fromTextFileWithPath(opts.input)
      }
    }

    errprint("ParseTweets: Generate tweets ...")
    val tweets1 = new ParseAndUniquifyTweets(opts)(lines)

    /* Maybe group tweets */
    val tweets = new GroupTweets(opts)(tweets1)

    opts.output_format match {
      case "json" => {
        /* We're outputting JSON's directly. */
        persist(TextOutput.toTextFile(tweets.map(_.json), opts.output))
        rename_output_files(filehand, opts.output, opts.corpus_name)
      }

      case "textdb" => {
        val tfct = new TokenizeCountAndFormat(opts)
        // Tokenize the combined text into words, possibly generate ngrams
        // from them, count them up and output results formatted into a record.
        val schema = ptd.create_schema
        val nicely_formatted = tweets.map { tw =>
          schema.make_line(tfct.tokenize_count_and_format(tw)) }
        dlist_output_textdb(schema, nicely_formatted, filehand, opts.output,
          opts.corpus_name)
      }

      case "stats" => {
        val get_stats = new GetStats(opts)
        val by_value = get_stats.get_by_value(tweets)
        val dlist_by_type = get_stats.get_by_type(by_value)
        val by_type = persist(dlist_by_type.materialize).toSeq.sorted
        val schema = new Schema(PropertyStats.row_fields,
          Map("corpus-name" -> opts.corpus_name,
              "textdb-type" -> "tweet-stats"))
        local_output_textdb(schema,
          by_type.map(x => schema.make_line(x.to_row(opts))),
          filehand, opts.output + "-tweet-stats", opts.corpus_name)
        val userstat = by_type.filter(x =>
          x.ty == "user" && x.key2 == "user").toSeq(0)
        errprint("\nCombined summary:")
        errprint("%s tweets by %s users = %.2f tweets/user",
          pretty_long(userstat.num_value_occurrences),
          pretty_long(userstat.num_value_types),
          userstat.num_value_occurrences.toDouble / userstat.num_value_types)
        val monthstat = by_type.filter(_.ty == "year/month")
        errprint("\nSummary by month:")
        for (mo <- monthstat)
          errprint("%-20s: %12s tweets", mo.key2,
            pretty_long(mo.num_value_occurrences))
        val daystat = by_type.filter(_.ty == "year/month/day")
        errprint("\nSummary by day:")
        for (d <- daystat)
          errprint("%-20s: %12s tweets", d.key2,
            pretty_long(d.num_value_occurrences))
        errprint("\n")
      }
    }

    errprint("ParseTweets: done.")

    finish_scoobi_app(opts)
  }
  /*

  To build a classifier for conserv vs liberal:

  1. Look for people retweeting congressmen or governor tweets, possibly at
     some minimum level of retweeting (or rely on followers, for some
     minimum number of people following)
  2. Make sure either they predominate having retweets from one party,
     and/or use the DW-NOMINATE scores to pick out people whose average
     ideology score of their retweets is near the extremes.
  */
}

