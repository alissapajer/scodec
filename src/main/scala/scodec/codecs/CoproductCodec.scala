package scodec
package codecs

import scalaz.\/
import scalaz.syntax.std.option._

import shapeless._
import labelled.FieldType
import record._
import ops.coproduct._
import ops.hlist._

import scodec.bits._

private[scodec] object CoproductCodec {

  /** Calcuates the type index of coproduct value `c` in coproduct type `C`. */
  private[scodec] def indexOf[C <: Coproduct](c: C): Int = {
    def go[CC <: Coproduct](c: CC, idx: Int): Int = c match {
      case Inl(_) => idx
      case Inr(t) => go(t, idx + 1)
    }
    go(c, 0)
  }

  private def encodeCoproduct[C <: Coproduct](codecs: List[Codec[C]], c: C): Err \/ BitVector = {
    val index = indexOf(c)
    for {
      codec <- codecs.lift(index).toRightDisjunction(Err(s"Not possible - index $index is out of bounds"))
      encValue <- codec.encode(c)
    } yield encValue
  }

  /** Creates a coproduct codec that uses the type index of the coproduct as the discriminator. */
  def indexBased[C <: Coproduct, L <: HList](codecs: L, discriminatorCodec: Codec[Int])(
    implicit aux: ToCoproductCodecs[C, L]
  ): Codec[C] = new Discriminated[C, L, Int](codecs, discriminatorCodec, indexOf, Some.apply)

  /** Codec that encodes/decodes a coproduct `C` discriminated by `A`. */
  private[scodec] class Discriminated[C <: Coproduct, L <: HList, A](
    codecs: L,
    discriminatorCodec: Codec[A],
    coproductToDiscriminator: C => A,
    discriminatorToIndex: A => Option[Int]
  )(implicit aux: ToCoproductCodecs[C, L]) extends Codec[C] {

    private val liftedCodecs: List[Codec[C]] = aux(codecs)

    def encode(c: C) = {
      val discriminator = coproductToDiscriminator(c)
      for {
        encDiscriminator <- discriminatorCodec.encode(discriminator)
        encValue <- encodeCoproduct(liftedCodecs, c)
      } yield encDiscriminator ++ encValue
    }

    def decode(buffer: BitVector) = (for {
      discriminator <- DecodingContext(discriminatorCodec.decode)
      index <- DecodingContext.liftE(discriminatorToIndex(discriminator).toRightDisjunction(Err(s"Unsupported discriminator $discriminator")))
      decoder <- DecodingContext.liftE(liftedCodecs.lift(index).toRightDisjunction(Err(s"Unsupported index $index (for discriminator $discriminator)")))
      value <- DecodingContext(decoder.decode)
    } yield value).run(buffer)

    override def toString = liftedCodecs.mkString("(", " :+: ", ")") + s" by $discriminatorCodec"
  }

  /** Codec that encodes/decodes a coproduct `C`. */
  private[scodec] class Choice[C <: Coproduct, L <: HList](
    codecs: L
  )(implicit aux: ToCoproductCodecs[C, L]) extends Codec[C] {

    private val liftedCodecs: List[Codec[C]] = aux(codecs)
    private val decoder: Decoder[C] = Decoder.choiceDecoder(liftedCodecs: _*)

    def encode(c: C) = encodeCoproduct(liftedCodecs, c)

    def decode(buffer: BitVector) = decoder.decode(buffer)

    override def toString = liftedCodecs.mkString("choice(", " :+: ", ")")
  }
}

/** Witness that allows converting an `HList` of codecs in to a list of coproduct codecs, where the coproduct is type aligned with the `HList`. */
sealed trait ToCoproductCodecs[C <: Coproduct, L <: HList] {
  def apply(l: L): List[Codec[C]]
}

/** Companion for [[ToCoproductCodecs]]. */
object ToCoproductCodecs {
  implicit val base: ToCoproductCodecs[CNil, HNil] = new ToCoproductCodecs[CNil, HNil] {
    def apply(hnil: HNil) = Nil
  }

