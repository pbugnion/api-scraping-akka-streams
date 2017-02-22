import play.api.libs.json.JsObject

// case class for all the restaurants in one postcode
final case class PostcodeRestaurants(postcode: String, restaurants: List[JsObject])
