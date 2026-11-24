package dev.tinkoprof.nofontshell.commands

import com.mojang.brigadier.arguments.StringArgumentType
import dev.tinkoprof.nofontshell.config.NoFontShellConfig
import dev.tinkoprof.nofontshell.telemetry.NoFontShellReportExporter
import dev.tinkoprof.nofontshell.telemetry.NoFontShellStats
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

object NoFontShellCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("nofontshell")
                    .then(
                        ClientCommands.literal("status").executes { ctx ->
                            val cfg = NoFontShellConfig.get()
                            ctx.source.sendFeedback(
                                Component.literal("[NoFontShell] ")
                                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                                    .append(
                                        Component.literal(
                                            "mode=${cfg.resolvedAction().name.lowercase()}, " +
                                                "noHinting=${cfg.disableTrueTypeHinting}, " +
                                                "packsScanned=${NoFontShellStats.scannedPacks()}, " +
                                                "fontsScanned=${NoFontShellStats.scannedFonts()}, " +
                                                "fontsBlocked=${NoFontShellStats.blockedFonts()}, " +
                                                "packsBlocked=${NoFontShellStats.blockedPacks()}"
                                        ).withStyle(ChatFormatting.WHITE)
                                    )
                            )
                            1
                        }
                    )
                    .then(
                        ClientCommands.literal("history").executes { ctx ->
                            val events = NoFontShellStats.recentEvents().take(8)
                            if (events.isEmpty()) {
                                ctx.source.sendFeedback(
                                    Component.literal("[NoFontShell] No suspicious TrueType fonts detected in this session.")
                                        .withStyle(ChatFormatting.GREEN)
                                )
                            } else {
                                ctx.source.sendFeedback(
                                    Component.literal("[NoFontShell] Last ${events.size} security events:")
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                                )
                                for (event in events) {
                                    ctx.source.sendFeedback(
                                        Component.literal(" - [${event.formattedTime()}] ")
                                            .withStyle(ChatFormatting.DARK_GRAY)
                                            .append(
                                                Component.literal("[${event.report.severity.label}] ")
                                                    .withStyle(event.report.severity.color)
                                            )
                                            .append(
                                                Component.literal(
                                                    "${event.source}/${event.packId} (${event.resourceId}) -> ${event.action.name.lowercase()} [${event.report.signature}]"
                                                ).withStyle(ChatFormatting.GRAY)
                                            )
                                    )
                                }
                            }
                            1
                        }
                    )
                    .then(
                        ClientCommands.literal("mode")
                            .then(
                                ClientCommands.argument("action", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        builder.suggest("log_only")
                                        builder.suggest("skip_font")
                                        builder.suggest("block_pack")
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val raw = StringArgumentType.getString(ctx, "action")
                                        val parsed = NoFontShellConfig.Action.parse(raw)
                                        val cfg = NoFontShellConfig.get()
                                        cfg.action = parsed.name.lowercase()
                                        NoFontShellConfig.saveCurrent()
                                        ctx.source.sendFeedback(
                                            Component.literal("[NoFontShell] Action mode updated to '${cfg.action}' and saved.")
                                                .withStyle(ChatFormatting.GREEN)
                                        )
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("hinting")
                            .then(
                                ClientCommands.literal("off").executes { ctx ->
                                    setHinting(ctx.source, enabled = false)
                                }
                            )
                            .then(
                                ClientCommands.literal("on")
                                    .executes { ctx ->
                                        ctx.source.sendError(
                                            Component.literal(
                                                "[NoFontShell] Enabling hinting re-exposes the FreeType TrueType interpreter " +
                                                    "(minecraft2shell bug class). Run '/nofontshell hinting on confirm' if you really want this."
                                            )
                                        )
                                        0
                                    }
                                    .then(
                                        ClientCommands.literal("confirm").executes { ctx ->
                                            setHinting(ctx.source, enabled = true)
                                        }
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("reload").executes { ctx ->
                            NoFontShellConfig.load()
                            val cfg = NoFontShellConfig.get()
                            ctx.source.sendFeedback(
                                Component.literal("[NoFontShell] Config reloaded (mode=${cfg.resolvedAction().name.lowercase()}, noHinting=${cfg.disableTrueTypeHinting}).")
                                    .withStyle(ChatFormatting.GREEN)
                            )
                            1
                        }
                    )
                    .then(
                        ClientCommands.literal("export").executes { ctx ->
                            try {
                                val path = NoFontShellReportExporter.exportReport()
                                ctx.source.sendFeedback(
                                    Component.literal("[NoFontShell] Report exported to: $path")
                                        .withStyle(ChatFormatting.GREEN)
                                )
                            } catch (e: Exception) {
                                ctx.source.sendError(
                                    Component.literal("[NoFontShell] Failed to export report: ${e.message}")
                                )
                            }
                            1
                        }
                    )
            )
        }
    }

    private fun setHinting(source: FabricClientCommandSource, enabled: Boolean): Int {
        val cfg = NoFontShellConfig.get()
        cfg.disableTrueTypeHinting = !enabled
        NoFontShellConfig.saveCurrent()
        source.sendFeedback(
            Component.literal(
                "[NoFontShell] FreeType TrueType hinting is now ${if (enabled) "ENABLED (unprotected)" else "DISABLED (protected)"}. " +
                    "Already-loaded glyphs are cached; press F3+T to reload resources."
            ).withStyle(if (enabled) ChatFormatting.RED else ChatFormatting.GREEN)
        )
        return 1
    }
}
