package org.tiqian.math.font.tooling

import java.security.MessageDigest
import org.tiqian.math.font.opentype.MathDeviceAdjustment
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphKernInfo
import org.tiqian.math.font.opentype.MathKernTable
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.OpenTypeMathReader

data class BakedOpenTypeMathMetadata(
    val fontSha256: String,
    val snapshotBytes: ByteArray,
)

/** Build-time entry point. Runtime artifacts contain only the matching decoder. */
object OpenTypeMathMetadataBaker {
    fun bake(fontBytes: ByteArray): BakedOpenTypeMathMetadata = bake(fontBytes, fontBytes)

    /**
     * Compiles layout tables from [layoutFontBytes] into a snapshot attached to
     * [runtimeFontBytes]. Both faces must preserve glyph ids, cmap, advances, and outlines.
     * That invariant is checked by the font compilation step before this baker runs.
     */
    fun bake(
        layoutFontBytes: ByteArray,
        runtimeFontBytes: ByteArray,
    ): BakedOpenTypeMathMetadata {
        val digest = MessageDigest.getInstance("SHA-256").digest(runtimeFontBytes).toHex()
        return BakedOpenTypeMathMetadata(
            fontSha256 = digest,
            snapshotBytes = OpenTypeMathSnapshotEncoder.encode(
                digest,
                OpenTypeMathReader().read(layoutFontBytes),
            ),
        )
    }
}
private object OpenTypeMathSnapshotEncoder {
    private const val FormatVersion = 3
    private val Magic = byteArrayOf(0x54, 0x51, 0x4D, 0x41, 0x54, 0x48, 0x00, 0x01)

    fun encode(fontSha256: String, font: OpenTypeMathFont): ByteArray {
        require(fontSha256.length == 64 && fontSha256.all { it in '0'..'9' || it in 'a'..'f' })
        val writer = SnapshotWriter()
        writer.writeBytes(Magic)
        writer.writeFixedInt(FormatVersion)
        writer.writeString(fontSha256)
        writer.writeInt(font.unitsPerEm)
        writer.writeInt(font.lineMetrics.typoAscender)
        writer.writeInt(font.lineMetrics.typoDescender)
        writer.writeInt(font.lineMetrics.typoLineGap)
        writer.writeNullableInt(font.xHeight)
        writer.writeConstants(font.constants)
        writer.writeGlyphIntMap(font.italicCorrections)
        writer.writeDeviceAdjustmentMap(font.italicCorrectionDeviceAdjustments)
        writer.writeGlyphSet(font.unsupportedItalicCorrectionVariationAdjustments)
        writer.writeGlyphSet(font.extendedShapeGlyphs)
        writer.writeMathKernMap(font.mathKernInfo)
        writer.writeConstructionMap(font.verticalConstructions)
        writer.writeGlyphIntMap(font.topAccentAttachments)
        writer.writeDeviceAdjustmentMap(font.topAccentAttachmentDeviceAdjustments)
        writer.writeGlyphSet(font.unsupportedTopAccentAttachmentVariationAdjustments)
        writer.writeConstructionMap(font.horizontalConstructions)
        writer.writeScalarGlyphMap(font.characterGlyphs)
        writer.writeGlyphAlternatesMap(font.scriptStyleAlternates)
        return writer.toByteArray()
    }
}

private class SnapshotWriter {
    private var bytes = ByteArray(4096)
    private var size = 0

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun writeBytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun writeFixedInt(value: Int) {
        ensure(4)
        bytes[size++] = (value ushr 24).toByte()
        bytes[size++] = (value ushr 16).toByte()
        bytes[size++] = (value ushr 8).toByte()
        bytes[size++] = value.toByte()
    }

    fun writeInt(value: Int) = writeVarUInt((value shl 1) xor (value shr 31))

    fun writeU16(value: UShort) = writeVarUInt(value.toInt())

    fun writeBoolean(value: Boolean) {
        ensure(1)
        bytes[size++] = if (value) 1 else 0
    }

