package scorex.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import play.api.libs.json.Json
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.block.Block.BlockId
import scorex.consensus.ConsensusModule
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.transaction.TransactionModule
import scorex.utils.ScorexLogging
import scorex.transaction.TypedTransaction._

import scala.util.{Failure, Try}

/**
  * A block is an atomic piece of data network participates are agreed on.
  *
  * A block has:
  * - transactions data: a sequence of transactions, where a transaction is an atomic state update.
  * Some metadata is possible as well(transactions Merkle tree root, state Merkle tree root etc).
  *
  * - consensus data to check whether block was generated by a right party in a right way. E.g.
  * "baseTarget" & "generatorSignature" fields in the Nxt block structure, nonce & difficulty in the
  * Bitcoin block structure.
  *
  * - a signature(s) of a block generator(s)
  *
  * - additional data: block structure version no, timestamp etc
  */

abstract class Block(timestamp: Long, version: Byte, reference: Block.BlockId, signerData: SignerData) extends ScorexLogging {
  type ConsensusDataType
  type TransactionDataType

  implicit val consensusModule: ConsensusModule[ConsensusDataType]
  implicit val transactionModule: TransactionModule[TransactionDataType]

  val consensusDataField: BlockField[ConsensusDataType]
  val transactionDataField: BlockField[TransactionDataType]

  val versionField: ByteBlockField = ByteBlockField("version", version)
  val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
  val referenceField: BlockIdField = BlockIdField("reference", reference)
  val signerDataField: SignerDataBlockField = SignerDataBlockField("signature", signerData)
  val uniqueId: BlockId = signerData.signature


  lazy val encodedId: String = Base58.encode(uniqueId)

  lazy val transactions = transactionModule.transactions(this)

  lazy val fee = consensusModule.feesDistribution(this).values.sum

  lazy val json =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      transactionDataField.json ++
      signerDataField.json ++
      Json.obj(
        "fee" -> fee,
        "blocksize" -> bytes.length
      )

  lazy val bytes = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      txBytes ++
      signerDataField.bytes
  }

  lazy val bytesWithoutSignature = bytes.dropRight(SignatureLength)

  def isValid: Boolean = {
    if (transactionModule.blockStorage.history.contains(this)) true //applied blocks are valid
    else {
      def history = transactionModule.blockStorage.history.contains(referenceField.value)

      def signature = EllipticCurveImpl.verify(signerDataField.value.signature, bytesWithoutSignature,
        signerDataField.value.generator.publicKey)

      def consensus = consensusModule.isValid(this)

      def transaction = transactionModule.isValid(this)

      if (!history) log.debug(s"Invalid block $encodedId: no parent block in history")
      else if (!signature) log.debug(s"Invalid block $encodedId: signature is not valid")
      else if (!consensus) log.debug(s"Invalid block $encodedId: consensus data is not valid")
      else if (!transaction) log.debug(s"Invalid block $encodedId: transaction data is not valid")

      history && signature && consensus && transaction
    }
  }

  override def equals(obj: scala.Any): Boolean = {
    import shapeless.syntax.typeable._
    obj.cast[Block].exists(_.uniqueId.sameElements(this.uniqueId))
  }
}


object Block extends ScorexLogging {
  type BlockId = Array[Byte]
  type BlockIds = Seq[BlockId]

  val BlockIdLength = SignatureLength

  //TODO Deser[Block] ??
  def parseBytes[CDT, TDT](bytes: Array[Byte])
                          (implicit consModule: ConsensusModule[CDT],
                           transModule: TransactionModule[TDT]): Try[Block] = Try {

    val version = bytes.head

    var position = 1

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position += 8

    val reference = bytes.slice(position, position + Block.BlockIdLength)
    position += BlockIdLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val consBlockField = consModule.parseBytes(cBytes).get
    position += cBytesLength

    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transModule.parseBytes(tBytes).get
    position += tBytesLength

    val genPK = bytes.slice(position, position + KeyLength)
    position += KeyLength

    val signature = bytes.slice(position, position + SignatureLength)

    new Block(timestamp, version, reference, SignerData(new PublicKeyAccount(genPK), signature)) {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override val transactionDataField: BlockField[TransactionDataType] = txBlockField

      override implicit val consensusModule: ConsensusModule[ConsensusDataType] = consModule
      override implicit val transactionModule: TransactionModule[TransactionDataType] = transModule

      override val consensusDataField: BlockField[ConsensusDataType] = consBlockField

    }
  }.recoverWith { case t: Throwable =>
    log.error("Error when parsing block", t)
    t.printStackTrace()
    Failure(t)
  }

  def build[CDT, TDT](version: Byte,
                      timestamp: Long,
                      reference: BlockId,
                      consensusData: CDT,
                      transactionData: TDT,
                      generator: PublicKeyAccount,
                      signature: Array[Byte])
                     (implicit consModule: ConsensusModule[CDT],
                      transModule: TransactionModule[TDT]): Block = {
    new Block(timestamp, version, reference, SignerData(generator, signature)) {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override implicit val transactionModule: TransactionModule[TDT] = transModule
      override implicit val consensusModule: ConsensusModule[CDT] = consModule


      override val transactionDataField: BlockField[TDT] = transModule.formBlockData(transactionData)

      override val consensusDataField: BlockField[CDT] = consensusModule.formBlockData(consensusData)


    }
  }

  def buildAndSign[CDT, TDT](version: Byte,
                             timestamp: Long,
                             reference: BlockId,
                             consensusData: CDT,
                             transactionData: TDT,
                             signer: PrivateKeyAccount)
                            (implicit consModule: ConsensusModule[CDT],
                             transModule: TransactionModule[TDT]): Block = {
    val nonSignedBlock = build(version, timestamp, reference, consensusData, transactionData, signer, Array())
    val toSign = nonSignedBlock.bytes
    val signature = EllipticCurveImpl.sign(signer, toSign)
    build(version, timestamp, reference, consensusData, transactionData, signer, signature)
  }

  def genesis[CDT, TDT](timestamp: Long = 0L, signatureStringOpt: Option[String] = None)(implicit consModule: ConsensusModule[CDT],
                                                                                         transModule: TransactionModule[TDT]): Block = {
    val version: Byte = 1

    val genesisSigner = new PrivateKeyAccount(Array.empty)

    val txBytesSize = transModule.genesisData.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transModule.genesisData.bytes
    val cBytesSize = consModule.genesisData.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consModule.genesisData.bytes

    val reference = Array.fill(BlockIdLength)(-1: Byte)

    val toSign: Array[Byte] = Array(version) ++
      Bytes.ensureCapacity(Longs.toByteArray(timestamp), 8, 0) ++
      reference ++
      cBytes ++
      txBytes ++
      genesisSigner.publicKey

    val signature = signatureStringOpt.map(Base58.decode(_).get)
      .getOrElse(EllipticCurveImpl.sign(genesisSigner, toSign))

    require(EllipticCurveImpl.verify(signature, toSign, genesisSigner.publicKey), "Passed genesis signature is not valid")


    new Block(timestamp = timestamp,
      version = 1,
      reference = reference,
      signerData = SignerData(genesisSigner, signature)) {


      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override implicit val transactionModule: TransactionModule[TDT] = transModule
      override implicit val consensusModule: ConsensusModule[CDT] = consModule

      override val transactionDataField: BlockField[TDT] = transactionModule.genesisData
      override val consensusDataField: BlockField[CDT] = consensusModule.genesisData

    }
  }
}
