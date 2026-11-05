package com.feed.client.screen;

import com.feed.client.FeedConfig;
import com.feed.data.PostType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Color themes of the MineFeed UI. Stored in feed-client.toml (ui.theme). */
@OnlyIn(Dist.CLIENT)
public enum Theme {
    //        id          bg          fg          gray        hover       accent      shadow      error       strong      light
    DARK("dark",         0xFF000000, 0xFFD6D6D6, 0xFF8A8A8A, 0xFF1C1C1C, 0xFFFFFF55, 0xFF2E2E2E, 0xFFFF5555, 0xFFFFFFFF, false),
    // light: cards are paper (bg) on a slightly darker desk (page)
    LIGHT("light",       0xFFFAF8F2, 0xFF1E1E1E, 0xFF6B665B, 0xFFEFEADF, 0xFF8F5F00, 0xFFC7C0B0, 0xFFC62828, 0xFF000000, true),
    NETHER("nether",     0xFF1A0707, 0xFFF0D6C8, 0xFFA07060, 0xFF2E0E0E, 0xFFFF8A1F, 0xFF3D1212, 0xFFFF4040, 0xFFFFFFFF, false),
    END("end",           0xFF0B0A14, 0xFFE8E4C9, 0xFF8C86A8, 0xFF1A1730, 0xFFC77DFF, 0xFF26213F, 0xFFFF5577, 0xFFFFFFFF, false),
    TERMINAL("terminal", 0xFF000A00, 0xFF7CFF7C, 0xFF3F9F3F, 0xFF0F240F, 0xFFC8FF3C, 0xFF143014, 0xFFFF6040, 0xFFCCFFCC, false);

    public final String id;
    public final int bg;
    public final int fg;
    public final int gray;
    public final int hover;
    public final int accent;
    public final int shadow;
    public final int error;
    public final int strong;
    public final boolean light;
    public final int page;

    private static Theme current = DARK;
    private static String appliedId;

    Theme(String id, int bg, int fg, int gray, int hover, int accent, int shadow, int error, int strong, boolean light) {
        this.id = id;
        this.bg = bg;
        this.fg = fg;
        this.gray = gray;
        this.hover = hover;
        this.accent = accent;
        this.shadow = shadow;
        this.error = error;
        this.strong = strong;
        this.light = light;
        this.page = light ? 0xFFE9E4D8 : bg;
    }

    public String nameKey() {
        return "feed.theme." + id;
    }

    public static Theme current() {
        return current;
    }

    public static Theme byId(String id) {
        for (Theme t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return DARK;
    }

    /** Bright 8-bit colors (post types, green, cyan) adapted to the theme: darker on light paper. */
    public int tint(int color) {
        if (!light) {
            return color;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0xFF000000 | (r * 55 / 100) << 16 | (g * 55 / 100) << 8 | (b * 55 / 100);
    }

    /** Applies the configured theme if it changed (cheap; called every tick and on screen init). */
    public static void sync() {
        String id = FeedConfig.theme();
        if (!id.equals(appliedId)) {
            apply(byId(id));
            appliedId = id;
        }
    }

    /** Switches now and saves the choice. */
    public static void choose(Theme t) {
        FeedConfig.setTheme(t.id);
        apply(t);
        appliedId = t.id;
    }

    private static void apply(Theme t) {
        current = t;
        RetroUi.BG = t.bg;
        RetroUi.FG = t.fg;
        RetroUi.GRAY = t.gray;
        RetroUi.HOVER_BG = t.hover;
        RetroUi.ACCENT = t.accent;
        RetroUi.SHADOW = t.shadow;
        RetroUi.ERROR = t.error;
        RetroUi.STRONG = t.strong;
        RetroUi.PAGE = t.page;
        RetroUi.DIM = t.light ? 0xC0000000 | (t.page & 0xFFFFFF) : 0xC8000000;
        AccountUi.GREEN = t.tint(0xFF55FF55);
        RetroUi.CYAN = t.tint(0xFF55FFFF);
        PostType.tint = t::tint;
    }
}
