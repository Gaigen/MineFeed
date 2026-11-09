package com.feed.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;

public final class FeedUtil {

    private FeedUtil() {
    }

    public static String jstr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    public static String clip(Font font, String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        return font.plainSubstrByWidth(s, maxWidth - font.width("...")) + "...";
    }
}
