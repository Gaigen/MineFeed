package com.feed.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Reaction glyphs from the bundled {@code feed:emoji} font (monochrome mask). */
public enum EmojiIcon {

    HEART("heart", "\uD83D\uDC94"),   // U+1F494
    COFFIN("coffin", "\u26B0"),       // U+26B0
    RING("ring", "\uD83D\uDEDF"),     // U+1F6DF
    NERD("nerd", "\uD83E\uDD13"),     // U+1F913
    CUP("cup", "\uD83C\uDFC6"),       // U+1F3C6
    CLOVER("clover", "\uD83C\uDF40"), // U+1F340
    FIRE("fire", "\uD83D\uDD25"),     // U+1F525
    ZZZ("zzz", "\uD83D\uDCA4");       // U+1F4A4

    public static final ResourceLocation FONT =
            new ResourceLocation("feed", "emoji");

    public final String key;
    // No U+FE0F — subset has no variation-selector glyphs.
    private final String glyph;
    private Component text;

    EmojiIcon(String key, String glyph) {
        this.key = key;
        this.glyph = glyph;
    }

    /** Lazy: enum constants init before static FONT. */
    private Component text() {
        if (text == null) {
            text = Component.literal(glyph).withStyle(Style.EMPTY.withFont(FONT));
        }
        return text;
    }

    public static EmojiIcon byKey(String key) {
        for (EmojiIcon icon : values()) {
            if (icon.key.equals(key)) {
                return icon;
            }
        }
        return ZZZ;
    }

    public int width(Font font) {
        return font.width(text());
    }

    public void draw(GuiGraphics g, Font font, int x, int y, int color) {
        g.drawString(font, text(), x, y, color, false);
    }
}
