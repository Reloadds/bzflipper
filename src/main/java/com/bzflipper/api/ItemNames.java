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

    /** Load id -> name from the resources endpoint JSON root. */
    public static void load(JsonObject resourcesRoot) {
        JsonArray items = resourcesRoot.getAsJsonArray("items");
        if (items == null) return;
        Map<String, String> m = new HashMap<>();
        for (JsonElement el : items) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("id") && o.has("name")) {
                m.put(o.get("id").getAsString(), clean(o.get("name").getAsString()));
            }
        }
        MAP = m;
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
