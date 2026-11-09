package com.feed.client.screen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.util.Locale;
/** Sanitizes pasted and typed account identifiers without an anonymous widget subclass. */
final class FilteredEditBox extends EditBox {
    private final boolean tag;
    FilteredEditBox(Font font, int x, int y, int w, int h, Component hint, boolean tag) {
        super(font, x, y, w, h, hint);
        this.tag = tag;
    }
    @Override public void insertText(String text) {
        String clean = tag ? text.replace("@", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "")
                           : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        super.insertText(clean);
    }
}
