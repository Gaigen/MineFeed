package com.feed.client.screen;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.ApiError;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Discord link (from the profile) or Discord sign-in (from the welcome screen).
 * Opens the browser, then polls the server until the user is back.
 */
@OnlyIn(Dist.CLIENT)
public class DiscordScreen extends AccountPanelScreen {

    private static final long POLL_MS = 2000;

    private final Screen back;
    private final Screen next;
    private final boolean login;
    private AccountApi.DiscordFlow flow;
    private boolean starting;
    private boolean polling;
    private boolean finished;
    private long lastPoll;
    private String intro;
    private int introY;
    private int statusY;
    private RetroButton reopen;
    private RetroButton copy;

    /** @param back where Cancel goes; @param next where to go after a successful sign-in / link. */
    public DiscordScreen(Screen back, Screen next, boolean login) {
        super(Component.translatable(login ? "feed.account.discord.login_title" : "feed.account.discord.link_title"));
        this.back = back;
        this.next = next;
        this.login = login;
    }

    @Override
    protected void init() {
        int w = panelWidth() - PAD * 2;
        intro = Component.translatable(login ? "feed.account.discord.login_intro" : "feed.account.discord.link_intro").getString();
        int introH = AccountUi.wrappedHeight(this.font, intro, w);
        int y = layoutPanel(introH + 6 + LINE + 6 + BTN_H + 6 + BTN_H);
        introY = y;
        y += introH + 6;
        statusY = y;
        y += LINE + 6;
        int bw = buttonsRowWidth(2, 8);
        reopen = addRenderableWidget(new RetroButton(innerX(), y, bw, BTN_H, reopenLabel(), b -> {
            if (flow == null || finished) {
                begin();
            } else {
                openBrowser();
            }
        }));
        copy = addRenderableWidget(new RetroButton(innerX() + bw + 8, y, bw, BTN_H,
                Component.translatable("feed.account.discord.copy"), b -> {
            if (flow != null) {
                this.minecraft.keyboardHandler.setClipboard(flow.url());
                b.setMessage(Component.translatable("feed.account.recovery.copied"));
            }
        }));
        y += BTN_H + 6;
        addRenderableWidget(new RetroButton(innerX(), y, innerW(), BTN_H,
                Component.translatable("feed.account.cancel"), b -> onClose()));
        if (flow == null && !starting && !finished) {
            begin();
        }
        updateButtons();
    }

    private Component reopenLabel() {
        return Component.translatable(flow == null || finished ? "feed.account.discord.retry" : "feed.account.discord.reopen");
    }

    private void updateButtons() {
        if (reopen != null) {
            reopen.setMessage(reopenLabel());
            reopen.active = !starting;
            copy.active = flow != null && !finished;
        }
    }

    private void begin() {
        starting = true;
        finished = false;
        flow = null;
        setStatus(Component.translatable("feed.account.discord.opening"), RetroUi.GRAY);
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().discordStart(login), f -> {
            starting = false;
            flow = f;
            lastPoll = System.currentTimeMillis();
            setStatus(Component.translatable("feed.account.discord.waiting"), RetroUi.GRAY);
            openBrowser();
            updateButtons();
        }, err -> {
            starting = false;
            finished = true;
            setStatus(AccountUi.describe(err), RetroUi.ERROR);
            updateButtons();
        });
    }

    private void openBrowser() {
        if (flow != null) {
            Util.getPlatform().openUri(flow.url());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (flow == null || finished || polling || System.currentTimeMillis() - lastPoll < POLL_MS) {
            return;
        }
        polling = true;
        lastPoll = System.currentTimeMillis();
        AccountApi.DiscordFlow current = flow;
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().discordPoll(current), r -> {
            polling = false;
            if (current != flow || finished) {
                return;
            }
            if ("pending".equals(r.status())) {
                return;
            }
            finished = true;
            if ("done".equals(r.status())) {
                done(r);
            } else {
                setStatus(AccountUi.describe(new ApiError(400, r.code(), r.error(), null)), RetroUi.ERROR);
            }
            updateButtons();
        }, err -> {
            polling = false;
            if (!err.isNetwork()) {
                finished = true;
                setStatus(AccountUi.describe(err), RetroUi.ERROR);
                updateButtons();
            }
        });
    }

    private void done(AccountApi.DiscordPoll r) {
        if (login) {
            AccountManager.INSTANCE.signIn(new AccountApi.Session(r.account(), r.key(), null));
            this.minecraft.setScreen(next);
            return;
        }
        AccountManager.INSTANCE.updateAccount(r.account());
        AccountApi.Account a = r.account();
        if (a.claimableTag() != null) {
            this.minecraft.setScreen(new RetroConfirmScreen(next,
                    Component.translatable("feed.account.discord.claim_title"),
                    Component.translatable("feed.account.discord.claim_confirm", "@" + a.claimableTag()),
                    Component.translatable("feed.account.discord.claim_yes"), false, () -> {
                this.minecraft.setScreen(next);
                AccountManager.thenOnMain(AccountManager.INSTANCE.api().setTag(a.claimableTag()), updated -> {
                    AccountManager.INSTANCE.updateAccount(updated);
                    if (next instanceof ProfileScreen ps) {
                        ps.notice(Component.translatable("feed.account.profile.tag_saved", "@" + updated.tag()), AccountUi.GREEN);
                    }
                }, err -> {
                    if (next instanceof ProfileScreen ps) {
                        ps.notice(AccountUi.describe(err), RetroUi.ERROR);
                    }
                });
            }));
            return;
        }
        if (next instanceof ProfileScreen ps) {
            ps.notice(Component.translatable(a.tagProtected() ? "feed.account.discord.linked_protected"
                    : "feed.account.discord.linked"), AccountUi.GREEN);
        }
        this.minecraft.setScreen(next);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountUi.drawWrapped(g, this.font, intro, innerX(), introY, innerW(), RetroUi.FG);
        String s = status.getString();
        if (!finished && flow != null && RetroUi.blink()) {
            s += "_";
        }
        g.drawString(this.font, this.font.plainSubstrByWidth(s, innerW()), innerX(), statusY, statusColor, false);
    }

    @Override
    public void onClose() {
        finished = true;
        this.minecraft.setScreen(back);
    }
}
