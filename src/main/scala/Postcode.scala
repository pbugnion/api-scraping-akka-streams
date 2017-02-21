
object Postcode {
  def normalize(postcode: String): String =
    postcode.toLowerCase.replace(" ", "")
}
