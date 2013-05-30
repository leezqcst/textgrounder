///////////////////////////////////////////////////////////////////////////////
//  SphereCell.scala
//
//  Copyright (C) 2011 Ben Wing, The University of Texas at Austin
//  Copyright (C) 2012 Stephen Roller, The University of Texas at Austin
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

import util.distances._

import gridlocate.{GeoCell,GeoGrid}

/////////////////////////////////////////////////////////////////////////////
//                             Cells in a grid                             //
/////////////////////////////////////////////////////////////////////////////

abstract class KMLSphereCell(
  grid: SphereGrid
) extends SphereCell(grid) {
  /**
   * Generate KML for a single cell.
   */
  def generate_kml(xfprob: Double, xf_minprob: Double, xf_maxprob: Double,
    params: KMLParameters): Iterable[xml.Elem]
}

/**
 * A cell in a polygonal shape.
 *
 * @param grid The Grid object for the grid this cell is in.
 */
abstract class PolygonalCell(
  grid: SphereGrid
) extends KMLSphereCell(grid) {
  /**
   * Return the boundary of the cell as an Iterable of coordinates, tracing
   * out the boundary vertex by vertex.  There is no need to duplicate the
   * first coordinate as the last one.
   */
  def get_boundary: Iterable[SphereCoord]

  /**
   * Specify the boundary of the polygon as a string.
   */
  def describe_boundary = get_boundary.map(_.toString).mkString(":")

  /**
   * Return the "inner boundary" -- something echoing the actual boundary of the
   * cell but with smaller dimensions.  Used for outputting KML to make the
   * output easier to read.
   */
  def get_inner_boundary = {
    val center = get_true_center
    for (coord <- get_boundary)
      yield SphereCoord((center.lat + coord.lat) / 2.0,
                  average_longitudes(center.long, coord.long))
  }

  /**
   * Generate the KML placemark for the cell's name.  Currently it's rectangular
   * for rectangular cells.  FIXME: Perhaps it should be generalized so it doesn't
   * need to be redefined for differently-shaped cells.
   *
   * @param name The name to display in the placemark
   */
  def generate_kml_name_placemark(name: String): xml.Elem

  def generate_kml(xfprob: Double, xf_minprob: Double, xf_maxprob: Double,
      params: KMLParameters) = {
    val offprob = xfprob - xf_minprob
    val fracprob = offprob / (xf_maxprob - xf_minprob)
    val boundary = get_inner_boundary
    val first = boundary.head
    val coordtext = "\n" + (
      for (coord <- boundary ++ Iterable(first)) yield (
        "%s,%s,%s" format (
          coord.long, coord.lat, fracprob * params.kml_max_height)
        )).mkString("\n") + "\n"
    val name =
      if (most_popular_document != null) most_popular_document.title
      else ""

    // Placemark indicating name
    val name_placemark = generate_kml_name_placemark(name)

    // Interpolate colors
    val color = Array(0.0, 0.0, 0.0)
    for (i <- 0 until 3) {
      color(i) = (params.kml_mincolor(i) +
        fracprob * (params.kml_maxcolor(i) - params.kml_mincolor(i)))
    }
    // Original color dc0155ff
    //rgbcolor = "dc0155ff"
    val revcol = color.reverse
    val rgbcolor = "ff%02x%02x%02x" format (
      revcol(0).toInt, revcol(1).toInt, revcol(2).toInt)

    // Yield cylinder indicating probability by height and color

    // !!PY2SCALA: BEGIN_PASSTHRU
    val cylinder_placemark =
      <Placemark>
        <name>{ "%s POLYGON" format name }</name>
        <styleUrl>#bar</styleUrl>
        <Style>
          <PolyStyle>
            <color>{ rgbcolor }</color>
            <colorMode>normal</colorMode>
          </PolyStyle>
        </Style>
        <Polygon>
          <extrude>1</extrude>
          <tessellate>1</tessellate>
          <altitudeMode>relativeToGround</altitudeMode>
          <outerBoundaryIs>
            <LinearRing>
              <coordinates>{ coordtext }</coordinates>
            </LinearRing>
          </outerBoundaryIs>
        </Polygon>
      </Placemark>
    // !!PY2SCALA: END_PASSTHRU
    if (params.kml_include_cell_names)
      Seq(name_placemark, cylinder_placemark)
    else
      Seq(cylinder_placemark)
  }
}

/**
 * A cell in a rectangular shape.
 *
 * @param grid The Grid object for the grid this cell is in.
 */
abstract class RectangularCell(
  grid: SphereGrid
) extends PolygonalCell(grid) {
  /**
   * Return the coordinate of the southwest point of the rectangle.
   */
  def get_southwest_coord: SphereCoord
  /**
   * Return the coordinate of the northeast point of the rectangle.
   */
  def get_northeast_coord: SphereCoord

  def describe_location = {
    "%s:%s" format (get_southwest_coord, get_northeast_coord)
  }

  override def get_true_center = {
    val sw = get_southwest_coord
    val ne = get_northeast_coord
    SphereCoord((sw.lat + ne.lat) / 2.0, (sw.long + ne.long) / 2.0)
  }

  /**
   * Define the center based on the southwest and northeast points,
   * or based on the centroid of the cell.
   */
  val centroid = new Array[Double](2)
  var num_docs: Int = 0

  def get_central_point = {
    if (num_docs == 0 ||
      get_sphere_docfact(grid).driver.params.center_method == "center") {
      get_true_center
      // use the actual cell center
      // also, if we have an empty cell, there is no such thing as
      // a centroid, so default to the center
    } else {
      // use the centroid
      SphereCoord(centroid(0) / num_docs, centroid(1) / num_docs)
    }
  }

  override def add_document(document: SphereDoc) {
    num_docs += 1
    centroid(0) += document.coord.lat
    centroid(1) += document.coord.long
    super.add_document(document)
  }



  /**
   * Define the boundary given the specified southwest and northeast
   * points.
   */
  def get_boundary = {
    val sw = get_southwest_coord
    val ne = get_northeast_coord
    val center = get_true_center
    val nw = SphereCoord(ne.lat, sw.long)
    val se = SphereCoord(sw.lat, ne.long)
    Seq(sw, nw, ne, se)
  }

  /**
   * Generate the name placemark as a smaller rectangle within the
   * larger rectangle. (FIXME: Currently it is exactly the size of
   * the inner boundary.  Perhaps this should be generalized, so
   * that the definition of this function can be handled up at the
   * polygonal-shaped-cell level.)
   */
  def generate_kml_name_placemark(name: String) = {
    val sw = get_southwest_coord
    val ne = get_northeast_coord
    val center = get_true_center
    // !!PY2SCALA: BEGIN_PASSTHRU
    // Because it tries to frob the # sign
    <Placemark>
      <name>{ name }</name>
      ,
      <Cell>
        <LatLonAltBox>
          <north>{ ((center.lat + ne.lat) / 2).toString }</north>
          <south>{ ((center.lat + sw.lat) / 2).toString }</south>
          <east>{ ((center.long + ne.long) / 2).toString }</east>
          <west>{ ((center.long + sw.long) / 2).toString }</west>
        </LatLonAltBox>
        <Lod>
          <minLodPixels>16</minLodPixels>
        </Lod>
      </Cell>
      <styleURL>#bar</styleURL>
      <Point>
        <coordinates>{ "%s,%s" format (center.long, center.lat) }</coordinates>
      </Point>
    </Placemark>
    // !!PY2SCALA: END_PASSTHRU
  }
}

