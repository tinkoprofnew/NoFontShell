package dev.tinkoprof.nofontshell.mixin;

import dev.tinkoprof.nofontshell.validation.PackFontInspector;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {
    @Shadow
    @Final
    private PackType type;

    @ModifyVariable(
            method = "createReload",
            at = @At("HEAD"),
            argsOnly = true
    )
    private List<PackResources> nofontshell$inspectClientResourcePacks(List<PackResources> packs) {
        if (this.type != PackType.CLIENT_RESOURCES || packs == null || packs.isEmpty()) {
            return packs;
        }
        return PackFontInspector.inspectReloadPacks(packs);
    }
}
