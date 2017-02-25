
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

object Sprint7 extends App {

  // Instantiate an actor system and materializer
  implicit val system = ActorSystem("Sprint7")
  val log = Logger("Sprint7")
  implicit val materializer = ActorMaterializer()

  // We need a web service client for querying the API
  implicit val ws = AhcWSClient()

  val outputPath = Paths.get("postcode_restaurants.json")
  val parallelismLevel = 2 // Number of concurrent threads to use to query the Yelp API

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

  // flow element that partitions according to the response status
  val responsePartitioner = Partition[(String, WSResponse)](2, s => s._2.status match {
    case 200 => 0
    case 429 => 1
    case status => println(s"bad status: $status") ; throw new IllegalStateException("bad")
  })

  def errorFilter(log: Logger) = Flow[(String, WSResponse)].filter {
    case (postcode, response) =>
      response.status match {
        case 200 | 429 => true
        case _ =>
          log.warn(
            s"Unexpected response for postcode $postcode: "+
              s"[status: ${response.status}, body: ${response.body}]")
          false
      }
  }

  def fetcher(
    parallelismLevel: Int,
    log: Logger,
    throttler: ActorRef
  ): Flow[String, (String, WSResponse), NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val postcodeSource = builder.add(Merge[String](2))
        val partitioner = builder.add(responsePartitioner)

        // normal flow: fetch postcodes and pass them to partitioner
        postcodeSource ~>
          apiQuerier(parallelismLevel, throttler) ~>
          errorFilter(log) ~>
          partitioner

        // reqeueing flow: push postcodes with 429 error codes through a flow that
        // alerts the throttler and then back into the source
        partitioner.out(1) ~>
          throttlerNotifier(throttler) ~>
          Flow[(String, WSResponse)]
            .map { case (postcode, response) => postcode }
            .buffer(1000, OverflowStrategy.fail) ~>
          postcodeSource.in(1)
        FlowShape(postcodeSource.in(0), partitioner.out(0))
      }
  )

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
    Source(remainingPostcodes)
      .via(fetcher(parallelismLevel, log, throttler))
      .via(StreamMonitor.monitor(1000) { count => log.info(s"Processed $count restaurants") })
      .via(errorLogger)
      .filter { case (postcode, response) => response.status == 200 }
      .map { case(postcode, response) =>
        val restaurants = YelpApi.parseSuccessfulResponse(postcode, response)
        PostcodeRestaurants(postcode, restaurants)
      }

  val ioResultFuture = postcodeResponses.runWith(postcodeResponseSerializer)

  val ioResult = Await.result(ioResultFuture, 31.days)
  log.info(s"Written ${ioResult.count} bytes to $outputPath")

  // clean up
  ws.close()
  materializer.shutdown()
  Await.ready(system.terminate(), 5.seconds)
}
