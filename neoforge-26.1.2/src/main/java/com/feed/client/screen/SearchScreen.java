package com.feed.client.screen;

import com.feed.client.AvatarCache;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Search: players by nick or @tag and #hashtags of the current space; results update while typing.
 * With an empty query it shows the popular tags. Enter opens the first result.
 */
public class SearchScreen extends ModalScreen {

    private static final int ROW_H = 20;
    private static final int TAG_ROW_H = 14;
    private static final long DEBOUNCE_MS = 300;
    private static final int MIXED_TAGS = 3;

    /** One result row: a player or a hashtag. */
    private record Entry(FeedApiClient.UserCard user, FeedApiClient.HashtagCount tag) {}

    private EditBox query;
    private String keep = "";
    private String asked = null;
    private long typedAt;
    private List<Entry> results;
    private boolean popular;
    private boolean searching;

    public SearchScreen(FeedScreen parent) {
        super(Component.translatable("feed.search.title"), parent);
    }

    private String space() {
        return parent.space();
    }

    @Override
    protected void init() {
        layout(280, 16 + PAD * 2 + 14 + AccountUi.FIELD_H + 10 + 10 * ROW_H);
        int y = bodyTop() + 4;
        query = AccountUi.field(this.font, innerX(), y, innerW(), 33, Component.translatable("feed.search.hint"));
        query.setValue(keep);
        query.setResponder(s -> {
            keep = s;
            typedAt = System.currentTimeMillis();
        });
        addField(query);
        setInitialFocus(query);
    }

    @Override
    public void tick() {
        super.tick();
        String q = keep.trim();
        if (q.equals(asked) || (asked != null && System.currentTimeMillis() - typedAt < DEBOUNCE_MS)) {
            return;
        }
        asked = q;
        searching = true;
        if (q.isEmpty() || q.equals("#")) {
            FeedApiClient.INSTANCE.hashtags(space(), null).whenComplete((tags, ex) -> done(q, true, null, tags, ex));
            return;
        }
        if (q.equals("@")) {
            searching = false;
            results = null;
            return;
        }
        if (q.startsWith("#")) {
            FeedApiClient.INSTANCE.hashtags(space(), q.substring(1)).whenComplete((tags, ex) -> done(q, false, null, tags, ex));
            return;
        }
        boolean tagLike = !q.startsWith("@") && TagText.normalizeHashtag(q + (q.length() < 2 ? "x" : "")) != null;
        FeedApiClient.INSTANCE.searchUsers(q).whenComplete((users, ex) -> {
            if (ex != null || !tagLike) {
                done(q, false, users, null, ex);
                return;
            }
            FeedApiClient.INSTANCE.hashtags(space(), q).whenComplete((tags, ex2) ->
                    done(q, false, users, tags == null ? null : tags.subList(0, Math.min(MIXED_TAGS, tags.size())), null));
        });
    }

    private void done(String q, boolean isPopular, List<FeedApiClient.UserCard> users,
                      List<FeedApiClient.HashtagCount> tags, Throwable ex) {
        this.minecraft.execute(() -> {
            if (!q.equals(asked)) {
                return;
            }
            searching = false;
            if (ex != null) {
                setStatus(FeedApiClient.unwrap(ex).userMessage(), RetroUi.ERROR);
                return;
            }
            setStatus(null, RetroUi.ERROR);
            List<Entry> list = new ArrayList<>();
            if (users != null) {
                users.forEach(u -> list.add(new Entry(u, null)));
            }
            if (tags != null) {
                tags.forEach(t -> list.add(new Entry(null, t)));
            }
            popular = isPopular;
            results = list;
        });
    }

