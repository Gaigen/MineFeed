package com.feed.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared drawing helpers for the retro terminal / 8-bit style. */
public final class RetroUi {

    public static final int BG = 0xFF000000;
    public static final int FG = 0xFFD6D6D6;
    public static final int GRAY = 0xFF8A8A8A;
    public static final int HOVER_BG = 0xFF1C1C1C;
    public static final int ACCENT = 0xFFFFFF55;
    public static final int SHADOW = 0xFF2E2E2E;
    public static final int ERROR = 0xFFFF5555;
    public static final int SHADOW_OFFSET = 3;
    public static final int CHIP_H = 12;

    private RetroUi() {
    }

    public static void drawBox(GuiGraphics g, int x, int y, int w, int h, boolean hovered) {
        g.fill(x + SHADOW_OFFSET, y + SHADOW_OFFSET,
                x + w + SHADOW_OFFSET, y + h + SHADOW_OFFSET, SHADOW);
        g.fill(x, y, x + w, y + h, hovered ? HOVER_BG : BG);
        g.renderOutline(x, y, w, h, hovered ? FG : GRAY);
    }

    public static int chipWidth(Font font, String text) {
        return font.width(text) + 9;
    }

    public static void drawChip(GuiGraphics g, Font font, String text, int x, int y, int color, boolean inverted) {
        drawChip(g, font, text, x, y, color, inverted, BG);
    }

    /** Inverted text uses BG — accent colors are bright. */
    public static void drawChip(GuiGraphics g, Font font, String text, int x, int y, int color, boolean inverted, int bg) {
        int w = chipWidth(font, text);
        g.fill(x, y, x + w, y + CHIP_H, inverted ? color : bg);
        g.renderOutline(x, y, w, CHIP_H, color);
        g.drawString(font, text, x + 5, y + 2, inverted ? BG : color, false);
    }

    public static int reactionChipWidth(Font font, EmojiIcon icon, String count) {
        return 4 + icon.width(font) + 3 + font.width(count) + 5;
    }

    /** Inactive = gray mask; active = filled accent with dark glyph/count. */
    public static void drawReactionChip(GuiGraphics g, Font font, EmojiIcon icon, int count,
                                        int x, int y, int color, boolean inverted, int bg) {
        String text = String.valueOf(count);
        int w = reactionChipWidth(font, icon, text);
        g.fill(x, y, x + w, y + CHIP_H, inverted ? color : bg);
        g.renderOutline(x, y, w, CHIP_H, inverted ? color : GRAY);
        icon.draw(g, font, x + 4, y + 2, inverted ? BG : GRAY);
        g.drawString(font, text, x + 4 + icon.width(font) + 3, y + 2, inverted ? BG : GRAY, false);
    }

    public static boolean blink() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    public static boolean blinkFast() {
        return (System.currentTimeMillis() / 300) % 2 == 0;
    }

    public static String flatten(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
    }

    /** Blinking "_ " prefix; text position stays fixed. */
    public static void drawBlinkingTitle(GuiGraphics g, Font font, String text, int x, int y, int color) {
        if (blink()) {
            g.drawString(font, "_", x, y, color, false);
        }
        g.drawString(font, text, x + font.width("_ "), y, color, false);
    }
}
