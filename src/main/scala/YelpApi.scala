
import scala.concurrent.Future

import play.api.libs.json.JsObject
import play.api.libs.ws.{WSClient, WSResponse}

object YelpApi {

  private val token = sys.env.getOrElse(
    "YELP_TOKEN", throw new IllegalStateException("Missing YELP_TOKEN environment variable"))
  private val url = "https://api.yelp.com/v3/businesses/search"

  def fetchPostcode(postcode: String)(implicit ws: WSClient)
      : Future[WSResponse] = {
    val responseFuture = ws.url(url)
      .withQueryString(
        "location" -> postcode, "limit" -> "50", "sort_by" -> "distance")
      .withHeaders("Authorization" -> s"Bearer $token")
      .get()

    responseFuture
  }

  def parseSuccessfulResponse(postcode: String, response: WSResponse)
      : List[JsObject] = {
    val businesses = (response.json \ "businesses").as[List[JsObject]]
    businesses.filter { business =>
      val businessPostcode = (business \ "location" \ "zip_code").as[String]
      Postcode.normalize(businessPostcode) ==  Postcode.normalize(postcode)
    }
  }
}
