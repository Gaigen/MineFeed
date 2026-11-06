package com.feed.client;

import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.RetroButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * MineFeed button left of survival/creative inventory. Offsets in {@link FeedConfig}.
 */
public final class InventoryFeedButton {

    private static final int BTN_W = 24;
    private static final int BTN_H = 20;
    private static final int GAP = 4;

    private InventoryFeedButton() {
    }

    public static void onScreenInit(net.minecraft.client.gui.screens.Screen current, java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> addListener) {
        if (!FeedConfig.showInventoryButton()) {
            return;
        }
        if (!(current instanceof InventoryScreen)
                && !(current instanceof CreativeModeInventoryScreen)) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) current;
        int x = ((com.feed.mixin.ContainerScreenAccessor) screen).feed$getLeftPos() - BTN_W - GAP + FeedConfig.inventoryButtonOffsetX();
        int y = ((com.feed.mixin.ContainerScreenAccessor) screen).feed$getTopPos() + FeedConfig.inventoryButtonOffsetY();
        addListener.accept(new RetroButton(x, y, BTN_W, BTN_H,
                Component.translatable("feed.screen.inventory.open"),
                b -> net.minecraft.client.Minecraft.getInstance().setScreen(new FeedScreen())));
    }
}
