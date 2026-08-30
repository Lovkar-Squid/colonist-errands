package me.lovkar.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards escorting the player (guard_me / all_towers) or holding a defensive
 * line surround the player constantly - mc_talking's urgent-contact system then
 * keeps picking them to walk up and "report things". While on military duty
 * their urgency is forced to 0: on duty they guard, complaints wait for the
 * dismissal.
 */
@Mixin(targets = "me.sshcrack.mc_talking.util.CitizenNeedAssessor", remap = false)
public abstract class CitizenNeedAssessorMixin {

    @Inject(method = "calculateUrgencyWeight", at = @At("RETURN"), cancellable = true)
    private static void colonist_errands$muteOnDuty(AbstractEntityCitizen citizen, CallbackInfoReturnable<Double> cir) {
        try {
            if (cir.getReturnValueD() <= 0.0) {
                return;
            }
            if (ErrandManager.isOnMilitaryDuty(citizen)) {
                cir.setReturnValue(0.0);
                return;
            }
            // Lovkar's idea #23: an open, not-overdue promise makes the citizen
            // PATIENT about the promised problem - no more walking up to nag
            // every few minutes. Overdue or resolved -> normal pestering resumes.
            if (citizen.getCitizenData() != null) {
                java.util.Set<String> topics = new java.util.HashSet<>(
                        me.lovkar.errands.PromiseStore.activeSuppressions(citizen.getCitizenData().getName()));
                // Lovkar's hospital report (Gunilda): a sick citizen he SENT to the
                // hospital left it a minute later to walk up and complain about
                // being sick. Under medical care = the health complaint is being
                // handled - mute it until they are cured (flag clears on cure).
                try {
                    var dh = citizen.getCitizenData().getCitizenDiseaseHandler();
                    if (dh != null && dh.sleepsAtHospital() && (dh.isSick() || dh.isHurt())) {
                        topics.add("health");
                    }
                } catch (Throwable ignored) {
                }
                if (!topics.isEmpty()) {
                    double reduced = me.lovkar.errands.PromiseStore.suppressedUrgency(citizen, topics);
                    if (reduced < cir.getReturnValueD()) {
                        cir.setReturnValue(reduced);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
