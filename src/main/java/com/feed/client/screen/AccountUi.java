package com.feed.client.screen;

import com.feed.client.account.ApiError;
import com.feed.client.account.TagRules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared bits for account screens: error texts, dates, retro text fields. */
public final class AccountUi {

    public static final int FIELD_H = 16;
    public static int GREEN = 0xFF55FF55; // set by Theme
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private AccountUi() {
    }

    public static String date(long epochMs) {
        return DATE.format(Instant.ofEpochMilli(epochMs));
    }

    public static Component problem(TagRules.Problem p) {
        return switch (p) {
            case LENGTH -> Component.translatable("feed.account.tag.length");
            case CHARS -> Component.translatable("feed.account.tag.chars");
            case DOUBLE_DOT -> Component.translatable("feed.account.tag.double_dot");
            case RESERVED -> Component.translatable("feed.account.tag.reserved");
            case NONE -> Component.empty();
        };
    }

    public static Component describe(ApiError e) {
        switch (e.code) {
            case "network":
                return Component.translatable("feed.screen.error.network");
            case "rate_limited":
                return Component.translatable("feed.account.error.rate_limited");
            case "tag_taken":
                return Component.translatable("feed.account.tag.taken");
            case "tag_cooldown": {
                Long at = e.longExtra("canChangeTagAt");
                return at == null ? Component.translatable("feed.account.tag.cooldown_generic")
                        : Component.translatable("feed.account.tag.cooldown", date(at));
            }
            case "bad_code":
                return Component.translatable("feed.account.error.bad_code");
            case "banned":
                return Component.translatable("feed.account.error.banned");
            case "bad_auth":
            case "no_auth":
                return Component.translatable("feed.account.error.session_lost");
            case "tag_protected":
                return Component.translatable("feed.account.tag.protected");
            case "discord_taken":
                return Component.translatable("feed.account.discord.err.taken");
            case "not_linked":
                return Component.translatable("feed.account.discord.err.not_linked");
            case "denied":
                return Component.translatable("feed.account.discord.err.denied");
            case "expired":
            case "flow_not_found":
                return Component.translatable("feed.account.discord.err.expired");
            case "discord_failed":
                return Component.translatable("feed.account.discord.err.failed");
            case "discord_off":
                return Component.translatable("feed.account.discord.err.off");
            case "bad_nick":
                return Component.translatable("feed.account.error.bad_nick");
            default:
                String m = e.getMessage();
                if (m != null && !m.isBlank()) {
                    return Component.literal(m);
                }
                return Component.translatable("feed.account.error.http", e.status);
        }
    }

    /** Borderless EditBox inside a retro frame (frame drawn by {@link #drawField}). */
    public static EditBox field(Font font, int x, int y, int w, int maxLen, Component hint) {
        EditBox box = new EditBox(font, x + 4, y + 4, w - 8, FIELD_H - 6, hint);
        box.setBordered(false);
        box.setMaxLength(maxLen);
        box.setTextColor(RetroUi.FG);
        box.setHint(hint.copy().withColor(RetroUi.GRAY & 0xFFFFFF));
        return box;
    }

    /** Lowercases whatever is typed or pasted and drops characters a tag can't contain. */
    public static EditBox tagField(Font font, int x, int y, int w, Component hint) {
        EditBox box = new EditBox(font, x + 4, y + 4, w - 8, FIELD_H - 6, hint) {
            @Override
            public void insertText(String text) {
                String clean = text.replace("@", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "");
                super.insertText(clean);
            }
        };
        box.setBordered(false);
        box.setMaxLength(TagRules.MAX);
        box.setTextColor(RetroUi.FG);
        box.setHint(hint.copy().withColor(RetroUi.GRAY & 0xFFFFFF));
        return box;
    }

    public static void drawField(GuiGraphics g, EditBox box) {
        int x = box.getX() - 4;
        int y = box.getY() - 4;
        int w = box.getWidth() + 8;
        boolean focused = box.isFocused();
        g.fill(x, y, x + w, y + FIELD_H, focused ? RetroUi.HOVER_BG : RetroUi.BG);
        g.renderOutline(x, y, w, FIELD_H, !box.isActive() ? RetroUi.SHADOW : focused ? RetroUi.FG : RetroUi.GRAY);
    }

    public static void drawLabel(GuiGraphics g, Font font, Component text, int x, int y) {
        g.drawString(font, text, x, y, RetroUi.GRAY, false);
    }

    /** Draws wrapped text, returns the y after the last line. */
    public static int drawWrapped(GuiGraphics g, Font font, String text, int x, int y, int w, int color) {
        for (String line : RetroUi.wrap(font, text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    public static int wrappedHeight(Font font, String text, int w) {
        return RetroUi.wrap(font, text, w).size() * (font.lineHeight + 1);
    }
}
