package dev.tinkoprof.nofontshell.telemetry

import dev.tinkoprof.nofontshell.validation.ValidationResult
import net.minecraft.ChatFormatting

enum class ThreatSeverity(val label: String, val color: ChatFormatting) {
    LOW("LOW", ChatFormatting.YELLOW),
    MEDIUM("MEDIUM", ChatFormatting.GOLD),
    HIGH("HIGH", ChatFormatting.RED),
    CRITICAL("CRITICAL", ChatFormatting.DARK_RED)
}

data class ThreatReport(
    val severity: ThreatSeverity,
    val signature: String,
    val summary: String
)

object FontThreatClassifier {
    @JvmStatic
    fun classify(result: ValidationResult): ThreatReport {
        val reasons = result.reasons()
        val joined = result.joinedReasons()

        val hasShz = reasons.any { it.startsWith("Dangerous TrueType bytecode opcode SHZ") }
        val hasHiddenOpcode = reasons.any { it.startsWith("Possible jump-reachable hidden opcode") }
        val hasIdef = reasons.any { it.contains("IDEF", ignoreCase = true) || it.contains("maxInstructionDefs") }
        val hasHugeStack = result.maxStackElements() >= 4096
        val hasLargeFpgm = result.fpgmLength() >= 4096
        val hasCompositeCycle = reasons.any { it.contains("Cyclic composite", ignoreCase = true) }
        val hasCompositeInstr = reasons.any { it.contains("Composite glyph") && it.contains("instructions") }

        val exploitProfile = hasIdef && (hasShz || hasHiddenOpcode || hasCompositeInstr || (hasHugeStack && hasLargeFpgm))

        return when {
            exploitProfile -> ThreatReport(
                severity = ThreatSeverity.CRITICAL,
                signature = "FREETYPE_VM_EXPLOIT_PROFILE",
                summary = "Matches FreeType TrueType VM / minecraft2shell exploit indicators ($joined)"
            )
            hasCompositeCycle || (hasCompositeInstr && hasHugeStack) -> ThreatReport(
                severity = ThreatSeverity.HIGH,
                signature = "MALICIOUS_COMPOSITE_GLYPH",
                summary = "High-risk composite glyph layout or bytecode ($joined)"
            )
            reasons.any { it.contains("exceeds limit") || it.contains("contains hinting instructions") } -> ThreatReport(
                severity = ThreatSeverity.MEDIUM,
                signature = "SUSPICIOUS_TTF_STRUCTURE",
                summary = "Suspicious TrueType table or hinting profile ($joined)"
            )
            else -> ThreatReport(
                severity = ThreatSeverity.LOW,
                signature = "MALFORMED_TTF_BINARY",
                summary = "Malformed or truncated TrueType binary ($joined)"
            )
        }
    }

    @JvmStatic
    fun classifyBatchSpray(ttfCount: Int, limit: Int): ThreatReport {
        val severity = if (ttfCount >= limit * 3) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        return ThreatReport(
            severity = severity,
            signature = "TTF_HEAP_GROOMING_BATCH",
            summary = "Pack bundles $ttfCount .ttf faces (limit=$limit), consistent with heap-grooming spray"
        )
    }
}
