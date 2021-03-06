///////////////////////////////////////////////////////////////////////////////
//  FindPolitical.scala
//
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

import collection.mutable

import java.io._

import org.apache.commons.logging

import com.nicta.scoobi.Scoobi._

import util.argparser._
import util.collection._
import util.error._
import util.hadoop._
import util.io
import util.os._
import util.print._
import util.textdb._

class FindPoliticalParams(ap: ArgParser) extends
    ScoobiProcessFilesParams(ap) {
  var political_twitter_accounts = ap.option[String](
    "political-twitter-accounts", "pta",
    must = be_specified,
    help="""Textdb database containing list of politicians and associated
    twitter accounts, for identifying liberal and conservative tweeters.
    The value can be any of the following: Either the data or schema file
    of the database; the common prefix of the two; or the directory
    containing them, provided there is only one textdb in the directory.""")
  var political_twitter_accounts_format = ap.option[String](
    "political-twitter-accounts-format", "ptaf",
    default = "officeholders",
    choices = Seq("officeholders", "ideo-users"),
    help="""Format for textdb specified in --political-twitter-accounts.
    Possibilities: 'officeholders' (a file containing data gleaned from
    the Internet, specifying holders of political offices and their parties),
    'ideo-users' (output from a previous run of FindPolitical, in
    TextGrounder corpus format, with ideology identified by a numeric
    score).""")
  var min_accounts = ap.option[Int]("min-accounts", default = 2,
    must = be_>(0),
    help="""Minimum number of political accounts referenced by Twitter users
    in order for users to be considered.  Default %default.""")
  var min_conservative = ap.option[Double]("min-conservative", "mc",
    default = 0.75,
    must = be_within(0.0, 1.0),
    help="""Minimum ideology score to consider a user as an "ideological
    conservative".  On the ideology scale, greater values indicate more
    conservative.  Currently, the scale runs from 0 to 1; hence, this value
    should be greater than 0.5.  Default %default.""")
  var max_liberal = ap.option[Double]("max-liberal", "ml",
    must = be_within(0.0, 1.0),
    help="""Maximum ideology score to consider a user as an "ideological
    liberal".  On the ideology scale, greater values indicate more
    conservative.  Currently, the scale runs from 0 to 1; hence, this value
    should be less than 0.5.  If unspecified, computed as the mirror image of
    the value of '--min-conservative' (e.g. 0.25 if
    --min-conservative=0.75).""")
  var iterations = ap.option[Int]("iterations", "i",
    default = 1,
    must = be_>(0),
    help="""Number of iterations when generating ideological users.""")
  var corpus_name = ap.option[String]("corpus-name",
    help="""Name of output corpus; for identification purposes.
    Default to name taken from input directory.""")
  var include_text = ap.flag("include-text",
    help="""Include text of users sending tweets referencing a feature.""")
  var ideological_ref_type = ap.option[String]("ideological-ref-type", "ilt",
    default = "retweets", choices = Seq("retweets", "mentions", "followers"),
    help="""Type of references to other accounts to use when determining the
    ideology of a user.  Possibilities are 'retweets' (accounts that tweets
    are retweeted from); 'mentions' (any @-mention of an account, including
    retweets); 'following' (accounts that a user is following).  Default
    %default.""")
  var political_feature_type = ap.multiOption[String]("political-feature-type",
    "pft",
    choices = Seq("retweets", "followers", "hashtags", "urls", "images",
      "unigrams", "bigrams", "trigrams", "ngrams"),
    aliasedChoices = Seq(Seq("user-mentions", "mentions")),
    help="""Type of political features to track when searching for data that may
    be associated with particular ideologies.  Possibilities are 'retweets'
    (accounts that tweets are retweeted from); 'mentions' (any @-mention of an
    account, including retweets); 'following' (accounts that a user is
    following); 'hashtags'; 'unigrams'; 'bigrams'; 'trigrams'; 'ngrams'.
    DOCUMENT THESE; NOT YET IMPLEMENTED. Multiple features can be tracked
    simultaneously by specifying this option multiple times.""")
    // FIXME: Should be able to specify multiple features separated by commas.
    // This requires that we fix argparser.scala to allow this.  Probably
    // should add an extra field to provide a way of splitting -- maybe a regexp,
    // maybe a function.
  // Schema for the input file, after file read
  var schema: Schema = _

  if (ap.parsedValues && !ap.specified("max-liberal"))
    max_liberal = 1.0 - min_conservative
}

