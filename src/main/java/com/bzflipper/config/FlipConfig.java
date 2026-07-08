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
    /** Ticks between macro actions (claim/sell/relist/navigate). 20 ticks = 1s. */
    public int actionDelayTicks = 3;

    /** Random extra 0..N ticks added to each delay so timing isn't robotic. */
    public int actionJitterTicks = 2;

    /** Deliberate EXTRA pace (ticks) between placing brand-NEW buy orders only —
     *  relists, claims and sells are never slowed by this. 0 = off (the default):
     *  the multi-step order flow already spaces new orders ~1.5s apart, so the
     *  book fills briskly. Raise it only if you want a bigger breather. */
    public int orderCooldownTicks = 0;

    /** Bazaar sell tax fraction used in margin math (auto-detected in-game). */
    public double taxFraction = 0.0125;

    /** Never list a sell offer below cost — if the competitive price would lose
     *  money vs what we paid, hold the line at a profitable price instead. */
    public boolean neverSellAtLoss = true;

    /** Minimum net profit (fraction of buy cost, after tax) to require when the
     *  loss-guard kicks in. 0 = break-even floor; 0.02 = insist on +2%. */
    public double minSellMargin = 0.0;

    /** Deprecated — concurrency now tracks your real Bazaar order limit directly
     *  (it fills the whole book, 14 base / 21 / 28, keeping one slot free for a
     *  freshly-filled buy to list its sell). Kept only so old configs still parse. */
    public int maxOpenOrders = 14;

    /** Total order-slot cap (buys + sells). Base 14; Bazaar Flipper perk raises to
     *  21/28. Auto-learned from the "maximum of N Bazaar orders" message. */
    public int maxBazaarOrders = 14;

    /** When the daily coin limit is hit, pause creating new orders for this long
     *  (it resets at server midnight); claimed goods exit via instasell meanwhile. */
    public int dailyLimitCooldownMinutes = 60;

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

    /** Cap orders so claimed items fit in inventory (avoids overflow to stash).
     *  Essences/shards bypass inventory and are never capped. */
    public boolean capByInventory = true;

    /** Keep this many inventory slots free as a buffer when sizing by inventory. */
    public int inventoryBuffer = 3;

    /** On startup / each pass, list ANY bazaar-sellable item found in your inventory
     *  (not just ones the macro bought) — clears leftovers and keeps space free. */
    public boolean sellAllBazaarItems = true;

    /**
     * Size each order to at most this fraction of the item's HOURLY volume, so
     * orders on liquid (high-demand) items are large but still fill quickly.
     */
    public double orderVolumeFraction = 0.5;

    /**
     * Skip items whose volume can't absorb at least this many coins per order —
     * an ABSOLUTE floor, so a bigger purse never reduces how many items qualify.
     * Filters out genuinely thin items without choking deployment. 0 = disable.
     */
    public double minOrderValue = 500_000;

    // ---- Auto flip sourcing (Hypixel Bazaar API) ----
    /** If true, pick the best flips live from the API instead of the fixed targets list. */
    public boolean useApiFlips = true;

    /** Seconds between API refreshes (min 10). Faster = quicker undercut reaction. */
    public int apiRefreshSeconds = 20;

    /** Minimum net margin (after tax) for an API-sourced flip. */
    public double apiMinMargin = 0.03;

    /** Maximum net margin — anything above is almost always an illiquid/manipulation trap. */
    public double apiMaxMargin = 0.30;

    // ---- Advanced ranking / risk (quant model) ----
    /** Volume weight in ranking: score = profitPerUnit × volume^β. 1 = pure
     *  throughput, <1 tilts toward margin/ROI (better when purse-limited). */
    public double rankVolumeBeta = 0.85;

    /** Extra margin demanded per unit of price volatility σ:
     *  requiredMargin = apiMinMargin + volatilityLambda·σ. Higher = more cautious. */
    public double volatilityLambda = 0.6;

    /** Fractional-Kelly position sizing: N ∝ bankroll·min(1, margin/σ²)·kellyFraction.
     *  0 disables Kelly (fall back to purse/volume/inventory caps only). */
    public double kellyFraction = 0.25;

    /** Skip an item whose realized-efficiency EMA (realized ÷ quoted profit) drops
     *  below this — it's chronically contested/manipulated. */
    public double minEfficiency = 0.35;

    /** Momentum weight: score ×= clamp(1 + trendWeight·trend). Prefers items
     *  trending UP (our sell fills into a rising market). 0 = ignore trend. */
    public double trendWeight = 0.6;

    /** Skip items mid-crash: if the price fell more than this fraction over the
     *  rolling window, don't buy into the drop (protects the sell side). */
    public double crashFilter = 0.08;

    /** Fraction of an item's volume we assume we capture, used to ESTIMATE fill
     *  rate before we've measured it live. Once measured, the real rate is used. */
    public double captureFraction = 0.30;

    // ---- Opportunity-cost capital recycling (#2) ----
    /** Recycle capital stuck in a non-filling sell offer by exiting it, so the
     *  coins move to higher-velocity flips. Off by default — it CAN realize a
     *  small loss (bounded by maxExitLossFraction). */
    public boolean freeStuckCapital = false;

    /** How long a barely-filling sell offer may sit before it's recycle-eligible. */
    public int maxHoldMinutes = 60;

    /** Max acceptable loss (fraction of cost) when recycling stuck capital. */
    public double maxExitLossFraction = 0.02;

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

    /** Don't claim a partial fill until at least this many units are ready — avoids
     *  tiny claims / micro sell offers. Small or stalled orders are exempt so they
     *  never get stranded; full fills always claim. */
    public int minClaimUnits = 15;

    /** Claim a partial anyway after it's been claimable this long, so a stalled
     *  order below the threshold never gets stranded. */
    public int claimGraceSeconds = 45;

    /** Force-refresh the Manage Orders screen every N seconds so fresh fills show
     *  up (Hypixel only updates the grid when the container is re-opened). */
    public int manageRefreshSeconds = 8;

    /** After this many relists on one order, stop fighting: blacklist the item for a while. */
    public int maxRelistsPerOrder = 6;

    /** How long a relist-war item stays blacklisted (minutes). */
    public int blacklistMinutes = 30;

    /** Brief skip (minutes) when an item's LIVE spread reads momentarily too thin
     *  at buy time — it usually widens back fast, so don't bench it for the full
     *  window (only a suspiciously-WIDE spread gets the long bench). */
    public int marginSkipMinutes = 3;

    /** Bench time for items that lose money or capture too little of their quoted
     *  margin — temporarily blacklisted, then given a fresh chance. */
    public int badItemBlacklistMinutes = 45;

    // ---- Booster Cookie automation ----
    /** Keep the Booster Cookie buff alive automatically (it cuts bazaar tax). */
    public boolean autoCookie = true;

    /** Renew when remaining buff time drops to this many days. */
    public double cookieRenewDays = 1.0;

    /** Safety cap: never instabuy a cookie above this price (0 = no cap). */
    public double cookieMaxPrice = 25_000_000;

    /** Hours between cookie-buff checks. */
    public int cookieCheckHours = 6;

    // ---- Web dashboard (localhost) ----
    /** Serve a live dashboard at http://localhost:{webPort} (localhost-only). */
    public boolean webDashboard = true;

    /** Dashboard port. */
    public int webPort = 7654;

    /** After this many relists on one item, price aggressively using the Bazaar's
     *  "5%/10% of spread" presets to jump the queue and end +0.1 wars. */
    public int aggressiveAfterRelists = 2;

    /** Cancel a buy order that's still 0% filled after this many minutes (dead
     *  capital) and redeploy the coins elsewhere. */
    public int buyStallMinutes = 10;

    /** If true, the macro only navigates + reads prices and never places orders.
     *  volatile: the web dashboard toggles this from its HTTP thread; the game
     *  tick loop must see the change promptly and safely. */
    public volatile boolean dryRun = true;

    // ---- Auto-reconnect ----
    /** Automatically rejoin the server after ANY involuntary disconnect (kick,
     *  timeout, "you are lagging", server restart, failed transfer). A deliberate
     *  quit-to-title is never touched, so you can always stop by hand. */
    public boolean autoReconnect = true;

    /** Seconds before the FIRST reconnect attempt — your window to cancel by
     *  clicking "Back to title". Repeated failures back off from here. */
    public int reconnectDelaySeconds = 5;

    /** Backoff ceiling (seconds) between repeated failed reconnects. */
    public int reconnectMaxDelaySeconds = 60;

    /** Give up after this many consecutive failed attempts. 0 = never give up
     *  (keep trying forever, one attempt per capped-backoff interval). */
    public int reconnectMaxAttempts = 0;

    // ---- Hands-free startup ----
    /** On Hypixel login: auto /skyblock → wait → /is → wait → start the flipper. */
    public boolean autoStart = true;

    /** Seconds to wait after reaching SkyBlock, and again after the island world loads. */
    public int autoDelaySeconds = 8;

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
                if (cfg != null) {
                    // Migrate the legacy 30-tick new-order cooldown (never chosen
                    // deliberately — it just slowed the book from filling) to off.
                    if (cfg.orderCooldownTicks == 30) { cfg.orderCooldownTicks = 0; cfg.save(); }
                    return cfg;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[bzflipper] Failed to load config, using defaults: " + e.getMessage());
        }
        FlipConfig cfg = new FlipConfig();
        cfg.save();
        return cfg;
    }

    /** synchronized: the dashboard's HTTP thread and the game thread can both save;
     *  serialize the writes so a concurrent save can't interleave/corrupt the file. */
    public synchronized void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[bzflipper] Failed to save config: " + e.getMessage());
        }
    }
}
