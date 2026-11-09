package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.client.ModerationApi;
import com.feed.client.PostOpener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moderation queue: open reports about posts and comments, grouped, with Open / Delete / Ban / Kick / Dismiss.
 * Server moderators see everything; community owners and mods see their communities.
 */
public class ModerationScreen extends ShellScreen {

    private static final int LIST_Y = 24;
    private static final int GAP = 6;
    private static final int BTN_H = 13;
    private static final int LINE = 10;
    private static final int MAX_TEXT_LINES = 3;

    private List<ModerationApi.Report> items = new ArrayList<>();
    /** Handled cards: key → result line (shown instead of the buttons until the next refresh). */
    private final Map<String, String> done = new HashMap<>();
    private final Map<String, Integer> doneColor = new HashMap<>();
    private boolean loading;
    private boolean offline;
    private Component error;
    private int scroll;
    private int contentHeight;
    private RetroButton refresh;

    private record Btn(int x, int y, int w, Runnable action) {}

    private final List<Btn> buttons = new ArrayList<>();

    public ModerationScreen(FeedScreen home) {
        super(Component.translatable("feed.mod.title"), home);
    }

    /** Dev preview: fixed reports, actions only change the cards locally. */
    public ModerationScreen preview(List<ModerationApi.Report> reports) {
        offline = true;
        items = new ArrayList<>(reports);
        return this;
    }

    @Override
    protected String navId() {
        return "mod";
    }

    @Override
    protected void init() {
        Component label = Component.translatable("feed.mod.refresh");
        int bw = this.font.width(label) + 12;
        refresh = addRenderableWidget(new RetroButton(contentLeft() + contentWidth() - bw, 5, bw, BTN_H, label, b -> fetch()));
        if (items.isEmpty() && !loading && !offline) {
            fetch();
        }
    }

    private void fetch() {
        if (offline) {
            done.clear();
            return;
        }
        loading = true;
        ModerationApi.reports().whenComplete((list, ex) -> this.minecraft.execute(() -> {
            loading = false;
            if (ex != null) {
                error = ModerationApi.describe(ex);
                return;
            }
            error = null;
            items = new ArrayList<>(list);
            done.clear();
            scroll = 0;
            home().loadCommunities();
        }));
    }

    private int openCount() {
        int n = 0;
        for (ModerationApi.Report r : items) {
            if (!done.containsKey(r.key())) {
                n++;
            }
        }
        return n;
    }

    // ---------- text ----------

    static String reasonLabel(String reason) {
        return Component.translatable("feed.report.reason." + reason).getString();
    }

    private String countLabel(int n) {
        int m10 = n % 10;
        int m100 = n % 100;
        String form = m10 == 1 && m100 != 11 ? "one" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "few" : "many";
        return tr("feed.mod.count." + form, n);
    }

    private String reasons(ModerationApi.Report r) {
        List<String> out = new ArrayList<>();
        for (String s : r.reasons()) {
            out.add(reasonLabel(s));
        }
        return String.join(", ", out);
    }

