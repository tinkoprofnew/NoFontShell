package dev.tinkoprof.nofontshell.mixin;

import dev.tinkoprof.nofontshell.NoFontShellMod;
import dev.tinkoprof.nofontshell.config.NoFontShellConfig;
import dev.tinkoprof.nofontshell.validation.PackFontInspector;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "loadRequestedPacks", at = @At("RETURN"), cancellable = true)
    private void nofontshell$inspectServerPacks(
            List<?> requests,
            CallbackInfoReturnable<List<Pack>> cir
    ) {
        List<Pack> loaded = cir.getReturnValue();
        if (loaded == null || loaded.isEmpty()) {
            return;
        }

        NoFontShellConfig config = NoFontShellConfig.get();
        NoFontShellConfig.Action action = config.resolvedAction();
        List<Pack> filtered = new ArrayList<>(loaded.size());
        boolean removedAny = false;

        for (Pack pack : loaded) {
            boolean safe = true;
            try (Stream<PackResources> stream = pack.open()) {
                for (PackResources resources : stream.toList()) {
                    try (resources) {
                        if (!PackFontInspector.inspectPack(resources, config, action, "server-download")) {
                            safe = false;
                        }
                    }
                }
            } catch (Exception e) {
                NoFontShellMod.LOGGER.warn(
                        "[NoFontShell] Failed to open server pack [id={}] for early inspection: {}",
                        pack.getId(), e.getMessage()
                );
                safe = false;
            }

            if (!safe && action == NoFontShellConfig.Action.BLOCK_PACK) {
                NoFontShellMod.LOGGER.warn(
                        "[NoFontShell] BLOCK_PACK: Rejected server resource pack [id={}] before activation.",
                        pack.getId()
                );
                removedAny = true;
                continue;
            }
            filtered.add(pack);
        }

        if (removedAny) {
            cir.setReturnValue(filtered);
        }
    }
}
