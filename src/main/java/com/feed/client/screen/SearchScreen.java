package com.feed.client.screen;

import com.feed.client.AvatarCache;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** Find a player by nick or @tag; results update while typing. Enter opens the first one. */
@OnlyIn(Dist.CLIENT)
public class SearchScreen extends ModalScreen {

    private static final int ROW_H = 20;
    private static final long DEBOUNCE_MS = 300;

    private EditBox query;
    private String keep = "";
    private String asked = "";
    private long typedAt;
    private List<FeedApiClient.UserCard> results;
    private boolean searching;

    public SearchScreen(FeedScreen parent) {
        super(Component.translatable("feed.search.title"), parent);
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
        if (!q.equals(asked) && System.currentTimeMillis() - typedAt >= DEBOUNCE_MS) {
            asked = q;
            if (q.isEmpty() || q.equals("@")) {
                results = null;
                return;
            }
            searching = true;
            FeedApiClient.INSTANCE.searchUsers(q).whenComplete((list, ex) -> this.minecraft.execute(() -> {
                if (!q.equals(asked)) {
                    return;
                }
                searching = false;
                if (ex != null) {
                    setStatus(FeedApiClient.unwrap(ex).userMessage(), RetroUi.ERROR);
                    return;
                }
                setStatus(null, RetroUi.ERROR);
                results = list;
            }));
        }
    }

    private void open(FeedApiClient.UserCard u) {
        parent.openProfile(u.id());
        this.minecraft.setScreen(parent);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int y = bodyTop() + 4 + AccountUi.FIELD_H + 8;
        if (results == null) {
            String hint = searching ? tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : "") : tr("feed.search.start");
            g.drawString(this.font, hint, x, y + 4, RetroUi.GRAY, false);
        } else if (results.isEmpty()) {
            g.drawString(this.font, tr("feed.search.empty"), x, y + 4, RetroUi.GRAY, false);
        } else {
            for (FeedApiClient.UserCard u : results) {
                if (y + ROW_H > py + ph - PAD) {
                    break;
                }
                boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H - 2;
                if (hover) {
                    g.fill(x, y, x + w, y + ROW_H - 2, RetroUi.HOVER_BG);
                }
                ResourceLocation head = AvatarCache.get(FeedApiClient.avatarKey(u.id(), u.avatar()));
                if (head != null) {
                    g.blit(head, x + 2, y + 1, 0, 0, 16, 16, 16, 16);
                } else {
                    g.fill(x + 2, y + 1, x + 18, y + 17, RetroUi.GRAY);
                }
                boolean me = FeedApiClient.isMine(u.id());
                String nick = FeedUtil.clip(this.font, u.nick(), w / 2);
                g.drawString(this.font, nick, x + 24, y + 5, me ? RetroUi.ACCENT : hover ? RetroUi.STRONG : RetroUi.FG, false);
                g.drawString(this.font, FeedUtil.clip(this.font, "@" + u.tag(), w - 30 - this.font.width(nick)),
                        x + 28 + this.font.width(nick), y + 5, RetroUi.GRAY, false);
                hit(x, y, w, ROW_H - 2, () -> open(u));
                y += ROW_H;
            }
        }
        drawStatus(g, py + ph - PAD - 8);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode) && results != null && !results.isEmpty()) {
            open(results.get(0));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
