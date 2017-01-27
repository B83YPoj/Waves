package scorex.waves.transaction

import scorex.app.RunnableApplication
import scorex.block.BlockField
import scorex.settings.{ChainParameters, Settings}
import scorex.transaction._

/**
  * Waves Transaction Module
  */
class WavesTransactionModule(chainParams: ChainParameters)(implicit override val settings: Settings,
                                                           application: RunnableApplication)
  extends SimpleTransactionModule(chainParams) {

  override val InitialBalance = chainParams.initialBalance

  override def genesisData: BlockField[SimpleTransactionModule.StoredInBlock] = {
    TransactionsBlockField(chainParams.genesisTxs)
  }
}
