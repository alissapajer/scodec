package scodec
package codecs

import scalaz.{ \/-, -\/ }

import shapeless._
import ops.hlist.{Prepend, RightFolder, Init, Last, Length, Split, FilterNot}
import UnaryTCConstraint._

import scodec.bits.BitVector
import scodec.HListOps._

private[scodec] object HListCodec {

  val hnilCodec: Codec[HNil] = new Codec[HNil] {
    override def encode(hn: HNil) = \/-(BitVector.empty)
    override def decode(buffer: BitVector) = \/-((buffer, HNil))
    override def toString = s"HNil"
  }

  def prepend[A, L <: HList](a: Codec[A], l: Codec[L]): Codec[A :: L] = new Codec[A :: L] {
    override def encode(xs: A :: L) = Codec.encodeBoth(a, l)(xs.head, xs.tail)
    override def decode(buffer: BitVector) = Codec.decodeBothCombine(a, l)(buffer) { _ :: _ }
    override def toString = s"$a :: $l"
  }

  object PrependCodec extends Poly2 {
    implicit def caseCodecAndCodecHList[A, L <: HList] = at[Codec[A], Codec[L]](prepend)
  }

  def append[L <: HList, A, LA <: HList](l: Codec[L], a: Codec[A])(implicit
    prepend: Prepend.Aux[L, A :: HNil, LA],
    init: Init.Aux[LA, L],
    last: Last.Aux[LA, A]
  ): Codec[LA] = new Codec[LA] {
    override def encode(xs: LA) = Codec.encodeBoth(l, a)(xs.init, xs.last)
    override def decode(buffer: BitVector) = Codec.decodeBothCombine(l, a)(buffer) { _ :+ _ }
    override def toString = s"append($l, $a)"
  }

  def concat[K <: HList, L <: HList, KL <: HList, KLen <: Nat](ck: Codec[K], cl: Codec[L])(implicit
    prepend: Prepend.Aux[K, L, KL],
    lengthK: Length.Aux[K, KLen],
    split: Split.Aux[KL, KLen, K, L]
  ): Codec[KL] = new Codec[KL] {
    override def encode(xs: KL) = {
      val (k, l) = xs.split[KLen]
      Codec.encodeBoth(ck, cl)(k, l)
    }
    override def decode(buffer: BitVector) = Codec.decodeBothCombine(ck, cl)(buffer) { _ ::: _ }
    override def toString = s"concat($ck, $cl)"
  }

  def flatPrepend[A, L <: HList](codecA: Codec[A], f: A => Codec[L]): Codec[A :: L] = new Codec[A :: L] {
    override def encode(xs: A :: L) = Codec.encodeBoth(codecA, f(xs.head))(xs.head, xs.tail)
    override def decode(buffer: BitVector) = (for {
      a <- DecodingContext(codecA.decode)
      l <- DecodingContext(f(a).decode)
    } yield a :: l).run(buffer)
    override def toString = s"flatPrepend($codecA, $f)"
  }

  def flatConcat[K <: HList, L <: HList, KL <: HList, KLen <: Nat](codecK: Codec[K], f: K => Codec[L])(implicit
    prepend: Prepend.Aux[K, L, KL],
    lengthK: Length.Aux[K, KLen],
    split: Split.Aux[KL, KLen, K, L]
  ): Codec[KL] = new Codec[KL] {
    override def encode(xs: KL) = {
      val (k, l) = xs.split[KLen]
      Codec.encodeBoth(codecK, f(k))(k, l)
    }
    override def decode(buffer: BitVector) = (for {
      k <- DecodingContext(codecK.decode)
      l <- DecodingContext(f(k).decode)
    } yield k ::: l).run(buffer)
    override def toString = s"flatConcat($codecK, $f)"
  }

  def flatAppend[L <: HList, A, LA <: HList, Len <: Nat](codecL: Codec[L], f: L => Codec[A])(implicit
    prepend: Prepend.Aux[L, A :: HNil, LA],
    length: Length.Aux[L, Len],
    split: Split.Aux[LA, Len, L, A :: HNil]
  ): Codec[LA] = new Codec[LA] {
    override def encode(xs: LA) = {
      val (l, rest) = xs.split[Len]
      Codec.encodeBoth(codecL, f(l))(l, rest.head)
    }
    override def decode(buffer: BitVector) = (for {
      l <- DecodingContext(codecL.decode)
      a <- DecodingContext(f(l).decode)
    } yield l :+ a).run(buffer)
    override def toString = s"flatConcat($codecL, $f)"
  }

  def apply[L <: HList : *->*[Codec]#λ, M <: HList](l: L)(implicit folder: RightFolder.Aux[L, Codec[HNil], PrependCodec.type, Codec[M]]): Codec[M] = {
    l.foldRight(hnilCodec)(PrependCodec)
  }

  def dropUnits[K <: HList, L <: HList](codec: Codec[K])(implicit fltr: FilterNot.Aux[K, Unit, L], ru: ReUnit[L, K]) =
    codec.xmap[L](_.filterNot[Unit], _.reUnit[K])
}

/**
 * Converts an `HList` of codecs in to a single codec.
 * That is, converts `Codec[X0] :: Codec[X1] :: ... :: Codec[Xn] :: HNil` in to a `Codec[X0 :: X1 :: ... :: Xn :: HNil].
 */
trait ToHListCodec[In <: HList] extends DepFn1[In] {
  type L <: HList
  type Out = Codec[L]
}

/** Companion for [[ToHListCodec]]. */
object ToHListCodec {
  type Aux[In0 <: HList, L0 <: HList] = ToHListCodec[In0] { type L = L0 }

  implicit def mk[I <: HList, L0 <: HList](implicit
    allCodecs: *->*[Codec]#λ[I],
    folder: RightFolder.Aux[I, Codec[HNil], HListCodec.PrependCodec.type, Codec[L0]]
  ): ToHListCodec.Aux[I, L0] = new ToHListCodec[I] {
    type L = L0
    def apply(i: I): Codec[L0] = HListCodec(i)
  }
}

