package me.marko.errands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Marko's idea #22: PROMISES. The player can promise a citizen something
 * ("I'll bring you 10 bread", "I'll build you a house by day 40"); the citizen
 * writes it down here. Open promises are injected into that citizen's prompt
 * on every conversation, so they bring them up naturally, remind the player
 * when a promise is due/overdue, thank them when kept and are disappointed
 * when broken. Persisted in config/colonist_errands_promises.json.
 */
public final class PromiseStore {

    public static final class Promise {
        public String citizenName;
        public String text;
        public long madeAtDay;
        public long dueDay;      // 0 = no deadline
        public String status;    // open | kept | broken
        public long resolvedAtDay;
        public String about;     // housing | food | health | work | general
        public String byPlayer;  // account name of the player who promised (null on pre-1.8 records)
    }

    /** Display label of a promise's maker ("Marko", alias-aware; legacy records = "the player"). */
    public static String makerLabel(Promise p) {
        return p.byPlayer == null || p.byPlayer.isBlank() ? "the player" : AliasStore.display(p.byPlayer);
    }

    private static final Path FILE = Path.of("config", "colonist_errands_promises.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Promise> PROMISES = new ArrayList<>();
    private static volatile boolean loaded = false;
    private static volatile long currentDay = 0;
    private static final int MAX_PER_CITIZEN = 6;

    private PromiseStore() {
    }

    /** Updated from the server tick so prompt building never needs level access. */
    public static void setCurrentDay(long day) {
        currentDay = day;
    }

    public static long currentDay() {
        return currentDay;
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                List<Promise> list = GSON.fromJson(Files.readString(FILE),
                        new TypeToken<List<Promise>>() {}.getType());
                if (list != null) {
                    PROMISES.addAll(list);
                }
                ColonistErrands.LOGGER.info("[Promises] Loaded {} promise(s)", PROMISES.size());
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to load promises", t);
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(PROMISES));
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to save promises", t);
        }
    }

    /** @return null on success, else an error message. */
    public static synchronized String add(String citizenName, String text, int dueInDays, String about, String byPlayer) {
        load();
        long open = PROMISES.stream()
                .filter(p -> p.citizenName.equals(citizenName) && "open".equals(p.status)).count();
        if (open >= MAX_PER_CITIZEN) {
            return "You already hold " + open + " open promises from players - politely refuse to track more "
                    + "until one is resolved.";
        }
        Promise p = new Promise();
        p.citizenName = citizenName;
        p.text = text;
        p.madeAtDay = currentDay;
        p.dueDay = dueInDays > 0 ? currentDay + dueInDays : 0;
        p.status = "open";
        p.about = about == null || about.isBlank() ? "general" : about;
        p.byPlayer = byPlayer;
        PROMISES.add(p);
        save();
        ColonistErrands.LOGGER.info("[Promises] {} <- '{}' (by {}, about {}, due day {})", citizenName, text,
                byPlayer == null ? "?" : byPlayer, p.about, p.dueDay);
        return null;
    }

    /**
     * Resolves the OLDEST open promise of this citizen - preferring one made by
     * the given player (multiplayer: you resolve YOUR OWN promise first).
     * @return the resolved Promise, or null if the citizen has none open.
     */
    public static synchronized Promise resolveOldest(String citizenName, boolean kept, String byPlayer) {
        load();
        Promise pick = null;
        if (byPlayer != null && !byPlayer.isBlank()) {
            for (Promise p : PROMISES) {
                if (p.citizenName.equals(citizenName) && "open".equals(p.status) && byPlayer.equals(p.byPlayer)) {
                    pick = p;
                    break;
                }
            }
        }
        if (pick == null) {
            for (Promise p : PROMISES) {
                if (p.citizenName.equals(citizenName) && "open".equals(p.status)) {
                    pick = p;
                    break;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        pick.status = kept ? "kept" : "broken";
        pick.resolvedAtDay = currentDay;
        save();
        ColonistErrands.LOGGER.info("[Promises] {} -> '{}' (by {}) marked {}", citizenName, pick.text,
                pick.byPlayer == null ? "?" : pick.byPlayer, pick.status);
        return pick;
    }

    public static synchronized List<Promise> openFor(String citizenName) {
        load();
        List<Promise> out = new ArrayList<>();
        for (Promise p : PROMISES) {
            if (p.citizenName.equals(citizenName) && "open".equals(p.status)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Snapshot of ALL open promises (PromiseWatcher's auto-detection). */
    public static synchronized List<Promise> openPromises() {
        load();
        List<Promise> out = new ArrayList<>();
        for (Promise p : PROMISES) {
            if ("open".equals(p.status)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Resolve THIS specific promise (auto-detection). @return false if it was already resolved. */
    public static synchronized boolean resolve(Promise target, boolean kept) {
        load();
        if (target == null || !"open".equals(target.status)) {
            return false;
        }
        target.status = kept ? "kept" : "broken";
        target.resolvedAtDay = currentDay;
        save();
        ColonistErrands.LOGGER.info("[Promises] {} -> '{}' (by {}) auto-marked {}", target.citizenName, target.text,
                target.byPlayer == null ? "?" : target.byPlayer, target.status);
        return true;
    }

    /** Prompt block for one citizen: open promises + freshly resolved ones (2 in-game days), per maker. */
    public static synchronized String promptBlockFor(String citizenName) {
        load();
        if (PROMISES.isEmpty() || citizenName == null) {
            return "";
        }
        StringBuilder open = new StringBuilder();
        StringBuilder recent = new StringBuilder();
        java.util.Map<String, int[]> record = new java.util.LinkedHashMap<>(); // maker -> [kept, broken]
        for (Promise p : PROMISES) {
            if (!citizenName.equals(p.citizenName)) continue;
            String maker = makerLabel(p);
            if ("open".equals(p.status)) {
                open.append("\n- ").append(maker).append(" promised: \"").append(p.text)
                        .append("\" (on colony day ").append(p.madeAtDay);
                if (p.about != null && !"general".equals(p.about)) {
                    open.append(", about your ").append(p.about);
                }
                if (p.dueDay > 0) {
                    open.append(", due by day ").append(p.dueDay);
                    if (currentDay > p.dueDay) {
                        open.append(" - OVERDUE, ").append(maker).append(" has not kept it yet");
                    }
                }
                open.append(")");
            } else {
                record.computeIfAbsent(maker, k -> new int[2])["kept".equals(p.status) ? 0 : 1]++;
                if (currentDay - p.resolvedAtDay <= 2) {
                    recent.append("\n- ").append(maker).append("'s promise \"").append(p.text).append("\" was recently ")
                            .append("kept".equals(p.status) ? "KEPT - you are grateful to them"
                                    : "BROKEN - you are disappointed in them");
                }
            }
        }
        if (open.length() == 0 && recent.length() == 0 && record.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nPromises players made to you (today is colony day ")
                .append(currentDay).append("):");
        if (open.length() > 0) {
            sb.append("\nOPEN promises (bring them up naturally; remind politely when one is due or overdue; when the ")
                    .append("maker says they fulfilled or cancelled one, call the resolve_promise tool):").append(open)
                    .append("\nEach promise belongs to the person who made it: remind, thank or blame ONLY that ")
                    .append("player - never nag someone about a different person's promise. While a promise is open ")
                    .append("and NOT overdue, do not complain about that problem - you trust them and wait patiently. ")
                    .append("Once OVERDUE you may complain about the broken timeline to its maker.");
        }
        if (recent.length() > 0) {
            sb.append("\nRecently resolved:").append(recent);
        }
        if (!record.isEmpty()) {
            sb.append("\nYour experience with each player's word:");
            record.forEach((maker, kb) -> {
                sb.append("\n- ").append(maker).append(": kept ").append(kb[0]).append(", broke ").append(kb[1])
                        .append(" promise(s) to you");
                if (kb[1] > kb[0]) {
                    sb.append(" - you are rightly skeptical of their promises");
                } else if (kb[0] >= 2 && kb[1] == 0) {
                    sb.append(" - they reliably keep their word to you");
                }
            });
        }
        return sb.toString();
    }

    /**
     * Colony-wide promise reputation, one line per player - citizens gossip, so
     * every citizen knows who keeps their word. Appended via the identity block.
     */
    public static synchronized String reputationBlock() {
        load();
        java.util.Map<String, int[]> rep = new java.util.LinkedHashMap<>(); // account -> [kept, broken]
        for (Promise p : PROMISES) {
            if (p.byPlayer == null || p.byPlayer.isBlank() || "open".equals(p.status)) continue;
            rep.computeIfAbsent(p.byPlayer, k -> new int[2])["kept".equals(p.status) ? 0 : 1]++;
        }
        if (rep.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n- Word around the colony about promise-keeping (colonists gossip):");
        rep.forEach((account, kb) -> sb.append(" ").append(AliasStore.display(account)).append(" has kept ")
                .append(kb[0]).append(" and broken ").append(kb[1]).append(" promise(s) to colonists."));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Marko's idea #23: an open promise SUPPRESSES the pestering about the
    // promised problem - the citizen stops walking up to complain until the
    // promise is overdue or resolved.
    // ------------------------------------------------------------------

    /** Days a promise without a deadline keeps the citizen patient. */
    private static final long NO_DEADLINE_PATIENCE_DAYS = 4;

    /**
     * Topics of this citizen's open promises that are still "trusted" (not
     * overdue; no-deadline promises count for NO_DEADLINE_PATIENCE_DAYS).
     * Empty set = nothing suppressed.
     */
    public static synchronized java.util.Set<String> activeSuppressions(String citizenName) {
        if (PROMISES.isEmpty() || citizenName == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> topics = new java.util.HashSet<>();
        for (Promise p : PROMISES) {
            if (!citizenName.equals(p.citizenName) || !"open".equals(p.status)) continue;
            long until = p.dueDay > 0 ? p.dueDay : p.madeAtDay + NO_DEADLINE_PATIENCE_DAYS;
            if (currentDay <= until) {
                topics.add(p.about == null ? "general" : p.about);
            }
        }
        return topics;
    }

    /**
     * Re-computes mc_talking's urgency weight with the promised topics muted
     * (mirrors CitizenNeedAssessor.calculateUrgencyWeight of mc_talking 1.7.1).
     * A 'housing' promise also mutes the happiness component of a homeless
     * citizen - their unhappiness IS the missing house.
     */
    public static double suppressedUrgency(com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen,
                                           java.util.Set<String> topics) {
        try {
            com.minecolonies.api.colony.ICitizenData data = citizen.getCitizenData();
            if (data == null) {
                return 0.0;
            }
            boolean homeless = data.getHomeBuilding() == null;
            double weight = 0.0;
            boolean general = topics.contains("general");
            double happiness = data.getCitizenHappinessHandler().getHappiness(data.getColony(), data);
            boolean happinessMuted = general || (homeless && topics.contains("housing"));
            if (!happinessMuted) {
                if (happiness < 3.0) weight += 1.5;
                else if (happiness < 5.0) weight += 0.6;
            }
            if (!topics.contains("health") && data.getCitizenDiseaseHandler().isSick()) {
                weight += 0.8;
            }
            if (!topics.contains("housing") && homeless) {
                weight += 0.7;
            }
            if (!topics.contains("food")) {
                double sat = data.getSaturation();
                if (sat <= 1.0) weight += 1.0;
                else if (sat <= 3.0) weight += 0.4;
            }
            if (!topics.contains("health")) {
                double hp = citizen.getHealth() / Math.max(1.0f, citizen.getMaxHealth()) * 100.0;
                if (hp < 25.0) weight += 1.0;
                else if (hp < 50.0) weight += 0.4;
            }
            // "stuck job" component is intentionally NEVER suppressed - a stuck
            // worker is a real technical problem no promise fixes.
            try {
                if (data.getJobStatus() == com.minecolonies.api.entity.ai.JobStatus.STUCK) {
                    weight += stuckMultiplier();
                }
            } catch (Throwable ignored) {
            }
            return weight;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** mc_talking's blockingTaskUrgencyMultiplier via reflection (YACL types aren't on our compile path). */
    private static double stuckMultiplier() {
        try {
            Class<?> cfgClass = Class.forName("me.sshcrack.mc_talking.config.McTalkingConfig");
            Object handler = cfgClass.getField("INSTANCE").get(null);
            Object cfg = handler.getClass().getMethod("instance").invoke(handler);
            return ((Number) cfgClass.getField("blockingTaskUrgencyMultiplier").get(cfg)).doubleValue();
        } catch (Throwable t) {
            return 1.5;
        }
    }
}
