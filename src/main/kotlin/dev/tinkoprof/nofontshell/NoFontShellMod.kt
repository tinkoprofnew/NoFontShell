package dev.tinkoprof.nofontshell

import dev.tinkoprof.nofontshell.commands.NoFontShellCommands
import dev.tinkoprof.nofontshell.config.NoFontShellConfig
import net.fabricmc.api.ClientModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object NoFontShellMod : ClientModInitializer {
    const val MOD_ID: String = "nofontshell"

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        NoFontShellConfig.load()
        NoFontShellCommands.register()

        val cfg = NoFontShellConfig.get()
        LOGGER.info(
            "[NoFontShell] Initialized for Minecraft 26.3 (action={}, disableTrueTypeHinting={}, maxTtfFacesPerPack={}, maxStackElements={}, maxCompositeDepth={})",
            cfg.resolvedAction(),
            cfg.disableTrueTypeHinting,
            cfg.maxTtfFacesPerPack,
            cfg.maxStackElements,
            cfg.maxCompositeDepth
        )
        if (!cfg.disableTrueTypeHinting) {
            LOGGER.warn("[NoFontShell] disableTrueTypeHinting=false: the FreeType TrueType interpreter is reachable from resource-pack fonts. Primary mitigation is OFF.")
        }
    }

    @JvmStatic
    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
