package scorex.consensus.mining

import akka.actor.{Actor, Cancellable}
import scorex.app.Application
import scorex.consensus.mining.Miner._
import scorex.network.{Broadcast, SendToRandom}
import scorex.network.Coordinator.AddBlock
import scorex.network.NetworkController.SendToNetwork
import scorex.network.message.Message
import scorex.utils.{NTP, ScorexLogging}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Try}

class Miner(application: Application) extends Actor with ScorexLogging {

  val r = application.basicMessagesSpecsRepo
  import  r._

  private lazy val blockGenerationDelay =
    math.max(application.settings.minerSettings.generationDelay.toMillis, BlockGenerationTimeShift.toMillis) millis

  private implicit lazy val transactionModule = application.transactionModule
  private lazy val consensusModule = application.consensusModule

  private var currentState = Option.empty[Seq[Cancellable]]

  private def accounts = application.wallet.privateKeyAccounts()

  override def receive: Receive = {
    case GuessABlock(rescheduleImmediately) =>
      if (rescheduleImmediately) { cancel() }
      if (currentState.isEmpty) { scheduleBlockGeneration() }

    case GenerateBlock =>

      cancel()

      val blockGenerated = tryToGenerateABlock()

      if (!blockGenerated) {
        scheduleBlockGeneration()
      }

    case Stop => context stop self
  }

  override def postStop(): Unit = { cancel() }

  private def tryToGenerateABlock(): Boolean = Try {
    log.debug("Trying to generate a new block")

    val blocks = application.consensusModule.generateNextBlocks(accounts)(application.transactionModule)
    if (blocks.nonEmpty) {
      blocks.foreach { newBlock =>
        println(s"!! broadcast block ${newBlock.encodedId}")
        application.networkController ! SendToNetwork(Message(BlockMessageSpec, Right(newBlock), None), SendToRandom)
      }
      application.coordinator ! AddBlock(blocks.last, None)
      true
    } else false
  } recoverWith { case e =>
      log.warn(s"Failed to generate new block: ${e.getMessage}")
      Failure(e)
  } getOrElse false

  protected def preciseTime: Long = NTP.correctedTime()

  private def scheduleBlockGeneration(): Unit = try {
    val schedule = if (application.settings.minerSettings.tfLikeScheduling) {
      val lastBlock = application.history.lastBlock
      val currentTime = preciseTime

      accounts
        .flatMap(acc => consensusModule.nextBlockGenerationTime(lastBlock, acc)(application.transactionModule).map(_ + BlockGenerationTimeShift.toMillis))
        .map(t => math.max(t - currentTime, blockGenerationDelay.toMillis))
        .filter(_ < MaxBlockGenerationDelay.toMillis)
        .map(_ millis)
        .distinct.sorted
    } else Seq.empty

    val tasks = if (schedule.isEmpty) {
      log.debug(s"Next block generation will start in $blockGenerationDelay")
      setSchedule(Seq(blockGenerationDelay))
    } else {
      val firstN = 3
      log.info(s"Block generation schedule: ${schedule.take(firstN).mkString(", ")}...")
      setSchedule(schedule)
    }

    currentState = Some(tasks)
  } catch {
    case e: UnsupportedOperationException =>
      log.debug(s"DB can't find last block because of unexpected modification")
  }

  private def cancel(): Unit = {
    currentState.toSeq.flatten.foreach(_.cancel())
    currentState = None
  }

  private def setSchedule(schedule: Seq[FiniteDuration]): Seq[Cancellable] = {
    val repeatIfNotDeliveredInterval = 10.seconds
    val systemScheduler = context.system.scheduler

    schedule.map { t => systemScheduler.schedule(t, repeatIfNotDeliveredInterval, self, GenerateBlock) }
  }
}

object Miner {

  case class GuessABlock(rescheduleImmediately: Boolean)

  case object Stop

  private case object GenerateBlock

  private[mining] val BlockGenerationTimeShift = 1 second

  private[mining] val MaxBlockGenerationDelay = 1 hour
}
