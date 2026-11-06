package com.feed.client.screen;

import com.feed.client.FeedUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

/** Spoilers the player opened in this game session: the texts of a post or comment, and pictures. */
public final class Spoilers {

    private static final Set<String> OPEN = new HashSet<>();

    private Spoilers() {
    }

    public static boolean isOpen(String id) {
        return id != null && OPEN.contains(id);
    }

    public static void open(String id) {
        if (id == null) {
            return;
        }
        if (OPEN.size() > 5000) {
            OPEN.clear();
        }
        OPEN.add(id);
    }

    /** Key of the picture of a post or comment (opened separately from its text, as in Discord). */
    public static String image(String id) {
        return id + "#img";
    }

    /** A covered picture: dark pixel noise, "Spoiler" and a hint. */
    public static void drawCover(GuiGraphics g, Font font, int x, int y, int w, int h, boolean hover) {
        g.fill(x, y, x + w, y + h, hover ? 0xFF3A3A44 : 0xFF2A2A32);
        int dot = hover ? 0xFF4A4A56 : 0xFF363640;
        for (int py = 0; py + 2 <= h; py += 4) {
            for (int px = (py / 4) % 2 * 2; px + 2 <= w; px += 4) {
                g.fill(x + px, y + py, x + px + 2, y + py + 2, dot);
            }
        }
        String title = Component.translatable("feed.spoiler").getString();
        String hint = FeedUtil.clip(font, Component.translatable("feed.spoiler.hint").getString(), w - 8);
        int cy = y + h / 2 - (h >= 30 ? 9 : 4);
        int tw = font.width(title) + 8;
        g.fill(x + w / 2 - tw / 2, cy - 2, x + w / 2 + tw / 2, cy + 9, 0xC0000000);
        RetroUi.centered(g, font, title, x + w / 2, cy, 0xFFFFFFFF);
        if (h >= 30) {
            RetroUi.centered(g, font, hint, x + w / 2, cy + 12, 0xFFB0B0B8);
        }
        g.renderOutline(x, y, w, h, hover ? 0xFFFFFFFF : 0xFF55555F);
    }
}
