/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.IntEnumShape
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.SensitiveTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.util.isStreaming
import software.amazon.smithy.rust.codegen.core.util.hasTrait

/**
 * Attach `meta` to symbols. `meta` is used by the generators (e.g. StructureGenerator) to configure the generated models.
 *
 * Protocols may inherit from this class and override the `xyzMeta` methods to modify structure generation.
 */
abstract class SymbolMetadataProvider(private val base: RustSymbolProvider) : WrappingSymbolProvider(base) {
    override fun toSymbol(shape: Shape): Symbol {
        val baseSymbol = base.toSymbol(shape)
        val meta =
            when (shape) {
                is MemberShape -> memberMeta(shape)
                is StructureShape -> structureMeta(shape)
                is UnionShape -> unionMeta(shape)
                is ListShape -> listMeta(shape)
                is MapShape -> mapMeta(shape)
                is NumberShape -> numberMeta(shape)
                is BlobShape -> blobMeta(shape)
                is StringShape ->
                    if (shape.hasTrait<EnumTrait>()) {
                        enumMeta(shape)
                    } else {
                        stringMeta(shape)
                    }

                else -> null
            }
        return baseSymbol.toBuilder().meta(meta).build()
    }

    abstract fun memberMeta(memberShape: MemberShape): RustMetadata

    abstract fun structureMeta(structureShape: StructureShape): RustMetadata

    abstract fun unionMeta(unionShape: UnionShape): RustMetadata

    abstract fun enumMeta(stringShape: StringShape): RustMetadata

    abstract fun listMeta(listShape: ListShape): RustMetadata

    abstract fun mapMeta(mapShape: MapShape): RustMetadata

    abstract fun stringMeta(stringShape: StringShape): RustMetadata

    abstract fun numberMeta(numberShape: NumberShape): RustMetadata

    abstract fun blobMeta(blobShape: BlobShape): RustMetadata
}

fun containerDefaultMetadata(
    shape: Shape,
    model: Model,
    additionalAttributes: List<Attribute> = emptyList(),
): RustMetadata {
    val derives = mutableSetOf(RuntimeType.Debug, RuntimeType.PartialEq, RuntimeType.Clone)

    val isSensitive =
        shape.hasTrait<SensitiveTrait>() ||
            // Checking the shape's direct members for the sensitive trait should suffice.
            // Whether their descendants, i.e. a member's member, is sensitive does not
            // affect the inclusion/exclusion of the derived `Debug` trait of _this_ container
            // shape; any sensitive descendant should still be printed as redacted.
            shape.members().any { it.getMemberTrait(model, SensitiveTrait::class.java).isPresent }

    if (isSensitive) {
        derives.remove(RuntimeType.Debug)
    }

    // Add conditional serde derives
    val allAttributes = additionalAttributes.toMutableList()

    // Add serde serialize attribute: #[cfg_attr(all(aws_sdk_unstable, feature = "serde-serialize"), derive(serde::Serialize))]
    allAttributes.add(
        Attribute(
            Attribute.cfgAttr(
                Attribute.feature("serde-serialize"),
                Attribute.derive(RuntimeType.SerdeSerialize),
            ),
        )
    )

    // Add serde deserialize attribute: #[cfg_attr(all(aws_sdk_unstable, feature = "serde-deserialize"), derive(serde::Deserialize))]
    allAttributes.add(
        Attribute(
            Attribute.cfgAttr(
                Attribute.feature("serde-deserialize"),
                Attribute.derive(RuntimeType.SerdeDeserialize),
            ),
        )
    )

    return RustMetadata(derives, allAttributes, Visibility.PUBLIC)
}

fun containerDefaultMetadataWithoutDeserialize(
    shape: Shape,
    model: Model,
    additionalAttributes: List<Attribute> = emptyList(),
): RustMetadata {
    val derives = mutableSetOf(RuntimeType.Debug, RuntimeType.PartialEq, RuntimeType.Clone)

    val isSensitive =
        shape.hasTrait<SensitiveTrait>() ||
            // Checking the shape's direct members for the sensitive trait should suffice.
            // Whether their descendants, i.e. a member's member, is sensitive does not
            // affect the inclusion/exclusion of the derived `Debug` trait of _this_ container
            // shape; any sensitive descendant should still be printed as redacted.
            shape.members().any { it.getMemberTrait(model, SensitiveTrait::class.java).isPresent }

    if (isSensitive) {
        derives.remove(RuntimeType.Debug)
    }

    // Add conditional serde derives (only serialize, not deserialize)
    val allAttributes = additionalAttributes.toMutableList()

    // Add serde serialize attribute: #[cfg_attr(all(aws_sdk_unstable, feature = "serde-serialize"), derive(serde::Serialize))]
    allAttributes.add(
        Attribute(
            Attribute.cfgAttr(
                Attribute.feature("serde-serialize"),
                Attribute.derive(RuntimeType.SerdeSerialize),
            ),
        )
    )

    // Don't add serde deserialize attribute for structures containing EventStreamSender

    return RustMetadata(derives, allAttributes, Visibility.PUBLIC)
}

