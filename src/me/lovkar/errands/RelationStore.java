package me.lovkar.errands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lovkar's idea #29: citizens remember HOW each player treats them. Rapport is
 * a per-(citizen, player) score fed by the citizen itself (the model calls
 * note_player_conduct after notably kind/rude moments) and by hard facts
 * (promises kept/broken). It colors the citizen's tone through the prompt and
 * leaks into colony gossip, so a player who is rude to everyone gets a
 * reputation. Persisted in config/colonist_errands_relations.json.
 */
public final class RelationStore {

    public static final class Note {
        public long day;
        public String text;
        public boolean kind;
    }

    public static final class Relation {
        public String citizen;
        public String player;   // account name
        public int rapport;     // -100..100
        public List<Note> notes = new ArrayList<>();
    }

    private static final Path FILE = Path.of("config", "colonist_errands_relations.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Relation> RELATIONS = new ArrayList<>();
    private static volatile boolean loaded = false;
    private static final int MAX_NOTES = 4;

    private RelationStore() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                List<Relation> list = GSON.fromJson(Files.readString(FILE),
                        new TypeToken<List<Relation>>() {}.getType());
                if (list != null) {
                    RELATIONS.addAll(list);
                }
                ColonistErrands.LOGGER.info("[Relations] Loaded {} citizen-player relation(s)", RELATIONS.size());
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to load relations", t);
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(RELATIONS));
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to save relations", t);
        }
    }

    private static synchronized Relation get(String citizen, String player, boolean create) {
        for (Relation r : RELATIONS) {
            if (r.citizen.equals(citizen) && r.player.equals(player)) {
                return r;
            }
        }
        if (!create) {
            return null;
        }
        Relation r = new Relation();
        r.citizen = citizen;
        r.player = player;
        RELATIONS.add(r);
        return r;
    }

    /** The citizen noticed notably kind/rude conduct (model-driven via note_player_conduct). */
    public static synchronized void note(String citizen, String playerAccount, boolean kind, String text, long day) {
        if (citizen == null || playerAccount == null || playerAccount.isBlank()) {
            return;
        }
        load();
        Relation r = get(citizen, playerAccount, true);
        r.rapport = clamp(r.rapport + (kind ? 7 : -9));
        Note n = new Note();
        n.day = day;
        n.kind = kind;
        n.text = text == null || text.isBlank() ? (kind ? "was notably kind" : "was notably rude") : text.trim();
        r.notes.add(n);
        while (r.notes.size() > MAX_NOTES) {
            r.notes.remove(0);
        }
        save();
        ColonistErrands.LOGGER.info("[Relations] {} notes {} was {} ('{}') -> rapport {}",
                citizen, playerAccount, kind ? "KIND" : "RUDE", n.text, r.rapport);
    }

    /** Hard fact: a promise to this citizen was kept or broken by that player. */
    public static synchronized void promiseResolved(String citizen, String playerAccount, boolean kept, long day) {
        if (citizen == null || playerAccount == null || playerAccount.isBlank()) {
            return;
        }
        load();
        Relation r = get(citizen, playerAccount, true);
        r.rapport = clamp(r.rapport + (kept ? 8 : -10));
        Note n = new Note();
        n.day = day;
        n.kind = kept;
        n.text = kept ? "kept a promise to you" : "broke a promise to you";
        r.notes.add(n);
        while (r.notes.size() > MAX_NOTES) {
            r.notes.remove(0);
        }
        save();
    }

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }

    /** Prompt block: how THIS player usually treats THIS citizen (empty when no history). */
    public static synchronized String blockFor(String citizen, String playerAccount) {
        load();
        Relation r = citizen == null || playerAccount == null ? null : get(citizen, playerAccount, false);
        if (r == null || (r.rapport == 0 && r.notes.isEmpty())) {
            return "";
        }
        String shown = AliasStore.display(playerAccount);
        StringBuilder sb = new StringBuilder("\n- How ").append(shown).append(" treats YOU (rapport ")
                .append(r.rapport).append("/100): ");
        if (r.rapport >= 25) {
            sb.append("they are consistently kind and respectful to you - be warm, open and extra helpful with them.");
        } else if (r.rapport >= 8) {
            sb.append("mostly pleasant so far - be friendly.");
        } else if (r.rapport <= -25) {
            sb.append("they are often rude to you - be cool, short and visibly reluctant with them ")
                    .append("(orders from leadership you still carry out, but without warmth), and you may ")
                    .append("honestly mention their past behavior.");
        } else if (r.rapport <= -8) {
            sb.append("they have been unpleasant to you before - be a bit guarded and reserved.");
        } else {
            sb.append("nothing remarkable either way yet.");
        }
        if (!r.notes.isEmpty()) {
            sb.append(" Moments you remember:");
            for (Note n : r.notes) {
                sb.append(" [day ").append(n.day).append("] ").append(n.text).append(";");
            }
        }
        return sb.toString();
    }

    /** Colony gossip about manners: average rapport per player across citizens. */
    public static synchronized String gossipBlock() {
        load();
        if (RELATIONS.isEmpty()) {
            return "";
        }
        Map<String, int[]> byPlayer = new LinkedHashMap<>(); // player -> [sum, count]
        for (Relation r : RELATIONS) {
            int[] a = byPlayer.computeIfAbsent(r.player, k -> new int[2]);
            a[0] += r.rapport;
            a[1]++;
        }
        StringBuilder sb = new StringBuilder();
        byPlayer.forEach((player, a) -> {
            int avg = a[0] / Math.max(1, a[1]);
            if (a[1] >= 2 && avg >= 15) {
                sb.append(" ").append(AliasStore.display(player)).append(" is known around the colony as kind and respectful.");
            } else if (a[1] >= 2 && avg <= -15) {
                sb.append(" ").append(AliasStore.display(player)).append(" has been rude to several colonists - people grumble about it.");
            }
        });
        if (sb.length() == 0) {
            return "";
        }
        return "\n- Colony gossip about manners:" + sb;
    }
}
