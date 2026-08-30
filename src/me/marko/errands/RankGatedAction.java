package me.marko.errands;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.manager.tools.FunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps a command tool with the RankGuard: the tool keeps its exact name,
 * description and parameters (the model sees no difference), but execution is
 * refused with a polite roleplay message when the speaking player's colony
 * rank is below the configured minimum for the tool's group.
 */
public final class RankGatedAction extends FunctionAction {

    private final FunctionAction inner;
    private final String group;

    public RankGatedAction(FunctionAction inner, String group) {
        super(inner.getName(), inner.getDescription(), inner.getProperty(), inner.isPlayerOnly());
        this.inner = inner;
        this.group = group;
    }

    @Override
    public boolean isEnabled() {
        return inner.isEnabled();
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject denied = RankGuard.check(citizen, colony, group, getName());
        return denied != null ? denied : inner.execute(citizen, colony, parameters);
    }
}
