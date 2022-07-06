package evo.derivation.circe

import io.circe.Decoder
import evo.derivation.Config
import scala.compiletime.{summonFrom, erasedValue, summonInline}
import evo.derivation.internal.underiveableError
import scala.deriving.Mirror
import evo.derivation.LazySummon.LazySummonByInstance
import evo.derivation.LazySummon.LazySummonByConfig
import evo.derivation.LazySummon
import io.circe.Encoder
import evo.derivation.internal.tupleFromProduct
import io.circe.Json
import evo.derivation.Config.FieldInfo
import io.circe.syntax._
import io.circe.JsonObject.apply
import io.circe.JsonObject
import evo.derivation.circe.EvoEncoder.SumEncoder
import evo.derivation.internal.Matching
import evo.derivation.internal.mirroredNames

trait EvoEncoder[A] extends Encoder.AsObject[A]

object EvoEncoder:
    inline def derived[A](using config: => Config[A]): EvoEncoder[A] =
        summonFrom {
            case mirror: Mirror.ProductOf[A] => deriveForProduct[A].instance
            case given Mirror.SumOf[A]       => deriveForSum[A]
            case _                           => underiveableError[EvoEncoder[A], A]
        }

    inline given [A: Mirror.ProductOf]: LazySummonByConfig[EvoEncoder, A] = deriveForProduct[A]

    private inline def deriveForSum[A](using config: => Config[A], mirror: Mirror.SumOf[A]): EvoEncoder[A] =
        given Matching[A] = Matching.create[A]

        val fieldInstances =
            LazySummon.all[mirror.MirroredElemLabels, A, Encoder, EvoEncoder, mirror.MirroredElemTypes]

        val names = mirroredNames[A]

        new SumEncoder[A](fieldInstances.toMap(names))

    private inline def deriveForProduct[A](using
        mirror: Mirror.ProductOf[A],
    ): LazySummonByConfig[EvoEncoder, A] =
        val fieldInstances =
            LazySummon.all[mirror.MirroredElemLabels, A, Encoder, EvoEncoder, mirror.MirroredElemTypes]

        ProductEncoder[A](using mirror)(using summonInline[A <:< Product])(fieldInstances)

    class ProductEncoder[A](using mirror: Mirror.ProductOf[A])(using A <:< Product)(
        fieldInstances: LazySummon.All[Encoder, mirror.MirroredElemTypes],
    ) extends LazySummonByConfig[EvoEncoder, A]:

        private def encodeField(info: FieldInfo, json: Json): Vector[(String, Json)] =
            json.asObject match
                case Some(obj) if info.embed => obj.toVector
                case _                       => Vector(info.name -> json)

        def instance(using config: => Config[A]): EvoEncoder[A] =
            lazy val infos = config.top.fieldInfos
            a =>
                val fields = tupleFromProduct(a)
                type Res = Vector[(String, Json)]
                val results = fieldInstances.useCollect[Res, FieldInfo](fields, infos)(
                  [X] => (info: FieldInfo, a: X, enc: Encoder[X]) => encodeField(info, enc(a)),
                )
                JsonObject.fromIterable(results.flatten)

    end ProductEncoder

    class SumEncoder[A](
        mkSubEncoders: => Map[String, Encoder[A]],
    )(using config: => Config[A], mirror: Mirror.SumOf[A], matching: Matching[A])
        extends EvoEncoder[A]:
        lazy val cfg      = config
        lazy val encoders = mkSubEncoders

        override def encodeObject(a: A): JsonObject =
            val constructor = matching.matched(a)
            val prod        = cfg.constructor(constructor).top

            val discrimValue = cfg.name(constructor)

            val json = encoders.get(constructor).fold(Json.fromJsonObject(JsonObject.empty))(_.apply(a))

            cfg.discriminator.zip(json.asObject) match
                case Some(field -> obj) =>
                    (field -> Json.fromString(discrimValue)) +: obj
                case None               =>
                    JsonObject.singleton(discrimValue, json)

    end SumEncoder

end EvoEncoder