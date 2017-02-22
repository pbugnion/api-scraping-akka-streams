
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import java.nio.file.{Paths, StandardOpenOption}
import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.NotUsed
import akka.util.ByteString

import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.ahc.AhcWSClient
import play.api.Logger

object Sprint3 extends App {

  // Instantiate an actor system and materializer
  implicit val system = ActorSystem("Sprint3")
  val log = Logger("Sprint3")
  import system.dispatcher // provides an execution context
  implicit val materializer = ActorMaterializer()

  // We need a web service client for querying the API
  implicit val ws = AhcWSClient()

  val outputPath = Paths.get("postcode_restaurants.json")
  val parallelismLevel = 8 // Number of concurrent threads to use to query the Yelp API

  def serializePostcodeRestaurant(postcodeRestaurants: PostcodeRestaurants): JsObject =
    Json.obj(
      "postcode" -> postcodeRestaurants.postcode,
      "fetch_time" -> LocalDateTime.now.toString,
      "data" -> postcodeRestaurants.restaurants
    )

  val postcodeResponseSerializer: Sink[PostcodeRestaurants, Future[IOResult]] = {
    val outputOpenOptions = Set(
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.APPEND
    )
    Flow[PostcodeRestaurants]
      .map { serializePostcodeRestaurant }
      .map { json => ByteString(Json.stringify(json) + '\n') }
      .toMat(FileIO.toPath(outputPath, outputOpenOptions))(Keep.right)
  }

  // Load the list of postcodes to query
  val allPostcodes: List[String] = PostcodeLoader.load()
  log.info(s"Found ${allPostcodes.size} unique postcodes.")

  // Load the list of postcodes we have already processed
  val donePostcodes: Set[String] = ExistingPostcodes.load(outputPath)
  log.info(s"Found ${donePostcodes.size} already processed.")

  // Filter the list of postcodes
  val remainingPostcodes = allPostcodes.filterNot { donePostcodes }
  log.info(s"There are ${remainingPostcodes.size} still to do.")

  // Define a source of restaurant arrays
  val postcodeResponses: Source[PostcodeRestaurants, NotUsed] =
    Source(remainingPostcodes.take(1000))
  // Query the API for a postcode
      .mapAsync(8) { postcode =>
        YelpApi.fetchPostcode(postcode).map { response => (postcode -> response) }
      }
      .filter { case (postcode, response) => response.status == 200 }
  // extract the restaurants in that postcode
      .map { case(postcode, response) =>
        val restaurants = YelpApi.parseSuccessfulResponse(postcode, response)
        PostcodeRestaurants(postcode, restaurants)
      }

  val ioResultFuture = postcodeResponses.runWith(postcodeResponseSerializer)

  val ioResult = Await.result(ioResultFuture, 600.seconds)
  println(s"Written ${ioResult.count} bytes to $outputPath")

  // clean up
  ws.close()
  materializer.shutdown()
  Await.ready(system.terminate(), 5.seconds)
}
