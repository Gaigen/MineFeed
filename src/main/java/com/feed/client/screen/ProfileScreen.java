package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.TagRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Account settings page: nick, @tag, avatar, Discord, recovery code / sign in / sign out — one row each. */
@OnlyIn(Dist.CLIENT)
public class ProfileScreen extends ShellScreen {

    private static final int BTN_H = AccountUi.FIELD_H;
    private static final int LABEL_W = 56;
    private static final int GAP = 6;
    private static final int CARD_Y = 22;
    private static final int CARD_H = 34;

    private TagChecker checker;
    private String nickValue;
    private String tagValue;
    private EditBox nick;
    private EditBox tag;
    private RetroButton saveNick;
    private RetroButton changeTag;
    private RetroButton discordButton;
    private RetroButton avatarButton;
    private final List<RetroButton> accountButtons = new ArrayList<>();
    private final List<EditBox> fields = new ArrayList<>();
    private boolean lostTagHandled;
    private boolean refreshed;
    private boolean busy;
    private Component status = Component.empty();
    private int statusColor = RetroUi.ERROR;
    private int nickY;
    private int tagY;
    private int avatarY;
    private int discordY;
    private int hiddenY;
    private RetroButton hiddenButton;
    private int nsfwY;
    private RetroButton nsfwButton;
    private int accountY;
    private int statusY;

    public ProfileScreen(FeedScreen home) {
        super(Component.translatable("feed.account.profile.title"), home);
    }

    @Override
    protected String navId() {
        return "profile";
    }

    private AccountApi.Account acc() {
        return AccountManager.INSTANCE.account();
    }

    private boolean onCooldown() {
        AccountApi.Account a = acc();
        return a != null && a.canChangeTagAt() != null && a.canChangeTagAt() > System.currentTimeMillis();
    }

    private void setStatus(Component text, int color) {
        status = text == null ? Component.empty() : text;
        statusColor = color;
    }

    private boolean isEnter(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
    }

    private int sideButtonW() {
        int w = 56;
        for (String k : new String[]{"feed.account.profile.save", "feed.account.profile.change", "feed.account.discord.link",
                "feed.account.discord.unlink", "feed.account.avatar.change"}) {
            w = Math.max(w, this.font.width(tr(k)) + 12);
        }
        return w;
    }

    private RetroButton button(int x, int y, int w, String key, net.minecraft.client.gui.components.Button.OnPress press) {
        Component label = Component.translatable(key);
        return addRenderableWidget(new RetroButton(x, y, w <= 0 ? this.font.width(label) + 12 : w, BTN_H, label, press));
    }

