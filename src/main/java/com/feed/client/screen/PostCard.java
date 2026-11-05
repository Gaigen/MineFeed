package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** A post card, shared by the feed and the post screen: head, name @tag, time · space, type chip, text, picture, reactions. */
@OnlyIn(Dist.CLIENT)
public final class PostCard {

    public static final int PAD = 6;
    public static final int LINE = 10;
    public static final int GAP = 4;
    private static final int TEXT_TOP = 28;
    private static final int FOOTER = 18;

    public enum Kind { CARD, AUTHOR, MENTION, HASHTAG, COORDS, POLL, REACTION, IMAGE, COMMENTS }

    public static final int POLL_ROW = 14;

    public record Hit(Kind kind, String value) {}

    public record Layout(Post post, List<String> lines, int w, int h, int imgW, int imgH) {}

    private PostCard() {
    }

    /** Pictures stay a fraction of the screen so a card never fills the whole view. */
    public static int imageMaxH(int screenH, boolean full) {
        return full ? Math.max(90, Math.min(220, screenH / 2)) : Math.max(72, Math.min(140, screenH / 3));
    }

    public static Layout layout(Font font, Post post, int w, int screenH, boolean full) {
        List<String> lines = RetroUi.wrap(font, post.text != null ? post.text : "", w - PAD * 2);
        int h = TEXT_TOP + lines.size() * LINE;
        int iw = 0;
        int ih = 0;
        if (post.imageUrl != null) {
            int[] s = ImageBox.fit(post.imageW, post.imageH, w - PAD * 2, imageMaxH(screenH, full));
            iw = s[0];
            ih = s[1];
            h += ih + 6;
        }
        h += pollHeight(post);
        return new Layout(post, lines, w, h + FOOTER, iw, ih);
    }

    private static int pollHeight(Post post) {
        return post.hasPoll() ? 2 + post.pollOptions.size() * POLL_ROW + 10 : 0;
    }

    private static int pollY(Layout l, int y) {
        return footerY(l, y) - 1 - pollHeight(l.post());
    }

    private static String author(Post p) {
        return p.author == null || p.author.isEmpty() ? "?" : p.author;
    }

    private static String handle(Post p) {
        if (p.fromDiscord) {
            return "Discord";
        }
        return p.authorTag != null ? "@" + p.authorTag : "";
    }

    private static int chipX(Font font, Layout l, int x) {
        return x + l.w() - PAD - RetroUi.chipWidth(font, l.post().displayPrefix());
    }

    private static int imageY(Layout l, int y) {
        return y + TEXT_TOP + l.lines().size() * LINE + 2;
    }

    private static int footerY(Layout l, int y) {
        return y + l.h() - FOOTER + 1;
    }

