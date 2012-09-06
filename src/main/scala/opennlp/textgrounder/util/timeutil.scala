///////////////////////////////////////////////////////////////////////////////
//  timeutil.scala
//
//  Copyright (C) 2011 Ben Wing, The University of Texas at Austin
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

package opennlp.textgrounder.util

import math._

package object timeutil {

  def format_minutes_seconds(seconds: Double, hours: Boolean = true) = {
    var secs = seconds
    var mins = (secs / 60).toInt
    secs = secs % 60
    val hourstr = {
      if (!hours) ""
      else {
        val hours = (mins / 60).toInt
        mins = mins % 60
        if (hours > 0) "%s hour%s " format (hours, if (hours == 1) "" else "s")
        else ""
      }
    }
    val secstr = (if (secs.toInt == secs) "%s" else "%1.1f") format secs
    "%s%s minute%s %s second%s" format (
        hourstr,
        mins, if (mins == 1) "" else "s",
        secstr, if (secs == 1) "" else "s")
  }

  /**
   * Parse a date and return a time as milliseconds since the Epoch
   * (Jan 1, 1970).  Accepts various formats, all variations of the
   * following:
   *
   * 20100802180502PST (= August 2, 2010, 18:05:02 Pacific Standard Time)
   * 20100802060502pmPST (= same)
   * 20100802100502pm (= same if current time zone is Eastern Daylight)
   *
   * That is, either 12-hour or 24-hour time can be given, and the time
   * zone can be omitted.  In addition, part or all of the time of day
   * (hours, minutes, seconds) can be omitted.  Years must always be
   * full (i.e. 4 digits).
   */
  def parse_date(datestr: String): Option[Long] = {
    // Variants for the hour-minute-second portion
    val hms_variants = List("", "HH", "HHmm", "HHmmss", "hhaa", "hhmmaa",
      "hhmmssaa")
    // Fully-specified format including date
    val full_fmt = hms_variants.map("yyyyMMdd"+_)
    // All formats, including variants with time zone specified
    val all_fmt = full_fmt ++ full_fmt.map(_+"zz")
    for (fmt <- all_fmt) {
      val pos = new java.text.ParsePosition(0)
      val formatter = new java.text.SimpleDateFormat(fmt)
      // (Possibly we shouldn't do this?) This rejects nonstandardness, e.g.
      // out-of-range values such as month 13 or hour 25; that's useful for
      // error-checking in case someone messed up entering the date.
      formatter.setLenient(false)
      val date = formatter.parse(datestr, pos)
      if (date != null && pos.getIndex == datestr.length)
        return Some(date.getTime)
    }
    None
  }

  /**
   * Parse a length-of-time specification, e.g. "5h" for 5 hours or "3m2s" for
   * "3 minutes 2 seconds".  Return number of milliseconds.
   *
   * Return Long.MinValue if can't parse.
   */
  def parse_time_length(offset: String): Long = {
    // FIXME: Write this!
    offset.toInt * 1000
  }

  /**
   * Parse a date and length of time into an interval.  Return tuple of
   * (start, end) if able to parse, else return None along with an error
   * message.
   */
  def parse_date_interval(str: String): (Option[(Long, Long)], String) = {
    val date_length = str.split("/")
    if (date_length.length != 2)
      (None, "Time chunk %s must be of the format 'START/LENGTH'"
        format str)
    else {
      val Array(datestr, lengthstr) = date_length
      parse_date(datestr) match {
        case Some(date) =>
          (Some((date, date + parse_time_length(lengthstr))), "")
        case None =>
          (None,
            "Can't parse time '%s'; should be something like 201008021805pm"
            format datestr)
      }
    }
  }
}

