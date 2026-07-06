package com.bzflipper.mc;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the player's purse (coins) from the SkyBlock sidebar.
 *
 * Robust against stray formatting/characters inside the number (which was
 * truncating "26,022,906" to "26,022"): we take the first whitespace-delimited
 * token after "Purse"/"Piggy" that contains a digit, strip everything except
 * digits/decimal, and honor a k/m/b suffix. Returns NaN if not found.
 */
public final class PurseReader {

    private PurseReader() {}

    private static volatile String lastRawLine = null;
    public static String lastRawLine() { return lastRawLine; }

    private static final Pattern NUM = Pattern.compile("[0-9][0-9.]*");

    public static double readPurse(MinecraftClient mc) {
        for (String line : ScoreboardReader.sidebarLines(mc)) {
            String lc = line.toLowerCase(Locale.ROOT);
            int i = lc.indexOf("purse");
            if (i < 0) i = lc.indexOf("piggy");
            if (i < 0) continue;

            lastRawLine = line;
            for (String tok : line.substring(i).split("\\s+")) {
                String low = tok.toLowerCase(Locale.ROOT);
                if (low.chars().noneMatch(Character::isDigit)) continue;

                double mult = low.contains("k") ? 1e3 : low.contains("m") ? 1e6 : low.contains("b") ? 1e9 : 1;
                Matcher m = NUM.matcher(tok.replaceAll("[^0-9.]", ""));
                if (!m.find()) continue;
                try {
                    return Double.parseDouble(m.group()) * mult;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Double.NaN;
    }
}
