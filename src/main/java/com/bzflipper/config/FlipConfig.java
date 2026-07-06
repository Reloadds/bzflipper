package com.bzflipper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loaded from / saved to  .minecraft/config/bzflipper.json
 */
public class FlipConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- Behaviour tuning ----
    /** Ticks between macro actions. 20 ticks = 1 second. Higher = slower & more human. */
    public int actionDelayTicks = 12;

    /** Random extra 0..N ticks added to each delay so timing isn't robotic. */
    public int actionJitterTicks = 8;

    /** Bazaar sell tax fraction used in margin math (0.0125 = 1.25%). */
    public double taxFraction = 0.0125;

    /** How many concurrent BUY orders to keep working at once (Bazaar allows up to 14 total). */
    public int maxOpenOrders = 6;

    /**
     * If true, place orders at our exact computed price (top buy + 0.1 / lowest
     * sell - 0.1) by typing into the "Custom Price" sign popup, instead of relying
     * on a preset best-price button. The portfolio flipper always uses custom
     * prices/amounts regardless of this flag.
     */
    public boolean useCustomPrice = false;

    // ---- Purse-aware spending ----
    /** Never spend the purse below this many coins. */
    public double coinReserve = 1_000_000;

    /** Fraction of spendable purse (purse - reserve) to commit to a single order. */
    public double orderBudgetFraction = 0.20;

    /** Hard cap on units per order (Bazaar's own limit is 71,680). */
    public int maxUnitsPerOrder = 71_680;

    // ---- Auto flip sourcing (Hypixel Bazaar API) ----
    /** If true, pick the best flips live from the API instead of the fixed targets list. */
    public boolean useApiFlips = true;

    /** Seconds between API refreshes (min 15). */
    public int apiRefreshSeconds = 60;

    /** Minimum net margin (after tax) for an API-sourced flip. */
    public double apiMinMargin = 0.03;

    /** Minimum weekly volume (both sides) for liquidity — avoids dead items. */
    public double apiMinWeeklyVolume = 500_000;

    /** Skip API items whose unit buy price exceeds this (0 = no cap). Keeps orders affordable. */
    public double apiMaxUnitPrice = 0;

    /** If true, the macro only navigates + reads prices and never places orders. */
    public boolean dryRun = true;

    /** Items the macro may flip. */
    public List<FlipTarget> targets = new ArrayList<>();

    public FlipConfig() {
        // Sensible starter targets — EDIT THESE. Names must match the Bazaar exactly.
        targets.add(new FlipTarget("Farming", "Enchanted Cactus Green", 0.04));
        targets.add(new FlipTarget("Mining", "Enchanted Redstone", 0.04));
    }

    // ---- Persistence ----

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("bzflipper.json");
    }

    public static FlipConfig load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                String json = Files.readString(p);
                FlipConfig cfg = GSON.fromJson(json, FlipConfig.class);
                if (cfg != null) return cfg;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[bzflipper] Failed to load config, using defaults: " + e.getMessage());
        }
        FlipConfig cfg = new FlipConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[bzflipper] Failed to save config: " + e.getMessage());
        }
    }
}
