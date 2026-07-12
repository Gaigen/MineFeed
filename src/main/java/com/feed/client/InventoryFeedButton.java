package com.feed.client;

import com.feed.Feed;
import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.RetroButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * MineFeed button left of survival/creative inventory. Offsets in {@link FeedConfig}.
 */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class InventoryFeedButton {

    private static final int BTN_W = 24;
    private static final int BTN_H = 20;
    private static final int GAP = 4;

    private InventoryFeedButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!FeedConfig.showInventoryButton()) {
            return;
        }
        if (!(event.getScreen() instanceof InventoryScreen)
                && !(event.getScreen() instanceof CreativeModeInventoryScreen)) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
        int x = screen.getGuiLeft() - BTN_W - GAP + FeedConfig.inventoryButtonOffsetX();
        int y = screen.getGuiTop() + FeedConfig.inventoryButtonOffsetY();
        event.addListener(new RetroButton(x, y, BTN_W, BTN_H,
                Component.translatable("feed.screen.inventory.open"),
                b -> screen.getMinecraft().setScreen(new FeedScreen())));
    }
}
