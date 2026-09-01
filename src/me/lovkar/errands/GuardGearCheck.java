package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * "Are my guards actually armed?" - the guard leaderboard made it obvious that
 * a barefoot guard just farms negative points, and MineColonies only whispers
 * about missing gear through the request system.
 * <p>
 * The checks mirror the game's own: worn armor is read from the citizen's
 * InventoryCitizen (authoritative - the entity's vanilla armor slots are
 * deliberately cleared by the guard AI), and the weapon test is the same
 * InventoryUtils equipment-level test the guard AI's hasTool() uses, capped by
 * the hut's level like MineColonies caps it.
 */
public final class GuardGearCheck {

    private GuardGearCheck() {
    }

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case FEET -> "boots";
            default -> slot.getName();
        };
    }

    /** The weapon type this guard's job needs, or null when it needs none (druid). */
    private static EquipmentTypeEntry weaponFor(IJob<?> job) {
        try {
            if (job instanceof com.minecolonies.core.colony.jobs.guard.JobHuscarl) {
                return ModEquipmentTypes.axe.get();
            }
            if (job instanceof com.minecolonies.core.colony.jobs.guard.JobKnight) {
                return ModEquipmentTypes.sword.get();
            }
            if (job instanceof com.minecolonies.core.colony.jobs.guard.JobMarksman) {
                return ModEquipmentTypes.crossbow.get();
            }
            if (job instanceof com.minecolonies.core.colony.jobs.guard.JobRanger) {
                return ModEquipmentTypes.bow.get();
            }
            if (job instanceof com.minecolonies.core.colony.jobs.guard.JobCavalry) {
                return ModEquipmentTypes.spear.get();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean wantsShield(IJob<?> job) {
        try {
            return job instanceof com.minecolonies.core.colony.jobs.guard.JobKnight
                    || job instanceof com.minecolonies.core.colony.jobs.guard.JobCavalry;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Spoken-ready report. Server thread (walks citizens and their buildings). */
    public static String report(IColony colony) {
        List<String> problems = new ArrayList<>();
        int guards = 0;
        int fine = 0;
        try {
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                IJob<?> job;
                try {
                    job = data.getJob();
                } catch (Throwable t) {
                    continue;
                }
                if (job == null || !job.isGuard()) continue;
                guards++;

                IBuilding hut = null;
                try {
                    hut = data.getWorkBuilding();
                } catch (Throwable ignored) {
                }
                int hutLevel = 0;
                int maxLevel = Integer.MAX_VALUE;
                if (hut != null) {
                    try {
                        hutLevel = hut.getBuildingLevel();
                        maxLevel = hut.getMaxEquipmentLevel();
                    } catch (Throwable ignored) {
                    }
                }

                List<String> missing = missingFor(data, job, maxLevel);
                if (missing == null) continue;

                if (missing.isEmpty()) {
                    fine++;
                    continue;
                }
                String name;
                try {
                    name = data.getName();
                } catch (Throwable t) {
                    name = "a guard";
                }
                problems.add(name + " (" + jobName(job) + ", hut level " + hutLevel + "): "
                        + String.join(", ", missing));
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Gear] guard gear check failed", t);
        }

        if (guards == 0) {
            return "This colony has NO guards at all - nobody is defending it.";
        }
        if (problems.isEmpty()) {
            return "All " + guards + " guards are fully kitted out - armor and weapons complete. Say it proudly.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(problems.size()).append(" of ").append(guards).append(" guards are missing gear");
        if (fine > 0) {
            sb.append(" (").append(fine).append(" fully equipped)");
        }
        sb.append(": ");
        int shown = 0;
        for (String p : problems) {
            if (shown >= 8) {
                sb.append("...and ").append(problems.size() - shown).append(" more. ");
                break;
            }
            sb.append(p).append(". ");
            shown++;
        }
        sb.append("Guards request their own gear through the colony's request system, so the usual cause is that "
                + "nobody CRAFTED it or the courier has not delivered yet - and a guard hut only accepts gear up to "
                + "its own level. Report it the way a worried soldier would, naming the worst cases first.");
        return sb.toString();
    }

    /**
     * What this one guard is really missing, right now. Null when we cannot read
     * their inventory at all. {@link #report} and {@link #promptLine} both go
     * through here so the spoken answer and the written report can never disagree.
     */
    private static List<String> missingFor(ICitizenData data, IJob<?> job, int maxLevel) {
        InventoryCitizen inv;
        try {
            inv = data.getInventory();
        } catch (Throwable t) {
            return null;
        }
        if (inv == null) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack worn;
            try {
                worn = inv.getArmorInSlot(slot);
            } catch (Throwable t) {
                continue;
            }
            if (ItemStackUtils.isEmpty(worn)) {
                missing.add("no " + slotName(slot));
            }
        }
        EquipmentTypeEntry weapon = weaponFor(job);
        if (weapon != null) {
            boolean armed = false;
            try {
                armed = InventoryUtils.hasItemHandlerEquipmentWithLevel(inv, weapon, 0, maxLevel);
            } catch (Throwable ignored) {
            }
            if (!armed) {
                String w = "weapon";
                try {
                    w = weapon.getDisplayName().getString().toLowerCase();
                } catch (Throwable ignored) {
                }
                missing.add("NO " + w);
            }
        }
        if (wantsShield(job)) {
            boolean hasShield = false;
            try {
                hasShield = InventoryUtils.findFirstSlotInItemHandlerWith(inv, Items.SHIELD) != -1;
            } catch (Throwable ignored) {
            }
            if (!hasShield) {
                missing.add("no shield");
            }
        }
        return missing;
    }

    /**
     * Lovkar: "the guards moan the whole time that they are missing something, or
     * that ANOTHER guard is, when they all have it."
     * <p>
     * Once a gear report has been spoken it lives on in that citizen's memories,
     * and the rumour mill carries it round the colony - so a complaint outlives the
     * problem by hours and spreads to guards it was never about. Memories cannot be
     * un-said, but the PROMPT is authoritative and current, so we simply state the
     * truth about their kit every time they speak, and forbid the stale complaint.
     */
    public static String promptLine(String citizenName) {
        try {
            for (IColony colony : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                    if (!citizenName.equals(data.getName())) {
                        continue;
                    }
                    IJob<?> job = data.getJob();
                    if (job == null || !job.isGuard()) {
                        return "";
                    }
                    int maxLevel = Integer.MAX_VALUE;
                    try {
                        IBuilding hut = data.getWorkBuilding();
                        if (hut != null) {
                            maxLevel = hut.getMaxEquipmentLevel();
                        }
                    } catch (Throwable ignored) {
                    }
                    List<String> missing = missingFor(data, job, maxLevel);
                    if (missing == null) {
                        return "";
                    }
                    if (missing.isEmpty()) {
                        return "\n\nYOUR KIT: you are FULLY EQUIPPED right now - armour, weapon and shield are all "
                                + "on you. Whatever you or anyone else complained about before has been dealt with. "
                                + "Do NOT say you are missing equipment, and do NOT say another guard is missing "
                                + "theirs - old talk about missing gear is out of date and repeating it is simply "
                                + "wrong.";
                    }
                    return "\n\nYOUR KIT: you really are missing " + String.join(", ", missing)
                            + ". That much is true and you may say it plainly. Say nothing about ANY OTHER guard's "
                            + "equipment - you have no idea what they are carrying, and old gossip about it is "
                            + "usually out of date.";
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * "Prioritise arming my guards": file the colony's own gear requests for every
     * missing piece, right now, instead of waiting for each guard's AI to notice
     * at their hut. Uses MineColonies' own Tool requestable with the hut's level
     * cap, so the request resolves exactly like the guard's own would - from
     * stock, from a crafter, or (honestly) as a player request.
     */
    public static String armAll(IColony colony) {
        int ordered = 0;
        int alreadyOnOrder = 0;
        int guards = 0;
        List<String> perGuard = new ArrayList<>();
        try {
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                IJob<?> job;
                try {
                    job = data.getJob();
                } catch (Throwable t) {
                    continue;
                }
                if (job == null || !job.isGuard()) continue;
                guards++;

                IBuilding hut;
                try {
                    hut = data.getWorkBuilding();
                } catch (Throwable t) {
                    continue;
                }
                if (hut == null) continue;
                int maxLevel;
                try {
                    maxLevel = hut.getMaxEquipmentLevel();
                } catch (Throwable t) {
                    maxLevel = Integer.MAX_VALUE;
                }
                InventoryCitizen inv;
                try {
                    inv = data.getInventory();
                } catch (Throwable t) {
                    continue;
                }
                if (inv == null) continue;

                List<String> got = new ArrayList<>();
                for (EquipmentSlot slot : ARMOR) {
                    boolean worn;
                    try {
                        worn = !ItemStackUtils.isEmpty(inv.getArmorInSlot(slot));
                    } catch (Throwable t) {
                        continue;
                    }
                    if (worn) continue;
                    EquipmentTypeEntry type = armorType(slot);
                    if (type == null) continue;
                    int r = order(hut, data, type, maxLevel);
                    if (r == 1) {
                        ordered++;
                        got.add(slotName(slot));
                    } else if (r == 0) {
                        alreadyOnOrder++;
                    }
                }
                EquipmentTypeEntry weapon = weaponFor(job);
                if (weapon != null) {
                    boolean armed = false;
                    try {
                        armed = InventoryUtils.hasItemHandlerEquipmentWithLevel(inv, weapon, 0, maxLevel);
                    } catch (Throwable ignored) {
                    }
                    if (!armed) {
                        int r = order(hut, data, weapon, maxLevel);
                        if (r == 1) {
                            ordered++;
                            try {
                                got.add(weapon.getDisplayName().getString().toLowerCase());
                            } catch (Throwable ignored) {
                                got.add("weapon");
                            }
                        } else if (r == 0) {
                            alreadyOnOrder++;
                        }
                    }
                }
                if (!got.isEmpty()) {
                    perGuard.add(data.getName() + ": " + String.join(", ", got));
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Gear] armAll failed", t);
        }

        if (guards == 0) {
            return "There are no guards in this colony to arm.";
        }
        if (ordered == 0) {
            return alreadyOnOrder > 0
                    ? ("Every missing piece is ALREADY on order (" + alreadyOnOrder + " of them) - the colony has "
                    + "asked for them, so it is the making or the carrying that is slow, not the asking. "
                    + "Say so plainly.")
                    : "Every guard is fully kitted out - nothing to order. Say it proudly.";
        }
        ColonistErrands.LOGGER.info("[Gear] Ordered {} missing piece(s) of guard kit ({} already on order)",
                ordered, alreadyOnOrder);
        StringBuilder sb = new StringBuilder("Ordered " + ordered + " missing piece"
                + (ordered == 1 ? "" : "s") + " of kit through the colony: ");
        int shown = 0;
        for (String g : perGuard) {
            if (shown >= 6) {
                sb.append("...and ").append(perGuard.size() - shown).append(" more guards. ");
                break;
            }
            sb.append(g).append("; ");
            shown++;
        }
        if (alreadyOnOrder > 0) {
            sb.append(alreadyOnOrder).append(" more were already on order. ");
        }
        sb.append("They arrive as they get made and delivered. Warn the player honestly: if a piece never turns up, "
                + "nobody in the colony can craft it and it is sitting on their own clipboard as a player request.");
        return sb.toString();
    }

    /** 1 = ordered, 0 = already requested, -1 = failed. */
    private static int order(IBuilding hut, ICitizenData data, EquipmentTypeEntry type, int maxLevel) {
        try {
            for (com.minecolonies.api.colony.requestsystem.request.IRequest<?> req : hut.getOpenRequests(data.getId())) {
                Object r = req.getRequest();
                if (r instanceof com.minecolonies.api.colony.requestsystem.requestable.Tool t
                        && t.getEquipmentType() == type) {
                    return 0;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            hut.createRequest(data,
                    new com.minecolonies.api.colony.requestsystem.requestable.Tool(type, 0,
                            maxLevel == Integer.MAX_VALUE ? 5 : maxLevel),
                    false);
            return 1;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Gear] could not request equipment", t);
            return -1;
        }
    }

    private static EquipmentTypeEntry armorType(EquipmentSlot slot) {
        try {
            return switch (slot) {
                case HEAD -> ModEquipmentTypes.helmet.get();
                case CHEST -> ModEquipmentTypes.chestplate.get();
                case LEGS -> ModEquipmentTypes.leggings.get();
                case FEET -> ModEquipmentTypes.boots.get();
                default -> null;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private static String jobName(IJob<?> job) {
        try {
            return job.getJobRegistryEntry().getKey().getPath();
        } catch (Throwable t) {
            return "guard";
        }
    }
}
