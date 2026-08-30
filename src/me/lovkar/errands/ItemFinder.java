package me.lovkar.errands;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Finds an item from a spoken name like "iron ingot", "eggplant", "dark oak planks". */
public final class ItemFinder {

    private ItemFinder() {
    }

    public static Item find(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.trim().toLowerCase();
        String qu = q.replace(' ', '_');

        Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            String path = id.getPath();
            String disp;
            try {
                disp = item.getDescription().getString().toLowerCase();
            } catch (Throwable t) {
                disp = path;
            }
            int score = Integer.MIN_VALUE;
            if (path.equals(qu) || disp.equals(q)) {
                score = 1000;
            } else if (path.contains(qu) || disp.contains(q)) {
                score = 500 - path.length(); // prefer the shortest (most specific) match
            } else if (qu.contains(path)) {
                score = 200 - (qu.length() - path.length());
            }
            if (score > bestScore) {
                bestScore = score;
                best = item;
                if (score == 1000) break;
            }
        }
        return bestScore == Integer.MIN_VALUE ? null : best;
    }

    /**
     * For planting: prefer the crop's SEED item, and when several mods add the same
     * crop (e.g. minecolonies AND culturaldelights both have eggplant), prefer
     * minecolonies > minecraft > farmersdelight - a MineColonies farm plants
     * MineColonies crops unless the player names the mod.
     */
    public static Item findSeedFor(String crop) {
        if (crop == null || crop.isBlank()) return null;
        String c = crop.trim().toLowerCase().replace(' ', '_');
        Item best = null;
        int bestScore = -1;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            String path = id.getPath();
            String stem = path.replaceFirst("_seeds?$", "");
            boolean stemMatch = stem.equals(c)
                    || stem.equals(c + "s")
                    || (c.endsWith("s") && stem.equals(c.substring(0, c.length() - 1)));
            if (!stemMatch) continue;
            int score = 0;
            if (!path.equals(stem)) score += 100; // it IS a seed item
            score += switch (id.getNamespace()) {
                case "minecolonies" -> 30;
                case "minecraft" -> 20;
                case "farmersdelight" -> 10;
                default -> 0;
            };
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        if (best != null) return best;
        return find(crop);
    }
}
