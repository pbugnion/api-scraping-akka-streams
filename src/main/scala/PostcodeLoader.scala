
import scala.io

import play.api.libs.json.Json

object PostcodeLoader {

  val postcodeUrl = "http://data.scala4datascience.com/restaurants/restaurants.json"

  def load(): List[String] = {
    io.Source.fromURL(postcodeUrl).getLines
      .map { Json.parse }
      .map { json => (json \ "postcode").asOpt[String] }
      .collect { case Some(postcode) if postcode.size > 1 => postcode }
      .map { Postcode.normalize }
      .filter { postcode => postcode.forall { _.isLetterOrDigit } } // strip out badly formed postcodes
      .toList
      .sorted
      .distinct
  }

}
