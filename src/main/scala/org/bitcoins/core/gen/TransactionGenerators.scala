package org.bitcoins.core.gen

import org.bitcoins.core.crypto.{ECPrivateKey, TxSigComponent, WitnessTxSigComponent, WitnessTxSigComponentRaw}
import org.bitcoins.core.currency.{CurrencyUnit, CurrencyUnits}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction.{TransactionInput, TransactionOutPoint, TransactionOutput, _}
import org.bitcoins.core.script.constant.ScriptNumber
import org.bitcoins.core.script.interpreter.ScriptInterpreter
import org.bitcoins.core.script.locktime.LockTimeInterpreter
import org.bitcoins.core.util.BitcoinSLogger
import org.scalacheck.Gen

/**
  * Created by chris on 6/21/16.
  */
trait TransactionGenerators extends BitcoinSLogger {

  /** Responsible for generating [[org.bitcoins.core.protocol.transaction.TransactionOutPoint]] */
  def outPoints : Gen[TransactionOutPoint] = for {
    txId <- CryptoGenerators.doubleSha256Digest
    vout <- NumberGenerator.uInt32s
  } yield TransactionOutPoint(txId, vout)

  /** Generates a random [[org.bitcoins.core.protocol.transaction.TransactionOutput]] */
  def outputs : Gen[TransactionOutput] = for {
    satoshis <- CurrencyUnitGenerator.satoshis
    (scriptPubKey, _) <- ScriptGenerators.scriptPubKey
  } yield TransactionOutput(satoshis, scriptPubKey)

  /** Generates a random [[org.bitcoins.core.protocol.transaction.TransactionInput]] */
  def inputs : Gen[TransactionInput] = for {
    outPoint <- outPoints
    scriptSig <- ScriptGenerators.scriptSignature
    sequenceNumber <- NumberGenerator.uInt32s
    randomNum <- Gen.choose(0,10)
  } yield {
    if (randomNum == 0) {
      //gives us a coinbase input
      TransactionInput(scriptSig)
    } else TransactionInput(outPoint,scriptSig,sequenceNumber)
  }

  /**
    * Generates an arbitrary [[org.bitcoins.core.protocol.transaction.Transaction]]
    * This transaction's [[TransactionInput]]s will not evaluate to true
    * inside of the [[org.bitcoins.core.script.interpreter.ScriptInterpreter]]
    */
  def transactions : Gen[Transaction] = Gen.oneOf(baseTransaction,witnessTransaction)


  def baseTransaction: Gen[BaseTransaction] = for {
    version <- NumberGenerator.uInt32s
    randomInputNum <- Gen.choose(1,10)
    inputs <- Gen.listOfN(randomInputNum, inputs)
    randomOutputNum <- Gen.choose(1,10)
    outputs <- Gen.listOfN(randomOutputNum, outputs)
    lockTime <- NumberGenerator.uInt32s
  } yield BaseTransaction(version, inputs, outputs, lockTime)

  /** Generates a random [[WitnessTransaction]] */
  def witnessTransaction: Gen[WitnessTransaction] = for {
    version <- NumberGenerator.uInt32s
    randomInputNum <- Gen.choose(1,10)
    inputs <- Gen.listOfN(randomInputNum, inputs)
    randomOutputNum <- Gen.choose(1,10)
    outputs <- Gen.listOfN(randomOutputNum, outputs)
    lockTime <- NumberGenerator.uInt32s
    witness <- WitnessGenerators.transactionWitness(inputs.size)
  } yield WitnessTransaction(version,inputs,outputs,lockTime, witness)

