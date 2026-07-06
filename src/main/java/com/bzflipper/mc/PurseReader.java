package com.bzflipper.mc;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the player's purse (coins) from the SkyBlock sidebar so the flipper
 * never commits more than it can afford. Returns NaN if it can't be found (e.g.
 * not on SkyBlock, or the line wording changed).
 */
public final class PurseReader {

    private PurseReader() {}

    // "Purse: 1,234,567" or "Piggy: 1.2M" etc.
    private static final Pattern PURSE = Pattern.compile(
            "(?:purse|piggy)\\s*:?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*([kmb])?", Pattern.CASE_INSENSITIVE);

    public static double readPurse(MinecraftClient mc) {
        for (String line : ScoreboardReader.sidebarLines(mc)) {
            Matcher m = PURSE.matcher(line);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                String suffix = m.group(2);
                if (suffix != null) {
                    switch (suffix.toLowerCase(Locale.ROOT)) {
                        case "k" -> v *= 1_000d;
                        case "m" -> v *= 1_000_000d;
                        case "b" -> v *= 1_000_000_000d;
                        default -> { }
                    }
                }
                return v;
            }
        }
        return Double.NaN;
    }
}
