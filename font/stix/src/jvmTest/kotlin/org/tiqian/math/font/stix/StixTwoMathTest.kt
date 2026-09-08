package org.tiqian.math.font.stix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.font.opentype.OpenTypeLineMetrics
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader

class StixTwoMathTest {
    @Test
    fun bundledComparisonFontHasRealMathData() {
        val font = StixTwoMath.load()

        assertEquals(1000, font.unitsPerEm)
        assertEquals(OpenTypeLineMetrics(762, -238, 250), font.lineMetrics)
        assertEquals(70, font.constants.scriptPercentScaleDown)
        assertEquals(55, font.constants.scriptScriptPercentScaleDown)
        assertEquals(1800, font.constants.displayOperatorMinHeight)
        assertEquals(258, font.constants.axisHeight)
        assertEquals(480, font.constants.accentBaseHeight)
        assertEquals(656, font.constants.flattenedAccentBaseHeight)
        assertEquals(listOf(175, 68, 68), listOf(
            font.constants.overbarVerticalGap, font.constants.overbarRuleThickness, font.constants.overbarExtraAscender,
        ))
        assertEquals(listOf(175, 68, 68), listOf(
            font.constants.underbarVerticalGap, font.constants.underbarRuleThickness, font.constants.underbarExtraDescender,
        ))
        assertEquals(210, font.constants.subscriptShiftDown)
        assertEquals(360, font.constants.superscriptShiftUp)
        assertEquals(252, font.constants.superscriptShiftUpCramped)
        assertEquals(150, font.constants.subSuperscriptGapMin)
        assertEquals(
            listOf(135, 300, 135, 670),
            listOf(
                font.constants.upperLimitGapMin,
                font.constants.upperLimitBaselineRiseMin,
                font.constants.lowerLimitGapMin,
                font.constants.lowerLimitBaselineDropMin,
            ),
        )
        assertEquals(640, font.constants.fractionNumeratorDisplayStyleShiftUp)
        assertEquals(640, font.constants.fractionDenominatorDisplayStyleShiftDown)
        assertEquals(68, font.constants.fractionRuleThickness)
        assertEquals(150, font.constants.fractionNumDisplayStyleGapMin)
        assertEquals(150, font.constants.fractionDenomDisplayStyleGapMin)
        assertEquals(
            listOf(85, 170, 68, 78, 65, -335, 55),
            listOf(
                font.constants.radicalVerticalGap,
                font.constants.radicalDisplayStyleVerticalGap,
                font.constants.radicalRuleThickness,
                font.constants.radicalExtraAscender,
                font.constants.radicalKernBeforeDegree,
                font.constants.radicalKernAfterDegree,
                font.constants.radicalDegreeBottomRaisePercent,
            ),
        )
        assertTrue(font.verticalConstructions.values.any { it.variants.size > 1 })
        assertTrue(font.italicCorrections.isNotEmpty())
        assertTrue(font.unsupportedItalicCorrectionVariationAdjustments.isEmpty())
        assertEquals(setOf(4010.toUShort()), font.italicCorrectionDeviceAdjustments.keys)
        assertEquals(19..19, font.italicCorrectionDeviceAdjustments.getValue(4010.toUShort()).ppemRange)
        assertEquals(listOf(1), font.italicCorrectionDeviceAdjustments.getValue(4010.toUShort()).deltasPx)
        assertTrue(font.verticalConstructions.size > 50)
        assertTrue(font.horizontalConstructions.size > 20)
        assertTrue(font.topAccentAttachments.isNotEmpty())
        assertEquals(
            setOf(3309, 3316, 3326).map(Int::toUShort).toSet(),
            font.topAccentAttachmentDeviceAdjustments.keys,
        )
        assertTrue(font.unsupportedTopAccentAttachmentVariationAdjustments.isEmpty())
        assertTrue(font.extendedShapeGlyphs.isNotEmpty())
        assertTrue(font.mathKernInfo.isNotEmpty())
        assertTrue(font.bytes.isNotEmpty())
    }

    @Test
    fun bundledSnapshotMatchesBundledFont() {
        val manifest = checkNotNull(javaClass.getResourceAsStream(StixTwoMath.ManifestResourcePath))
            .use { PackagedOpenTypeMathManifestCodec.decode(it.readBytes()) }
        val face = manifest.faces.single()
        assertEquals(StixTwoMath.FamilyId, manifest.familyId)
        val snapshot = checkNotNull(javaClass.getResourceAsStream(StixTwoMath.SnapshotResourcePath))
            .use { it.readBytes() }
        val font = VerifiedOpenTypeMathSnapshotLoader.load(
            StixTwoMath.loadBytes(),
            snapshot,
            face.fontSha256,
        )
        assertEquals(258, font.constants.axisHeight)
    }
}

private val org.tiqian.math.font.opentype.MathDeviceAdjustment.ppemRange: IntRange
    get() = startPpem..endPpem
