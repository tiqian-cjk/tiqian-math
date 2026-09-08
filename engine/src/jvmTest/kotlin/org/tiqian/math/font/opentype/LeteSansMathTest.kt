package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class LeteSansMathTest {
    @Test
    fun bundledFontHasRealMathConstantsAndParenthesisVariants() {
        val font = LeteSansMath.load()

        assertEquals(1000, font.unitsPerEm)
        assertEquals(OpenTypeLineMetrics(750, -250, 0), font.lineMetrics)
        assertEquals(70, font.constants.scriptPercentScaleDown)
        assertEquals(55, font.constants.scriptScriptPercentScaleDown)
        assertEquals(1400, font.constants.displayOperatorMinHeight)
        assertEquals(280, font.constants.axisHeight)
        assertEquals(527, font.constants.accentBaseHeight)
        assertEquals(689, font.constants.flattenedAccentBaseHeight)
        assertEquals(listOf(150, 66, 50), listOf(
            font.constants.overbarVerticalGap, font.constants.overbarRuleThickness, font.constants.overbarExtraAscender,
        ))
        assertEquals(listOf(150, 66, 50), listOf(
            font.constants.underbarVerticalGap, font.constants.underbarRuleThickness, font.constants.underbarExtraDescender,
        ))
        assertEquals(250, font.constants.subscriptShiftDown)
        assertEquals(420, font.constants.superscriptShiftUp)
        assertEquals(370, font.constants.superscriptShiftUpCramped)
        assertEquals(170, font.constants.subSuperscriptGapMin)
        assertEquals(
            listOf(150, 150, 150, 600),
            listOf(
                font.constants.upperLimitGapMin,
                font.constants.upperLimitBaselineRiseMin,
                font.constants.lowerLimitGapMin,
                font.constants.lowerLimitBaselineDropMin,
            ),
        )
        assertEquals(580, font.constants.fractionNumeratorDisplayStyleShiftUp)
        assertEquals(700, font.constants.fractionDenominatorDisplayStyleShiftDown)
        assertEquals(66, font.constants.fractionRuleThickness)
        assertEquals(200, font.constants.fractionNumDisplayStyleGapMin)
        assertEquals(200, font.constants.fractionDenomDisplayStyleGapMin)
        assertEquals(
            listOf(96, 142, 76, 76, 276, -400, 64),
            font.constants.radicalValues(),
        )
        assertTrue(font.verticalConstructions.values.any { it.variants.size > 1 })
        assertEquals(828, font.italicCorrections.size)
        assertTrue(font.unsupportedItalicCorrectionVariationAdjustments.isEmpty())
        assertEquals(89, font.verticalConstructions.size)
        assertEquals(44, font.horizontalConstructions.size)
        assertEquals(1519, font.topAccentAttachments.size)
        assertTrue(font.extendedShapeGlyphs.isNotEmpty())
        assertTrue(font.mathKernInfo.isNotEmpty())
        assertContentEquals(LeteSansMath.loadBytes(), font.bytes)
    }

    @Test
    fun designUnitScalingIsExact() {
        val font = LeteSansMath.load()
        assertEquals(20f, font.scaleDesignUnits(font.unitsPerEm, 20f))
    }

    @Test
    fun prebakedRegularAndBoldMetadataIsAttachedToTheBundledRuntimeFonts() {
        listOf(
            PrebakedCase(
                LeteSansMath.loadBytes(),
                LeteSansMath.load(),
            ),
            PrebakedCase(
                LeteSansMath.loadBoldBytes(),
                LeteSansMath.loadBold(),
            ),
        ).forEach { case ->
            assertContentEquals(case.fontBytes, case.prebaked.bytes)
            assertTrue(case.prebaked.characterGlyphs.isNotEmpty())
            assertTrue(case.prebaked.constants.axisHeight > 0)
        }
    }

    @Test
    fun preparedSnapshotRejectsMismatchedBytesBeforeAttachingMetadata() {
        val snapshot = checkNotNull(javaClass.getResourceAsStream(LeteSansMath.SnapshotResourcePath))
            .use { it.readBytes() }
        val prepared = VerifiedOpenTypeMathSnapshotLoader.prepare(snapshot)
        val bytes = LeteSansMath.loadBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        val failure = assertFailsWith<IllegalStateException> { prepared.attach(bytes) }
        assertTrue(failure.message.orEmpty().contains("SHA-256 mismatch"))
    }

    @Test
    fun repeatedLoadsShareImmutableMetadataButNotMutableFontBytes() {
        val first = LeteSansMath.load()
        val second = LeteSansMath.load()

        assertNotSame(first.bytes, second.bytes)
        assertContentEquals(first.bytes, second.bytes)
        assertSame(first.constants, second.constants)
        assertSame(first.italicCorrections, second.italicCorrections)
        assertSame(first.mathKernInfo, second.mathKernInfo)
        assertSame(first.verticalConstructions, second.verticalConstructions)
        assertSame(first.horizontalConstructions, second.horizontalConstructions)
    }

    @Test
    fun radicalMathValueDeviceAdjustmentIsRejectedExplicitly() {
        val bytes = Files.readAllBytes(
            Path.of(checkNotNull(System.getProperty("tiqianLeteSourceRegularFont"))),
        )
        val constants = bytes.mathConstantsOffset()
        val deviceOffset = constants + 8 + 45 * 4 + 2
        bytes[deviceOffset] = 0
        bytes[deviceOffset + 1] = 1

        val failure = assertFailsWith<OpenTypeMathException> { OpenTypeMathReader().read(bytes) }
        assertEquals(DiagnosticCode.UnsupportedMathDeviceAdjustment, failure.diagnosticCode)
        assertTrue(failure.message.orEmpty().contains("MathConstants[45]"))
    }
}

private data class PrebakedCase(
    val fontBytes: ByteArray,
    val prebaked: OpenTypeMathFont,
)

private fun OpenTypeMathConstants.radicalValues(): List<Int> = listOf(
    radicalVerticalGap,
    radicalDisplayStyleVerticalGap,
    radicalRuleThickness,
    radicalExtraAscender,
    radicalKernBeforeDegree,
    radicalKernAfterDegree,
    radicalDegreeBottomRaisePercent,
)

private fun ByteArray.mathConstantsOffset(): Int {
    val tableCount = u16(4)
    val mathRecord = (0 until tableCount).firstNotNullOf { tableIndex ->
        val record = 12 + tableIndex * 16
        val tag = String(this, record, 4, Charsets.ISO_8859_1)
        if (tag == "MATH") record else null
    }
    val mathOffset = u32(mathRecord + 8)
    return mathOffset + u16(mathOffset + 4)
}

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.u32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)
