package com.feed;

import com.feed.client.FeedConfig;
import com.feed.client.ModKeyMappings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Feed.MOD_ID)
public class Feed {

    public static final String MOD_ID = "feed";

    public static final Logger LOG = LoggerFactory.getLogger("Feed");

    public Feed(IEventBus modEventBus, ModContainer modContainer) {
        LOG.info("MineFeed mod constructing");

        FeedConfig.register(modContainer);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerExtensionPoint(
                    IConfigScreenFactory.class,
                    ConfigurationScreen::new);
            modEventBus.addListener(ModKeyMappings::registerKeys);
        }
    }
}
