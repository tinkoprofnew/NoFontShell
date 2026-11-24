package dev.tinkoprof.nofontshell.telemetry

import dev.tinkoprof.nofontshell.config.NoFontShellConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object NoFontShellReportExporter {
    fun exportReport(): Path {
        val outPath = FabricLoader.getInstance().configDir.resolve("nofontshell-report.txt")
        val cfg = NoFontShellConfig.get()
        val events = NoFontShellStats.recentEvents()

        val content = buildString {
            appendLine("=== NoFontShell Security Report ===")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Action Mode: ${cfg.resolvedAction()}")
            appendLine("Disable TrueType Hinting (FT_LOAD_NO_HINTING): ${cfg.disableTrueTypeHinting}")
            appendLine("Limits: maxTtfFacesPerPack=${cfg.maxTtfFacesPerPack}, maxStackElements=${cfg.maxStackElements}, maxStorage=${cfg.maxStorage}, maxFpgmBytes=${cfg.maxFpgmBytes}, maxCompositeDepth=${cfg.maxCompositeDepth}")
            appendLine()
            appendLine("--- Session Counters ---")
            appendLine("Packs Scanned : ${NoFontShellStats.scannedPacks()}")
            appendLine("Fonts Scanned : ${NoFontShellStats.scannedFonts()}")
            appendLine("Fonts Blocked : ${NoFontShellStats.blockedFonts()}")
            appendLine("Packs Blocked : ${NoFontShellStats.blockedPacks()}")
            appendLine()
            appendLine("--- Recent Security Events (${events.size}) ---")
            if (events.isEmpty()) {
                appendLine("(No suspicious TrueType fonts or packs detected in this session.)")
            } else {
                events.forEachIndexed { idx, e ->
                    appendLine(
                        "${idx + 1}. [${e.formattedTime()}] [${e.report.severity.label}] " +
                            "stage=${e.stage} action=${e.action} source=${e.source} pack=${e.packId} resource=${e.resourceId}"
                    )
                    appendLine("   signature=${e.report.signature} :: ${e.report.summary}")
                }
            }
        }

        Files.createDirectories(outPath.parent)
        Files.writeString(outPath, content, StandardCharsets.UTF_8)
        return outPath
    }
}
