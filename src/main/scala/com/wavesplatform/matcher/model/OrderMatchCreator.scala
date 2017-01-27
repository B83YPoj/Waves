package com.wavesplatform.matcher.model

import com.wavesplatform.settings.WavesSettings
import scorex.transaction.SimpleTransactionModule._
import scorex.transaction.{SignedTransaction, TransactionModule}
import scorex.transaction.assets.exchange.{Order, OrderMatch}
import scorex.transaction.state.database.blockchain.StoredState
import scorex.transaction.state.database.state.extension.OrderMatchStoredState
import scorex.utils.NTP
import scorex.wallet.Wallet

trait OrderMatchCreator {
  val transactionModule: TransactionModule[StoredInBlock]
  val storedState: StoredState
  val wallet: Wallet
  val settings: WavesSettings
  //TODO ???
  val omss = storedState.validators.filter(_.isInstanceOf[OrderMatchStoredState]).head
    .asInstanceOf[OrderMatchStoredState]

  //TODO dirty hack
  val omExtension = storedState.extensions.filter(_.isInstanceOf[OrderMatchStoredState]).head.asInstanceOf[OrderMatchStoredState]

  private var txTime: Long = 0

  private def getTimestamp: Long = {
    txTime = Math.max(NTP.correctedTime(), txTime + 1)
    txTime
  }

  def createTransaction(sumbitted: LimitOrder, counter: LimitOrder): OrderMatch = {
    val matcher = wallet.privateKeyAccount(sumbitted.order.matcher.address).get

    val price = counter.price
    val amount = math.min(sumbitted.amount, counter.amount)
    val (buy, sell) = Order.splitByType(sumbitted.order, counter.order)
    val (buyFee, sellFee) =  calculateMatcherFee(buy, sell, amount: Long)
    OrderMatch.create(matcher, buy, sell, price, amount, buyFee, sellFee, settings.orderMatchTxFee, getTimestamp)
  }

  def calculateMatcherFee(buy: Order, sell: Order, amount: Long): (Long, Long) = {
    def calcFee(o: Order, amount: Long): Long = {
<<<<<<< HEAD
      omss.findPrevOrderMatchTxs(o)
=======
      omExtension.findPrevOrderMatchTxs(o)
>>>>>>> ab1c12b76b94c2705acf075e0b0ab9e82ebe1688
      val p = BigInt(amount) * o.matcherFee  / o.amount
      p.toLong
    }
    (calcFee(buy, amount), calcFee(sell, amount))
  }

  def isValid(orderMatch: OrderMatch): Boolean = {
    transactionModule.isValid(orderMatch, orderMatch.timestamp)
  }

  def sendToNetwork(tx: SignedTransaction): Unit = {
    transactionModule.onNewOffchainTransaction(tx)
  }
}
