
import scala.collection.immutable
import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props}

import play.api.Logger

object Throttler {
  // messages received
  case object WantToPass
  case object RequestLimitExceeded

  // messages sent by this actor
  case object MayPass

  // messages sent to itself
  case object Open

  def props(log: Logger): Props = Props(classOf[Throttler], log)
}

class Throttler(log: Logger) extends Actor {

  import Throttler._
  import context.dispatcher

  private var waitQueue = immutable.Queue.empty[ActorRef]

  override def receive: Receive = open

  val open: Receive = {
    case WantToPass =>
      sender() ! MayPass
    case RequestLimitExceeded =>
      log.info("Request limit exceeded: throttling")
      context.become(closed)
      sheduleOpen()
  }

  val closed: Receive = {
    case WantToPass =>
      log.info(
        s"Currently throttled: queueing request. " +
          s"Current queue size ${waitQueue.size}")
      waitQueue = waitQueue.enqueue(sender())
    case Open =>
      log.info("Releasing waiting actors")
      context.become(open)
      releaseWaiting()
      log.debug("Actors released")
  }

  private def releaseWaiting(): Unit = {
    waitQueue.foreach { _ ! MayPass }
    waitQueue = immutable.Queue.empty
  }

  private def sheduleOpen(): Unit = {
    context.system.scheduler.scheduleOnce(30.minutes) { self ! Open }
  }
}
