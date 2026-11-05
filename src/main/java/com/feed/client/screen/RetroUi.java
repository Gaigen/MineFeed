package com.feed.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared drawing helpers for the retro terminal / 8-bit style. */
public final class RetroUi {

    // Set by Theme (not final on purpose: a theme switch recolors everything at once).
    public static int BG = 0xFF000000;
    public static int FG = 0xFFD6D6D6;
    public static int GRAY = 0xFF8A8A8A;
    public static int HOVER_BG = 0xFF1C1C1C;
    public static int ACCENT = 0xFFFFFF55;
    public static int SHADOW = 0xFF2E2E2E;
    public static int ERROR = 0xFFFF5555;
    /** Highlighted text (hovered menu items). */
    public static int STRONG = 0xFFFFFFFF;
    public static int CYAN = 0xFF55FFFF;
    /** Page background behind cards (same as BG on dark themes, a shade darker on the light one). */
    public static int PAGE = 0xFF000000;
    /** Modal backdrop over the page. */
    public static int DIM = 0xC8000000;
    public static final int SHADOW_OFFSET = 3;
    public static final int CHIP_H = 12;

    private RetroUi() {
    }

    /** Centered text without the vanilla shadow. */
    public static void centered(GuiGraphics g, Font font, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    public static void centered(GuiGraphics g, Font font, net.minecraft.network.chat.Component text, int cx, int y, int color) {
        centered(g, font, text.getString(), cx, y, color);
    }

    /** Vanilla text fields on MineFeed screens are drawn flat, in theme colors (see the mixins). */
    public static boolean flatFields() {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        return screen != null && screen.getClass().getName().startsWith("com.feed.");
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
        drawReactionChip(g, font, icon, count, x, y, color, inverted, bg, false);
    }

    public static void drawReactionChip(GuiGraphics g, Font font, EmojiIcon icon, int count,
                                        int x, int y, int color, boolean inverted, int bg, boolean hover) {
        String text = String.valueOf(count);
        int w = reactionChipWidth(font, icon, text);
        int c = inverted ? BG : hover ? FG : GRAY;
        g.fill(x, y, x + w, y + CHIP_H, inverted ? color : hover ? HOVER_BG : bg);
        g.renderOutline(x, y, w, CHIP_H, inverted ? color : hover ? FG : GRAY);
        icon.draw(g, font, x + 4, y + 2, c);
        g.drawString(font, text, x + 4 + icon.width(font) + 3, y + 2, c, false);
    }

    /** Player head / Discord picture, or a gray square with the first letter while it loads. */
    public static void drawAvatar(GuiGraphics g, Font font, String key, String name, int x, int y, int size) {
        net.minecraft.resources.ResourceLocation avatar = com.feed.client.AvatarCache.get(key);
        if (avatar != null) {
            g.blit(avatar, x, y, 0, 0, size, size, size, size);
        } else {
            g.fill(x, y, x + size, y + size, GRAY);
            String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            g.drawString(font, letter, x + (size - font.width(letter)) / 2 + 1, y + (size - 8) / 2, BG, false);
        }
    }

    /** Small hand-drawn button (13 px high in the mockup). */
    public static void drawButton(GuiGraphics g, Font font, String label, int x, int y, int w, int h,
                                  boolean hover, boolean enabled, int color) {
        drawBox(g, x, y, w, h, hover && enabled);
        int c = !enabled ? GRAY : hover ? FG : color;
        g.drawString(font, label, x + (w - font.width(label)) / 2 + 1, y + (h - 8) / 2 + 1, c, false);
    }

    public static int buttonWidth(Font font, String label) {
        return font.width(label) + 12;
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

    private static final java.util.regex.Pattern COORDS =
            java.util.regex.Pattern.compile("\\[-?\\d{1,8} -?\\d{1,4} -?\\d{1,8}(?: (?:nether|end))?]");

    public static java.util.List<String> wrap(Font font, String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null) {
            return lines;
        }
        // coordinates "[x y z]" never break across lines
        java.util.regex.Matcher m = COORDS.matcher(text);
        StringBuilder glued = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(glued, java.util.regex.Matcher.quoteReplacement(m.group().replace(' ', '\u0001')));
        }
        m.appendTail(glued);
        for (String raw : glued.toString().split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String rawWord : raw.split(" ")) {
                String word = rawWord.replace('\u0001', ' ');
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.width(candidate) > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