    /** spaceName: shown after the time ("5 мин · Лента"), null = omit. */
    public static void draw(GuiGraphics g, Font font, Layout l, int x, int y, boolean hovered, boolean interactive,
                            int mouseX, int mouseY, String spaceName) {
        Post post = l.post();
        PostType type = post.postType();
        int w = l.w();
        RetroUi.drawBox(g, x, y, w, l.h(), hovered);
        int cardBg = hovered ? RetroUi.HOVER_BG : RetroUi.BG;

        RetroUi.drawAvatar(g, font, FeedApiClient.avatarKey(post.uuid, post.avatar), author(post), x + PAD, y + PAD, 16);

        int chipX = chipX(font, l, x);
        int nx = x + PAD + 21;
        boolean own = FeedApiClient.isMine(post.uuid);
        String name = FeedUtil.clip(font, author(post), Math.max(20, chipX - nx - 8));
        boolean nameHover = interactive && !post.fromDiscord && post.uuid != null
                && mouseX >= x + PAD && mouseX < nx + font.width(name) && mouseY >= y + PAD && mouseY < y + PAD + 16;
        g.drawString(font, name, nx, y + PAD, own ? RetroUi.ACCENT : nameHover ? RetroUi.STRONG : RetroUi.FG, false);
        String handle = handle(post);
        int hx = nx + font.width(name) + 4;
        if (!handle.isEmpty() && hx + font.width(handle) < chipX - 4) {
            g.drawString(font, handle, hx, y + PAD, RetroUi.GRAY, false);
        }
        String meta = FeedTime.ago(post.createdAt) + (spaceName != null ? " \u00b7 " + spaceName : "")
                + (post.edited ? " \u00b7 " + net.minecraft.network.chat.Component.translatable("feed.post.edited").getString() : "");
        int mx = nx;
        if (post.pinned) {
            PixelIcon.draw(g, "pin", mx, y + PAD + 9, RetroUi.ACCENT);
            mx += 10;
            String pin = net.minecraft.network.chat.Component.translatable("feed.post.pinned").getString();
            g.drawString(font, pin, mx, y + PAD + 9, RetroUi.ACCENT, false);
            mx += font.width(pin);
            meta = " \u00b7 " + meta;
        }
        g.drawString(font, FeedUtil.clip(font, meta, Math.max(20, chipX - mx - 4)), mx, y + PAD + 9, RetroUi.GRAY, false);
        RetroUi.drawChip(g, font, post.displayPrefix(), chipX, y + PAD, post.prefixColor(), false, cardBg);

        int ly = y + TEXT_TOP;
        for (String line : l.lines()) {
            drawRich(g, font, line, x + PAD, ly, RetroUi.FG, interactive ? mouseX : -1, mouseY);
            ly += LINE;
        }
        if (post.imageUrl != null) {
            int ix = x + PAD;
            int iy = imageY(l, y);
            g.fill(ix + 2, iy + 2, ix + l.imgW() + 3, iy + l.imgH() + 3, RetroUi.SHADOW);
            ImageBox.draw(g, font, post.imageUrl, ix, iy, l.imgW(), l.imgH());
            if (interactive && mouseX >= ix && mouseX < ix + l.imgW() && mouseY >= iy && mouseY < iy + l.imgH()) {
                g.renderOutline(ix - 1, iy - 1, l.imgW() + 2, l.imgH() + 2, RetroUi.FG);
                int px = ix + l.imgW() - 12;
                int py = iy + l.imgH() - 12;
                g.fill(px, py, px + 11, py + 11, 0xC8000000);
                PixelIcon.draw(g, "plus", px + 2, py + 2, 0xFFFFFFFF);
            }
        }

        if (post.hasPoll()) {
            drawPoll(g, font, post, x + PAD, pollY(l, y), w - PAD * 2, interactive, mouseX, mouseY, cardBg);
        }

        int by = footerY(l, y);
        int rx = x + PAD;
        for (String key : type.reactions()) {
            int count = post.reactionCount(key);
            EmojiIcon icon = EmojiIcon.byKey(key);
            int cw = RetroUi.reactionChipWidth(font, icon, String.valueOf(count));
            boolean hv = interactive && mouseX >= rx && mouseX < rx + cw && mouseY >= by && mouseY < by + RetroUi.CHIP_H;
            RetroUi.drawReactionChip(g, font, icon, count, rx, by, type.accent(), post.mine(key), cardBg, hv);
            rx += cw + GAP;
        }
        String cc = String.valueOf(post.commentCount);
        int cw = font.width(cc) + 18;
        g.fill(rx, by, rx + cw, by + RetroUi.CHIP_H, cardBg);
        g.renderOutline(rx, by, cw, RetroUi.CHIP_H, RetroUi.GRAY);
        PixelIcon.draw(g, "chat", rx + 4, by + 2, RetroUi.GRAY);
        g.drawString(font, cc, rx + 13, by + 2, RetroUi.GRAY, false);
    }

    /** Before voting: option buttons; after (or on a closed question): bars with percentages, my choice marked. */
    private static void drawPoll(GuiGraphics g, Font font, Post post, int x, int y, int w, boolean interactive,
                                 int mouseX, int mouseY, int cardBg) {
        int total = post.pollTotal();
        boolean results = post.pollMy >= 0 || post.solved || FeedApiClient.isMine(post.uuid);
        int oy = y + 2;
        for (int i = 0; i < post.pollOptions.size(); i++) {
            boolean hover = interactive && !post.solved && mouseX >= x && mouseX < x + w && mouseY >= oy && mouseY < oy + POLL_ROW - 2;
            boolean mine = i == post.pollMy;
            int rowH = POLL_ROW - 2;
            g.fill(x, oy, x + w, oy + rowH, cardBg);
            if (results && total > 0) {
                int fw = (int) Math.round((double) w * post.pollVotes[i] / total);
                if (fw > 0) {
                    g.fill(x, oy, x + fw, oy + rowH, mine ? (RetroUi.ACCENT & 0x00FFFFFF) | 0x55000000 : (RetroUi.GRAY & 0x00FFFFFF) | 0x40000000);
                }
            }
            g.renderOutline(x, oy, w, rowH, mine ? RetroUi.ACCENT : hover ? RetroUi.FG : RetroUi.GRAY);
            String label = (mine ? "\u2713 " : "") + post.pollOptions.get(i);
            String right = results ? (total > 0 ? Math.round(100.0 * post.pollVotes[i] / total) + "%" : "0%") : "";
            int rw = font.width(right);
            g.drawString(font, FeedUtil.clip(font, label, w - 12 - rw), x + 4, oy + 2, mine ? RetroUi.ACCENT : RetroUi.FG, false);
            if (!right.isEmpty()) {
                g.drawString(font, right, x + w - 4 - rw, oy + 2, RetroUi.GRAY, false);
            }
            oy += POLL_ROW;
        }
        String info = net.minecraft.network.chat.Component.translatable("feed.poll.votes", total).getString()
                + (post.solved ? " \u00b7 " + net.minecraft.network.chat.Component.translatable("feed.poll.closed").getString()
                : !results ? " \u00b7 " + net.minecraft.network.chat.Component.translatable("feed.poll.hint").getString() : "");
        PixelIcon.draw(g, "poll", x, oy, RetroUi.GRAY);
        g.drawString(font, FeedUtil.clip(font, info, w - 10), x + 10, oy, RetroUi.GRAY, false);
    }

