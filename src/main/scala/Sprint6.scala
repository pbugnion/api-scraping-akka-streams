
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import java.nio.file.{Paths, StandardOpenOption}
import java.time.LocalDateTime

import akka.actor.{ActorSystem, ActorRef}
import akka.stream._
import akka.stream.scaladsl._
import akka.NotUsed
import akka.util.{ByteString, Timeout}
import akka.pattern.ask

import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse
import play.api.libs.ws.ahc.AhcWSClient
import play.api.Logger

object Sprint6 extends App {

  // Instantiate an actor system and materializer
  implicit val system = ActorSystem("Sprint6")
  val log = Logger("Sprint6")
  import system.dispatcher // provides an execution context
  implicit val materializer = ActorMaterializer()

  // We need a web service client for querying the API
  implicit val ws = AhcWSClient()

  val outputPath = Paths.get("postcode_restaurants.json")
  val parallelismLevel = 8 // Number of concurrent threads to use to query the Yelp API
  val maxErrors = 10 // Stop the stream after seeing this many error codes

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

  val errorLimiter: Flow[(String, WSResponse), (String, WSResponse), NotUsed] =
    Flow[(String, WSResponse)].limitWeighted(maxErrors.toLong) {
      case (postcode, response) =>
        if (response.status == 200 || response.status == 429) 0 else 1
    }

  val errorLogger: Flow[(String, WSResponse), (String, WSResponse), NotUsed] =
    Flow[(String, WSResponse)].map { case (postcode, response) =>
      if (response.status != 200) {
        log.warn(s"Non 200 response for postcode $postcode: [status: ${response.status}, body: ${response.body}]")
      }
      (postcode -> response)
    }

  def apiQuerier(parallelismLevel: Int, throttler: ActorRef)
      : Flow[String, (String, WSResponse), NotUsed] =
    Flow[String].mapAsync(parallelismLevel) { postcode =>
      import system.dispatcher
      implicit val throttledTimeout = Timeout(2.hours)
      for {
        _ <- throttler ? Throttler.WantToPass
        response <- YelpApi.fetchPostcode(postcode)
      } yield (postcode -> response)
    }

  def throttlerNotifier(throttler: ActorRef)
      : Flow[(String, WSResponse), (String, WSResponse), NotUsed] = {
    Flow[(String, WSResponse)].map { case (postcode, response) =>
      if (response.status == 429) {
        throttler ! Throttler.RequestLimitExceeded
      }
      postcode -> response
    }
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

  val throttler = system.actorOf(Throttler.props(log))

  // Define a source of restaurant arrays
  val postcodeResponses: Source[PostcodeRestaurants, NotUsed] =
    Source(remainingPostcodes.take(30000))
      .via(apiQuerier(parallelismLevel, throttler))
      .via(throttlerNotifier(throttler))
      .via(StreamMonitor.monitor(1000) { count => log.info(s"Processed $count restaurants") })
      .via(errorLogger)
      .via(errorLimiter)
      .filter { case (postcode, response) => response.status == 200 }
      .map { case(postcode, response) =>
        val restaurants = YelpApi.parseSuccessfulResponse(postcode, response)
        PostcodeRestaurants(postcode, restaurants)
      }

  val ioResultFuture = postcodeResponses.runWith(postcodeResponseSerializer)

  val ioResult = Await.result(ioResultFuture, 2.hours)
  println(s"Written ${ioResult.count} bytes to $outputPath")

  // clean up
  ws.close()
  materializer.shutdown()
  Await.ready(system.terminate(), 5.seconds)
}
