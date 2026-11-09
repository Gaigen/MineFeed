package com.feed;
import com.feed.client.FeedConfig;
import com.feed.client.ModKeyMappings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Mod(value = Feed.MOD_ID, dist = Dist.CLIENT)
public final class Feed {
 public static final String MOD_ID = "feed";
 public static final Logger LOG = LoggerFactory.getLogger("Feed");
 public static String clientVersion() { return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("0.0.0"); }
 public Feed(IEventBus modBus) { FeedConfig.load(); modBus.addListener(Feed::registerKeys); }
 private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(ModKeyMappings.OPEN_FEED); }
}