  implicit def step[A, CT <: Coproduct, LT <: HList](
    implicit tailAux: ToCoproductCodecs[CT, LT],
    inj: ops.coproduct.Inject[A :+: CT, A]
  ): ToCoproductCodecs[A :+: CT, Codec[A] :: LT] = new ToCoproductCodecs[A :+: CT, Codec[A] :: LT] {
    def apply(l: Codec[A] :: LT): List[Codec[A :+: CT]] = {

      val headCodec: Codec[A :+: CT] = new Codec[A :+: CT] {
        val codec: Codec[A] = l.head
        def encode(c: A :+: CT) = c match {
          case Inl(a) => codec.encode(a)
          case Inr(ct) => \/.left(Err(s"cannot encode $ct"))
        }
        def decode(buffer: BitVector) =
          codec.decode(buffer).map { case (rem, a) => (rem, Coproduct[A :+: CT](a)) }
        override def toString = codec.toString
      }

      val tailCodecs: List[Codec[A :+: CT]] = tailAux(l.tail).map { d: Codec[CT] =>
        new Codec[A :+: CT] {
          def encode(c: A :+: CT) = c match {
            case Inr(a) => d.encode(a)
            case Inl(ch) => \/.left(Err(s"cannot encode $c"))
          }
          def decode(buffer: BitVector) =
            d.decode(buffer).map { case (rem, a) => (rem, Inr(a): A :+: CT) }
          override def toString = d.toString
        }
      }

      headCodec :: tailCodecs
    }
  }
}

/**
 * Supports building a coproduct codec.
 *
 * A coproduct codec is built by:
 *  - specifying a codec for each member of the coproduct, separated by the `:+:` operator
 *  - specifying the discriminator codec and mapping between discriminator values and coproduct members
 *  - alternatively, instead of specifying a discriminator codec, using the `choice` combinator to create
 *    a codec that encodes no discriminator and hence, decodes by trying each codec in succession and using
 *    the first successful result
 *
 * To specify the discriminator, call either `discriminatedByIndex(intCodec)` or `discriminatedBy(codec).using(Sized(...))`.
 * The former uses the type index as the discriminator value.
 *
 * For example: {{{
(int32 :+: bool(8) :+: variableSizeBytes(uint8, ascii)).discriminatedByIndex(uint8)
 }}}
 * The first 8 bits of the resulting binary contains the discriminator value due to usage of the `uint8` codec as
 * the discriminator codec. A discriminator value of 0 causes the remaining bits to be encoded/decoded with `int32`.
 * Similarly, a value of 1 causes the remaining bits to be encoded/decoded with `bool(8)` and a value of 2 causes
 * the remaining bits to be encoded/decoded as a sized ASCII string.
 *
 * Alternatively, discriminator values can be explicitly specified using `discriminatedBy(codec).using(Sized(...))`.
 *
 * For example: {{{
 (int32 :+: bool(8) :+: variableSizeBytes(uint8, ascii)).discriminatedBy(fixedSizeBytes(1, ascii)).using(Sized("i", "b", "s"))
 }}}
 * In this example, integers are associated with the discriminator `i`, booleans with `b`, and strings with `s`. The discriminator
 * is encoded with `fixedSizeBytes(1, ascii)`.
 *
 * The methods which generate a `Codec` return a `Codec[R]` instead of a `Codec[C]`. Typically, `C =:= R` but the `xmap` and
 * `exmap` methods allow transformations between `C` and `R` to be deferred until after the codec is built.
 *
 * @tparam C coproduct type
 * @tparam L hlist type that has a codec for each type in the coproduct `C`
 * @tparam R resulting codec type
 */
