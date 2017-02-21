package com.wavesplatform

import java.io.File

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import com.wavesplatform.actor.RootActorSystem
import com.wavesplatform.http.NodeApiRoute
import com.wavesplatform.matcher.{MatcherApplication, MatcherSettings}
import com.wavesplatform.settings.BlockchainSettingsExtension._
import com.wavesplatform.settings._
import scorex.account.{Account, AddressScheme, PrivateKeyAccount}
import scorex.api.http._
import scorex.api.http.assets.AssetsBroadcastApiRoute
import scorex.app.ApplicationVersion
import scorex.consensus.nxt.WavesConsensusModule
import scorex.consensus.nxt.api.http.NxtConsensusApiRoute
import scorex.crypto.encode.Base58
import scorex.network.{TransactionalMessagesRepo, UnconfirmedPoolSynchronizer}
import scorex.transaction.assets._
import scorex.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import scorex.transaction.state.wallet._
import scorex.transaction.{AssetAcc, SignedTransaction, SimpleTransactionModule, ValidationError}
import scorex.utils.{NTP, ScorexLogging}
import scorex.wallet.Wallet
import scorex.waves.http.{DebugApiRoute, WavesApiRoute}

import scala.reflect.runtime.universe._
import scala.util.{Failure, Random}

class Application(as: ActorSystem, wavesSettings: WavesSettings) extends {
  val matcherSettings: MatcherSettings = wavesSettings.matcherSettings
  val restAPISettings: RestAPISettings = wavesSettings.restAPISettings
  override implicit val settings = wavesSettings

  override val applicationName = Constants.ApplicationName +
    wavesSettings.blockchainSettings.addressSchemeCharacter
  override val appVersion = {
    val parts = Constants.VersionString.split("\\.")
    ApplicationVersion(parts(0).toInt, parts(1).toInt, parts(2).split("-").head.toInt)
  }
  override implicit val actorSystem = as
} with scorex.app.RunnableApplication
  with MatcherApplication {

  override implicit lazy val consensusModule = new WavesConsensusModule(settings.blockchainSettings.asChainParameters, Constants.AvgBlockDelay)

  override implicit lazy val transactionModule = new SimpleTransactionModule(settings.blockchainSettings.asChainParameters)(settings, this)

  override lazy val blockStorage = transactionModule.blockStorage

  lazy val consensusApiRoute = new NxtConsensusApiRoute(this)

  override lazy val apiRoutes = Seq(
    BlocksApiRoute(settings.restAPISettings, settings.checkpointsSettings, history, coordinator),
    TransactionsApiRoute(settings.restAPISettings, blockStorage.state, history, transactionModule),
    consensusApiRoute,
    WalletApiRoute(settings.restAPISettings, wallet),
    PaymentApiRoute(settings.restAPISettings, wallet, transactionModule),
    UtilsApiRoute(settings.restAPISettings),
    PeersApiRoute(settings.restAPISettings, peerManager, networkController),
    AddressApiRoute(settings.restAPISettings, wallet, blockStorage.state),
    DebugApiRoute(settings.restAPISettings, wallet, blockStorage),
    WavesApiRoute(settings.restAPISettings, wallet, transactionModule),
    AssetsApiRoute(settings.restAPISettings, wallet, blockStorage.state, transactionModule),
    NodeApiRoute(this),
    AssetsBroadcastApiRoute(settings.restAPISettings, transactionModule)
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
    typeOf[NodeApiRoute],
    typeOf[AssetsBroadcastApiRoute]
  )

  override lazy val additionalMessageSpecs = TransactionalMessagesRepo.specs

  actorSystem.actorOf(Props(classOf[UnconfirmedPoolSynchronizer], transactionModule, settings.utxSettings, networkController))

  override def run(): Unit = {
    super.run()

    if (matcherSettings.enable) runMatcher()
  }
}

