package dev.tinkoprof.nofontshell.validation;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

public final class SafeFallbackGlyphProvider implements GlyphProvider {
    public static final SafeFallbackGlyphProvider INSTANCE = new SafeFallbackGlyphProvider();

    private SafeFallbackGlyphProvider() {}

    @Override
    public UnbakedGlyph getGlyph(int codepoint) {
        return null;
    }

    @Override
    public IntSet getSupportedGlyphs() {
        return IntSets.emptySet();
    }

    @Override
    public void close() {

    }
}
