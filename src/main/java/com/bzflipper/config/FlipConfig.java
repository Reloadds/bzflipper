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

    /** How many buy-order slots to keep working at once (v0.1 uses 1). */
    public int maxOpenOrders = 1;

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
