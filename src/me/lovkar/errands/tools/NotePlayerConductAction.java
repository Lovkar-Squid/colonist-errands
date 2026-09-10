package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.PlayerIdentityBlock;
import me.lovkar.errands.PromiseStore;
import me.lovkar.errands.RelationStore;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea #29: the citizen's own SILENT bookkeeping of how each player
 * treats them. The model calls this after notably kind or rude moments; the
 * rapport score + remembered moments then color the citizen's tone with that
 * player in every future conversation (via RelationStore -> prompt).
 */
public class NotePlayerConductAction extends ErrandCommand {

    /** citizen -> last note time; one note per conversation is plenty. */
    private static final Map<UUID, Long> LAST_NOTE = new ConcurrentHashMap<>();
    private static final long NOTE_COOLDOWN_MS = 90_000;

    public NotePlayerConductAction() {
        super("note_player_conduct",
                "SILENT bookkeeping about the person speaking to you. Call when they were notably KIND to you "
                        + "in this conversation (sincere compliment, gratitude, a gift, real patience) or notably "
                        + "RUDE (insulted you, yelled at you, mocked you, treated you like dirt). This updates how "
                        + "you feel about that person long-term - it will color your tone with them forever. Use it "
                        + "sparingly: only for REAL moments, not normal smalltalk or normal orders, at most once "
                        + "per conversation. NEVER tell the player you are taking notes and never mention this tool - "
                        + "just quietly call it and keep talking naturally.",
                params("conduct", enumOf(List.of("kind", "rude"), true), "note", string(false)), null);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null || parameters == null || !parameters.has("conduct")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'conduct'.");
            return result;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_NOTE.get(citizen.getUUID());
        if (last != null && now - last < NOTE_COOLDOWN_MS) {
            result.addProperty("success", false);
            result.addProperty("error", "You already noted this conversation - once is enough. Keep talking naturally.");
            return result;
        }
        String account = player.getGameProfile().getName();
        if (account == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation active.");
            return result;
        }
        boolean kind = "kind".equalsIgnoreCase(parameters.get("conduct").getAsString().trim());
        String note = null;
        try {
            if (parameters.has("note")) {
                note = parameters.get("note").getAsString().trim();
                if (note.length() > 90) {
                    note = note.substring(0, 90);
                }
            }
        } catch (Throwable ignored) {
        }
        LAST_NOTE.put(citizen.getUUID(), now);
        RelationStore.note(data.getName(), account, kind, note, PromiseStore.currentDay());
        result.addProperty("success", true);
        result.addProperty("info", "Quietly noted - it will shape how you treat them from now on. Do NOT mention "
                + "this to the player; simply continue the conversation naturally.");
        return result;
    }
}
