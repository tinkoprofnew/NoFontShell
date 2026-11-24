package dev.tinkoprof.nofontshell.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.tinkoprof.nofontshell.validation.PackFontInspector;
import dev.tinkoprof.nofontshell.validation.SafeFallbackGlyphProvider;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TrueTypeGlyphProviderDefinition.class)
public abstract class TrueTypeGlyphProviderDefinitionMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void nofontshell$validateBeforeFreeType(
            ResourceManager resourceManager,
            CallbackInfoReturnable<GlyphProvider> cir
    ) {
        Identifier fontResourceId = this.location.withPrefix("font/");
        if (!PackFontInspector.shouldAllowFontLoad(resourceManager, fontResourceId)) {
            cir.setReturnValue(SafeFallbackGlyphProvider.INSTANCE);
        }
    }
}
