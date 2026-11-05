package com.feed.client;

import com.feed.Feed;
import com.feed.client.account.AccountManager;
import com.feed.client.screen.CommunityScreen;
import com.feed.client.screen.FeedScreen;
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
 * F8 / F7 on a feed page open a sample community as its owner / as a member (fixed data).
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
        if (!on() || !(event.getScreen() instanceof FeedScreen feed)) {
            return;
        }
        int k = event.getKeyCode();
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
}
