package org.bitcoins.core.currency

import org.bitcoins.core.consensus.Consensus
import org.bitcoins.core.number.{BaseNumbers, Int32, Int64, SignedNumber}
import org.bitcoins.core.protocol.NetworkElement
import org.bitcoins.core.serializers.{RawBitcoinSerializerHelper, RawSatoshisSerializer}
import org.bitcoins.core.util.{BitcoinSLogger, Factory}


sealed trait CurrencyUnit extends NetworkElement {
  type A
  def underlying : A

  def satoshis: Satoshis

  def >=(c : CurrencyUnit) : Boolean = {
    satoshis.underlying >= c.satoshis.underlying
  }

  def >(c : CurrencyUnit) : Boolean = {
    satoshis.underlying > c.satoshis.underlying
  }

  def <(c : CurrencyUnit) : Boolean = {
    satoshis.underlying < c.satoshis.underlying
  }

  def <=(c : CurrencyUnit) : Boolean = {
    satoshis.underlying <= c.satoshis.underlying
  }

  def !=(c : CurrencyUnit) : Boolean = !(this == c)

  def ==(c: CurrencyUnit): Boolean = satoshis == c.satoshis

  def +(c : CurrencyUnit) : CurrencyUnit = {
    Satoshis(satoshis.underlying + c.satoshis.underlying)
  }

  def -(c : CurrencyUnit) : CurrencyUnit = {
    Satoshis(satoshis.underlying - c.satoshis.underlying)
  }

  def *(c : CurrencyUnit) : CurrencyUnit = {
    Satoshis(satoshis.underlying * c.satoshis.underlying)
  }

  def unary_- : CurrencyUnit = {
    Satoshis(- satoshis.underlying)
  }
}

sealed trait Satoshis extends CurrencyUnit {
  override type A = Int64
  override def hex = RawSatoshisSerializer.write(this)
  override def satoshis: Satoshis = this

  def ==(satoshis: Satoshis): Boolean = underlying == satoshis.underlying
}

object Satoshis extends Factory[Satoshis] with BaseNumbers[Satoshis] {
  private case class SatoshisImpl(underlying : Int64) extends Satoshis

  val min = Satoshis(Int64.min)
  val max = Satoshis(Int64.max)
  val zero = Satoshis(Int64.zero)
  val one = Satoshis(Int64.one)

  override def fromBytes(bytes : Seq[Byte]): Satoshis = RawSatoshisSerializer.read(bytes)

  def apply(int64: Int64): Satoshis = SatoshisImpl(int64)
}

sealed trait Bitcoins extends CurrencyUnit {
  override type A = BigDecimal
  override def satoshis: Satoshis = {
    val sat = underlying * CurrencyUnits.btcToSatoshiScalar
    Satoshis(Int64(sat.toLongExact))
  }
  override def hex = satoshis.hex
}

object Bitcoins extends BaseNumbers[Bitcoins] {
  val min = Bitcoins((-Consensus.maxMoney).satoshis)
  val max = Bitcoins(Consensus.maxMoney.satoshis)
  val zero = Bitcoins(Satoshis.zero)
  val one = Bitcoins(1)
  private case class BitcoinsImpl(underlying: BigDecimal) extends Bitcoins

  def apply(underlying: BigDecimal): Bitcoins = BitcoinsImpl(underlying)

  def apply(satoshis: Satoshis): Bitcoins = {
    val b = satoshis.underlying.underlying * CurrencyUnits.satoshisToBTCScalar
    Bitcoins(b)
  }
}


object CurrencyUnits {

  /** The number you need to multiply BTC by to get it's satoshis */
  val btcToSatoshiScalar: Long = 100000000
  val satoshisToBTCScalar: BigDecimal = BigDecimal(1.0) / btcToSatoshiScalar
  val oneBTC: CurrencyUnit = Satoshis(Int64(btcToSatoshiScalar))
  val oneMBTC: CurrencyUnit = Satoshis(Int64(btcToSatoshiScalar / 1000))
  val zero: CurrencyUnit = Satoshis.zero
  val negativeSatoshi = Satoshis(Int64(-1))

  def toSatoshis(unit : CurrencyUnit): Satoshis = unit match {
    case b: Bitcoins => b.satoshis
    case x: Satoshis => x
  }
}
