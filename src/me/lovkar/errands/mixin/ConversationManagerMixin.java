package me.lovkar.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.SlotGuard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Slots are not taken from people who are still talking - see {@link SlotGuard}.
 */
@Mixin(targets = "me.sshcrack.mc_talking.ConversationManager", remap = false)
public abstract class ConversationManagerMixin {

    @Shadow(remap = false)
    @Final
    private static Set<UUID> addedEntities;

    /** Mumbling is small talk. */
    @Inject(method = "startMumbling", at = @At("HEAD"), remap = false, require = 0)
    private static void colonist_errands$mumbleBegins(AbstractEntityCitizen citizen, CallbackInfo ci) {
        SlotGuard.enter();
    }

    @Inject(method = "startMumbling", at = @At("RETURN"), remap = false, require = 0)
    private static void colonist_errands$mumbleEnds(AbstractEntityCitizen citizen, CallbackInfo ci) {
        SlotGuard.exit();
    }

    /**
     * Small talk asking for a slot when all are taken: if nobody has gone quiet,
     * the answer is no - never an eviction of somebody mid-sentence.
     */
    @Inject(method = "claimSlot", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void colonist_errands$noEvictionForSmallTalk(AbstractEntityCitizen citizen, boolean isPlayerConversation,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        try {
            if (isPlayerConversation || !SlotGuard.smallTalk()) {
                return;
            }
            if (addedEntities.contains(citizen.getUUID())) {
                return; // already holds one
            }
            int max = SlotGuard.maxAgents();
            if (addedEntities.size() < max) {
                return; // a free slot - no eviction involved
            }
            if (SlotGuard.quietVictim(addedEntities) == null) {
                ColonistErrands.LOGGER.info("[Voice] No free slot for small talk and everyone is still talking - "
                        + "{} waits instead of cutting somebody off", citizen.getCitizenData().getName());
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** When something IS allowed to evict, take a quiet slot before a busy one. */
    @Inject(method = "findEvictableNonPlayerSlot", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void colonist_errands$preferQuietVictim(CallbackInfoReturnable<UUID> cir) {
        try {
            UUID chosen = cir.getReturnValue();
            if (chosen == null || !SlotGuard.isTalking(chosen)) {
                return;
            }
            UUID quiet = SlotGuard.quietVictim(addedEntities);
            if (quiet != null) {
                cir.setReturnValue(quiet);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Honest capacity: a slot whose holder is still talking is not "available". */
    @Inject(method = "hasLowPriorityCapacity", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void colonist_errands$honestCapacity(int slotsNeeded, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue()) {
                return;
            }
            int max = SlotGuard.maxAgents();
            int free = max - addedEntities.size();
            if (free + SlotGuard.countQuietNonPlayer(addedEntities) < slotsNeeded) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