object FindPolitical extends
    ScoobiProcessFilesApp[FindPoliticalParams] {
  abstract class FindPoliticalAction(opts: FindPoliticalParams)
    extends ScoobiProcessFilesAction {
    val progname = "FindPolitical"
  }

  /**
   * Count of total number of references given a sequence of
   * (data, weight, times) pairs of references to a particular data point.
   */
  def count_refs[T](seq: Iterable[(T, Double, Int)]) = seq.map(_._3).sum
  /**
   * Count of total weight given a sequence of (data, weight, times) pairs
   * of references to a particular data point.
   */
  def count_weight[T](seq: Iterable[(T, Double, Int)]) =
    seq.map{ case (_, weight, times) => weight*times }.sum

  /**
   * Count of total number of accounts given a sequence of (data, times) pairs
   * of references to a particular data point.
   */
  def count_accounts[T](seq: Iterable[(T, Double, Int)]) = seq.size


  /**
   * Description of a "politico" -- a politician along their party and
   * known twitter accounts.
   */
  case class Politico(last: String, first: String, title: String,
      party: String, where: String, accounts: Iterable[String]) {
    def full_name = first + " " + last
  }
  implicit val politico_wire =
    mkCaseWireFormat(Politico.apply _, Politico.unapply _)

  def encode_ideo_refs_map(seq: Iterable[(String, Double, Int)]) =
    (for ((account, ideology, count) <- seq.toSeq sortWith (_._3 > _._3)) yield
      ("%s:%.2f:%s" format (
        encode_string_for_map_field(account), ideology, count))
    ) mkString " "

  def empty_ideo_refs_map = Seq[(String, Double, Int)]()

  /**
   * Description of a user and the accounts referenced, both political and
   * nonpolitical, along with ideology.
   *
   * @param user Twitter account of the user
   * @param ideology Computed ideology of the user (higher values indicate
   *   more conservative)
   * @param ideo_refs Set of references to other accounts used in computing
   *   the ideology (either mentions, retweets or following, based on
   *   --ideological-ref-type); this is a sequence of tuples of
   *   (account, ideology, times), i.e. an account, its ideology and the number
   *   of times it was seen
   * @param lib_ideo_refs Subset of `ideo_refs` that refer to liberal users
   * @param cons_ideo_refs Subset of `ideo_refs` that refer to conservative users
   * @param fields Field values of user's tweets (concatenated)
   */
  case class IdeologicalUser(user: String, ideology: Double,
      ideo_refs: Iterable[(String, Double, Int)],
      lib_ideo_refs: Iterable[(String, Double, Int)],
      cons_ideo_refs: Iterable[(String, Double, Int)],
      fields: IndexedSeq[String]) {
    def get_feature_values(factory: IdeologicalUserAction, ty: String) = {
      ty match {
        case field@("retweets" | "user-mentions" | "hashtags") =>
          decode_count_map(
            factory.user_subschema.get_field(fields, field))
        // case "followers" => FIXME
        // case "unigrams" => FIXME
        // case "bigrams" => FIXME
        // case "trigrams" => FIXME
        // case "ngrams" => FIXME
      }
    }

    def to_row(opts: FindPoliticalParams) =
      Seq(user, "%.3f" format ideology,
        count_accounts(ideo_refs),
        count_refs(ideo_refs),
        encode_ideo_refs_map(ideo_refs),
        count_accounts(lib_ideo_refs),
        count_refs(lib_ideo_refs),
        encode_ideo_refs_map(lib_ideo_refs),
        count_accounts(cons_ideo_refs),
        count_refs(cons_ideo_refs),
        encode_ideo_refs_map(cons_ideo_refs),
        fields mkString "\t"
      ) mkString "\t"
  }
  implicit val ideological_user_wire =
    mkCaseWireFormat(IdeologicalUser.apply _, IdeologicalUser.unapply _)

  class IdeologicalUserAction(opts: FindPoliticalParams) extends
      FindPoliticalAction(opts) {
    val operation_category = "IdeologicalUser"

    val user_subschema_fieldnames =
      opts.schema.fieldnames filterNot (_ == "user")
    val user_subschema = new SubSchema(user_subschema_fieldnames,
      opts.schema.fixed_values, opts.schema)

    def row_fields =
      Seq("user", "ideology",
        "num-ideo-accounts", "num-ideo-refs", "ideo-refs",
        "num-lib-ideo-accounts", "num-lib-ideo-refs", "lib-ideo-refs",
        "num-cons-ideo-accounts", "num-cons-ideo-refs", "cons-ideo-refs") ++
      user_subschema_fieldnames

    /**
     * For a given user, determine if the user is an "ideological user"
     * and if so, return an object describing the user.
     *
     * @param line Line of data describing a user, from `ParseTweets --grouping=user`
     * @param accounts Mapping of ideological accounts and their ideology
     * @param include_extra_fields True if we should include extra fields
     *   in the object specifying the references to ideological users that
     *   were found; only if we're writing the objects out for human inspection,
     *   not when we're iterating further
     */
    def get_ideological_user(line: String, accounts: Map[String, Double],
        include_extra_fields: Boolean) = {
      error_wrap(line, None: Option[IdeologicalUser]) { line => {
        val fields = line.split("\t", -1)

        def subsetted_fields =
          if (include_extra_fields)
            user_subschema.map_original_fieldvals(fields)
          else IndexedSeq[String]()

        // get list of (refs, times) pairs
        val ideo_ref_field =
          if (opts.ideological_ref_type == "mentions") "user-mentions"
          else opts.ideological_ref_type
        val ideo_refs =
          decode_count_map(opts.schema.get_field(fields, ideo_ref_field))
        val text = opts.schema.get_field(fields, "text")
        val user = opts.schema.get_field(fields, "user")
        //errprint("For user %s, ideo_refs: %s", user, ideo_refs.toList)
        // find references to a politician
        val libcons_ideo_refs =
          for {(ideo_ref, times) <- ideo_refs
               lower_ideo_ref = ideo_ref.toLowerCase
               if accounts contains lower_ideo_ref
               ideology = accounts(lower_ideo_ref)}
            yield (lower_ideo_ref, ideology, times)
        //errprint("libcons_ideo_refs: %s", libcons_ideo_refs.toList)
        val num_libcons_ideo_refs = count_refs(libcons_ideo_refs)
        if (num_libcons_ideo_refs > 0) {
          val ideology = count_weight(libcons_ideo_refs)/num_libcons_ideo_refs
          if (include_extra_fields) {
            val lib_ideo_refs = libcons_ideo_refs.filter {
              case (lower_ideo_ref, ideology, times) =>
                ideology <= opts.max_liberal
            }
            val num_lib_ideo_refs = count_refs(lib_ideo_refs)
            val cons_ideo_refs = libcons_ideo_refs.filter {
              case (lower_ideo_ref, ideology, times) =>
                ideology >= opts.min_conservative
            }
            val num_cons_ideo_refs = count_refs(cons_ideo_refs)
            val ideo_user =
              IdeologicalUser(user, ideology, libcons_ideo_refs, lib_ideo_refs,
              cons_ideo_refs, subsetted_fields)
            Some(ideo_user)
          } else {
            val ideo_user =
              IdeologicalUser(user, ideology, empty_ideo_refs_map,
                empty_ideo_refs_map, empty_ideo_refs_map,
                IndexedSeq[String]())
            Some(ideo_user)
          }
        } else if (accounts contains user.toLowerCase) {
          val ideology = accounts(user.toLowerCase)
          val ideo_user =
            IdeologicalUser(user, ideology, empty_ideo_refs_map,
              empty_ideo_refs_map, empty_ideo_refs_map, subsetted_fields)
          Some(ideo_user)
        } else
          None
      }}
    }
  }

  /**
   * A political data point -- a piece of data (e.g. user mention, retweet,
   * hash tag, URL, n-gram, etc.) in a tweet by an ideological user.
   *
   * @param data Data of the data point
   * @param ty Type of data point
   * @param spellings Map of actual (non-lowercased) spellings of data point
   *   by usage
   * @param num_accounts Total number of accounts referencing data point
   * @param num_refs Total number of references to data point
   * @param num_lib_accounts Number of accounts with a noticeably
   *   "liberal" ideology referencing data point
   * @param num_lib_refs Number of references to data point from accounts
   *   with a noticeably "liberal" ideology
   * @param num_cons_accounts Number of accounts with a noticeably
   *   "conservative" ideology referencing data point
   * @param num_cons_refs Number of references to data point from accounts
   *   with a noticeably "conservative" ideology
   * @param num_refs_ideo_weighted Sum of references weighted by ideology of
   *   person doing the referenceing, so that we can compute a weighted
   *   average to determine their ideology.
   * @param num_mentions Total number of mentions
   * @param num_lib_mentions Number of times mentioned by people with
   *   a noticeably "liberal" ideology
   * @param num_conserv_mentions Number of times mentioned by people with
   *   a noticeably "conservative" ideology
   * @param num_ideo_mentions Sum of mentions weighted by ideology of
   *   person doing the mentioning, so that we can compute a weighted
   *   average to determine their ideology.
   * @param all_text Text of all users referencing the politico.
   */
  case class PoliticalFeature(value: String, spellings: Map[String, Int],
    num_accounts: Int, num_refs: Int,
    num_lib_accounts: Int, num_lib_refs: Int,
    num_cons_accounts: Int, num_cons_refs: Int,
    num_refs_ideo_weighted: Double, all_text: Iterable[String]) {
    def to_row(opts: FindPoliticalParams) =
      Seq(value, encode_count_map(spellings.toSeq),
        num_accounts, num_refs,
        num_lib_accounts, num_lib_refs,
        num_cons_accounts, num_cons_refs,
        num_refs_ideo_weighted/num_refs,
        if (opts.include_text) all_text mkString " !! " else "(omitted)"
      ) mkString "\t"
  }

  object PoliticalFeature {

    def row_fields =
      Seq("value", "spellings", "num-accounts", "num-refs",
        "num-lib-accounts", "num-lib-refs",
        "num-cons-accounts", "num-cons-refs",
        "ideology", "all-text")
    /**
     * For a given ideological user, generate the "potential politicos": other
     * people referenced, along with their ideological scores.
     */
    def get_political_features(factory: IdeologicalUserAction,
        user: IdeologicalUser, ty: String,
        opts: FindPoliticalParams) = {
      for {(ref, times) <- user.get_feature_values(factory, ty)
           lcref = ref.toLowerCase } yield {
        val is_lib = user.ideology <= opts.max_liberal
        val is_conserv = user.ideology >= opts.min_conservative
        PoliticalFeature(
          lcref, Map(ref->times), 1, times,
          if (is_lib) 1 else 0,
          if (is_lib) times else 0,
          if (is_conserv) 1 else 0,
          if (is_conserv) times else 0,
          times * user.ideology,
          Seq("FIXME fill-in text maybe")
        )
      }
    }

    /**
     * Merge two PoliticalFeature objects, which must refer to the same user.
     * Add up the references and combine the set of spellings.
     */
    def merge_political_features(u1: PoliticalFeature, u2: PoliticalFeature) = {
      assert_==(u1.value, u2.value)
      PoliticalFeature(u1.value, combine_maps(u1.spellings, u2.spellings),
        u1.num_accounts + u2.num_accounts,
        u1.num_refs + u2.num_refs,
        u1.num_lib_accounts + u2.num_lib_accounts,
        u1.num_lib_refs + u2.num_lib_refs,
        u1.num_cons_accounts + u2.num_cons_accounts,
        u1.num_cons_refs + u2.num_cons_refs,
        u1.num_refs_ideo_weighted + u2.num_refs_ideo_weighted,
        u1.all_text ++ u2.all_text)
    }
  }

  implicit val political_feature =
    mkCaseWireFormat(PoliticalFeature.apply _, PoliticalFeature.unapply _)

  class FindPoliticalDriver(opts: FindPoliticalParams)
      extends FindPoliticalAction(opts) {
    val operation_category = "Driver"

    /**
     * Read the set of ideological accounts.  Create a "Politico" object for
     * each such account, and return a map from a normalized (lowercased)
     * version of each account to the corresponding Politico object (which
     * may refer to multiple accounts).
     */
    def read_ideological_accounts(filename: String) = {
      val politico =
        """^([^ .]+)\. (.*?), (.*?) (-+ |(?:@[^ ]+ )+)([RDI?]) \((.*)\)$""".r
      val all_accounts =
        // Open the file and read line by line.
        for ((line, lineind) <- io.localfh.openr(filename).zipWithIndex
             // Skip comments and blank lines
             if !line.startsWith("#") && !(line.trim.length == 0)) yield {
          lineno = lineind + 1
          line match {
            // Match the line.
            case politico(title, last, first, accountstr, party, where) => {
              // Compute the list of normalized accounts.
              val accounts =
                if (accountstr.startsWith("-")) Seq[String]()
                // `tail` removes the leading @; lowercase to normalize
                else accountstr.split(" ").map(_.tail.toLowerCase).toSeq
              val obj = Politico(last, first, title, party, where, accounts)
              for (account <- accounts) yield (account, obj)
            }
            case _ => {
              warning(line, "Unable to match")
              Seq[(String, Politico)]()
            }
          }
        }
      lineno = 0
      // For each account read in, we generated multiple pairs; flatten and
      // convert to a map.  Reverse because the last of identical keys will end
      // up in the map but we want the first one taken.
      all_accounts.flatten.toSeq.reverse.toMap
    }

    /**
     * Convert map of accounts-&gt;politicos to accounts-&gt;ideology
     */
    def politico_accounts_map_to_ideo_users_map(
        accounts: Map[String, Politico]) = {
      accounts.
        filter { case (string, politico) => "DR".contains(politico.party) }.
        map { case (string, politico) =>
          (string, politico.party match { case "D" => 0.0; case "R" => 1.0 }) }
    }

    /*
     2. We go through users looking for references to these politicians.  For
        users that reference politicians, we can compute an "ideology" score of
        the user by a weighted average of the references by the ideology of
        the politicians.
     3. For each such user, look at all other people referenced -- the idea is
        we want to look for people referenced a lot especially by users with
        a consistent ideology (e.g. Glenn Beck or Rush Limbaugh for
        conservatives), which we can then use to mark others as having a
        given ideology.  For each person, we generate a record with their
        name, the number of times they were referenced and an ideology score
        and merge these all together.
     */
  }

  def create_params(ap: ArgParser) = new FindPoliticalParams(ap)
  val progname = "FindPolitical"

  def run() {
    // For testing
    // errprint("Calling error_wrap ...")
    // error_wrap(1,0) { _ / 0 }
    val opts = init_scoobi_app()
    /*
     We are doing the following:

     1. We are given a list of known politicians, their twitter accounts, and
        their ideology -- either determined simply by their party, or using
        the DW-NOMINATE score or similar.
     2. We go through users looking for references to these politicians.  For
        users that reference politicians, we can compute an "ideology" score of
        the user by a weighted average of the references by the ideology of
        the politicians.
     3. For each such user, look at all other people referenced -- the idea is
        we want to look for people referenced a lot especially by users with
        a consistent ideology (e.g. Glenn Beck or Rush Limbaugh for
        conservatives), which we can then use to mark others as having a
        given ideology.  For each person, we generate a record with their
        name, the number of times they were referenced and an ideology score
        and merge these all together.
     */
    val ptp = new FindPoliticalDriver(opts)
    val filehand = new HadoopFileHandler(configuration)
    if (opts.corpus_name == null) {
      val (_, tail) = filehand.split_filename(opts.input)
      opts.corpus_name = tail.replace("*", "_")
    }
    var accounts: Map[String, Double] =
      if (opts.political_twitter_accounts_format == "officeholders") {
        val politico_accounts =
          ptp.read_ideological_accounts(opts.political_twitter_accounts)
        ptp.politico_accounts_map_to_ideo_users_map(politico_accounts)
      }
      else {
        val rows =
          TextDB.read_textdb(filehand, opts.political_twitter_accounts)
        (for (row <- rows) yield {
          val user = row.gets("user")
          val ideology = row.get[Double]("ideology")
          (user.toLowerCase, ideology)
        }).toMap
      }
    // errprint("Accounts: %s", accounts)

    opts.schema = Schema.read_schema_from_textdb(filehand, opts.input)

    def output_directory_for_corpus_type(corpus_type: String) =
      opts.output + "-" + corpus_type

    /**
     * For the given sequence of lines and related info for writing output a
     * corpus, return a tuple of two thunks: One for persisting the data,
     * the other for fixing up the data into a proper corpus.
     */
    def output_lines(lines: DList[String], corpus_type: String,
        fields: Iterable[String]) = {
      val outdir = output_directory_for_corpus_type(corpus_type)
      (TextOutput.toTextFile(lines, outdir), () => {
        rename_output_files(filehand, outdir, opts.corpus_name)
        output_schema_for_corpus_type(corpus_type, fields)
      })
    }

    def output_schema_for_corpus_type(corpus_type: String,
        fields: Iterable[String]) {
      val outdir = output_directory_for_corpus_type(corpus_type)
      val fixed_fields =
        Map("corpus-name" -> opts.corpus_name,
            "generating-app" -> "FindPolitical",
            "corpus-type" -> "twitter-%s".format(corpus_type)) ++
        opts.non_default_params_string.toMap ++
        Map(
          "ideological-ref-type" -> opts.ideological_ref_type,
          "political-feature-type" -> "%s".format(opts.political_feature_type)
        )
      val out_schema = new Schema(fields, fixed_fields)
      out_schema.output_constructed_schema_file(filehand,
        "%s/%s" format (outdir, opts.corpus_name))
    }

    var ideo_users: DList[IdeologicalUser] = null

    val ideo_fact = new IdeologicalUserAction(opts)
    val matching_patterns =
      TextDB.data_file_matching_patterns(filehand, opts.input)
    val lines: DList[String] = TextInput.fromTextFile(matching_patterns: _*)

    errprint("Step 1, pass 0: %s ideological users on input",
      accounts.size)
    for (iter <- 1 to opts.iterations) {
      errprint(
        "Step 1, pass %s: Filter corpus for conservatives/liberals, compute ideology."
        format iter)
      val last_pass = iter == opts.iterations
      ideo_users =
        lines.flatMap(ideo_fact.get_ideological_user(_, accounts, last_pass))
      if (!last_pass) {
        accounts =
          persist(ideo_users.materialize).map(x =>
            (x.user.toLowerCase, x.ideology)).toMap
        errprint("Step 1, pass %s: %s ideological users on input",
          iter, accounts.size)
        errprint("Step 1, pass %s: done." format iter)
      }
    }

    val (ideo_users_persist, ideo_users_fixup) =
      output_lines(ideo_users.map(_.to_row(opts)), "ideo-users",
        ideo_fact.row_fields)
    /* This is a separate function because including it inline in the for loop
       below results in a weird deserialization error. */
    def handle_political_feature_type(ty: String) = {
      errprint("Step 2: Handling feature type '%s' ..." format ty)
      val political_features = ideo_users.
        flatMap(PoliticalFeature.
          get_political_features(ideo_fact, _, ty, opts)).
        groupBy(_.value).
        combine(PoliticalFeature.merge_political_features).
        map(_._2)
      output_lines(political_features.map(_.to_row(opts)),
        "political-features-%s" format ty, PoliticalFeature.row_fields)
    }

    errprint("Step 2: Generate political features.")
    val (ft_persists, ft_fixups) = (
      for (ty <- opts.political_feature_type) yield
        handle_political_feature_type(ty)
    ).unzip
    persist(Seq(ideo_users_persist) ++ ft_persists)
    ideo_users_fixup()
    for (fixup <- ft_fixups)
      fixup()
    errprint("Step 1, pass %s: done." format opts.iterations)
    errprint("Step 2: done.")

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