/**
 * The base metadata supports a set of attributes that are used by generators to decorate code.
 *
 * By default, we apply `#[non_exhaustive]` in [additionalAttributes] only to client structures since breaking model
 * changes are fine when generating server code.
 */
class BaseSymbolMetadataProvider(
    private val base: RustSymbolProvider,
    private val additionalAttributes: List<Attribute>,
) : SymbolMetadataProvider(base) {
    override fun memberMeta(memberShape: MemberShape): RustMetadata {
        val baseMetadata = when (val container = model.expectShape(memberShape.container)) {
            is StructureShape -> RustMetadata(visibility = Visibility.PUBLIC)

            is UnionShape, is CollectionShape, is MapShape -> RustMetadata(visibility = Visibility.PUBLIC)

            // This covers strings with the enum trait for now, and can be removed once we're fully on EnumShape
            // TODO(https://github.com/smithy-lang/smithy-rs/issues/1700): Remove this `is StringShape` match arm
            is StringShape -> RustMetadata(visibility = Visibility.PUBLIC)
            is IntEnumShape -> RustMetadata(visibility = Visibility.PUBLIC)

            else -> TODO("Unrecognized container type: $container")
        }

        // Add serde(skip) to ByteStream and EventReceiver/EventStreamSender fields when serde features are enabled
        val memberSymbol = base.toSymbol(memberShape)
        val isEventReceiver = memberSymbol.rustType().let { rustType ->
            rustType is RustType.Application && rustType.type.name == "EventReceiver"
        }
        val isEventStreamSender = memberSymbol.rustType().let { rustType ->
            rustType is RustType.Application && rustType.type.name == "EventStreamSender"
        }
        
        return if (memberShape.isStreaming(model) || isEventReceiver || isEventStreamSender) {
            val serdeSkipAttributes = listOf(
                Attribute(
                    Attribute.cfgAttr(
                        Attribute.any(Attribute.feature("serde-serialize"), Attribute.feature("serde-deserialize")),
                        Attribute.serde("skip")
                    )
                )
            )
            baseMetadata.copy(additionalAttributes = baseMetadata.additionalAttributes + serdeSkipAttributes)
        } else {
            baseMetadata
        }
    }

    override fun structureMeta(structureShape: StructureShape): RustMetadata {
        // Check if structure contains EventStreamSender members
        val hasEventStreamSender = structureShape.members().any { memberShape ->
            val memberSymbol = base.toSymbol(memberShape)
            memberSymbol.rustType().let { rustType ->
                rustType is RustType.Application && rustType.type.name == "EventStreamSender"
            }
        }
        
        return if (hasEventStreamSender) {
            // For structures containing EventStreamSender, don't add serde::Deserialize
            containerDefaultMetadataWithoutDeserialize(structureShape, model, additionalAttributes)
        } else {
            containerDefaultMetadata(structureShape, model, additionalAttributes)
        }
    }

    override fun unionMeta(unionShape: UnionShape) = containerDefaultMetadata(unionShape, model, additionalAttributes)

    override fun enumMeta(stringShape: StringShape): RustMetadata =
        containerDefaultMetadata(stringShape, model, additionalAttributes).withDerives(
            // Smithy's `enum` shapes can additionally be `Eq`, `PartialOrd`, `Ord`, and `Hash` because they can
            // only contain strings.
            RuntimeType.Eq,
            RuntimeType.PartialOrd,
            RuntimeType.Ord,
            RuntimeType.Hash,
        )

    // Only the server subproject uses these, so we provide a sane and conservative default implementation here so that
    // the rest of symbol metadata providers can just delegate to it.
    private fun defaultRustMetadata() = RustMetadata(visibility = Visibility.PRIVATE)

    override fun listMeta(listShape: ListShape) = defaultRustMetadata()

    override fun mapMeta(mapShape: MapShape) = defaultRustMetadata()

    override fun stringMeta(stringShape: StringShape) = defaultRustMetadata()

    override fun numberMeta(numberShape: NumberShape) = defaultRustMetadata()

    override fun blobMeta(blobShape: BlobShape) = defaultRustMetadata()
}

private const val META_KEY = "meta"

fun Symbol.Builder.meta(rustMetadata: RustMetadata?): Symbol.Builder = this.putProperty(META_KEY, rustMetadata)

fun Symbol.expectRustMetadata(): RustMetadata =
    this.getProperty(META_KEY, RustMetadata::class.java).orElseThrow {
        CodegenException(
            "Expected `$this` to have metadata attached but it did not.",
        )
    }
