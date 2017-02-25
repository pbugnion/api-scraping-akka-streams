
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.NotUsed

import play.api.libs.ws.ahc.AhcWSClient

object Sprint1 extends App {

  // Instantiate an actor system and materializer
  implicit val system = ActorSystem("Sprint1")
  import system.dispatcher // provides an execution context
  implicit val materializer = ActorMaterializer()

  // We need a web service client for querying the API
  implicit val ws = AhcWSClient()

  val parallelismLevel = 8 // Number of concurrent threads to use to query the Yelp API

  val postcodes: List[String] = PostcodeLoader.load()  // Load the list of postcodes to query

  // Define a source of restaurant arrays
  val postcodeResponses: Source[PostcodeRestaurants, NotUsed] =
    Source(postcodes.take(100))
      // Query the API for a postcode
      .mapAsync(parallelismLevel) { postcode =>
        YelpApi.fetchPostcode(postcode).map { response => (postcode -> response) }
      }
      .filter { case (postcode, response) => response.status == 200 }
      // extract the restaurants in that postcode
      .map { case(postcode, response) =>
        val restaurants = YelpApi.parseSuccessfulResponse(postcode, response)
        PostcodeRestaurants(postcode, restaurants)
      }

  postcodeResponses.runForeach {
    case PostcodeRestaurants(postcode, restaurants) => println(restaurants)
  }

  Thread.sleep(10000) // give the stream time to run

  // clean up
  ws.close()
  materializer.shutdown()
  Await.ready(system.terminate(), 5.seconds)
}