    fun writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeCount(encoded.size)
        writeBytes(encoded)
    }

    fun writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    fun writeConstants(value: OpenTypeMathConstants) {
        with(value) {
            listOf(
                scriptPercentScaleDown, scriptScriptPercentScaleDown, delimitedSubFormulaMinHeight,
                displayOperatorMinHeight, mathLeading, axisHeight, accentBaseHeight,
                flattenedAccentBaseHeight, subscriptShiftDown, subscriptTopMax,
                subscriptBaselineDropMin, superscriptShiftUp, superscriptShiftUpCramped,
                superscriptBottomMin, superscriptBaselineDropMax, subSuperscriptGapMin,
                superscriptBottomMaxWithSubscript, spaceAfterScript, upperLimitGapMin,
                upperLimitBaselineRiseMin, lowerLimitGapMin, lowerLimitBaselineDropMin,
                stackTopShiftUp, stackTopDisplayStyleShiftUp, stackBottomShiftDown,
                stackBottomDisplayStyleShiftDown, stackGapMin, stackDisplayStyleGapMin,
                fractionNumeratorShiftUp, fractionNumeratorDisplayStyleShiftUp,
                fractionDenominatorShiftDown, fractionDenominatorDisplayStyleShiftDown,
                fractionNumeratorGapMin, fractionNumDisplayStyleGapMin, fractionRuleThickness,
                fractionDenominatorGapMin, fractionDenomDisplayStyleGapMin, overbarVerticalGap,
                overbarRuleThickness, overbarExtraAscender, underbarVerticalGap,
                underbarRuleThickness, underbarExtraDescender, radicalVerticalGap,
                radicalDisplayStyleVerticalGap, radicalRuleThickness, radicalExtraAscender,
                radicalKernBeforeDegree, radicalKernAfterDegree, radicalDegreeBottomRaisePercent,
            ).forEach(::writeInt)
        }
    }

    fun writeGlyphIntMap(value: Map<UShort, Int>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, measurement) ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
            writeInt(measurement)
        }
    }

    fun writeGlyphSet(value: Set<UShort>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.sorted().forEach { glyph ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
        }
    }

    fun writeScalarGlyphMap(value: Map<Int, UShort>) {
        writeCount(value.size)
        var previousScalar = 0
        var previousGlyph = 0
        value.entries.sortedBy { it.key }.forEach { (scalar, glyph) ->
            writeVarUInt(scalar - previousScalar)
            writeInt(glyph.toInt() - previousGlyph)
            previousScalar = scalar
            previousGlyph = glyph.toInt()
        }
    }

    fun writeGlyphAlternatesMap(value: Map<UShort, List<UShort>>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, alternates) ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
            writeCount(alternates.size)
            var previousAlternate = glyph.toInt()
            alternates.forEach { alternate ->
                writeInt(alternate.toInt() - previousAlternate)
                previousAlternate = alternate.toInt()
            }
        }
    }

    fun writeDeviceAdjustmentMap(value: Map<UShort, MathDeviceAdjustment>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, adjustment) ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
            writeInt(adjustment.startPpem)
            writeInt(adjustment.endPpem)
            writeInt(adjustment.deltaFormat)
            writeCount(adjustment.deltasPx.size)
            adjustment.deltasPx.forEach(::writeInt)
        }
    }

    fun writeMathKernMap(value: Map<UShort, MathGlyphKernInfo>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, info) ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
            writeKernTable(info.topRight)
            writeKernTable(info.topLeft)
            writeKernTable(info.bottomRight)
            writeKernTable(info.bottomLeft)
        }
    }

    private fun writeKernTable(value: MathKernTable?) {
        writeBoolean(value != null)
        if (value == null) return
        writeCount(value.correctionHeights.size)
        value.correctionHeights.forEach(::writeInt)
        writeCount(value.kernValues.size)
        value.kernValues.forEach(::writeInt)
    }

    fun writeConstructionMap(value: Map<UShort, MathGlyphConstruction>) {
        writeCount(value.size)
        var previousGlyph = 0
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, construction) ->
            writeVarUInt(glyph.toInt() - previousGlyph)
            previousGlyph = glyph.toInt()
            writeCount(construction.variants.size)
            var previousVariantGlyph = glyph.toInt()
            construction.variants.forEach { variant ->
                writeInt(variant.glyphId.toInt() - previousVariantGlyph)
                previousVariantGlyph = variant.glyphId.toInt()
                writeInt(variant.advanceMeasurement)
            }
            writeBoolean(construction.assembly != null)
            construction.assembly?.let { writeAssembly(it, glyph) }
        }
    }

    private fun writeAssembly(assembly: MathGlyphAssembly, baseGlyph: UShort) {
        writeInt(assembly.minimumConnectorOverlap)
        writeInt(assembly.italicCorrection)
        writeCount(assembly.parts.size)
        var previousPartGlyph = baseGlyph.toInt()
        assembly.parts.forEach { part ->
            writeInt(part.glyphId.toInt() - previousPartGlyph)
            previousPartGlyph = part.glyphId.toInt()
            writeInt(part.startConnectorLength)
            writeInt(part.endConnectorLength)
            writeInt(part.fullAdvance)
            writeBoolean(part.extender)
        }
    }

    private fun ensure(additional: Int) {
        val required = size + additional
        if (required <= bytes.size) return
        var newSize = bytes.size
        while (newSize < required) newSize *= 2
        bytes = bytes.copyOf(newSize)
    }

    private fun writeCount(value: Int) {
        require(value >= 0)
        writeVarUInt(value)
    }

    private fun writeVarUInt(value: Int) {
        var remaining = value
        while (remaining and -0x80 != 0) {
            ensure(1)
            bytes[size++] = ((remaining and 0x7f) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        ensure(1)
        bytes[size++] = remaining.toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