    private List<String> textLines(ModerationApi.Report r, int w) {
        String t = r.text() == null ? "" : RetroUi.flatten(r.text());
        List<String> lines = t.isEmpty() ? new ArrayList<>() : new ArrayList<>(RetroUi.wrap(this.font, t, w));
        if (lines.size() > MAX_TEXT_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_TEXT_LINES));
            lines.set(MAX_TEXT_LINES - 1, FeedUtil.clip(this.font, lines.get(MAX_TEXT_LINES - 1) + "\u2026", w));
        }
        if (r.original() != null && !r.original().equals(r.text())) {
            List<String> orig = RetroUi.wrap(this.font, tr("feed.mod.original") + " " + RetroUi.flatten(r.original()), w);
            for (int i = 0; i < Math.min(3, orig.size()); i++) {
                String l = orig.get(i);
                lines.add("\u0002" + (i == 2 && orig.size() > 3 ? FeedUtil.clip(this.font, l + "\u2026", w) : l));
            }
        }
        if (r.image()) {
            lines.add("\u0001" + tr("feed.mod.image"));
        }
        if (lines.isEmpty()) {
            lines.add("\u0001" + tr("feed.mod.no_text"));
        }
        return lines;
    }

    private String fromLine(ModerationApi.Report r) {
        List<String> tags = new ArrayList<>();
        for (String t : r.reporters()) {
            tags.add("@" + t);
        }
        String s = tr("feed.mod.from", String.join(", ", tags));
        if (r.count() > r.reporters().size()) {
            s += " " + tr("feed.mod.more", r.count() - r.reporters().size());
        }
        return s;
    }

    private int heightOf(ModerationApi.Report r, int w) {
        return 32 + textLines(r, w - 34).size() * LINE + (1 + Math.min(2, r.details().size())) * LINE + 6 + BTN_H + 6;
    }

    // ---------- rendering ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        buttons.clear();
        super.render(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        int w = contentWidth();
        int open = openCount();
        drawTitle(g, this.title.getString(), open > 0 ? countLabel(open) : null, x, TITLE_Y, refresh.getX() - 6);

        int top = LIST_Y;
        int bottom = this.height - 4;
        boolean inList = mouseY >= top && mouseY < bottom;
        g.enableScissor(sidebarW(), top, this.width, bottom);
        int y = top - scroll;
        for (ModerationApi.Report r : items) {
            int h = heightOf(r, w);
            if (y + h + RetroUi.SHADOW_OFFSET >= top && y <= bottom) {
                drawItem(g, r, x, y, w, h, inList ? mouseX : -1, inList ? mouseY : -1, top, bottom);
            }
            y += h + GAP;
        }
        contentHeight = y + scroll - top;
        if (items.isEmpty()) {
            String msg = loading ? tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : "")
                    : error != null ? error.getString() : tr("feed.mod.empty");
            g.drawString(this.font, msg, x + 6, top + 6, error != null ? RetroUi.ERROR : RetroUi.GRAY, false);
            if (!loading && error == null) {
                g.drawString(this.font, tr(CommunityApi.serverMod() ? "feed.mod.scope.all" : "feed.mod.scope.communities"),
                        x + 6, top + 18, RetroUi.GRAY, false);
            }
        }
        g.disableScissor();
        drawScrollbar(g, top, bottom, scroll, contentHeight);
        if (error != null && !items.isEmpty()) {
            String e = FeedUtil.clip(this.font, error.getString(), w - 12);
            int ew = this.font.width(e) + 10;
            int ey = bottom - 14;
            g.fill(x + w - ew, ey, x + w, ey + 12, RetroUi.BG);
            g.renderOutline(x + w - ew, ey, ew, 12, RetroUi.ERROR);
            g.drawString(this.font, e, x + w - ew + 5, ey + 2, RetroUi.ERROR, false);
        }
    }

    private void drawItem(GuiGraphics g, ModerationApi.Report r, int x, int y, int w, int h, int mouseX, int mouseY,
                          int top, int bottom) {
        String result = done.get(r.key());
        RetroUi.drawBox(g, x, y, w, h, false);
        if (result == null) {
            g.fill(x, y, x + 2, y + h, RetroUi.ERROR);
        }
        // line 1: "3 жалобы · Спам, Оскорбления"            space · time
        int ly = y + 5;
        String count = countLabel(r.count());
        int cw = RetroUi.chipWidth(this.font, count);
        RetroUi.drawChip(g, this.font, count, x + 6, ly - 2, result == null ? RetroUi.ERROR : RetroUi.GRAY, result == null);
        String time = FeedTime.ago(r.lastAt());
        int timeX = x + w - this.font.width(time) - 6;
        g.drawString(this.font, time, timeX, ly, RetroUi.GRAY, false);
        boolean global = r.space() == null || "global".equals(r.space());
        String space = global ? tr("feed.space.global") : r.spaceName() == null ? "?" : r.spaceName();
        int spaceW = Math.min(this.font.width(space), w / 3);
        int sx = timeX - 8 - spaceW;
        PixelIcon.draw(g, global ? "globe" : "lock", sx - 10, ly, RetroUi.GRAY);
        g.drawString(this.font, FeedUtil.clip(this.font, space, spaceW), sx, ly, RetroUi.FG, false);
        g.drawString(this.font, FeedUtil.clip(this.font, reasons(r), Math.max(10, sx - 16 - (x + 10 + cw))), x + 10 + cw, ly,
                RetroUi.ACCENT, false);

        // line 2: avatar, nick @tag · комментарий
        int ay = y + 17;
        ModerationApi.Author a = r.author();
        String nick = a.nick() == null ? "?" : a.nick();
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(a.id(), a.avatar()), nick, x + 6, ay, 16);
        int nx = x + 27;
        boolean nameHover = a.id() != null && mouseY >= ay && mouseY < ay + 9 && mouseX >= nx
                && mouseX < nx + this.font.width(nick);
        String shown = FeedUtil.clip(this.font, nick, w / 3);
        g.drawString(this.font, shown, nx, ay, nameHover ? RetroUi.STRONG : RetroUi.FG, false);
        if (nameHover) {
            g.fill(nx, ay + 9, nx + this.font.width(shown), ay + 10, RetroUi.STRONG);
        }
        String meta = (a.tag() != null ? " @" + a.tag() : "") + " \u00b7 "
                + tr(r.commentId() != null ? "feed.mod.kind.comment" : "feed.mod.kind.post");
        g.drawString(this.font, FeedUtil.clip(this.font, meta, Math.max(10, x + w - 6 - nx - this.font.width(shown))),
                nx + this.font.width(shown), ay, RetroUi.GRAY, false);

        // reported text
        int ty = ay + 11;
        for (String line : textLines(r, w - 34)) {
            boolean marker = line.startsWith("\u0001");
            boolean orig = line.startsWith("\u0002");
            g.drawString(this.font, marker || orig ? line.substring(1) : line, nx, ty,
                    orig ? RetroUi.ACCENT : marker ? RetroUi.GRAY : RetroUi.FG, false);
            ty += LINE;
        }
        ty += 4;
        // who reported + their words
        g.drawString(this.font, FeedUtil.clip(this.font, fromLine(r), w - 34), nx, ty, RetroUi.GRAY, false);
        ty += LINE;
        for (int i = 0; i < Math.min(2, r.details().size()); i++) {
            String d = "\u00ab" + RetroUi.flatten(r.details().get(i)) + "\u00bb";
            g.drawString(this.font, FeedUtil.clip(this.font, d, w - 34), nx, ty, RetroUi.GRAY, false);
            ty += LINE;
        }

        // actions
        int by = y + h - BTN_H - 6;
        if (result != null) {
            g.drawString(this.font, result, nx, by + 3, doneColor.getOrDefault(r.key(), AccountUi.GREEN), false);
            return;
        }
        int bx = nx;
        boolean clickable = by >= top && by + BTN_H <= bottom;
        bx += drawBtn(g, tr("feed.mod.open"), bx, by, RetroUi.FG, mouseX, mouseY, clickable, () -> open(r)) + 4;
        bx += drawBtn(g, tr("feed.mod.delete"), bx, by, RetroUi.ERROR, mouseX, mouseY, clickable, () -> confirmDelete(r)) + 4;
        if (r.canBan()) {
            bx += drawBtn(g, tr("feed.mod.ban"), bx, by, RetroUi.ERROR, mouseX, mouseY, clickable, () -> ban(r)) + 4;
        }
        if (r.canKick()) {
            drawBtn(g, tr("feed.mod.kick"), bx, by, RetroUi.ERROR, mouseX, mouseY, clickable, () -> confirmKick(r));
        }
        String dismiss = tr("feed.mod.dismiss");
        int dw = RetroUi.buttonWidth(this.font, dismiss);
        drawBtn(g, dismiss, x + w - 6 - dw, by, RetroUi.GRAY, mouseX, mouseY, clickable, () -> act(r, "dismiss"));
    }

    private int drawBtn(GuiGraphics g, String label, int x, int y, int color, int mouseX, int mouseY, boolean clickable,
                        Runnable action) {
        int w = RetroUi.buttonWidth(this.font, label);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H;
        RetroUi.drawButton(g, this.font, label, x, y, w, BTN_H, hover, true, color);
        if (clickable) {
            buttons.add(new Btn(x, y, w, action));
        }
        return w;
    }

    // ---------- actions ----------

    private void open(ModerationApi.Report r) {
        if (offline) {
            return;
        }
        PostOpener.open(this.minecraft, home(), r.postId(), this);
    }

    private void confirmDelete(ModerationApi.Report r) {
        String what = tr(r.commentId() != null ? "feed.mod.delete_comment" : "feed.mod.delete_post");
        this.minecraft.setScreen(new RetroConfirmScreen(this, Component.literal(what),
                Component.translatable("feed.mod.delete_confirm"), Component.translatable("feed.mod.delete"), true, () -> {
            this.minecraft.setScreen(this);
            act(r, "delete");
        }));
    }

    private void confirmKick(ModerationApi.Report r) {
        String nick = r.author().nick() == null ? "?" : r.author().nick();
        this.minecraft.setScreen(new RetroConfirmScreen(this, Component.translatable("feed.mod.kick"),
                Component.translatable("feed.mod.kick_confirm", nick, r.spaceName() == null ? "?" : r.spaceName()),
                Component.translatable("feed.mod.kick"), true, () -> {
            this.minecraft.setScreen(this);
            act(r, "kick");
        }));
    }

    private void ban(ModerationApi.Report r) {
        BanScreen s = new BanScreen(home(), r.author().id(), r.author().nick(), r.postId(), r.commentId(), until -> {
            markDone(r, tr("feed.mod.done.banned"), RetroUi.ERROR);
            // a ban closes every open report about this player
            for (ModerationApi.Report o : items) {
                if (o != r && r.author().id() != null && r.author().id().equals(o.author().id()) && !done.containsKey(o.key())) {
                    markDone(o, tr("feed.mod.done.banned"), RetroUi.ERROR);
                }
            }
        });
        if (offline) {
            s.offline();
        }
        s.from(this);
        this.minecraft.setScreen(s);
    }

    private void act(ModerationApi.Report r, String action) {
        error = null;
        String ok = tr("feed.mod.done." + action);
        int color = "dismiss".equals(action) ? RetroUi.GRAY : AccountUi.GREEN;
        if (offline) {
            markDone(r, ok, color);
            return;
        }
        done.put(r.key(), "...");
        doneColor.put(r.key(), RetroUi.GRAY);
        ModerationApi.resolve(r.postId(), r.commentId(), action).whenComplete((v, ex) -> this.minecraft.execute(() -> {
            if (ex != null) {
                done.remove(r.key());
                error = ModerationApi.describe(ex);
                return;
            }
            markDone(r, ok, color);
            if (!"dismiss".equals(action)) {
                home().refresh();
            }
        }));
    }

    private void markDone(ModerationApi.Report r, String text, int color) {
        if (!done.containsKey(r.key()) || "...".equals(done.get(r.key()))) {
            CommunityApi.reportsHandled(1);
        }
        done.put(r.key(), text);
        doneColor.put(r.key(), color);
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (Btn b : new ArrayList<>(buttons)) {
            if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + BTN_H) {
                b.action().run();
                return true;
            }
        }
        int x = contentLeft();
        int w = contentWidth();
        if (mouseY < LIST_Y || mouseY >= this.height - 4 || mouseX < x || mouseX >= x + w) {
            return false;
        }
        int y = LIST_Y - scroll;
        for (ModerationApi.Report r : items) {
            int h = heightOf(r, w);
            if (mouseY >= y && mouseY < y + h) {
                ModerationApi.Author a = r.author();
                int ay = y + 17;
                String nick = a.nick() == null ? "?" : a.nick();
                if (a.id() != null && !offline && mouseY >= ay && mouseY < ay + 9 && mouseX >= x + 27
                        && mouseX < x + 27 + this.font.width(nick)) {
                    openPlayer(a.id());
                }
                return true;
            }
            y += h + GAP;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        int viewport = this.height - 4 - LIST_Y;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport), scroll - (int) (dy * SCROLL_STEP)));
        return true;
    }
}
