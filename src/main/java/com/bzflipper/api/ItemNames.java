package com.bzflipper.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a Bazaar product tag (e.g. ENCHANTED_CACTUS_GREEN, RED_ROSE, LOG:1) to its
 * REAL in-game display name (Enchanted Cactus Green, Poppy, Spruce Log) so the
 * flipper can find items via /bz search.
 *
 * Primary source: Hypixel's resources/skyblock/items endpoint (covers ~970 of the
 * ~1930 Bazaar items). The rest are three systematic families we name by rule:
 * essences, attribute shards, and enchantment books.
 */
public final class ItemNames {

    private ItemNames() {}

    private static volatile Map<String, String> MAP = Map.of();
    private static volatile Map<String, String> MATERIAL = Map.of();

    /** Load id -> name (and material) from the resources endpoint JSON root. */
    public static void load(JsonObject resourcesRoot) {
        JsonArray items = resourcesRoot.getAsJsonArray("items");
        if (items == null) return;
        Map<String, String> m = new HashMap<>();
        Map<String, String> mats = new HashMap<>();
        for (JsonElement el : items) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) continue;
            String id = o.get("id").getAsString();
            if (o.has("name")) m.put(id, clean(o.get("name").getAsString()));
            if (o.has("material")) mats.put(id, o.get("material").getAsString());
        }
        MAP = m;
        MATERIAL = mats;
    }

    /** True if claiming this item bypasses the inventory (essences are a currency
     *  → essence storage; no physical item ever enters the inventory).
     *
     *  SHARDS DO NOT BYPASS: claiming a SHARD_* buy order puts real item stacks in
     *  the player inventory (confirmed live — a 1713× shard claim filled it and the
     *  server refused further claims with "You don't have the space required").
     *  Listing them here caused claims to skip every inventory-space guard AND
     *  orders to be sized with no inventory cap. Only add a family here when it is
     *  CERTAIN no physical item lands in the inventory. */
    public static boolean bypassesInventory(String tag) {
        return tag != null && tag.startsWith("ESSENCE_");
    }

    /** Best-effort stack size: 1 for enchanted books and gear/tools/skull items,
     *  64 for normal materials. Used to size orders so claims fit in inventory. */
    public static int stackSize(String tag) {
        if (tag == null) return 64;
        if (tag.startsWith("ENCHANTMENT_")) return 1;   // enchanted books
        return isNonStackable(MATERIAL.getOrDefault(tag, "")) ? 1 : 64;
    }

    private static boolean isNonStackable(String mat) {
        if (mat == null || mat.isEmpty()) return false;
        if (mat.equals("SKULL_ITEM")) return true;
        for (String p : new String[]{"SWORD", "PICKAXE", "_AXE", "SPADE", "SHOVEL", "_HOE",
                "BOW", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "FISHING_ROD",
                "SHEARS", "SHIELD", "ELYTRA", "TRIDENT", "ENCHANTED_BOOK"}) {
            if (mat.contains(p)) return true;
        }
        return false;
    }

    public static boolean loaded() { return !MAP.isEmpty(); }

    /** Real display name for a tag, using the map first, then rule-based fallbacks. */
    public static String name(String tag) {
        String n = MAP.get(tag);
        return n != null ? n : fallback(tag);
    }

    static String fallback(String tag) {
        if (tag.startsWith("ESSENCE_")) return title(tag.substring(8)) + " Essence";
        if (tag.startsWith("SHARD_"))   return title(tag.substring(6)) + " Shard";
        if (tag.startsWith("ENCHANTMENT_")) {
            String rest = tag.substring("ENCHANTMENT_".length());
            String level = "";
            int u = rest.lastIndexOf('_');
            if (u > 0 && rest.substring(u + 1).matches("\\d+")) {
                level = roman(Integer.parseInt(rest.substring(u + 1)));
                rest = rest.substring(0, u);
            }
            if (rest.startsWith("ULTIMATE_")) rest = rest.substring("ULTIMATE_".length());
            return (title(rest) + " " + level).trim();
        }
        return title(tag);
    }

    /** FOO_BAR:2 / FOO_BAR -> "Foo Bar". */
    static String title(String s) {
        String[] parts = s.toLowerCase(Locale.ROOT).replace(':', ' ').split("[_ ]");
        StringBuilder b = new StringBuilder();
        for (String w : parts) {
            if (w.isBlank()) continue;
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return b.toString().trim();
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    static String roman(int n) { return (n >= 0 && n < ROMAN.length) ? ROMAN[n] : String.valueOf(n); }

    /** Strip Minecraft color codes and trim. */
    static String clean(String s) { return s.replaceAll("(?i)§[0-9a-fk-or]", "").trim(); }
}
