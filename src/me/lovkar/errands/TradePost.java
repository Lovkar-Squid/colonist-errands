package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.api.colony.managers.interfaces.IStatisticsManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Soft bridge to the MC Trade Post addon (mctradepost) - Lovkar plays with it and
 * kept asking how the marketplace and trade coins actually work, so the
 * colonists can now answer from the live colony instead of from a wiki.
 * <p>
 * Everything here is reflective and wrapped: with MC Trade Post absent the mod
 * still loads and the tools simply say the colony has no trade post. The colony
 * balance itself is a plain MineColonies statistic ("current_balance"), so that
 * part needs no reflection at all.
 */
public final class TradePost {

    private TradePost() {
    }

    private static final String MARKETPLACE_ID = "mctradepost:marketplace";
    private static final String BALANCE_STAT = "current_balance";
    private static final String CFG = "com.deathfrog.mctradepost.MCTPConfig";
    private static final String MARKETPLACE_CLASS =
            "com.deathfrog.mctradepost.core.colony.buildings.workerbuildings.BuildingMarketplace";
    private static final String ECON_CLASS =
            "com.deathfrog.mctradepost.core.colony.buildings.modules.BuildingEconModule";

    private static volatile Boolean present = null;

    public static boolean loaded() {
        Boolean p = present;
        if (p == null) {
            try {
                Class.forName(CFG);
                p = Boolean.TRUE;
            } catch (Throwable t) {
                p = Boolean.FALSE;
            }
            present = p;
        }
        return p;
    }