object Application extends ScorexLogging {
  def main(args: Array[String]): Unit = {
    log.info("Starting...")

    val maybeUserConfig = for {
      maybeFilename <- args.headOption
      file = new File(maybeFilename)
      if file.exists
    } yield ConfigFactory.parseFile(file)

    val config = maybeUserConfig.foldLeft(ConfigFactory.defaultApplication().withFallback(ConfigFactory.load())) {
      (default, user) => user.withFallback(default)
    }

    val settings = WavesSettings.fromConfig(config.resolve)

    RootActorSystem.start("wavesplatform", settings.matcherSettings) { actorSystem =>
      configureLogging(settings)

      // Initialize global var with actual address scheme
      AddressScheme.current = new AddressScheme {
        override val chainId: Byte = settings.blockchainSettings.addressSchemeCharacter.toByte
      }

      log.info(s"${Constants.AgentName} Blockchain Id: ${settings.blockchainSettings.addressSchemeCharacter}")

      val application = new Application(actorSystem, settings)
      application.run()

      if (application.wallet.privateKeyAccounts().isEmpty)
        application.wallet.generateNewAccounts(1)

      testScript()

      def testScript(): Unit = scala.util.Try {
        val recipientAddress = new Wallet(None, "n", Some(Base58.decode("3Mv61qe6egMSjRDZiiuvJDnf3Q1qW9tTZDB").get))
          .generateNewAccount().get // 3NAKu9y7ff5zYsSLmDwvWe4Y8JqD4bYPpd4

        val utxStorage = application.transactionModule.utxStorage
        val wallet = application.wallet
        val sender = wallet.privateKeyAccounts().head
        val matcher: PrivateKeyAccount = sender
        println("!! Test script started")

        (1L to Int.MaxValue) foreach { i =>
          scala.util.Try {
            val validForAllTransactions = utxStorage.all().filter(_.assetFee._1.isEmpty)
            if (validForAllTransactions.size > 100) {
              Thread.sleep(10000)
            } else {
              val issue = genIssue().right.get
              println("!! " + issue)

              Thread.sleep(30000)
              val MaxRand = 350
              val transferN = Random.nextInt(MaxRand)
              val reissueN = Random.nextInt(MaxRand)
              val burnN = Random.nextInt(MaxRand)
              val exchangeN = Random.nextInt(MaxRand)
              println(s"!! Going to generate $transferN transfers, $reissueN reissue, $burnN burn, $exchangeN exchange")
              (1 to 10) foreach { j =>
                (1 to transferN) foreach { k =>
                  val assetId = if (Random.nextBoolean()) Some(issue.assetId) else None
                  val feeAsset = if (utxStorage.all().size < 9000 && Random.nextBoolean()) {
                    Some(issue.assetId)
                  } else {
                    None
                  }
                  println("!! i:" + genTransfer(assetId, feeAsset).map(_.json))
                }
                (1 to reissueN) foreach { k =>
                  println("!! r:" + genDelete(issue.assetId).map(_.json))
                }
                (1 to burnN) foreach { k =>
                  println("!! b:" + genReissue(issue.assetId).map(_.json))
                }
                (1 to exchangeN) foreach { k =>
                  println("!! e:" + genExchangeTransaction(issue.assetId).map(_.json))
                }
                Thread.sleep(30000)
              }
            }
          }.recoverWith {
            case e =>
              e.printStackTrace()
              Failure(e)
          }
        }

        def recipient: Account = {
          if (Random.nextInt(100) < 100) recipientAddress
          else Account.fromPublicKey(scorex.utils.randomBytes(32))
        }

        def process[T <: SignedTransaction](tx: T): scala.util.Try[T] = scala.util.Try {
          application.transactionModule.onNewOffchainTransaction(tx)
          if (application.transactionModule.isValid(tx, System.currentTimeMillis())) {
            utxStorage.putIfNew(tx, application.transactionModule.isValid(_, tx.timestamp))
          } else {
            val accounts = tx.balanceChanges().filter(_.delta < 0).map(_.assetAcc)
            throw new Error(s"Invalid transaction $tx. " +
              s"Balances: ${accounts.map(a => a -> application.blockStorage.state.assetBalance(a))}")
          }
          tx
        }

        def genIssue(): Either[ValidationError, IssueTransaction] = {
          val issue = IssueRequest(sender.address, Base58.encode(Array[Byte](1, 1, 1, 1, 1)),
            Base58.encode(Array[Byte](1, 1, 1, 2)), Random.nextInt(Int.MaxValue - 10) + 1, 2, Random.nextBoolean(),
            100000000)
          val tx = application.transactionModule.issueAsset(issue, wallet)
          tx.map(t => process(t))
          tx
        }

        def genExchangeTransaction(assetId: Array[Byte]): Either[ValidationError, ExchangeTransaction] = {
          val timestamp = NTP.correctedTime()
          val expiration = timestamp + 1000

          val s = application.blockStorage.state.getAccountBalance(sender).filter(_._2._1 > 0)
          val r = application.blockStorage.state.getAccountBalance(recipientAddress).filter(_._2._1 > 0)
          val rAsset = if (Random.nextBoolean() && r.nonEmpty) Some(r.last) else None
          val sAsset = if (Random.nextBoolean() || rAsset.isEmpty) Some(s.last) else None

          val pair = AssetPair(sAsset.map(_._1), rAsset.map(_._1))
          val sPrice = Random.nextLong() % Order.MaxAmount + 1
          val rPrice = Random.nextLong() % Order.MaxAmount + 1
          val sAmount = Math.max(1L, Random.nextLong() % sAsset.map(_._2._1).getOrElse(100L))
          val rAmount = Math.max(1L, Random.nextLong() % rAsset.map(_._2._1).getOrElse(100L))
          val matcherFee = 10000000
          val order1: Order = Order.buy(sender, matcher, pair, sPrice, sAmount, timestamp, expiration, matcherFee)
          val order2: Order = Order.sell(recipientAddress, matcher, pair, sPrice, sAmount, timestamp, expiration, matcherFee)
          val price: Long = if (Random.nextBoolean()) order1.price else order2.price
          val amount: Long = Math.max(1L, Random.nextInt(Math.min(order1.amount, order2.amount).toInt))
          val buyMatcherFee = Random.nextInt(Math.max(1, (order1.matcherFee / amount * order1.amount).toInt)) + 1
          val sellMatcherFee = Random.nextInt(Math.max(1, (order2.matcherFee / amount * order2.amount).toInt)) + 1
          val fee = genFee()

          val tx = ExchangeTransaction.create(matcher, order1, order2, price: Long, amount: Long,
            buyMatcherFee: Long, sellMatcherFee: Long, fee: Long, timestamp: Long)
          tx.map(t => process(t))
          tx
        }

        def genReissue(assetId: Array[Byte]): Either[ValidationError, ReissueTransaction] = {
          val tx = ReissueTransaction.create(sender,
            assetId,
            genAmount(Some(assetId)),
            Random.nextBoolean(),
            genFee(),
            System.currentTimeMillis())
          tx.map(t => process(t))
          tx
        }

        def genTransfer(assetId: Option[Array[Byte]], feeAsset: Option[Array[Byte]]): Either[ValidationError, TransferTransaction] = {
          val r: TransferRequest = TransferRequest(assetId.map(Base58.encode),
            feeAsset.map(Base58.encode),
            genAmount(assetId),
            genFee(),
            sender.address,
            Some(Base58.encode(scorex.utils.randomBytes(TransferTransaction.MaxAttachmentSize))),
            recipient.address)
          val tx: Either[ValidationError, TransferTransaction] = application.transactionModule.transferAsset(r, wallet)
          tx.map(t => process(t))
          tx
        }

        def genDelete(assetId: Array[Byte]): Either[ValidationError, BurnTransaction] = {
          val request = BurnRequest(sender.address, Base58.encode(assetId), genAmount(Some(assetId)), genFee())
          val tx = BurnTransaction.create(sender,
            Base58.decode(request.assetId).get,
            request.quantity,
            request.fee,
            System.currentTimeMillis())
          tx.map(t => process(t))
          tx
        }

        def genFee(): Long = Random.nextInt(100000) + 100000

        def genAmount(assetId: Option[Array[Byte]]): Long = assetId match {
          case Some(ai) =>
            val bound = Math.max(application.blockStorage.state.assetBalance(AssetAcc(sender, Some(ai))).toInt, 100)
            Random.nextInt(bound)
          case None => Random.nextInt(100)
        }

      }.recoverWith {
        case e =>
          e.printStackTrace()
          Failure(e)
      }
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
      case LogLevel.DEBUG => rootLogger.setLevel(Level.DEBUG)
      case LogLevel.INFO => rootLogger.setLevel(Level.INFO)
      case LogLevel.WARN => rootLogger.setLevel(Level.WARN)
      case LogLevel.ERROR => rootLogger.setLevel(Level.ERROR)
    }
  }
}
