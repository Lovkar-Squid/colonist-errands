package me.marko.errands.mixin;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.core.items.ItemAssistantHammer;
import me.marko.errands.BuilderAssist;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marko's idea #31: when the player helps a builder with the assistant hammer,
 * the colony REACTS. placeBlock is the server-side entry (called from
 * PlayerAssistantBuildRequestMessage), so hooking its tail fires exactly when
 * a real build-assist attempt went through the claimed work order.
 */
@Mixin(value = ItemAssistantHammer.class, remap = false)
public abstract class ItemAssistantHammerMixin {

    @Inject(method = "placeBlock", at = @At("TAIL"))
    private void colonist_errands$onAssist(Player player, IColony colony, IWorkOrder workOrder,
                                           BlockPos interactPos, CallbackInfo ci) {
        BuilderAssist.onHammerUsed(player, colony, workOrder);
    }
}
