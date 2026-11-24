package dev.tinkoprof.nofontshell.mixin;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import dev.tinkoprof.nofontshell.config.NoFontShellConfig;
import org.lwjgl.util.freetype.FreeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TrueTypeGlyphProvider.class)
public abstract class TrueTypeGlyphProviderMixin {
    @ModifyArg(
            method = "loadGlyph",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/util/freetype/FreeType;FT_Load_Glyph(Lorg/lwjgl/util/freetype/FT_Face;II)I"
            ),
            index = 2
    )
    private int nofontshell$addNoHintingFlag(int loadFlags) {
        if (NoFontShellConfig.get().disableTrueTypeHinting) {
            return loadFlags | FreeType.FT_LOAD_NO_HINTING;
        }
        return loadFlags;
    }
}
