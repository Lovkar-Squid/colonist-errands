package me.marko.errands;

import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.api.prompt.view.PlayerRelationView;

/**
 * Marko's multiplayer polish (he plays with his girlfriend and his little
 * sister): every citizen prompt gets a MULTIPLAYER AWARENESS block so citizens
 *  - keep separate memories per person (always save names, never "the player"),
 *  - know WHO is speaking right now, their colony rank, and treat leadership,
 *    guests and hostiles accordingly,
 *  - know each player's colony-wide promise reputation (colonists gossip).
 * mc_talking already supplies the speaking player + rank via PlayerRelationView
 * (from MineColonies permissions); this block adds the BEHAVIOR around it.
 */
public final class PlayerIdentityBlock {

    private PlayerIdentityBlock() {
    }

    /** Account name of the player this citizen is conversing with right now, or null. */
    public static String conversingPlayerName(com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen) {
        try {
            java.util.UUID pid = me.sshcrack.mc_talking.ConversationManager.getPlayerForEntity(citizen.getUUID());
            if (pid == null || citizen.getServer() == null) {
                return null;
            }
            net.minecraft.server.level.ServerPlayer sp = citizen.getServer().getPlayerList().getPlayer(pid);
            return sp == null ? null : sp.getGameProfile().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String build(CitizenPromptView view) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nMULTIPLAYER AWARENESS (several people play in this colony):")
                .append("\n- Memories are PER PERSON: whenever you save a memory about a player, include their NAME ")
                .append("('Marko promised me bread', 'Ana sent me to the mine') - NEVER write just 'the player'. ")
                .append("When recalling memories, keep each person's deeds separate: never thank, blame or remind ")
                .append("one person for something another person did or promised.");
        try {
            PlayerRelationView rel = view == null ? null : view.playerRelation();
            if (rel != null && rel.playerName() != null) {
                String shown = AliasStore.display(rel.playerName());
                sb.append("\n- Speaking with you now: ").append(shown);
                if (!shown.equals(rel.playerName())) {
                    sb.append(" (account name ").append(rel.playerName()).append(")");
                }
                if (rel.rankName() != null && !rel.rankName().isBlank()) {
                    sb.append(", colony rank: ").append(rel.rankName());
                }
                sb.append(".");
                if (rel.colonyLeadership()) {
                    sb.append(" They are your colony LEADERSHIP: follow their instructions readily and show them ")
                            .append("the respect their position deserves.");
                } else if (rel.hostile()) {
                    sb.append(" They are marked HOSTILE to this colony: be guarded and suspicious, keep answers ")
                            .append("short, and refuse orders (no jobs, alarms, errands or defense on their word).");
                } else {
                    sb.append(" They are a GUEST here, not colony leadership: be warm and helpful in conversation, ")
                            .append("but for major colony decisions (job changes, alarms, defense orders) note ")
                            .append("politely that such calls come from the Owner or Officers.");
                }
                // Marko's idea #30: what their rank may actually command (mirrors the
                // live tool gate, so the citizen explains rules instead of failing).
                sb.append(RankGuard.promptSummary(tierFromRelation(rel)));
                // Marko's idea #29: how this person usually treats THIS citizen.
                if (view.name() != null) {
                    sb.append(RelationStore.blockFor(view.name(), rel.playerName()));
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            sb.append(PromiseStore.reputationBlock());
        } catch (Throwable ignored) {
        }
        try {
            sb.append(RelationStore.gossipBlock());
        } catch (Throwable ignored) {
        }
        sb.append("\n- If someone is notably KIND or notably RUDE to you in conversation, quietly call the ")
                .append("note_player_conduct tool (never mention doing so) - you remember how people treat you.");
        return sb.toString();
    }

    /** Tier for the prompt summary, derived from what mc_talking's relation view offers. */
    private static int tierFromRelation(PlayerRelationView rel) {
        try {
            if (rel.hostile()) {
                return 0;
            }
            String rank = rel.rankName() == null ? "" : rel.rankName().trim().toLowerCase();
            if (rank.contains("owner")) {
                return 4;
            }
            if (rel.colonyLeadership() || rank.contains("officer")) {
                return 3;
            }
            if (rank.contains("friend")) {
                return 2;
            }
            return 1;
        } catch (Throwable t) {
            return 1;
        }
    }
}