  /**
    * Creates a [[ECPrivateKey]], then creates a [[P2PKScriptPubKey]] from that private key
    * Finally creates a  [[Transaction]] that spends the [[P2PKScriptPubKey]] correctly
    */
  def signedP2PKTransaction: Gen[(TxSigComponent, ECPrivateKey)] = for {
    (signedScriptSig, scriptPubKey, privateKey) <- ScriptGenerators.signedP2PKScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(scriptPubKey)
    (signedTx,inputIndex) = buildSpendingTransaction(creditingTx,signedScriptSig,outputIndex)
    signedTxSignatureComponent = TxSigComponent(signedTx,inputIndex,
      scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield (signedTxSignatureComponent,privateKey)

  /**
    * Creates a [[ECPrivateKey]], then creates a [[P2PKHScriptPubKey]] from that private key
    * Finally creates a  [[Transaction]] that spends the [[P2PKHScriptPubKey]] correctly
    */
  def signedP2PKHTransaction: Gen[(TxSigComponent, ECPrivateKey)] = for {
    (signedScriptSig, scriptPubKey, privateKey) <- ScriptGenerators.signedP2PKHScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(scriptPubKey)
    (signedTx,inputIndex) = buildSpendingTransaction(creditingTx,signedScriptSig,outputIndex)
    signedTxSignatureComponent = TxSigComponent(signedTx,inputIndex,
      scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield (signedTxSignatureComponent,privateKey)


  /**
    * Creates a sequence of [[ECPrivateKey]], then creates a [[MultiSignatureScriptPubKey]] from those private keys,
    * Finally creates a [[Transaction]] that spends the [[MultiSignatureScriptPubKey]] correctly
    */
  def signedMultiSigTransaction: Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (signedScriptSig, scriptPubKey, privateKey) <- ScriptGenerators.signedMultiSignatureScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(scriptPubKey)
    (signedTx,inputIndex) = buildSpendingTransaction(creditingTx,signedScriptSig,outputIndex)
    signedTxSignatureComponent = TxSigComponent(signedTx,inputIndex,
      scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield (signedTxSignatureComponent,privateKey)


  /**
    * Creates a transaction which contains a [[P2SHScriptSignature]] that correctly spends a [[P2SHScriptPubKey]]
    */
  def signedP2SHTransaction: Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (signedScriptSig, scriptPubKey, privateKey) <- ScriptGenerators.signedP2SHScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(signedScriptSig.redeemScript)
    (signedTx,inputIndex) = buildSpendingTransaction(creditingTx,signedScriptSig,outputIndex)
    signedTxSignatureComponent = TxSigComponent(signedTx,inputIndex,
      scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield (signedTxSignatureComponent,privateKey)



  /** Generates a validly constructed CLTV transaction, which has a 50/50 chance of being spendable or unspendable. */
  def randomCLTVTransaction : Gen[(TxSigComponent, Seq[ECPrivateKey])] = {
    Gen.oneOf(unspendableCLTVTransaction,spendableCLTVTransaction)
  }

  /**
    * Creates a [[ECPrivateKey]], then creates a [[CLTVScriptPubKey]] from that private key
    * Finally creates a [[Transaction]] that CANNNOT spend the [[CLTVScriptPubKey]] because the LockTime requirement
    * is not satisfied (i.e. the transaction's lockTime has not surpassed the CLTV value in the [[CLTVScriptPubKey]])
    *
    * @return
    */
  def unspendableCLTVTransaction : Gen[(TxSigComponent, Seq[ECPrivateKey])] =  for {
    (cltvLockTime,txLockTime) <- unspendableCLTVValues
    sequence <- NumberGenerator.uInt32s.suchThat(n => n < UInt32.max)
    (scriptSig,scriptPubKey,privKeys) <- ScriptGenerators.signedCLTVScriptSignature(cltvLockTime,txLockTime,sequence)
    unspendable = lockTimeTxHelper(scriptSig,scriptPubKey,privKeys,sequence,Some(txLockTime))
  } yield unspendable

  /**
    *  Creates a [[ECPrivateKey]], then creates a [[CLTVScriptPubKey]] from that private key
    *  Finally creates a [[Transaction]] that can successfully spend the [[CLTVScriptPubKey]]
    */
  def spendableCLTVTransaction : Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (cltvLockTime,txLockTime) <- spendableCLTVValues
    sequence <- NumberGenerator.uInt32s.suchThat(n => n < UInt32.max)
    (scriptSig,scriptPubKey,privKeys) <- ScriptGenerators.signedCLTVScriptSignature(cltvLockTime,txLockTime,sequence)
    spendable = lockTimeTxHelper(scriptSig,scriptPubKey,privKeys,sequence,Some(txLockTime))
  } yield spendable

  /**
    *  Creates a [[ECPrivateKey]], then creates a [[CSVScriptPubKey]] from that private key
    *  Finally creates a [[Transaction]] that can successfully spend the [[CSVScriptPubKey]]
    */
  def spendableCSVTransaction : Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (csvScriptNum, sequence) <- spendableCSVValues
    tx <- csvTransaction(csvScriptNum,sequence)
  } yield tx

  /** Creates a CSV transaction that's timelock has not been met */
  def unspendableCSVTransaction : Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (csvScriptNum, sequence) <- unspendableCSVValues
    tx <- csvTransaction(csvScriptNum, sequence)
  } yield tx

  def csvTransaction(csvScriptNum: ScriptNumber, sequence: UInt32): Gen[(TxSigComponent, Seq[ECPrivateKey])] = for {
    (signedScriptSig, csvScriptPubKey, privateKeys) <- ScriptGenerators.signedCSVScriptSignature(csvScriptNum, sequence)
  } yield lockTimeTxHelper(signedScriptSig, csvScriptPubKey, privateKeys, sequence,None)

  /** Generates a [[Transaction]] that has a valid [[EscrowTimeoutScriptSignature]] that specifically spends the
    * [[EscrowTimeoutScriptPubKey]] using the multisig escrow branch  */
  def spendableMultiSigEscrowTimeoutTransaction(outputs: Seq[TransactionOutput]): Gen[TxSigComponent] = for {
    sequence <- NumberGenerator.uInt32s
    amount <- CurrencyUnitGenerator.satoshis
    (scriptSig, scriptPubKey,privKeys) <- ScriptGenerators.signedMultiSigEscrowTimeoutScriptSig(sequence,outputs,amount)
    (creditingTx,outputIndex) = buildCreditingTransaction(TransactionConstants.validLockVersion,scriptPubKey,amount)
    (spendingTx, inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,scriptSig,
      outputIndex, TransactionConstants.lockTime,sequence,outputs)
    txSigComponent = TxSigComponent(spendingTx,inputIndex,scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield txSigComponent

  /** Generates a [[Transaction]] that has a valid [[EscrowTimeoutScriptSignature]] that specfically spends the
    * [[EscrowTimeoutScriptPubKey]] using the timeout branch */
  def spendableTimeoutEscrowTimeoutTransaction(outputs: Seq[TransactionOutput]): Gen[TxSigComponent] = for {
    (csvScriptNum,sequence) <- spendableCSVValues
    (scriptSig, scriptPubKey,privKeys) <- ScriptGenerators.timeoutEscrowTimeoutScriptSig(csvScriptNum,sequence,outputs)
    (creditingTx,outputIndex) = buildCreditingTransaction(TransactionConstants.validLockVersion,scriptPubKey)
    (spendingTx, inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,
      scriptSig,outputIndex,UInt32.zero,sequence,outputs)
    txSigComponent = TxSigComponent(spendingTx,inputIndex,scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield txSigComponent

  /** Generates a [[Transaction]] that has a valid [[EscrowTimeoutScriptSignature]] */
  def spendableEscrowTimeoutTransaction(outputs: Seq[TransactionOutput] = Nil): Gen[TxSigComponent] = {
    Gen.oneOf(spendableMultiSigEscrowTimeoutTransaction(outputs),
      spendableTimeoutEscrowTimeoutTransaction(outputs))
  }

  /** Generates a [[Transaction]] that has a valid [[EscrowTimeoutScriptSignature]] */
  def spendableEscrowTimeoutTransaction: Gen[TxSigComponent] = {
    Gen.oneOf(spendableMultiSigEscrowTimeoutTransaction(Nil),
      spendableTimeoutEscrowTimeoutTransaction(Nil))
  }
  /** Generates a CSVEscrowTimeoutTransaction that should evaluate to false when run through the [[ScriptInterpreter]] */
  def unspendableTimeoutEscrowTimeoutTransaction: Gen[TxSigComponent] = for {
    (csvScriptNum, sequence) <- unspendableCSVValues
    (scriptSig, scriptPubKey,privKeys) <- ScriptGenerators.timeoutEscrowTimeoutScriptSig(csvScriptNum,sequence,Nil)
    (creditingTx,outputIndex) = buildCreditingTransaction(TransactionConstants.validLockVersion,scriptPubKey)
    (spendingTx, inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,scriptSig,outputIndex,
      TransactionConstants.lockTime,sequence)
    txSigComponent = TxSigComponent(spendingTx,inputIndex,scriptPubKey,Policy.standardScriptVerifyFlags)
  } yield txSigComponent

  def unspendableMultiSigEscrowTimeoutTransaction: Gen[TxSigComponent] = for {
    sequence <- NumberGenerator.uInt32s
    (multiSigScriptPubKey,_) <- ScriptGenerators.multiSigScriptPubKey
    (lock,_) <- ScriptGenerators.lockTimeScriptPubKey
    escrow = EscrowTimeoutScriptPubKey(multiSigScriptPubKey,lock)
    multiSigScriptSig <- ScriptGenerators.multiSignatureScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(TransactionConstants.validLockVersion,escrow)
    escrowScriptSig = EscrowTimeoutScriptSignature.fromMultiSig(multiSigScriptSig)
    (spendingTx, inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,
      escrowScriptSig,outputIndex,TransactionConstants.lockTime,sequence)
    txSigComponent = TxSigComponent(spendingTx,inputIndex,escrow,Policy.standardScriptVerifyFlags)
  } yield txSigComponent

  def unspendableEscrowTimeoutTransaction: Gen[TxSigComponent] =  {
    Gen.oneOf(unspendableTimeoutEscrowTimeoutTransaction, unspendableMultiSigEscrowTimeoutTransaction)
  }

  /** Generates a [[WitnessTransaction]] that has all of it's inputs signed correctly */
  def signedP2WPKHTransaction: Gen[(WitnessTxSigComponent,Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent, privKeys) <- WitnessGenerators.signedP2WPKHTransactionWitness
  } yield (wtxSigComponent,privKeys)

  /** Generates a [[WitnessTransaction]] that has an input spends a raw P2WSH [[WitnessScriptPubKey]] */
  def signedP2WSHP2PKTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent, privKeys) <- WitnessGenerators.signedP2WSHP2PKTransactionWitness
  } yield (wtxSigComponent,privKeys)

  /** Generates a [[WitnessTransaction]] that has an input spends a raw P2WSH [[WitnessScriptPubKey]] */
  def signedP2WSHP2PKHTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent, privKeys) <- WitnessGenerators.signedP2WSHP2PKHTransactionWitness
  } yield (wtxSigComponent,privKeys)

  def signedP2WSHMultiSigTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent, privKeys) <- WitnessGenerators.signedP2WSHMultiSigTransactionWitness
  } yield (wtxSigComponent,privKeys)

  def signedP2WSHMultiSigEscrowTimeoutTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent,privKeys) <- WitnessGenerators.signedP2WSHMultiSigEscrowTimeoutWitness
  } yield (wtxSigComponent,privKeys)

  def spendableP2WSHTimeoutEscrowTimeoutTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = for {
    (_,wtxSigComponent,privKeys) <- WitnessGenerators.spendableP2WSHTimeoutEscrowTimeoutWitness
  } yield (wtxSigComponent,privKeys)

  def signedP2WSHEscrowTimeoutTransaction: Gen[(WitnessTxSigComponentRaw, Seq[ECPrivateKey])] = {
    Gen.oneOf(signedP2WSHMultiSigEscrowTimeoutTransaction,spendableP2WSHTimeoutEscrowTimeoutTransaction)
  }

  /** Creates a signed P2SH(P2WPKH) transaction */
  def signedP2SHP2WPKHTransaction: Gen[(WitnessTxSigComponent, Seq[ECPrivateKey])] = for {
    (signedScriptSig, scriptPubKey, privKeys, witness, amount) <- ScriptGenerators.signedP2SHP2WPKHScriptSignature
    (creditingTx,outputIndex) = buildCreditingTransaction(signedScriptSig.redeemScript, amount)
    (signedTx,inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,
      signedScriptSig, outputIndex, witness)
    signedTxSignatureComponent = WitnessTxSigComponent(signedTx,inputIndex,
      scriptPubKey, Policy.standardScriptVerifyFlags,amount)
  } yield (signedTxSignatureComponent, privKeys)

  def signedP2WSHTransaction: Gen[(WitnessTxSigComponentRaw,Seq[ECPrivateKey])] = {
    Gen.oneOf(signedP2WSHP2PKTransaction, signedP2WSHP2PKHTransaction, signedP2WSHMultiSigTransaction,
      signedP2WSHEscrowTimeoutTransaction)
  }
  /** Creates a signed P2SH(P2WSH) transaction */
  def signedP2SHP2WSHTransaction: Gen[(WitnessTxSigComponent, Seq[ECPrivateKey])] = for {
    (witness,wtxSigComponent, privKeys) <- WitnessGenerators.signedP2WSHTransactionWitness
    p2shScriptPubKey = P2SHScriptPubKey(wtxSigComponent.scriptPubKey)
    p2shScriptSig = P2SHScriptSignature(wtxSigComponent.scriptPubKey.asInstanceOf[WitnessScriptPubKey])
    (creditingTx,outputIndex) = buildCreditingTransaction(p2shScriptSig.redeemScript, wtxSigComponent.amount)
    sequence = wtxSigComponent.transaction.inputs(wtxSigComponent.inputIndex.toInt).sequence
    locktime = wtxSigComponent.transaction.lockTime
    (signedTx,inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion,creditingTx,p2shScriptSig,outputIndex,locktime,sequence,witness)
    signedTxSignatureComponent = WitnessTxSigComponent(signedTx,inputIndex,
      p2shScriptPubKey, Policy.standardScriptVerifyFlags, wtxSigComponent.amount)
  } yield (signedTxSignatureComponent,privKeys)


  /**
    * Builds a spending transaction according to bitcoin core
    * @return the built spending transaction and the input index for the script signature
    */
  def buildSpendingTransaction(version : UInt32, creditingTx : Transaction,scriptSignature : ScriptSignature,
                               outputIndex : UInt32, locktime : UInt32, sequence : UInt32) : (Transaction,UInt32) = {
    val output = TransactionOutput(CurrencyUnits.zero,EmptyScriptPubKey)
    buildSpendingTransaction(version,creditingTx,scriptSignature,outputIndex,locktime,sequence,Seq(output))
  }

  def buildSpendingTransaction(version : UInt32, creditingTx : Transaction,scriptSignature : ScriptSignature,
                               outputIndex : UInt32, locktime : UInt32, sequence : UInt32, outputs: Seq[TransactionOutput]): (Transaction,UInt32) = {
    val os = if (outputs.isEmpty) {
      Seq(TransactionOutput(CurrencyUnits.zero,EmptyScriptPubKey))
    } else {
      outputs
    }
    val outpoint = TransactionOutPoint(creditingTx.txId,outputIndex)
    val input = TransactionInput(outpoint,scriptSignature, sequence)
    val tx = Transaction(version,Seq(input),os,locktime)
    (tx,UInt32.zero)
  }

  /**
    * Builds a spending transaction according to bitcoin core with max sequence and a locktime of zero.
    * @return the built spending transaction and the input index for the script signature
    */
  def buildSpendingTransaction(creditingTx : Transaction,scriptSignature : ScriptSignature, outputIndex : UInt32) : (Transaction,UInt32) = {
    buildSpendingTransaction(TransactionConstants.version, creditingTx, scriptSignature, outputIndex,
      TransactionConstants.lockTime, TransactionConstants.sequence)
  }

  /** Builds a spending [[WitnessTransaction]] with the given parameters */
  def buildSpendingTransaction(creditingTx: Transaction, scriptSignature: ScriptSignature, outputIndex: UInt32,
                               locktime: UInt32, sequence: UInt32, witness: TransactionWitness): (WitnessTransaction, UInt32) = {
    buildSpendingTransaction(TransactionConstants.version,creditingTx,scriptSignature,outputIndex,locktime,sequence,witness)
  }

  def buildSpendingTransaction(version: UInt32, creditingTx: Transaction, scriptSignature: ScriptSignature, outputIndex: UInt32,
                               locktime: UInt32, sequence: UInt32, witness: TransactionWitness): (WitnessTransaction, UInt32) = {

    val outputs = dummyOutputs
    buildSpendingTransaction(version,creditingTx,scriptSignature,outputIndex,locktime,sequence,witness,outputs)
  }

  def dummyOutputs: Seq[TransactionOutput] = Seq(TransactionOutput(CurrencyUnits.zero,EmptyScriptPubKey))

  def buildSpendingTransaction(version: UInt32, creditingTx: Transaction, scriptSignature: ScriptSignature, outputIndex: UInt32,
                               locktime: UInt32, sequence: UInt32, witness: TransactionWitness, outputs: Seq[TransactionOutput]): (WitnessTransaction, UInt32) = {
    val outpoint = TransactionOutPoint(creditingTx.txId,outputIndex)
    val input = TransactionInput(outpoint,scriptSignature,sequence)
    (WitnessTransaction(version,Seq(input), outputs,locktime,witness), UInt32.zero)
  }

  def buildSpendingTransaction(creditingTx: Transaction, scriptSignature: ScriptSignature, outputIndex: UInt32,
                               witness: TransactionWitness): (WitnessTransaction, UInt32) = {
    buildSpendingTransaction(TransactionConstants.version,creditingTx,scriptSignature,outputIndex,witness)
  }

  def buildSpendingTransaction(version: UInt32, creditingTx: Transaction, scriptSignature: ScriptSignature, outputIndex: UInt32,
                               witness: TransactionWitness): (WitnessTransaction, UInt32) = {
    val locktime = TransactionConstants.lockTime
    val sequence = TransactionConstants.sequence
    buildSpendingTransaction(version,creditingTx,scriptSignature,outputIndex,locktime,sequence,witness)
  }

  /**
    * Mimics this test utility found in bitcoin core
    * https://github.com/bitcoin/bitcoin/blob/605c17844ea32b6d237db6d83871164dc7d59dab/src/test/script_tests.cpp#L57
    * @return the transaction and the output index of the scriptPubKey
    */
  def buildCreditingTransaction(scriptPubKey : ScriptPubKey) : (Transaction,UInt32) = {
    //this needs to be all zeros according to these 3 lines in bitcoin core
    //https://github.com/bitcoin/bitcoin/blob/605c17844ea32b6d237db6d83871164dc7d59dab/src/test/script_tests.cpp#L64
    //https://github.com/bitcoin/bitcoin/blob/80d1f2e48364f05b2cdf44239b3a1faa0277e58e/src/primitives/transaction.h#L32
    //https://github.com/bitcoin/bitcoin/blob/605c17844ea32b6d237db6d83871164dc7d59dab/src/uint256.h#L40
    buildCreditingTransaction(TransactionConstants.version, scriptPubKey)
  }

  def buildCreditingTransaction(scriptPubKey: ScriptPubKey, amount: CurrencyUnit): (Transaction, UInt32) = {
    buildCreditingTransaction(TransactionConstants.version,scriptPubKey,amount)
  }

  /**
    * Builds a crediting transaction with a transaction version parameter.
    * Example: useful for creating transactions with scripts containing OP_CHECKSEQUENCEVERIFY.
    * @return
    */
  def buildCreditingTransaction(version : UInt32, scriptPubKey: ScriptPubKey) : (Transaction, UInt32) = {
    buildCreditingTransaction(version,scriptPubKey,CurrencyUnits.zero)
  }

  def buildCreditingTransaction(version: UInt32, output: TransactionOutput): (Transaction,UInt32) = {
    val outpoint = EmptyTransactionOutPoint
    val scriptSignature = ScriptSignature("0000")
    val input = TransactionInput(outpoint,scriptSignature,TransactionConstants.sequence)
    val tx = Transaction(version,Seq(input),Seq(output),TransactionConstants.lockTime)
    (tx,UInt32.zero)
  }

  def buildCreditingTransaction(version: UInt32, scriptPubKey: ScriptPubKey, amount: CurrencyUnit): (Transaction,UInt32) = {
    buildCreditingTransaction(version, TransactionOutput(amount,scriptPubKey))
  }

  private def lockTimeTxHelper(signedScriptSig : LockTimeScriptSignature, lock : LockTimeScriptPubKey,
                               privKeys : Seq[ECPrivateKey], sequence : UInt32, lockTime: Option[UInt32]) : (TxSigComponent, Seq[ECPrivateKey]) = {
    val (creditingTx, outputIndex) = buildCreditingTransaction(TransactionConstants.validLockVersion, lock)
    //Transaction version must not be less than 2 for a CSV transaction
    val (signedSpendingTx, inputIndex) = buildSpendingTransaction(TransactionConstants.validLockVersion, creditingTx,
      signedScriptSig, outputIndex, lockTime.getOrElse(TransactionConstants.lockTime), sequence)
    val txSigComponent = TxSigComponent(signedSpendingTx, inputIndex,
      lock, Policy.standardScriptVerifyFlags)
    (txSigComponent, privKeys)
  }

  /**
    * Determines if the transaction's lockTime value and CLTV script lockTime value are of the same type
    * (i.e. determines whether both are a timestamp or block height)
    */
  private def cltvLockTimesOfSameType(num: ScriptNumber, lockTime: UInt32) : Boolean = num.underlying match {
      case negative if negative < 0 => false
      case positive if positive >= 0 =>
        if (!(
          (lockTime < TransactionConstants.locktimeThreshold && num.underlying < TransactionConstants.locktimeThreshold.underlying) ||
            (lockTime >= TransactionConstants.locktimeThreshold && num.underlying >= TransactionConstants.locktimeThreshold.underlying)
          )) return false
        true
  }

  /**
    * Determines if the transaction input's sequence value and CSV script sequence value are of the same type
    * (i.e. determines whether both are a timestamp or block-height)
    */
  private def csvLockTimesOfSameType(sequenceNumbers : (ScriptNumber, UInt32)) : Boolean = {
    LockTimeInterpreter.isCSVLockByRelativeLockTime(sequenceNumbers._1, sequenceNumbers._2) ||
      LockTimeInterpreter.isCSVLockByBlockHeight(sequenceNumbers._1, sequenceNumbers._2)
  }

  /**
    * Generates a pair of CSV values: a transaction input sequence, and a CSV script sequence value, such that the txInput
    * sequence mask is always greater than the script sequence mask (i.e. generates values for a validly constructed and spendable CSV transaction)
    */
  def spendableCSVValues : Gen[(ScriptNumber, UInt32)] = Gen.oneOf(validScriptNumberAndSequenceForBlockHeight,
      validScriptNumberAndSequenceForRelativeLockTime)

  /** To indicate that we should evaulate a OP_CSV operation based on
    * blockheight we need 1 << 22 bit turned off. See BIP68 for more details */
  private def lockByBlockHeightBitSet = UInt32("ffbfffff")

  /** Generates a [[UInt32]] s.t. the block height bit is set according to BIP68 */
  private def sequenceForBlockHeight: Gen[UInt32] = validCSVSequence.map { n =>
    val result: UInt32 = n & lockByBlockHeightBitSet
    require(LockTimeInterpreter.isCSVLockByBlockHeight(result), "Block height locktime bit was not set: " + result)
    result
  }

  /** Generates a [[ScriptNumber]] and [[UInt32]] s.t. the pair can be spent by an OP_CSV operation */
  private def validScriptNumberAndSequenceForBlockHeight: Gen[(ScriptNumber,UInt32)] = {
    sequenceForBlockHeight.flatMap { s =>
      val seqMasked = TransactionConstants.sequenceLockTimeMask
      val validScriptNums = s & seqMasked
      Gen.choose(0L, validScriptNums.underlying).map { sn =>
        val scriptNum = ScriptNumber(sn & lockByBlockHeightBitSet.underlying)
        require(LockTimeInterpreter.isCSVLockByBlockHeight(scriptNum))
        require(LockTimeInterpreter.isCSVLockByBlockHeight(s))
        (scriptNum,s)
      }
    }
  }

  /** Generates a [[UInt32]] with the locktime bit set according to BIP68 */
  private def sequenceForRelativeLockTime: Gen[UInt32] = validCSVSequence.map { n =>
    val result = n | TransactionConstants.sequenceLockTimeTypeFlag
    require(LockTimeInterpreter.isCSVLockByRelativeLockTime(result), "Relative locktime bit was not set: " + result)
    result
  }

  /** Generates a valid [[ScriptNumber]] and [[UInt32]] s.t. the pair will evaluate to true by a OP_CSV operation */
  private def validScriptNumberAndSequenceForRelativeLockTime: Gen[(ScriptNumber,UInt32)] = {
    sequenceForRelativeLockTime.flatMap { s =>
      val seqMasked = TransactionConstants.sequenceLockTimeMask
      val validScriptNums = s & seqMasked
      Gen.choose(0L, validScriptNums.underlying).map { sn =>
        val scriptNum = ScriptNumber(sn | TransactionConstants.sequenceLockTimeTypeFlag.underlying)
        require(LockTimeInterpreter.isCSVLockByRelativeLockTime(scriptNum))
        require(LockTimeInterpreter.isCSVLockByRelativeLockTime(s))
        (scriptNum,s)
      }
    }
  }
  /** Generates a [[UInt32]] s.t. the locktime enabled flag is set. See BIP68 for more info */
  private def validCSVSequence: Gen[UInt32] = NumberGenerator.uInt32s.map { n =>
    //makes sure the 1 << 31 is TURNED OFF,
    //need this to generate spendable CSV values without discarding a bunch of test cases
    val result = n & UInt32(0x7FFFFFFF)
    require(LockTimeInterpreter.isLockTimeBitOff(ScriptNumber(result.underlying)))
    result
  }

  /**
    * Generates a pair of CSV values: a transaction input sequence, and a CSV script sequence value, such that the txInput
    * sequence mask is always less than the script sequence mask (i.e. generates values for a validly constructed and NOT spendable CSV transaction).
    */
  def unspendableCSVValues : Gen[(ScriptNumber, UInt32)] = ( for {
    sequence <- NumberGenerator.uInt32s
    csvScriptNum <- NumberGenerator.uInt32s.map(x => ScriptNumber(x.underlying)).suchThat(x => LockTimeInterpreter.isLockTimeBitOff(x))
  } yield (csvScriptNum, sequence)).suchThat(x => !csvLockTimesOfSameType(x))

  /** generates a [[ScriptNumber]] and [[UInt32]] locktime for a transaction such that the tx will be spendable */
  private def spendableCLTVValues: Gen[(ScriptNumber,UInt32)] = for {
    txLockTime <- NumberGenerator.uInt32s
    cltvLockTime <- NumberGenerator.uInt32s.suchThat(num => cltvLockTimesOfSameType(ScriptNumber(num.underlying),txLockTime) &&
      num < txLockTime).map(x => ScriptNumber(x.underlying))
  } yield (cltvLockTime,txLockTime)

  /** Generates a [[ScriptNumber]] and [[UInt32]] locktime for a transaction such that the tx will be unspendable */
  private def unspendableCLTVValues: Gen[(ScriptNumber,UInt32)] = for {
    txLockTime <- NumberGenerator.uInt32s
    cltvLockTime <- NumberGenerator.uInt32s.suchThat(num => num >= txLockTime || !cltvLockTimesOfSameType(ScriptNumber(num.underlying),txLockTime)).map(x => ScriptNumber(x.underlying))
  } yield (cltvLockTime,txLockTime)

}

object TransactionGenerators extends TransactionGenerators
