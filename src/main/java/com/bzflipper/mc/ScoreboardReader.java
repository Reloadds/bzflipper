package com.bzflipper.mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the SkyBlock sidebar (scoreboard) as plain text lines. SkyBlock renders
 * each line as a team prefix + suffix on a hidden entry, so we reconstruct the
 * visible text by decorating each entry with its team.
 *
 * MAPPING NOTE: scoreboard APIs shift between versions. If this fails to compile,
 * the likely culprits are getScoreboardEntries / getScoreHolderTeam names.
 */
public final class ScoreboardReader {

    private ScoreboardReader() {}

    public static List<String> sidebarLines(MinecraftClient mc) {
        List<String> out = new ArrayList<>();
        if (mc.world == null) return out;
        try {
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return out;

            for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
                String owner = entry.owner();
                Team team = sb.getScoreHolderTeam(owner);
                String line = owner;
                if (team != null) {
                    line = team.getPrefix().getString() + owner + team.getSuffix().getString();
                }
                out.add(stripFormatting(line));
            }
        } catch (Throwable t) {
            System.err.println("[bzflipper] scoreboard read failed: " + t);
        }
        return out;
    }

    /** The sidebar title line (usually "SKYBLOCK"), or "" if unavailable. */
    public static String title(MinecraftClient mc) {
        if (mc.world == null) return "";
        ScoreboardObjective obj = mc.world.getScoreboard()
                .getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        return obj == null ? "" : stripFormatting(obj.getDisplayName().getString());
    }

    private static String stripFormatting(String s) {
        return s.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
    }
}