    private void open(Entry e) {
        if (e.user() != null) {
            parent.openProfile(e.user().id());
        } else {
            parent.openHashtag(space(), e.tag().tag());
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int y = bodyTop() + 4 + AccountUi.FIELD_H + 8;
        int bottom = py + ph - PAD;
        if (results == null) {
            String hint = searching ? tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : "") : tr("feed.search.start");
            g.text(this.font, hint, x, y + 4, RetroUi.GRAY, false);
        } else if (popular) {
            String where = "global".equals(space()) ? tr("feed.space.global") : parent.community() != null ? parent.community().name() : "";
            g.text(this.font, FeedUtil.clip(this.font, Component.translatable("feed.search.popular", where).getString(), w),
                    x, y + 2, RetroUi.GRAY, false);
            y += 14;
            if (results.isEmpty()) {
                for (String line : this.font.getSplitter().splitLines(tr("feed.search.popular_empty"), w, net.minecraft.network.chat.Style.EMPTY)
                        .stream().map(net.minecraft.network.chat.FormattedText::getString).toList()) {
                    g.text(this.font, line, x, y + 2, RetroUi.FG, false);
                    y += 10;
                }
            } else {
                // popular tags as chips that wrap
                int cx = x;
                for (Entry e : results) {
                    String label = "#" + e.tag().tag() + " " + e.tag().posts();
                    int cw = RetroUi.chipWidth(this.font, label);
                    if (cx + cw > x + w && cx > x) {
                        cx = x;
                        y += RetroUi.CHIP_H + 4;
                    }
                    if (y + RetroUi.CHIP_H > bottom - 10) {
                        break;
                    }
                    boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + RetroUi.CHIP_H;
                    RetroUi.drawChip(g, this.font, label, cx, y, hover ? RetroUi.FG : RetroUi.CYAN, hover);
                    hit(cx, y, cw, RetroUi.CHIP_H, () -> open(e));
                    cx += cw + 4;
                }
                y += RetroUi.CHIP_H + 10;
                g.text(this.font, FeedUtil.clip(this.font, tr("feed.search.tip"), w), x, y, RetroUi.GRAY, false);
            }
        } else if (results.isEmpty()) {
            g.text(this.font, tr(keep.trim().startsWith("#") ? "feed.search.empty_tags" : "feed.search.empty"), x, y + 4,
                    RetroUi.GRAY, false);
        } else {
            for (Entry e : results) {
                int rh = e.user() != null ? ROW_H : TAG_ROW_H;
                if (y + rh > bottom) {
                    break;
                }
                boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + rh - 2;
                if (hover) {
                    g.fill(x, y, x + w, y + rh - 2, RetroUi.HOVER_BG);
                }
                if (e.user() != null) {
                    drawUser(g, e.user(), x, y, w, hover);
                } else {
                    String tag = "#" + e.tag().tag();
                    g.text(this.font, FeedUtil.clip(this.font, tag, w - 60), x + 4, y + 2, hover ? RetroUi.STRONG : RetroUi.CYAN, false);
                    String n = Component.translatable("feed.search.tag_posts", e.tag().posts()).getString();
                    g.text(this.font, n, x + w - 4 - this.font.width(n), y + 2, RetroUi.GRAY, false);
                }
                hit(x, y, w, rh - 2, () -> open(e));
                y += rh;
            }
        }
        drawStatus(g, bottom - 8);
    }

    private void drawUser(GuiGraphicsExtractor g, FeedApiClient.UserCard u, int x, int y, int w, boolean hover) {
        Identifier head = AvatarCache.get(FeedApiClient.avatarKey(u.id(), u.avatar()));
        if (head != null) {
            g.blit(head, x + 2, y + 1, 0, 0, 16, 16, 16, 16);
        } else {
            g.fill(x + 2, y + 1, x + 18, y + 17, RetroUi.GRAY);
        }
        boolean me = FeedApiClient.isMine(u.id());
        String nick = FeedUtil.clip(this.font, u.nick(), w / 2);
        g.text(this.font, nick, x + 24, y + 5, me ? RetroUi.ACCENT : hover ? RetroUi.STRONG : RetroUi.FG, false);
        g.text(this.font, FeedUtil.clip(this.font, "@" + u.tag(), w - 30 - this.font.width(nick)),
                x + 28 + this.font.width(nick), y + 5, RetroUi.GRAY, false);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(), scanCode = event.scancode(), modifiers = event.modifiers();
        if (isEnter(keyCode) && results != null && !results.isEmpty()) {
            open(results.get(0));
            return true;
        }
        return super.keyPressed(event);
    }
}
