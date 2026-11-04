package com.feed.client.screen;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.TagRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Own profile: change nick / @tag, recovery code, sign in with a code, sign out. */
@OnlyIn(Dist.CLIENT)
public class ProfileScreen extends AccountPanelScreen {

    private static final int SIDE_BTN_W = 66;
    private static final int GAP = 6;

    private final Screen parent;
    private TagChecker checker;
    private String nickValue;
    private String tagValue;
    private EditBox nick;
    private EditBox tag;
    private RetroButton saveNick;
    private RetroButton changeTag;
    private boolean refreshed;
    private String note;
    private int infoY;
    private int nickLabelY;
    private int tagLabelY;
    private int tagStatusY;
    private int noteY;
    private int statusY;

    public ProfileScreen(Screen parent) {
        super(Component.translatable("feed.account.profile.title"));
        this.parent = parent;
    }

    private AccountApi.Account acc() {
        return AccountManager.INSTANCE.account();
    }

    private boolean onCooldown() {
        AccountApi.Account a = acc();
        return a != null && a.canChangeTagAt() != null && a.canChangeTagAt() > System.currentTimeMillis();
    }

    @Override
    protected void init() {
        AccountApi.Account a = acc();
        if (a == null) {
            this.minecraft.tell(() -> {
                if (this.minecraft.screen == this) {
                    this.minecraft.setScreen(new WelcomeScreen(parent));
                }
            });
            return;
        }
        if (nickValue == null) {
            nickValue = a.nick();
            tagValue = a.tag();
        }
        checker = new TagChecker(a.tag());
        int w = panelWidth() - PAD * 2;
        note = Component.translatable("feed.account.tag.rules").getString();
        int noteH = AccountUi.wrappedHeight(this.font, note, w);
        int y = layoutPanel(3 * LINE + 6 + LINE + AccountUi.FIELD_H + 6 + LINE + AccountUi.FIELD_H + 3 + LINE
                + noteH + 4 + LINE + 4 + BTN_H + 6 + BTN_H);
        infoY = y;
        y += 3 * LINE + 6;

        int fieldW = innerW() - SIDE_BTN_W - GAP;
        nickLabelY = y;
        y += LINE;
        nick = addField(AccountUi.field(this.font, innerX(), y, fieldW, TagRules.NICK_MAX,
                Component.translatable("feed.account.nick.hint")));
        nick.setValue(nickValue);
        nick.setResponder(s -> nickValue = s);
        saveNick = addRenderableWidget(new RetroButton(innerX() + fieldW + GAP, y - 1, SIDE_BTN_W, AccountUi.FIELD_H + 2,
                Component.translatable("feed.account.profile.save"), b -> saveNick()));
        y += AccountUi.FIELD_H + 6;

        tagLabelY = y;
        y += LINE;
        tag = addField(AccountUi.tagField(this.font, innerX(), y, fieldW,
                Component.translatable("feed.account.tag.hint")));
        tag.setResponder(s -> {
            tagValue = s;
            checker.onChanged(s);
        });
        tag.setValue(tagValue);
        checker.onChanged(tagValue);
        tag.setEditable(!onCooldown());
        changeTag = addRenderableWidget(new RetroButton(innerX() + fieldW + GAP, y - 1, SIDE_BTN_W, AccountUi.FIELD_H + 2,
                Component.translatable("feed.account.profile.change"), b -> confirmTag()));
        y += AccountUi.FIELD_H + 3;
        tagStatusY = y;
        y += LINE;
        noteY = y;
        y += noteH + 4;
        statusY = y;
        y += LINE + 4;

        int bw = buttonsRowWidth(3, GAP);
        addRenderableWidget(new RetroButton(innerX(), y, bw, BTN_H,
                Component.translatable("feed.account.profile.new_code"), b -> confirmNewCode()));
        addRenderableWidget(new RetroButton(innerX() + bw + GAP, y, bw, BTN_H,
                Component.translatable("feed.account.profile.code_login"),
                b -> this.minecraft.setScreen(new RecoverScreen(this, parent))));
        addRenderableWidget(new RetroButton(innerX() + (bw + GAP) * 2, y, innerW() - (bw + GAP) * 2, BTN_H,
                Component.translatable("feed.account.profile.logout"), b -> confirmLogout()));
        y += BTN_H + 6;
        addRenderableWidget(new RetroButton(innerX(), y, innerW(), BTN_H,
                Component.translatable("feed.account.back"), b -> onClose()));
        updateButtons();

        if (!refreshed) {
            refreshed = true;
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().me(), fresh -> {
                AccountManager.INSTANCE.updateAccount(fresh);
                if (this.minecraft.screen == this) {
                    rebuildWidgets();
                }
            }, err -> {
                if (this.minecraft.screen == this && AccountManager.INSTANCE.account() == null) {
                    this.minecraft.setScreen(new WelcomeScreen(parent));
                }
            });
        }
    }

    private void updateButtons() {
        if (saveNick == null) {
            return;
        }
        AccountApi.Account a = acc();
        String clean = TagRules.cleanNick(nickValue);
        saveNick.active = !busy && a != null && TagRules.validNick(clean) && !clean.equals(a.nick());
        changeTag.active = !busy && !onCooldown() && checker.state() != TagChecker.State.OWN
                && checker.submittable();
    }

    private void saveNick() {
        String clean = TagRules.cleanNick(nickValue);
        if (busy || !TagRules.validNick(clean)) {
            return;
        }
        busy = true;
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().setNick(clean), a -> {
            busy = false;
            AccountManager.INSTANCE.updateAccount(a);
            nickValue = a.nick();
            if (nick != null) {
                nick.setValue(nickValue);
            }
            setStatus(Component.translatable("feed.account.profile.nick_saved"), AccountUi.GREEN);
        }, this::fail);
    }

    private void confirmTag() {
        AccountApi.Account a = acc();
        if (busy || a == null || !checker.submittable() || onCooldown()) {
            return;
        }
        String newTag = checker.value();
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.account.profile.tag_confirm_title"),
                Component.translatable("feed.account.profile.tag_confirm", "@" + a.tag(), "@" + newTag),
                Component.translatable("feed.account.profile.change"), false, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().setTag(newTag), updated -> {
                busy = false;
                AccountManager.INSTANCE.updateAccount(updated);
                tagValue = updated.tag();
                setStatus(Component.translatable("feed.account.profile.tag_saved", "@" + updated.tag()), AccountUi.GREEN);
                rebuildWidgets();
            }, err -> {
                if ("tag_taken".equals(err.code)) {
                    checker.markTaken();
                }
                fail(err);
            });
        }));
    }

    private void confirmNewCode() {
        if (busy) {
            return;
        }
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.account.profile.new_code_title"),
                Component.translatable("feed.account.profile.new_code_confirm"),
                Component.translatable("feed.account.profile.new_code"), false, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().newRecoveryCode(), code -> {
                busy = false;
                this.minecraft.setScreen(new RecoveryCodeScreen(this, code, false));
            }, this::fail);
        }));
    }

    private void confirmLogout() {
        AccountApi.Account a = acc();
        if (busy || a == null) {
            return;
        }
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.account.profile.logout_title"),
                Component.translatable("feed.account.profile.logout_confirm", "@" + a.tag()),
                Component.translatable("feed.account.profile.logout"), true, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            // Sign out locally even if the server can't be reached: the key is gone from this PC.
            AccountManager.INSTANCE.api().logout().whenComplete((v, ex) -> this.minecraft.execute(() -> {
                busy = false;
                AccountManager.INSTANCE.signOutLocal();
                this.minecraft.setScreen(new WelcomeScreen(parent));
            }));
        }));
    }

    private void fail(com.feed.client.account.ApiError err) {
        busy = false;
        if (AccountManager.INSTANCE.account() == null) {
            this.minecraft.setScreen(new WelcomeScreen(parent));
            return;
        }
        setStatus(AccountUi.describe(err), RetroUi.ERROR);
    }

    @Override
    public void tick() {
        super.tick();
        if (checker != null) {
            checker.tick();
        }
        updateButtons();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode) && getFocused() == nick) {
            saveNick();
            return true;
        }
        if (isEnter(keyCode) && getFocused() == tag) {
            confirmTag();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountApi.Account a = acc();
        if (a == null) {
            return;
        }
        int x = innerX();
        String handle = "@" + a.tag();
        g.drawString(this.font, handle, x, infoY, RetroUi.ACCENT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(a.nick(), innerW() - this.font.width(handle) - 8),
                x + this.font.width(handle) + 8, infoY, RetroUi.FG, false);
        g.drawString(this.font, Component.translatable("feed.account.profile.since", AccountUi.date(a.createdAt())),
                x, infoY + LINE, RetroUi.GRAY, false);
        g.drawString(this.font, Component.translatable(a.discordLinked()
                        ? (a.tagProtected() ? "feed.account.profile.discord_protected" : "feed.account.profile.discord_linked")
                        : "feed.account.profile.discord_none"),
                x, infoY + 2 * LINE, RetroUi.GRAY, false);

        AccountUi.drawLabel(g, this.font, Component.translatable("feed.account.nick.label"), x, nickLabelY);
        AccountUi.drawLabel(g, this.font, Component.translatable("feed.account.tag.label"), x, tagLabelY);
        if (onCooldown()) {
            g.drawString(this.font, Component.translatable("feed.account.tag.cooldown", AccountUi.date(a.canChangeTagAt())),
                    x, tagStatusY, RetroUi.GRAY, false);
        } else {
            g.drawString(this.font, checker.message(), x, tagStatusY, checker.color(), false);
        }
        AccountUi.drawWrapped(g, this.font, note, x, noteY, innerW(), RetroUi.SHADOW + 0x303030);
        drawStatus(g, statusY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
