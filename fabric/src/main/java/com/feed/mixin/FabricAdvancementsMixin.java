package com.feed.mixin;

import com.feed.client.AdvancementHook;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mixin(ClientAdvancements.class)
public abstract class FabricAdvancementsMixin {
    @Shadow @Final private Map<AdvancementHolder, AdvancementProgress> progress;
    @Unique private final Set<ResourceLocation> feed$previouslyDone = new HashSet<>();
    @Unique private boolean feed$receivedInitialPacket;

    @Inject(method = "update", at = @At("HEAD"))
    private void feed$beforeUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        feed$previouslyDone.clear();
        progress.forEach((holder, state) -> { if (state.isDone()) feed$previouslyDone.add(holder.id()); });
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void feed$afterUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        if (feed$receivedInitialPacket && !packet.shouldReset()) {
            progress.forEach((holder, state) -> {
                if (packet.getProgress().containsKey(holder.id()) && state.isDone() && !feed$previouslyDone.contains(holder.id())) {
                    AdvancementHook.onAdvancement(holder);
                }
            });
        }
        feed$receivedInitialPacket = true;
    }
}