    /** Text with @mentions in the accent color and #hashtags in cyan. */
    public static void drawRich(GuiGraphics g, Font font, String line, int x, int y, int color) {
        drawRich(g, font, line, x, y, color, -1, -1);
    }

    /** Same, underlining the link under the mouse. */
    public static void drawRich(GuiGraphics g, Font font, String line, int x, int y, int color, int mouseX, int mouseY) {
        boolean row = mouseY >= y - 1 && mouseY < y + 10;
        for (TagText.Seg seg : TagText.segments(line)) {
            int c = switch (seg.kind()) {
                case MENTION -> RetroUi.ACCENT;
                case HASHTAG -> RetroUi.CYAN;
                case COORDS -> AccountUi.GREEN;
                default -> color;
            };
            int w = font.width(seg.text());
            g.drawString(font, seg.text(), x, y, c, false);
            if (seg.tag() && row && mouseX >= x && mouseX < x + w) {
                g.fill(x, y + 9, x + w - 1, y + 10, c);
            }
            x += w;
        }
    }

    /** Clickable thing under the mouse in a wrapped line: "@tag" (player) or "#tag" (hashtag), else null. */
    public static String linkAt(Font font, String line, int x, double mouseX) {
        for (TagText.Seg seg : TagText.segments(line)) {
            int w = font.width(seg.text());
            if (mouseX >= x && mouseX < x + w) {
                return switch (seg.kind()) {
                    case MENTION -> "@" + trimMention(seg.text());
                    case HASHTAG -> "#" + TagText.normalizeHashtag(seg.text());
                    case COORDS -> "~" + seg.text().substring(1, seg.text().length() - 1);
                    default -> null;
                };
            }
            x += w;
        }
        return null;
    }

    /** @tag under the mouse in a wrapped line (without "@"), or null. */
    public static String mentionAt(Font font, String line, int x, double mouseX) {
        String link = linkAt(font, line, x, mouseX);
        return link != null && link.startsWith("@") ? link.substring(1) : null;
    }

    /** "@tag." at the end of a sentence: drop trailing dots. */
    public static String trimMention(String seg) {
        String t = seg.startsWith("@") ? seg.substring(1) : seg;
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.toLowerCase();
    }

    public static Hit hit(Font font, Layout l, int x, int y, double mouseX, double mouseY) {
        Post post = l.post();
        int nx = x + PAD + 21;
        if (!post.fromDiscord && post.uuid != null && mouseY >= y + PAD && mouseY < y + PAD + 16
                && mouseX >= x + PAD && mouseX < nx + font.width(author(post))) {
            return new Hit(Kind.AUTHOR, post.uuid);
        }
        int ly = y + TEXT_TOP;
        if (mouseY >= ly && mouseY < ly + l.lines().size() * LINE) {
            int index = (int) ((mouseY - ly) / LINE);
            String link = linkAt(font, l.lines().get(index), x + PAD, mouseX);
            if (link != null) {
                return new Hit(link.startsWith("#") ? Kind.HASHTAG : link.startsWith("~") ? Kind.COORDS : Kind.MENTION, link.substring(1));
            }
        }
        if (post.imageUrl != null) {
            int iy = imageY(l, y);
            if (mouseX >= x + PAD && mouseX < x + PAD + l.imgW() && mouseY >= iy && mouseY < iy + l.imgH()) {
                return new Hit(Kind.IMAGE, post.imageUrl);
            }
        }
        if (post.hasPoll() && !post.solved) {
            int oy = pollY(l, y) + 2;
            for (int i = 0; i < post.pollOptions.size(); i++) {
                if (mouseX >= x + PAD && mouseX < x + l.w() - PAD && mouseY >= oy && mouseY < oy + POLL_ROW - 2) {
                    return new Hit(Kind.POLL, String.valueOf(i));
                }
                oy += POLL_ROW;
            }
        }
        int by = footerY(l, y);
        if (mouseY >= by && mouseY < by + RetroUi.CHIP_H) {
            int rx = x + PAD;
            for (String key : post.postType().reactions()) {
                int w = RetroUi.reactionChipWidth(font, EmojiIcon.byKey(key), String.valueOf(post.reactionCount(key)));
                if (mouseX >= rx && mouseX < rx + w) {
                    return new Hit(Kind.REACTION, key);
                }
                rx += w + GAP;
            }
            int cw = font.width(String.valueOf(post.commentCount)) + 18;
            if (mouseX >= rx && mouseX < rx + cw) {
                return new Hit(Kind.COMMENTS, null);
            }
        }
        return new Hit(Kind.CARD, null);
    }
}