final class CoproductCodecBuilder[C <: Coproduct, L <: HList, R] private[scodec] (
  codecs: L, cToR: C => Err \/ R, rToC: R => Err \/ C
)(implicit aux: ToCoproductCodecs[C, L]) {

  private def toR(c: Codec[C]): Codec[R] = c.exmap(cToR, rToC)

  /** Adds a codec to the head of this coproduct codec. */
  def :+:[A](left: Codec[A]): CoproductCodecBuilder[A :+: C, Codec[A] :: L, A :+: C] =
    CoproductCodecBuilder(left :: codecs)

  /**
   * Creates the coproduct codec using the specified integer codec as the discriminator codec
   * and using coproduct indices as discriminators.
   *
   * For example, `(a :+: b :+: c).discriminatedByIndex(uint8)` results in using `0` for `a`,
   * `1` for `b`, and `2` for `c`.
   */
  def discriminatedByIndex(discriminatorCodec: Codec[Int]): Codec[R] =
    toR(CoproductCodec.indexBased(codecs, discriminatorCodec))

  /** Supports creation of a coproduct codec that uses an arbitrary discriminator. */
  def discriminatedBy[A](discriminatorCodec: Codec[A]): NeedDiscriminators[A] =
    new NeedDiscriminators(discriminatorCodec)

  /** Assists in creating a coproduct codec, after the coproduct type and discriminator type have been fixed. */
  final class NeedDiscriminators[A] private[CoproductCodecBuilder] (discriminatorCodec: Codec[A]) {

    /**
     * Specifies the discriminator values for each of the coproduct type members.
     *
     * The collection must list the discriminators in the order that the corresponding types appear in the coproduct.
     */
    def using[N <: Nat](discriminators: Sized[Seq[A], N])(implicit ev: ops.coproduct.Length.Aux[C, N]): Codec[R] =
      usingUnsafe(discriminators.seq)

    /**
     * Specifies the discriminator values for each of the union type members by providing the discriminators
     * as a record with the same keys as the union.
     */
    def using[L <: HList](bindings: L)(implicit keyDiscriminators: CoproductBuilderKeyDiscriminators[C, L, A]): Codec[R] =
      toR(new CoproductCodec.Discriminated(
        codecs,
        discriminatorCodec,
        keyDiscriminators.toDiscriminator(bindings),
        (a: A) => keyDiscriminators.fromDiscriminator(bindings)(a, 0)))

    /**
     * Determines discriminators values automatically by looking for a `Discriminator[R, X, A]`
     * for each component type `X` in the coproduct `C`.
     */
    def auto(implicit auto: CoproductBuilderAutoDiscriminators[R, C, A]): Codec[R] = usingUnsafe(auto.discriminators)

    /** Unsafe version of `using` -- discriminators must be equal in length to the number of components in `C`. */
    private def usingUnsafe(discriminators: Seq[A]): Codec[R] = {
      val toDiscriminator: C => A = c => discriminators(CoproductCodec.indexOf(c))
      val fromDiscriminator: A => Option[Int] = a => {
        val idx = discriminators.indexWhere { (x: A) => x == a }
        if (idx >= 0) Some(idx) else None
      }
      toR(new CoproductCodec.Discriminated(codecs, discriminatorCodec, toDiscriminator, fromDiscriminator))
    }
  }

  /**
   * Creates a coproduct codec that encodes no discriminator. Rather, decoding is accomplished by
   * trying each codec in order and using the first successful result.
   */
  def choice: Codec[R] = toR(new CoproductCodec.Choice(codecs))

  /**
   * Creates a builder that applies the specified transformations to any codecs generated by the returned builder.
   */
  def xmap[S](rToS: R => S, sToR: S => R): CoproductCodecBuilder[C, L, S] =
    new CoproductCodecBuilder(codecs, c => cToR(c) map rToS, s => rToC(sToR(s)))

  /**
   * Creates a builder that applies the specified transformations to any codecs generated by the returned builder.
   */
  def exmap[S](rToS: R => Err \/ S, sToR: S => Err \/ R): CoproductCodecBuilder[C, L, S] =
    new CoproductCodecBuilder(codecs, c => cToR(c) flatMap rToS, s => sToR(s) flatMap rToC)
}

