package scorex.waves

import org.scalatest.{BeforeAndAfterAll, DoNotDiscover, FunSuite, Matchers}
import play.api.libs.json.Json
import scorex.api.http.{InvalidAddress, InvalidSender, NegativeFee}
import scorex.waves.transaction.SignedPayment

@DoNotDiscover
class WavesAPISpecification extends FunSuite with Matchers with BeforeAndAfterAll {

  import TestingCommons._

  override def beforeAll: Unit = {
    start()
  }

  override def afterAll: Unit = {
    stop()
  }

  test("/waves/broadcast-signed-payment API route can not send to address from another net") {

    val senderPublicKey = "GvXeYd2iFJUNV7KgeGV2cdnScyrEvrr9uPYJeQFtvg21"
    val recipient = "3MqS3mVY4Yr4HoTdpWiEaq9phwbaoWS2W6A"
    val timestamp = 1465391445252L
    val amount = 10000000000000L
    val payment = SignedPayment(timestamp, amount, 400L, recipient, senderPublicKey, "", "1")
    val json = Json.toJson(payment).toString

    val response = postRequest(us = "/waves/broadcast-signed-payment", body = json)
    assert(response.toString == InvalidAddress.json.toString)
  }


  test("/wave/broadcast-signed-payment can not send from suspendedAddresses") {
    val senderPublicKey = "4ZADu6W3ZBfNv1LLQCkoHAdiVfvatZNNB6reSyo7c9qJ"
    val recipient = "3N5jhcA7R98AUN12ee9pB7unvnAKfzb3nen"
    val timestamp = 1465391445252L
    val amount = 10000000000000L
    val payment = SignedPayment(timestamp, amount, 1L, recipient, senderPublicKey, "", "1")
    val json = Json.toJson(payment).toString
    val response = postRequest(us = "/waves/broadcast-signed-payment", body = json)
    assert(response.toString == InvalidSender.json.toString)
  }

  test("/waves/broadcast-signed-payment can not send tx with small fee") {
    val senderPublicKey = "GvXeYd2iFJUNV7KgeGV2cdnScyrEvrr9uPYJeQFtvg21"
    val recipient = "3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J"
    val timestamp = 1465391445252L
    val amount = 10000000000000L
    val payment = SignedPayment(timestamp, amount, 4L, recipient, senderPublicKey, "", "1")
    val json = Json.toJson(payment).toString

    val response = postRequest(us = "/waves/broadcast-signed-payment", body = json)
    assert(response.toString == NegativeFee.json.toString)
  }

}