
import scala.io
import scala.util.Try

import java.nio.file.Path
import java.io.FileNotFoundException

import play.api.libs.json.Json

object ExistingPostcodes {
  def load(path: Path): Set[String] = {
    Try { io.Source.fromFile(path.toFile) }
      .map {
        _.getLines
          .map { Json.parse }
          .map { json => (json \ "postcode").as[String] }
          .toSet
      }
      .recover {
        case t: FileNotFoundException => Set.empty[String]
      }
      .get
  }

}