/** Companion for [[CoproductCodecBuilder]]. */
object CoproductCodecBuilder {
  def apply[C <: Coproduct, L <: HList](codecs: L)(implicit aux: ToCoproductCodecs[C, L]): CoproductCodecBuilder[C, L, C] =
    new CoproductCodecBuilder(codecs, \/.right, \/.right)
}

/** Witness for `CoproductCodecBuilder#NeedDiscriminators#using`. */
sealed trait CoproductBuilderKeyDiscriminators[C <: Coproduct, L <: HList, A] {
  def toDiscriminator(bindings: L)(c: C): A
  def fromDiscriminator(bindings: L)(a: A, idx: Int): Option[Int]
}

/** Companion for [[CoproductBuilderKeyDiscriminators]]. */
object CoproductBuilderKeyDiscriminators {
  implicit def nil[A]: CoproductBuilderKeyDiscriminators[CNil, HNil, A] =
    new CoproductBuilderKeyDiscriminators[CNil, HNil, A] {
      def toDiscriminator(bindings: HNil)(c: CNil): A = sys.error("impossible")
      def fromDiscriminator(bindings: HNil)(a: A, idx: Int): Option[Int] = None
    }

  implicit def step[K <: Symbol, V, CT <: Coproduct, L <: HList, LT <: HList, A](implicit
    remover: ops.record.Remover.Aux[L, K, (A, LT)],
    tailDiscriminators: CoproductBuilderKeyDiscriminators[CT, LT, A]
  ): CoproductBuilderKeyDiscriminators[FieldType[K, V] :+: CT, L, A] =
    new CoproductBuilderKeyDiscriminators[FieldType[K, V] :+: CT, L, A] {
      def toDiscriminator(bindings: L)(c: FieldType[K, V] :+: CT): A = {
        val (binding, restBindings) = remover(bindings)
        c match {
          case Inl(_) => binding
          case Inr(ct) => tailDiscriminators.toDiscriminator(restBindings)(ct)
        }
      }
      def fromDiscriminator(bindings: L)(a: A, idx: Int): Option[Int] = {
        val (binding, restBindings) = remover(bindings)
        if (binding == a) Some(idx)
        else tailDiscriminators.fromDiscriminator(restBindings)(a, idx + 1)
      }
    }
}

/** Witness for `CoproductCodecBuilder#NeedDiscriminators#auto`. */
sealed trait CoproductBuilderAutoDiscriminators[X, C <: Coproduct, A] {
  def discriminators: List[A]
}

/** Companion for [[CoproductBuilderAutoDiscriminators]]. */
object CoproductBuilderAutoDiscriminators {

  implicit def cnil[X, A]: CoproductBuilderAutoDiscriminators[X, CNil, A] =
    new CoproductBuilderAutoDiscriminators[X, CNil, A] {
      def discriminators = Nil
    }

  implicit def coproduct[X, A, CH, CT <: Coproduct](implicit
    headDiscriminator: Discriminator[X, CH, A],
    tailAuto: CoproductBuilderAutoDiscriminators[X, CT, A]
  ): CoproductBuilderAutoDiscriminators[X, CH :+: CT, A] =
    new CoproductBuilderAutoDiscriminators[X, CH :+: CT, A] {
      def discriminators = headDiscriminator.value :: tailAuto.discriminators
    }

  implicit def union[X, A, K, V, CT <: Coproduct](implicit
    headDiscriminator: Discriminator[X, V, A],
    tailAuto: CoproductBuilderAutoDiscriminators[X, CT, A]
  ): CoproductBuilderAutoDiscriminators[X, FieldType[K, V] :+: CT, A] =
    new CoproductBuilderAutoDiscriminators[X, FieldType[K, V] :+: CT, A] {
      def discriminators = headDiscriminator.value :: tailAuto.discriminators
    }
}
