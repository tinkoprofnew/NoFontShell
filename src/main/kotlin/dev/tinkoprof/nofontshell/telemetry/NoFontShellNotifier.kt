package dev.tinkoprof.nofontshell.telemetry

import dev.tinkoprof.nofontshell.config.NoFontShellConfig
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

object NoFontShellNotifier {
    private val notifiedPacksInReload = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun resetReloadSuppression() {
        notifiedPacksInReload.clear()
    }

    @JvmStatic
    fun notifyOncePerPack(
        source: String,
        packId: String,
        action: NoFontShellConfig.Action,
        report: ThreatReport
    ) {
        val key = "$source|$packId|${action.name}"
        if (!notifiedPacksInReload.add(key)) {
            return
        }

        val client = try {
            Minecraft.getInstance()
        } catch (_: Throwable) {
            return
        }

        client.execute {
            val player = client.player ?: return@execute
            val message = Component.literal("[NoFontShell] ")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(
                    Component.literal("[${report.severity.label}] ")
                        .withStyle(report.severity.color, ChatFormatting.BOLD)
                )
                .append(
                    Component.literal(
                        "Action=${action.name.lowercase()} on $source pack '$packId' (${report.signature}). Run /nofontshell history for details."
                    ).withStyle(ChatFormatting.GRAY)
                )
            player.sendSystemMessage(message)
        }
    }
}
