package me.lovkar.errands.mixin;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import me.lovkar.errands.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lovkar's request: allow more than one tavern per colony. MineColonies
 * hard-codes the one-tavern rule right here in BlockHutTavern.canPlaceAt -
 * with max_taverns > 1 in colonist_errands_settings.properties this mixin
 * replaces it with a COUNT check (mirroring the vanilla loop, including its
 * client-side skip). At max_taverns=1 (the default) vanilla logic runs
 * untouched.
 */
@Mixin(targets = "com.minecolonies.core.blocks.huts.BlockHutTavern", remap = false)
public abstract class BlockHutTavernMixin {

    @Inject(method = "canPlaceAt", at = @At("HEAD"), cancellable = true)
    private void colonist_errands$multiTavern(BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
        try {
            int max = Settings.maxTaverns();
            if (max <= 1) {
                return; // vanilla rule stays
            }
            IColony colony = IColonyManager.getInstance().getIColony(player.level(), pos);
            if (colony == null) {
                cir.setReturnValue(true);
                return;
            }
            int taverns = 0;
            for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                if (colony.getWorld() == null || colony.getWorld().isClientSide
                        || !building.hasModule(BuildingModules.TAVERN_VISITOR)) {
                    continue;
                }
                taverns++;
            }
            if (taverns < max) {
                cir.setReturnValue(true);
            } else {
                player.displayClientMessage(Component.literal("This colony already has " + taverns
                        + " tavern(s) - the limit is " + max
                        + " (max_taverns in colonist_errands_settings.properties)."), false);
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // fall through to vanilla behavior
        }
    }
}
