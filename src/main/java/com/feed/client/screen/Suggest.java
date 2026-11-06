package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @player and #hashtag suggestions while typing: watches the word before the cursor,
 * asks the server (debounced) and draws a row of chips; Tab or a click puts the choice in.
 */
public final class Suggest {

    /** Text + cursor of some field, and a way to replace a range of it. */
    public interface Field {
        String value();

        int cursor();

        void replace(int start, int end, String text);
    }

    private static final Pattern TOKEN = Pattern.compile("(?:^|(?<=[\\s(]))([@#])([\\p{L}\\p{N}_.]{1,32})$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final long DEBOUNCE_MS = 250;
    private static final int MAX = 5;

    private final Field field;
    private final Supplier<String> space;
    private String token;      // "@al" / "#фе" currently under the cursor, null = none
    private int tokenStart;
    private String asked;
    private long changedAt;
    private List<String> items = List.of(); // with the prefix: "@alex", "#ферма"

    public Suggest(Field field, Supplier<String> space) {
        this.field = field;
        this.space = space;
    }

    public boolean active() {
        return token != null && !items.isEmpty();
    }

    /** Call every tick. */
    public void tick() {
        String v = field.value();
        int c = Math.min(field.cursor(), v.length());
        Matcher m = TOKEN.matcher(v.substring(0, c));
        String now = null;
        int start = -1;
        if (m.find()) {
            now = m.group(1) + m.group(2);
            start = m.start(1);
        }
        if (!java.util.Objects.equals(now, token)) {
            token = now;
            tokenStart = start;
            changedAt = System.currentTimeMillis();
            if (now == null) {
                items = List.of();
                asked = null;
            }
        }
        if (token == null || token.equals(asked) || System.currentTimeMillis() - changedAt < DEBOUNCE_MS) {
            return;
        }
        String q = token;
        asked = q;
        String prefix = q.substring(1);
        if (q.startsWith("#")) {
            FeedApiClient.INSTANCE.hashtags(space.get(), prefix).whenComplete((list, ex) -> Minecraft.getInstance().execute(() -> {
                if (ex != null || !q.equals(token)) {
                    return;
                }
                List<String> out = new ArrayList<>();
                for (FeedApiClient.HashtagCount t : list) {
                    if (out.size() < MAX && !("#" + t.tag()).equalsIgnoreCase(q)) {
                        out.add("#" + t.tag());
                    }
                }
                items = out;
            }));
        } else {
            if (prefix.length() < 1) {
                return;
            }
            FeedApiClient.INSTANCE.searchUsers(prefix).whenComplete((list, ex) -> Minecraft.getInstance().execute(() -> {
                if (ex != null || !q.equals(token)) {
                    return;
                }
                List<String> out = new ArrayList<>();
                for (FeedApiClient.UserCard u : list) {
                    if (out.size() < MAX && u.tag() != null && !("@" + u.tag()).equalsIgnoreCase(q)) {
                        out.add("@" + u.tag());
                    }
                }
                items = out;
            }));
        }
    }

    /** Puts the n-th suggestion in place of the typed word. */
    public void apply(int n) {
        if (!active() || n < 0 || n >= items.size()) {
            return;
        }
        String pick = items.get(n);
        int end = Math.min(field.cursor(), field.value().length());
        String after = end < field.value().length() ? "" : " ";
        field.replace(tokenStart, end, pick + after);
        token = null;
        asked = null;
        items = List.of();
    }

    /** Tab: the first suggestion. */
    public boolean tab() {
        if (!active()) {
            return false;
        }
        apply(0);
        return true;
    }

    /** Draws "Tab ›" and the chips from x within width; returns true if something was drawn. */
    public boolean render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY, int bg) {
        if (!active()) {
            return false;
        }
        String hint = "Tab \u203a";
        int cx = x;
        if (bg != 0) {
            g.fill(x - 2, y - 2, x + w + 2, y + RetroUi.CHIP_H + 2, bg);
        }
        g.drawString(font, hint, cx, y + 2, RetroUi.GRAY, false);
        cx += font.width(hint) + 4;
        for (int i = 0; i < items.size(); i++) {
            String s = items.get(i);
            int cw = RetroUi.chipWidth(font, s);
            if (cx + cw > x + w) {
                break;
            }
            boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + RetroUi.CHIP_H;
            int color = s.startsWith("#") ? RetroUi.CYAN : RetroUi.ACCENT;
            RetroUi.drawChip(g, font, s, cx, y, hover ? RetroUi.FG : color, i == 0 && !hover);
            cx += cw + 4;
        }
        return true;
    }

    /** Click on a chip drawn by {@link #render}. */
    public boolean click(Font font, int x, int y, int w, double mouseX, double mouseY) {
        if (!active() || mouseY < y || mouseY >= y + RetroUi.CHIP_H) {
            return false;
        }
        int cx = x + font.width("Tab \u203a") + 4;
        for (int i = 0; i < items.size(); i++) {
            int cw = RetroUi.chipWidth(font, items.get(i));
            if (cx + cw > x + w) {
                break;
            }
            if (mouseX >= cx && mouseX < cx + cw) {
                apply(i);
                return true;
            }
            cx += cw + 4;
        }
        return false;
    }

    // ---------- adapters ----------

    public static Field of(net.minecraft.client.gui.components.MultiLineEditBox box) {
        net.minecraft.client.gui.components.MultilineTextField tf = ((com.feed.mixin.MultiLineEditBoxAccessor) box).feed$textField();
        return new Field() {
            @Override
            public String value() {
                return tf.value();
            }

            @Override
            public int cursor() {
                return tf.cursor();
            }

            @Override
            public void replace(int start, int end, String text) {
                tf.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, end);
                tf.setSelecting(true);
                tf.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, start);
                tf.setSelecting(false);
                tf.insertText(text);
            }
        };
    }

    public static Field of(net.minecraft.client.gui.components.EditBox box) {
        return new Field() {
            @Override
            public String value() {
                return box.getValue();
            }

            @Override
            public int cursor() {
                return box.getCursorPosition();
            }

            @Override
            public void replace(int start, int end, String text) {
                String v = box.getValue();
                String nv = v.substring(0, start) + text + v.substring(Math.min(end, v.length()));
                box.setValue(nv);
                box.setCursorPosition(start + text.length());
                box.setHighlightPos(start + text.length());
            }
        };
    }
}