    @Override
    protected void init() {
        AccountApi.Account a = acc();
        if (a == null) {
            this.minecraft.tell(() -> {
                if (this.minecraft.screen == this) {
                    this.minecraft.setScreen(new WelcomeScreen(home()));
                }
            });
            return;
        }
        if (nickValue == null) {
            nickValue = a.nick();
            tagValue = a.tag();
        }
        fields.clear();
        accountButtons.clear();
        checker = new TagChecker(a.tag());
        int x = contentLeft();
        int w = contentWidth();
        int bw = sideButtonW();
        int fx = x + LABEL_W;
        int fw = w - LABEL_W - bw - GAP;
        int bx = x + w - bw;

        Component back = Component.translatable("feed.account.profile.back");
        int backW = this.font.width(back) + 12;
        addRenderableWidget(new RetroButton(x + w - backW, 5, backW, 13, back, b -> openPlayer(ownId())));

        int y = CARD_Y + CARD_H + 10;
        nickY = y;
        nick = AccountUi.field(this.font, fx, y, fw, TagRules.NICK_MAX, Component.translatable("feed.account.nick.hint"));
        fields.add(addRenderableWidget(nick));
        nick.setValue(nickValue);
        nick.setResponder(v -> nickValue = v);
        saveNick = button(bx, y, bw, "feed.account.profile.save", b -> saveNick());
        y += 30;

        tagY = y;
        tag = AccountUi.tagField(this.font, fx, y, fw, Component.translatable("feed.account.tag.hint"));
        fields.add(addRenderableWidget(tag));
        tag.setResponder(v -> {
            tagValue = v;
            checker.onChanged(v);
        });
        tag.setValue(tagValue);
        checker.onChanged(tagValue);
        tag.setEditable(!onCooldown() || a.claimableTag() != null);
        changeTag = button(bx, y, bw, "feed.account.profile.change", b -> confirmTag());
        y += 30;

        avatarY = y;
        avatarButton = button(bx, y, bw, "feed.account.avatar.change", b -> cycleAvatar());
        y += 26;

        discordY = y;
        discordButton = button(bx, y, bw, a.discordLinked() ? "feed.account.discord.unlink" : "feed.account.discord.link",
                b -> discordAction());
        y += 26;

        hiddenY = y;
        hiddenButton = button(bx, y, bw, "feed.hide.list", b -> this.minecraft.setScreen(new HiddenPlayersScreen(home()).from(this)));
        y += 26;

        nsfwY = y;
        nsfwButton = button(bx, y, bw, a.showNsfw() ? "feed.nsfw.turn_off" : "feed.nsfw.turn_on", b -> toggleNsfw());
        y += 26;

        accountY = y;
        int ax = fx;
        RetroButton code = button(ax, y + 12, 0, "feed.account.profile.new_code", b -> confirmNewCode());
        ax += code.getWidth() + 4;
        RetroButton login = button(ax, y + 12, 0, "feed.account.profile.code_login",
                b -> this.minecraft.setScreen(new RecoverScreen(this, home())));
        ax += login.getWidth() + 4;
        RetroButton logout = button(ax, y + 12, 0, "feed.account.profile.logout", b -> confirmLogout());
        logout.color(RetroUi.ERROR);
        accountButtons.add(code);
        accountButtons.add(login);
        accountButtons.add(logout);
        y += 12 + BTN_H + 8;
        statusY = y;
        updateButtons();

        if (a.lostTag() != null && !lostTagHandled) {
            lostTagHandled = true;
            setStatus(Component.translatable("feed.account.lost_tag.notice", "@" + a.lostTag()), RetroUi.ACCENT);
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().ackLostTag(),
                    AccountManager.INSTANCE::updateAccount, err -> { });
        }

