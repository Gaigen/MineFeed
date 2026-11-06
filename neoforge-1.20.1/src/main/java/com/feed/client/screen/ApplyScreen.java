package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.ApiError;
import com.feed.client.account.UpdateChecker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/** Application for an own community; reviewed by server staff in Discord. */
public class ApplyScreen extends ModalScreen {

    private static final int ABOUT_H = 44;
    private static final int W = 320;

    private enum Mode { FORM, NEED_DISCORD, PENDING, OWNER, SENT }

    private Mode mode;
    private EditBox name;
    private EditBox description;
    private EditBox links;
    private MultiLineEditBox about;
    private String[] keep = {"", "", "", ""};
    private String discordUrl;
    private int y0;

    public ApplyScreen(FeedScreen parent) {
        super(Component.translatable("feed.apply.title"), parent);
    }

    private Mode pickMode() {
        if (mode == Mode.SENT) {
            return Mode.SENT;
        }
        CommunityApi.Mine m = CommunityApi.cached();
        if (m != null && m.communities().stream().anyMatch(CommunityApi.Community::isOwner)) {
            return Mode.OWNER;
        }
        if (m != null && m.application() != null && m.application().pending()) {
            return Mode.PENDING;
        }
        AccountApi.Account a = AccountManager.INSTANCE.account();
        if (a != null && !a.discordLinked()) {
            return Mode.NEED_DISCORD;
        }
        return Mode.FORM;
    }

    @Override
    protected void init() {
        mode = pickMode();
        if (mode != Mode.FORM) {
            layout(300, 110);
            return;
        }
        int w0 = Math.min(W, this.width - 16) - PAD * 2;
        int introH = AccountUi.wrappedHeight(this.font, tr("feed.apply.intro"), w0);
        int h = 16 + introH + 4 + rejectedH() + 3 * (10 + AccountUi.FIELD_H + 4) + 10 + ABOUT_H + 4 + 22 + BTN_H + PAD * 2;
        layout(W, h);
        int x = innerX();
        int w = innerW();
        int y = bodyTop() + introH + 4 + rejectedH();
        y0 = y;
        y += 10;
        name = addField(AccountUi.field(this.font, x, y, w, 32, Component.translatable("feed.apply.name")));
        y += AccountUi.FIELD_H + 4 + 10;
        description = addField(AccountUi.field(this.font, x, y, w, 200, Component.translatable("feed.apply.description")));
        y += AccountUi.FIELD_H + 4 + 10;
        about = new RetroTextArea(this.font, x, y, w, ABOUT_H, Component.translatable("feed.apply.about_hint"), Component.empty());
        about.setCharacterLimit(1000);
        addRenderableWidget(about);
        y += ABOUT_H + 4 + 10;
        links = addField(AccountUi.field(this.font, x, y, w, 300, Component.translatable("feed.apply.links_hint")));
        name.setValue(keep[0]);
        description.setValue(keep[1]);
        about.setValue(keep[2]);
        links.setValue(keep[3]);
        name.setResponder(s -> keep[0] = s);
        description.setResponder(s -> keep[1] = s);
        about.setValueListener(s -> keep[2] = s);
        links.setResponder(s -> keep[3] = s);
        setInitialFocus(name);
    }

    private int rejectedH() {
        CommunityApi.Mine m = CommunityApi.cached();
        return m != null && m.application() != null && "rejected".equals(m.application().status()) ? 22 : 0;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int by = py + ph - PAD - BTN_H;
        switch (mode) {
            case FORM -> {
                int y = AccountUi.drawWrapped(g, this.font, tr("feed.apply.intro"), x, bodyTop(), w, RetroUi.GRAY);
                if (rejectedH() > 0) {
                    CommunityApi.Application a = CommunityApi.cached().application();
                    AccountUi.drawWrapped(g, this.font, tr("feed.apply.rejected", a.name(), a.reason() == null ? "-" : a.reason()),
                            x, y + 2, w, RetroUi.ERROR);
                }
                int ly = y0;
                g.drawString(this.font, tr("feed.apply.name"), x, ly, RetroUi.FG, false);
                ly += 10 + AccountUi.FIELD_H + 4;
                g.drawString(this.font, tr("feed.apply.description"), x, ly, RetroUi.FG, false);
                ly += 10 + AccountUi.FIELD_H + 4;
                g.drawString(this.font, tr("feed.apply.about"), x, ly, RetroUi.FG, false);
                ly += 10 + ABOUT_H + 4;
                g.drawString(this.font, tr("feed.apply.links"), x, ly, RetroUi.FG, false);
                drawStatus(g, by - 22);
                String send = tr("feed.apply.submit");
                int bw = buttonWidth(send);
                button(g, send, px + pw - PAD - bw, by, bw, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY, this::submit);
                if (discordUrl != null) {
                    button(g, tr("feed.apply.open_discord"), x, by, 0, BTN_H, RetroUi.FG, false, mouseX, mouseY,
                            () -> net.minecraft.Util.getPlatform().openUri(discordUrl));
                }
            }
            case NEED_DISCORD -> {
                AccountUi.drawWrapped(g, this.font, tr("feed.apply.need_discord"), x, bodyTop() + 4, w, RetroUi.FG);
                String link = tr("feed.apply.link_discord");
                int bw = buttonWidth(link);
                button(g, link, px + pw - PAD - bw, by, bw, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY,
                        () -> this.minecraft.setScreen(new DiscordScreen(this, this, false)));
            }
            case PENDING -> {
                CommunityApi.Application a = CommunityApi.cached().application();
                AccountUi.drawWrapped(g, this.font, tr("feed.apply.pending", a.name()), x, bodyTop() + 4, w, RetroUi.FG);
                closeButton(g, by, mouseX, mouseY);
            }
            case OWNER -> {
                AccountUi.drawWrapped(g, this.font, tr("feed.apply.has_community"), x, bodyTop() + 4, w, RetroUi.FG);
                closeButton(g, by, mouseX, mouseY);
            }
            case SENT -> {
                AccountUi.drawWrapped(g, this.font, tr("feed.apply.sent"), x, bodyTop() + 4, w, AccountUi.GREEN);
                closeButton(g, by, mouseX, mouseY);
            }
        }
    }

    private void closeButton(GuiGraphics g, int by, int mouseX, int mouseY) {
        String ok = tr("feed.apply.close");
        int bw = buttonWidth(ok);
        button(g, ok, px + pw - PAD - bw, by, bw, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String n = name.getValue().trim();
        if (n.length() < 3) {
            setStatus(Component.translatable("feed.community.error.bad_name"), RetroUi.ERROR);
            return;
        }
        busy = true;
        discordUrl = null;
        setStatus(null, RetroUi.ERROR);
        CommunityApi.apply(n, description.getValue().trim(), about.getValue().trim(), links.getValue().trim())
                .whenComplete((app, ex) -> this.minecraft.execute(() -> {
                    busy = false;
                    if (ex != null) {
                        ApiError e = CommunityApi.error(ex);
                        if ("not_in_guild".equals(e.code)) {
                            String u = e.extra.has("discordUrl") && !e.extra.get("discordUrl").isJsonNull()
                                    ? e.extra.get("discordUrl").getAsString() : null;
                            discordUrl = u != null ? u : UpdateChecker.discordUrl();
                        }
                        setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                        return;
                    }
                    mode = Mode.SENT;
                    keep = new String[] {"", "", "", ""};
                    parent.loadCommunities();
                    rebuildWidgets();
                }));
    }
}