    /** A config value from MCTPConfig, via its own String map (no mod types cross the boundary). */
    public static int config(String key, int fallback) {
        try {
            Object raw = Class.forName(CFG).getMethod("getSerializableConfigSettings").invoke(null);
            if (raw instanceof Map<?, ?> map) {
                Object v = map.get(key);
                if (v != null) return (int) Double.parseDouble(v.toString().trim());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** The colony's money, in raw value points (a coin is worth {@link #coinValue()} of these). */
    public static int balance(IColony colony) {
        try {
            return colony.getStatisticsManager().getStatTotal(BALANCE_STAT);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int coinValue() {
        int v = config("tradeCoinValue", 1000);
        return v > 0 ? v : 1000;
    }

    public static int mintingLevel() {
        return config("mintingLevel", 2);
    }

    public static List<IBuilding> marketplaces(IColony colony) {
        List<IBuilding> out = new ArrayList<>();
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b == null) continue;
                try {
                    if (MARKETPLACE_ID.equals(b.getBuildingType().getRegistryName().toString())) {
                        out.add(b);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Full spoken-ready status of the colony's trade post economy. Server thread. */
    public static String statusText(IColony colony) {
        if (!loaded()) {
            return "This world does not have the MC Trade Post addon installed, so there is no marketplace economy "
                    + "and no trade coins at all.";
        }
        int coinValue = coinValue();
        int mintLevel = mintingLevel();
        int points = balance(colony);
        int coins = points / coinValue;
        StringBuilder sb = new StringBuilder();
        sb.append("Colony treasury: ").append(points).append(" in value (that is ").append(coins)
                .append(" trade coins' worth at ").append(coinValue).append(" per coin). ");

        List<IBuilding> markets = marketplaces(colony);
        if (markets.isEmpty()) {
            sb.append("We have NO marketplace built, so nothing is being sold and no coins can be minted. ");
            sb.append("A marketplace needs a shopkeeper, stocked display shelves and tavern VISITORS to buy - ")
                    .append("citizens never shop.");
            return sb.toString();
        }

        int totalSold = 0;
        int totalCash = 0;
        int totalMinted = 0;
        boolean anyCanMint = false;
        for (IBuilding b : markets) {
            int level = 0;
            try {
                level = b.getBuildingLevel();
            } catch (Throwable ignored) {
            }
            String shopkeeper = null;
            boolean open = false;
            int onSale = -1;
            try {
                Class<?> mp = Class.forName(MARKETPLACE_CLASS);
                Object shk = mp.getMethod("shopkeeper").invoke(b);
                if (shk instanceof ICitizenData cd) shopkeeper = cd.getName();
                Object o = mp.getMethod("isOpenForBusiness").invoke(b);
                open = o instanceof Boolean bo && bo;
                Object shelves = mp.getMethod("getDisplayShelvesWithItemsForSale").invoke(b);
                if (shelves instanceof List<?> l) onSale = l.size();
            } catch (Throwable ignored) {
            }
            int[] stats = econStats(b);
            totalSold += stats[0];
            totalCash += stats[1];
            totalMinted += stats[2];
            if (level >= mintLevel) anyCanMint = true;

            sb.append("Marketplace (level ").append(level).append("): ")
                    .append(shopkeeper == null ? "NO shopkeeper hired" : "shopkeeper " + shopkeeper
                            + (open ? " is at work" : " is NOT at work right now"));
            if (onSale >= 0) {
                sb.append(", ").append(onSale).append(onSale == 1 ? " shelf" : " shelves").append(" stocked for sale");
            }
            sb.append(". ");
        }
        sb.append("Sales so far: ").append(totalSold).append(" items for ").append(totalCash)
                .append(" in value; ").append(totalMinted).append(" coins minted. ");
        if (anyCanMint) {
            int affordable = points / coinValue;
            sb.append("Minting IS possible (needs marketplace level ").append(mintLevel).append("): the treasury ")
                    .append("covers ").append(affordable).append(" coin").append(affordable == 1 ? "" : "s")
                    .append(" right now.");
        } else {
            sb.append("No marketplace has reached level ").append(mintLevel)
                    .append(" yet, so coins CANNOT be minted here - that is what the ")
                    .append("'this marketplace cannot mint coins' message means. Upgrade it.");
        }
        return sb.toString();
    }

    /** {itemsSold, cashGenerated, coinsMinted} from the marketplace's econ module. */
    public static int[] econStats(IBuilding b) {
        int[] out = new int[]{0, 0, 0};
        try {
            Class<?> econCls = Class.forName(ECON_CLASS);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object econ = b.getModule((Class) econCls);
            if (econ == null) return out;
            Object sm = econCls.getMethod("getBuildingStatisticsManager").invoke(econ);
            if (sm instanceof IStatisticsManager stats) {
                out[0] = stats.getStatTotal("item.sold");
                out[1] = stats.getStatTotal("cash.generated");
                out[2] = stats.getStatTotal("coins.minted");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean autoMint(IBuilding b) {
        try {
            Object key = Class.forName(MARKETPLACE_CLASS).getField("AUTOMINT").get(null);
            if (key instanceof ISettingKey<?> k) {
                Object v = b.getSettingValueOrDefault(cast(k), Boolean.FALSE);
                return v instanceof Boolean bo && bo;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ISettingKey cast(ISettingKey<?> k) {
        return k;
    }

    /**
     * Mints coins at the best eligible marketplace and hands them to the player.
     * Returns a spoken-ready sentence. Server thread only.
     */
    public static String mint(IColony colony, ServerPlayer player, int coins) {
        if (!loaded()) {
            return "There is no MC Trade Post in this world, so there are no trade coins to mint.";
        }
        if (player == null) {
            return "I cannot tell who to hand the coins to - the player should come closer and ask again.";
        }
        int coinValue = coinValue();
        int mintLevel = mintingLevel();
        int points = balance(colony);

        IBuilding best = null;
        int bestLevel = -1;
        for (IBuilding b : marketplaces(colony)) {
            int level;
            try {
                level = b.getBuildingLevel();
            } catch (Throwable t) {
                continue;
            }
            if (level >= mintLevel && level > bestLevel) {
                bestLevel = level;
                best = b;
            }
        }
        if (best == null) {
            List<IBuilding> any = marketplaces(colony);
            if (any.isEmpty()) {
                return "We have no marketplace at all - a marketplace is what mints trade coins, and it has to be "
                        + "level " + mintLevel + " or higher.";
            }
            return "Our marketplace is not level " + mintLevel + " yet, so it CANNOT mint coins - that is exactly "
                    + "the refusal the player saw. It needs upgrading first.";
        }
        int affordable = points / coinValue;
        if (affordable <= 0) {
            return "The colony treasury only holds " + points + " in value and a single trade coin costs "
                    + coinValue + " - there is not enough to mint even one. The marketplace has to SELL more first "
                    + "(stocked shelves plus tavern visitors).";
        }
        int want = Math.max(1, Math.min(coins, Math.min(affordable, 512)));

        List<?> minted;
        try {
            minted = (List<?>) Class.forName(MARKETPLACE_CLASS)
                    .getMethod("mintCoins", net.minecraft.world.entity.player.Player.class, int.class)
                    .invoke(best, player, want);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[TradePost] mintCoins failed", t);
            return "The marketplace refused to mint just now.";
        }
        if (minted == null || minted.isEmpty()) {
            return "The mint came back empty - the treasury did not cover it after all.";
        }
        int handed = 0;
        for (Object o : minted) {
            if (!(o instanceof ItemStack stack) || stack.isEmpty()) continue;
            handed += stack.getCount();
            try {
                if (!player.addItem(stack.copy())) {
                    player.drop(stack.copy(), false);
                }
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("[TradePost] could not hand over minted coins", t);
            }
        }
        ColonistErrands.LOGGER.info("[TradePost] Minted {} trade coins (cost {} value)", handed, handed * coinValue);
        return "Minted " + handed + " trade coin" + (handed == 1 ? "" : "s") + " for "
                + (handed * coinValue) + " out of the colony treasury and handed them straight to the player. "
                + "Say it the way a shopkeeper would.";
    }
}