        if (!refreshed) {
            refreshed = true;
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().me(), fresh -> {
                AccountManager.INSTANCE.updateAccount(fresh);
                if (this.minecraft.screen == this) {
                    rebuildWidgets();
                }
            }, err -> {
                if (this.minecraft.screen == this && AccountManager.INSTANCE.account() == null) {
                    this.minecraft.setScreen(new WelcomeScreen(home()));
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
        changeTag.active = !busy && checker.state() != TagChecker.State.OWN && checker.submittable()
                && (!onCooldown() || isClaimValue());
        discordButton.active = !busy;
        avatarButton.active = !busy;
        hiddenButton.active = !busy && com.feed.client.HiddenPlayers.count() > 0;
        nsfwButton.active = !busy;
        for (RetroButton b : accountButtons) {
            b.active = !busy;
        }
    }

    private static String avatarChoice(AccountApi.Account a) {
        String c = a.avatarChoice();
        return "discord".equals(c) || "none".equals(c) ? c : "skin";
    }

    /** skin → Discord (if there is a picture) → none → skin */
    private void cycleAvatar() {
        AccountApi.Account a = acc();
        if (busy || a == null) {
            return;
        }
        String next = switch (avatarChoice(a)) {
            case "skin" -> a.hasDiscordAvatar() ? "discord" : "none";
            case "discord" -> "none";
            default -> "skin";
        };
        busy = true;
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().setAvatar(next), updated -> {
            busy = false;
            AccountManager.INSTANCE.updateAccount(updated);
            setStatus(Component.translatable("feed.account.avatar.saved"), AccountUi.GREEN);
            rebuildWidgets();
        }, this::fail);
    }

    /** On: only after "I am 18 or older"; off: right away. */
    private void toggleNsfw() {
        AccountApi.Account a = acc();
        if (busy || a == null) {
            return;
        }
        if (a.showNsfw()) {
            saveNsfw(false);
            return;
        }
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.nsfw.confirm_title"),
                Component.translatable("feed.nsfw.confirm"),
                Component.translatable("feed.nsfw.confirm_yes"), true, () -> {
            this.minecraft.setScreen(this);
            saveNsfw(true);
        }));
    }

    private void saveNsfw(boolean on) {
        busy = true;
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().setShowNsfw(on), updated -> {
            busy = false;
            AccountManager.INSTANCE.updateAccount(updated);
            setStatus(Component.translatable(on ? "feed.nsfw.saved_on" : "feed.nsfw.saved_off"), AccountUi.GREEN);
            rebuildWidgets();
        }, this::fail);
    }

    private boolean isClaimValue() {
        AccountApi.Account a = acc();
        return a != null && a.claimableTag() != null && a.claimableTag().equals(checker.value());
    }

    /** Shown when coming back from another screen (e.g. Discord link). */
    public void notice(Component text, int color) {
        setStatus(text, color);
    }

    private void discordAction() {
        AccountApi.Account a = acc();
        if (busy || a == null) {
            return;
        }
        if (!a.discordLinked()) {
            this.minecraft.setScreen(new DiscordScreen(this, this, false));
            return;
        }
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.account.discord.unlink_title"),
                Component.translatable("feed.account.discord.unlink_confirm"),
                Component.translatable("feed.account.discord.unlink"), true, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            AccountManager.thenOnMain(AccountManager.INSTANCE.api().discordUnlink(), updated -> {
                busy = false;
                AccountManager.INSTANCE.updateAccount(updated);
                setStatus(Component.translatable("feed.account.discord.unlinked"), AccountUi.GREEN);
                rebuildWidgets();
            }, this::fail);
        }));
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
        boolean claim = checker.isClaim() || isClaimValue();
        if (busy || a == null || !checker.submittable() || (onCooldown() && !claim)) {
            return;
        }
        String newTag = checker.value();
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.account.profile.tag_confirm_title"),
                claim ? Component.translatable("feed.account.discord.claim_confirm", "@" + newTag)
                        : Component.translatable("feed.account.profile.tag_confirm", "@" + a.tag(), "@" + newTag),
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
                this.minecraft.setScreen(new WelcomeScreen(home()));
            }));
        }));
    }

    private void fail(com.feed.client.account.ApiError err) {
        busy = false;
        if (AccountManager.INSTANCE.account() == null) {
            this.minecraft.setScreen(new WelcomeScreen(home()));
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
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        for (EditBox f : fields) {
            if (f.isVisible()) {
                AccountUi.drawField(g, f);
            }
        }
    }

    private void label(GuiGraphics g, String key, int y) {
        g.drawString(this.font, tr(key), contentLeft() + 4, y + 4, RetroUi.GRAY, false);
    }

    private void hint(GuiGraphics g, String text, int y, int color, int maxW) {
        g.drawString(this.font, FeedUtil.clip(this.font, text, maxW), contentLeft() + LABEL_W, y, color, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountApi.Account a = acc();
        if (a == null) {
            return;
        }
        int x = contentLeft();
        int w = contentWidth();
        int bw = sideButtonW();
        int textW = w - LABEL_W - bw - GAP;
        drawTitle(g, this.title.getString(), null, x, TITLE_Y, x + w - 70);

        RetroUi.drawBox(g, x, CARD_Y, w, CARD_H, false);
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(a.id(), null), a.nick(), x + 6, CARD_Y + 5, 24);
        g.renderOutline(x + 6, CARD_Y + 5, 24, 24, RetroUi.GRAY);
        g.drawString(this.font, FeedUtil.clip(this.font, a.nick(), w - 44), x + 36, CARD_Y + 7, RetroUi.FG, false);
        String handle = "@" + a.tag();
        g.drawString(this.font, handle, x + 36, CARD_Y + 18, RetroUi.ACCENT, false);
        String since = " \u00b7 " + Component.translatable("feed.account.profile.since", AccountUi.date(a.createdAt())).getString();
        g.drawString(this.font, FeedUtil.clip(this.font, since, w - 44 - this.font.width(handle)),
                x + 36 + this.font.width(handle), CARD_Y + 18, RetroUi.GRAY, false);

        label(g, "feed.account.nick.label", nickY);
        hint(g, tr("feed.account.nick.rules"), nickY + 18, RetroUi.GRAY, w - LABEL_W);

        label(g, "feed.account.tag.label", tagY);
        if (onCooldown() && a.claimableTag() != null && !isClaimValue()) {
            hint(g, tr("feed.account.tag.cooldown_claim", "@" + a.claimableTag()), tagY + 18, RetroUi.GRAY, w - LABEL_W);
        } else if (onCooldown() && a.claimableTag() == null) {
            hint(g, tr("feed.account.tag.cooldown", AccountUi.date(a.canChangeTagAt())), tagY + 18, RetroUi.GRAY, w - LABEL_W);
        } else if (checker.state() == TagChecker.State.OWN) {
            hint(g, tr("feed.account.tag.rules_short"), tagY + 18, RetroUi.GRAY, w - LABEL_W);
        } else {
            hint(g, checker.message().getString(), tagY + 18, checker.color(), w - LABEL_W);
        }

        label(g, "feed.account.avatar.label", avatarY);
        String choice = avatarChoice(a);
        hint(g, tr("feed.account.avatar.value." + choice), avatarY + 1, RetroUi.FG, textW);
        hint(g, tr(a.hasDiscordAvatar() ? "feed.account.avatar.hint" : "feed.account.avatar.hint_nodiscord"),
                avatarY + 11, RetroUi.GRAY, textW);

        label(g, "feed.account.discord.label", discordY);
        String dval = !a.discordLinked() ? tr("feed.account.discord.value_none")
                : a.discordUsername() != null ? tr("feed.account.discord.value_user", "@" + a.discordUsername())
                : tr("feed.account.discord.value_linked");
        hint(g, dval, discordY + 1, RetroUi.FG, textW);
        hint(g, a.tagProtected() ? tr("feed.account.discord.hint_protected") : tr("feed.account.discord.hint"),
                discordY + 11, a.tagProtected() ? AccountUi.GREEN : RetroUi.GRAY, textW);

        label(g, "feed.hide.label", hiddenY);
        int hc = com.feed.client.HiddenPlayers.count();
        hint(g, hc == 0 ? tr("feed.hide.none") : tr("feed.hide.count", hc), hiddenY + 1, RetroUi.FG, textW);
        hint(g, tr("feed.hide.hint"), hiddenY + 11, RetroUi.GRAY, textW);

        label(g, "feed.nsfw.label", nsfwY);
        hint(g, tr(a.showNsfw() ? "feed.nsfw.value_on" : "feed.nsfw.value_off"), nsfwY + 1, a.showNsfw() ? RetroUi.ERROR : RetroUi.FG, textW);
        hint(g, tr("feed.nsfw.hint"), nsfwY + 11, RetroUi.GRAY, textW);

        label(g, "feed.account.key.label", accountY);
        hint(g, tr("feed.account.key.value"), accountY + 1, RetroUi.GRAY, w - LABEL_W);

        if (busy) {
            hint(g, tr("feed.account.working") + (RetroUi.blink() ? "_" : ""), statusY, RetroUi.GRAY, w - LABEL_W);
        } else if (!status.getString().isEmpty()) {
            AccountUi.drawWrapped(g, this.font, status.getString(), x + LABEL_W, statusY, w - LABEL_W, statusColor);
        }
    }

    @Override
    public void onClose() {
        openPlayer(ownId());
    }
}
