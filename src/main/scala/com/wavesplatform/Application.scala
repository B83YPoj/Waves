package com.wavesplatform

import akka.actor.{ActorSystem, Props}
import com.wavesplatform.actor.RootActorSystem
import com.wavesplatform.consensus.WavesConsensusModule
import com.wavesplatform.http.NodeApiRoute
import com.wavesplatform.settings._
import scorex.account.AddressScheme
import scorex.api.http._
import scorex.app.ApplicationVersion
import scorex.consensus.nxt.api.http.NxtConsensusApiRoute
import scorex.crypto.encode.Base58
import scorex.network.{TransactionalMessagesRepo, UnconfirmedPoolSynchronizer}
import scorex.transaction.assets.{IssueTransaction, TransferTransaction}
import scorex.transaction.state.wallet.{IssueRequest, TransferRequest}
import scorex.utils.ScorexLogging
import scorex.waves.http.{DebugApiRoute, WavesApiRoute}
import scorex.waves.transaction.WavesTransactionModule

import scala.reflect.runtime.universe._
import scala.util.{Failure, Random}

class Application(as: ActorSystem, appSettings: WavesSettings) extends {
  override implicit val settings = appSettings
  override val applicationName = "waves"
  override val appVersion = {
    val parts = Constants.VersionString.split("\\.")
    ApplicationVersion(parts(0).toInt, parts(1).toInt, parts(2).split("-").head.toInt)
  }
  override implicit val actorSystem = as
} with scorex.app.RunnableApplication {

  override implicit lazy val consensusModule = new WavesConsensusModule()

  override implicit lazy val transactionModule = new WavesTransactionModule()(settings, this, settings.chainParams)

  override lazy val blockStorage = transactionModule.blockStorage

  lazy val consensusApiRoute = new NxtConsensusApiRoute(this)

  override lazy val apiRoutes = Seq(
    BlocksApiRoute(this),
    TransactionsApiRoute(this),
    consensusApiRoute,
    WalletApiRoute(this),
    PaymentApiRoute(this),
    UtilsApiRoute(this),
    PeersApiRoute(this),
    AddressApiRoute(this),
    DebugApiRoute(this),
    WavesApiRoute(this),
    AssetsApiRoute(this),
    NodeApiRoute(this)
  )

  override lazy val apiTypes = Seq(
    typeOf[BlocksApiRoute],
    typeOf[TransactionsApiRoute],
    typeOf[NxtConsensusApiRoute],
    typeOf[WalletApiRoute],
    typeOf[PaymentApiRoute],
    typeOf[UtilsApiRoute],
    typeOf[PeersApiRoute],
    typeOf[AddressApiRoute],
    typeOf[DebugApiRoute],
    typeOf[WavesApiRoute],
    typeOf[AssetsApiRoute],
    typeOf[NodeApiRoute]
  )

  override lazy val additionalMessageSpecs = TransactionalMessagesRepo.specs

  //checks
  require(transactionModule.balancesSupport)
  require(transactionModule.accountWatchingSupport)

  actorSystem.actorOf(Props(classOf[UnconfirmedPoolSynchronizer], transactionModule, settings, networkController))
}

object Application extends ScorexLogging {
  def main(args: Array[String]): Unit =
    RootActorSystem.start("wavesplatform") { actorSystem =>
      //TODO: gagarin55-change to info cuz default log level is info
      log.debug("Starting with args: {} ", args)
      val filename = args.headOption.getOrElse("settings.json")
      val settings = new WavesSettings(filename)

      configureLogging(settings)

      // Initialize global var with actual address scheme
      AddressScheme.current = settings.chainParams.addressScheme

      log.info(s"${Constants.AgentName} Blockchain Id: ${settings.chainParams.addressScheme.chainId}")

      val application = new Application(actorSystem, settings)
      application.run()

      if (application.wallet.privateKeyAccounts().isEmpty)
        application.wallet.generateNewAccounts(1)

      testScript()

      def testScript(): Unit = scala.util.Try {
        val wallet = application.wallet
        val sender = wallet.privateKeyAccounts().head
        println("Test script started")

        (1L to Int.MaxValue) foreach { i =>
          val issue = genIssue()
          println(issue)

          (1 to 10) foreach { j =>
            (1 to 102) foreach { k =>
              val assetId = if (Random.nextBoolean()) Some(issue.assetId) else None
              val feeAsset = if (Random.nextBoolean()) Some(issue.assetId) else None
              scala.util.Try {
                println(genTransfer(assetId, feeAsset))
              }
            }
            Thread.sleep(1000)
          }
        }

        def recipient = application.consensusModule.generators(application.blockStorage.history.lastBlock).head

        def genIssue(): IssueTransaction = {
          val issue = IssueRequest(sender.address, Base58.encode(Array[Byte](1, 1, 1, 1, 1)),
            Base58.encode(Array[Byte](1, 1, 1, 2)), Random.nextInt(Int.MaxValue - 10) + 1, 2, true, 100000000)
          application.transactionModule.issueAsset(issue, wallet).get
        }

        def genTransfer(assetId: Option[Array[Byte]], feeAsset: Option[Array[Byte]]): TransferTransaction = {
          val r: TransferRequest = TransferRequest(assetId.map(Base58.encode), feeAsset.map(Base58.encode),
            Random.nextInt(Int.MaxValue), Random.nextInt(10) + 1, sender.address, Base58.encode(Array(1: Byte)),
            recipient.address)

          application.transactionModule.transferAsset(r, wallet).get
        }


      }.recoverWith {
        case e =>
          e.printStackTrace()
          Failure(e)
      }

    }


  /**
    * Configure logback logging level according to settings
    */
  private def configureLogging(settings: WavesSettings) = {
    import ch.qos.logback.classic.{Level, LoggerContext}
    import org.slf4j._

    val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME)
    settings.loggingLevel match {
      case "info" => rootLogger.setLevel(Level.INFO)
      case "debug" => rootLogger.setLevel(Level.DEBUG)
      case "error" => rootLogger.setLevel(Level.ERROR)
      case "warn" => rootLogger.setLevel(Level.WARN)
      case "trace" => rootLogger.setLevel(Level.TRACE)
      case _ =>
        log.warn(s"Unknown loggingLevel = ${settings.loggingLevel}. Going to set INFO level")
        rootLogger.setLevel(Level.INFO)
    }
  }
}
