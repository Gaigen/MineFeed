package com.feed.client;

import com.feed.Feed;
import com.feed.client.account.AccountManager;
import com.feed.client.screen.CommunityScreen;
import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.ModerationScreen;
import com.feed.client.screen.ReportScreen;
import com.feed.client.screen.ShellScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Development only: with -Dminefeed.preview the feed opens straight from the title screen,
 * so the UI can be checked without loading a world (every time the title screen shows up).
 * F8 / F7 on a feed page open a sample community as its owner / as a member (fixed data),
 * F9 the moderation queue with sample reports, F6 the report dialog (nothing is sent),
 * F10 shows the last loaded post as pinned (local only).
 */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class DevPreview {

    private DevPreview() {
    }

    private static boolean on() {
        return System.getProperty("minefeed.preview") != null;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!on() || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        event.getScreen().getMinecraft().tell(() -> event.getScreen().getMinecraft().setScreen(new FeedScreen()));
    }

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!on() || !(event.getScreen() instanceof ShellScreen)) {
            return;
        }
        int k = event.getKeyCode();
        if (k == GLFW.GLFW_KEY_F9 || k == GLFW.GLFW_KEY_F6) {
            FeedScreen home = event.getScreen() instanceof FeedScreen f ? f : new FeedScreen();
            if (k == GLFW.GLFW_KEY_F9) {
                CommunityApi.previewMod = true;
                Minecraft.getInstance().setScreen(new ModerationScreen(home).preview(reports()));
            } else {
                ReportScreen r = new ReportScreen(home, "0", null).offline();
                r.from(event.getScreen());
                Minecraft.getInstance().setScreen(r);
            }
            event.setCanceled(true);
            return;
        }
        if (!(event.getScreen() instanceof FeedScreen feed)) {
            return;
        }
        if (k == GLFW.GLFW_KEY_F10) {
            feed.previewPin();
            event.setCanceled(true);
            return;
        }
        if (k == GLFW.GLFW_KEY_F8 || k == GLFW.GLFW_KEY_F7) {
            Minecraft.getInstance().setScreen(sample(feed, k == GLFW.GLFW_KEY_F8));
            event.setCanceled(true);
        }
    }

    private static CommunityScreen sample(FeedScreen feed, boolean owner) {
        var me = AccountManager.INSTANCE.account();
        String myId = me == null ? "me" : me.id();
        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        CommunityApi.Community c = new CommunityApi.Community("sample", "Проект Шахта", "Копаем глубже всех", 12,
                owner ? myId : "kuznec", owner ? "owner" : "member", 0, 1,
                "Ванильное выживание с друзьями: строим шахту до бедрока и общий склад.", "discord.gg/example",
                "https://discord.com/channels/1/2");
        List<CommunityApi.Code> codes = List.of(
                new CommunityApi.Code("MF-8Q2K-7XPL", "для Discord-канала", 3, 10, now + 7 * day, true, false),
                new CommunityApi.Code("KUZNEC", "лично Кузнецу", 0, 1, null, false, false),
                new CommunityApi.Code("MF-T7QW-4KDB", "весенний ивент", 25, 25, now - 3 * day, false, true));
        String notch = "skin:069a79f444e94726a5befca90e38aaf5";
        String jeb = "skin:853c80ef3c3749fdaa49938b674adae6";
        List<CommunityApi.Member> members = owner
                ? List.of(
                new CommunityApi.Member(myId, me == null ? "aboba" : me.tag(), me == null ? "Абоба" : me.nick(), "owner", null, null, now - 30 * day),
                new CommunityApi.Member("kuznec", "forge_master", "Кузнец", "mod", "лично Кузнецу", notch, now - 20 * day),
                new CommunityApi.Member("alex", "alex", "Алекс", "member", "для Discord-канала", jeb, now - 6 * day),
                new CommunityApi.Member("miner", "deep_miner", "Шахтёр", "member", "для Discord-канала", null, now - 2 * day))
                : List.of(
                new CommunityApi.Member("kuznec", "forge_master", "Кузнец", "owner", null, notch, now - 30 * day),
                new CommunityApi.Member(myId, me == null ? "aboba" : me.tag(), me == null ? "Абоба" : me.nick(), "member", null, null, now - day));
        List<CommunityApi.JoinRequest> requests = List.of(
                new CommunityApi.JoinRequest("kot", "kot_boss", "Котик", "для Discord-канала", now - 20 * 60_000L, null));
        return new CommunityScreen(feed, c).preview(codes, members, requests);
    }

    private static List<ModerationApi.Report> reports() {
        long now = System.currentTimeMillis();
        String notch = "skin:069a79f444e94726a5befca90e38aaf5";
        String jeb = "skin:853c80ef3c3749fdaa49938b674adae6";
        return List.of(
                new ModerationApi.Report("1", null, "global", null, 3, now - 12 * 60_000L,
                        List.of("spam", "scam"), List.of("alex", "deep_miner", "kot_boss"),
                        List.of("Кидает ссылку на сторонний сайт", "Уже третий раз за день"),
                        "Всем привет, кто хочет на ферму?",
                        "РАЗДАЮ АЛМАЗЫ БЕСПЛАТНО!!! Заходи на free-diamonds.example и вводи пароль от аккаунта",
                        false, new ModerationApi.Author("spam1", "diamond_giver", "АлмазныйКороль", null), "chat", true, false),
                new ModerationApi.Report("2", "21", "sample", "Проект Шахта", 1, now - 2 * 3_600_000L,
                        List.of("abuse"), List.of("forge_master"), List.of(),
                        "ты вообще играть не умеешь, удали игру", null,
                        false, new ModerationApi.Author("rude", "griefer228", "Грифер", jeb), null, true, true),
                new ModerationApi.Report("3", null, "global", null, 1, now - 26 * 3_600_000L,
                        List.of("nsfw"), List.of("alex"), List.of(),
                        "", null, true, new ModerationApi.Author("art", "pixel_art", "Художник", notch), "build", true, false));
    }
}
