package com.feed;
import com.feed.client.FeedConfig;
import com.feed.client.ModKeyMappings;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Mod(Feed.MOD_ID) public class Feed {
 public static final String MOD_ID="feed";
 public static final Logger LOG=LoggerFactory.getLogger("Feed");
 public static String clientVersion() { return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("0.0.0"); }
 public Feed() { FeedConfig.register(); FMLJavaModLoadingContext.get().getModEventBus().addListener(ModKeyMappings::registerKeys); }
}
