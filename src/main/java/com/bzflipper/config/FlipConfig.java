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
    public int actionDelayTicks = 18;

    /** Random extra 0..N ticks added to each delay so timing isn't robotic. */
    public int actionJitterTicks = 12;

    /** Extra pause (ticks) after placing an order before starting the next one. */
    public int orderCooldownTicks = 40;

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
    /** Never spend the purse below this many coins. Raise it to keep a buffer. */
    public double coinReserve = 0;

    /** Safety cap: no single order may exceed this fraction of spendable purse.
     *  Capital is otherwise split evenly across the open order slots. */
    public double orderBudgetFraction = 0.50;

    /** Hard cap on units per order (Bazaar's own limit is 71,680). */
    public int maxUnitsPerOrder = 71_680;

    /**
     * Size each order to at most this fraction of the item's HOURLY volume, so
     * orders on liquid (high-demand) items are large but still fill quickly.
     */
    public double orderVolumeFraction = 0.5;

    // ---- Auto flip sourcing (Hypixel Bazaar API) ----
    /** If true, pick the best flips live from the API instead of the fixed targets list. */
    public boolean useApiFlips = true;

    /** Seconds between API refreshes (min 15). */
    public int apiRefreshSeconds = 60;

    /** Minimum net margin (after tax) for an API-sourced flip. */
    public double apiMinMargin = 0.03;

    /** Maximum net margin — anything above is almost always an illiquid/manipulation trap. */
    public double apiMaxMargin = 0.30;

    /** Minimum weekly volume (both sides) for liquidity — avoids dead items. */
    public double apiMinWeeklyVolume = 500_000;

    /** Skip API items whose unit buy price exceeds this (0 = no cap). Keeps orders affordable. */
    public double apiMaxUnitPrice = 0;

    // ---- Anti-manipulation guards ----
    /**
     * Max allowed price gap between the #1 and #2 entries in the order book.
     * A lone top order far from the rest of the book is a spoof/manipulation.
     */
    public double apiMaxTopGap = 0.15;

    // ---- Claiming / relisting behaviour ----
    /** Claim a buy order's goods once at least this fraction is filled (1.0 = wait for full). */
    public double partialClaimFraction = 0.15;

    /** After this many relists on one order, stop fighting: blacklist the item for a while. */
    public int maxRelistsPerOrder = 6;

    /** How long a relist-war item stays blacklisted (minutes). */
    public int blacklistMinutes = 30;

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
