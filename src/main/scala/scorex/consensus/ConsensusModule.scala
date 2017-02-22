package scorex.consensus

import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.block.{Block, BlockField}
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.transaction.TransactionModule

trait ConsensusModule {

  def isValid(block: Block)(implicit transactionModule: TransactionModule): Boolean

  def blockOrdering(implicit transactionModule: TransactionModule): Ordering[(Block)] =
    Ordering.by {
      block =>
        val parent = transactionModule.blockStorage.history.blockById(block.referenceField.value).get
        val blockCreationTime = nextBlockGenerationTime(parent, block.signerDataField.value.generator)
          .getOrElse(block.timestampField.value)

        (block.blockScore, -blockCreationTime)
    }

  def generateNextBlock(account: PrivateKeyAccount)
                           (implicit transactionModule: TransactionModule): Option[Block]

  def generateNextBlocks[TransactionalBlockData](accounts: Seq[PrivateKeyAccount])
                           (implicit transactionModule: TransactionModule): Seq[Block] = {
    accounts.flatMap { acc =>
      generateNextBlock(acc) match {
        case Some(b) => b +: (1 to 50).flatMap(i => generateNextBlock(acc))
        case None => Seq()
      }
    }
  }

  def nextBlockGenerationTime(lastBlock: Block, account: PublicKeyAccount)
                             (implicit transactionModule: TransactionModule): Option[Long]

}
