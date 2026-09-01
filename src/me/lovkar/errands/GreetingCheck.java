package me.lovkar.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.pregen.PregenerationTaskService;
import net.minecraft.world.entity.player.Player;

/**
 * Lovkar's report: a colonist with a greeting ready for him RUNS UP to him
 * before they manage to say it - the urgent-contact system picks the same
 * citizen the greeting was queued for, and the walk starts on top of (or
 * instead of) the hello.
 * <p>
 * If someone is about to greet the player standing right there, that IS the
 * contact - so their urgency is held at zero until the greeting has played.
 */
public final class GreetingCheck {

    private GreetingCheck() {
    }

    private static final double RANGE = 24.0;

    /** Does this citizen have a greeting queued for a player standing nearby? */
    public static boolean aboutToGreet(AbstractEntityCitizen citizen) {
        try {
            Player near = citizen.level().getNearestPlayer(citizen, RANGE);
            if (near == null) {
                return false;
            }
            return PregenerationTaskService.hasPlayerGreeting(citizen.getUUID(), near.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }
}
