package com.bzflipper.mc;

import com.bzflipper.api.BazaarApi;
import com.bzflipper.api.FlipCandidate;
import com.bzflipper.api.ItemNames;
import com.bzflipper.config.FlipConfig;
import com.bzflipper.config.FlipTarget;
import com.bzflipper.core.BazaarStrings;
import com.bzflipper.core.Keys;
import com.bzflipper.core.PriceMath;
import com.bzflipper.mc.OrderParser.ParsedOrder;
import com.bzflipper.track.ProfitTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v0.5 — full-loop portfolio flipper.
 *
 * Every planning pass parses the Manage Orders grid into structured orders
 * (side, price, amount, fill %) and takes exactly ONE action, in priority order:
 *
 *   1. CLAIM any filled order (buy → queue its items for selling; sell → profit)
 *   2. RELIST any order beaten on price — detected by EXACT math: our recorded
 *      price vs the live top-of-book from the Hypixel API (no tooltip guessing)
 *   3. SELL queued claimed items (competitive price read fresh at listing time)
 *   4. BUY the best-ranked flip we don't already hold (ranked by expected
 *      profit throughput = net profit/unit × liquidity; sized by purse & volume)
 *
 * State recovery: orders found in the grid that we don't know about are adopted
 * (with their price parsed from lore), so a restart mid-flight picks up cleanly.
 */
public class BazaarMacro {

    public enum Phase {
        IDLE, PLAN, NAV_SEARCH, WAIT_SIGN,
        BUY_OPEN, BUY_AMOUNT, BUY_PRICE, BUY_CONFIRM,
        SELL_OPEN, SELL_AMOUNT, SELL_PRICE, SELL_CONFIRM,
        SELL_INSTANT, CANCEL, PICKUP_STASH,
        COOKIE_CHECK, COOKIE_INSTABUY, COOKIE_AMOUNT, COOKIE_MOVE, COOKIE_USE,
        COOKIE_CONFIRM, COOKIE_VERIFY
    }

    private static final int STUCK_LIMIT = 40;
    /** Ignore price differences below half a tick (float noise). */
    private static final double EPS = PriceMath.TICK / 2;
    /** Don't prune an order from memory until it's had time to appear in the grid. */
    private static final long PRUNE_AGE_MS = 90_000;

    /** COOKIE_CHECK/COOKIE_USE bypass checkStuck(), so give them their own timeout
     *  (~10s) — enough for a couple of /sbmenu attempts, short enough that a stuck
     *  menu can never wedge the whole bot. */
    private static final int COOKIE_STUCK_LIMIT = 200;

    /** Safety net for the chat/sidebar restart pause: if we latched "server
     *  closing" but no restart actually happened (no world change) within this
     *  window and the sidebar no longer shows it, auto-resume instead of freezing. */
    private static final long SERVER_CLOSING_MAX_MS = 10 * 60_000L;

    private int orderLimit;                 // total slots (learned from chat)
    private long dailyLimitUntil = 0;       // pause new orders until this time
    private boolean serverClosing = false;  // Hypixel restart in progress → pause
    private long serverClosingSince = 0;    // when the restart pause latched (for the timeout above)
    private int cookiePhaseTicks = 0;       // stuck-timeout counter for the two early-return cookie phases
    private Object lastWorld = null;         // detect world change (rejoin)
    private long lastManageRefresh = 0;      // periodic Manage re-open to catch new fills
    private final Map<String, Long> firstClaimable = new HashMap<>();  // stall-grace timing
    private final Map<String, Integer> claimReadyMark = new HashMap<>();  // last-seen claimable count (progress detection)
    private String mergingItem = null;       // item whose duplicate sell offers we're combining
    private boolean inventoryFull = false;   // server said "no space" → sell before claiming
    private boolean stashPending = false;    // materials went to the stash → recover them

    // Booster Cookie refresh guard.
    private long nextCookieCheck = 0;
    private int cookieCmdCooldown = 0;
    public volatile String cookieStatus = "?";
    /** Live remaining buff time (ms) from the tab-list footer: >0 active, 0 expired,
     *  -1 unknown/unreadable. -1 is NEVER treated as safe (#4). */
    private volatile long cookieRemainMs = -1;
    /** True while a refresh cycle is in progress (drives HUD, panic teardown, and
     *  the top-of-cycle gate so we don't re-trigger while already refreshing). */
    private volatile boolean cookieRefreshActive = false;
    /** Two-stage consume tracking (#3): 0 idle, 1 = used cookie / awaiting confirm
     *  GUI, 2 = confirm clicked / awaiting timer re-read. */
    private volatile int cookieStage = 0;
    private int cookieRetries = 0;
    private long cookieBeforeMs = -1;          // timer read just before the consume (#3 verify)
    private int cookiePrevSelected = -1;       // hotbar slot to restore after consume (#12)
    private int cookieHotbarSlot = -1;         // where we placed the cookie (0–8)
    private boolean cookieSlotSelected = false; // paced two-step: select, wait, THEN use (#12)
    private int cookieVerifyTicks = 0;         // grace for the footer to catch up after consume
    private long lastFooterOkAt = 0;           // last definite footer read (for the #4 unknown fallback)
    /** Non-null = hard-stopped on a cookie failure; shown on the HUD. */
    public volatile String cookieError = null;

    // Adaptive margin controller.
    private double baseMinMargin;              // the configured floor, captured at start
    private long lastMarginAdjust = 0;

    /** Canonical item key (shared with OrderParser/BazaarApi). */
    private static String key(String s) { return Keys.norm(s); }

    /** Is this item already queued for sale? KEY-based — the same item can arrive
     *  under differently-formatted names (grid vs API vs inventory sweep), and an
     *  exact-string contains() would queue it twice. */
    private boolean queuedForSell(String item) {
        String k = key(item);
        return pendingSells.stream().anyMatch(s -> key(s).equals(k));
    }

    /** Remove an item from the sell queue by KEY (name forms differ across grid /
     *  API / inventory) — an exact removeFirstOccurrence could miss it and leave a
     *  ghost entry that re-lists a duplicate offer. */
    private void dequeueSell(String item) {
        String k = key(item);
        pendingSells.removeIf(s -> key(s).equals(k));
    }

    private final FlipConfig config;
    private final BazaarApi api;
    private final ProfitTracker tracker;

    private boolean enabled = false;
    private Phase phase = Phase.IDLE;
    private int delayTimer = 0;
    private int openCooldown = 0;
    private int navCooldown = 0;
    private int buyCooldown = 0;

    private Phase lastPhase = Phase.IDLE;
    private int stuckTicks = 0;

    // Sign popup queue.
    private String pendingSignText = null;
    private Phase phaseAfterSign = Phase.IDLE;

    // Navigation + active-operation context.
    private String navItem = null;
    private Phase navAfter = Phase.IDLE;
    private String activeItem = null;
    private double ourBuyPrice = Double.NaN, ourSellPrice = Double.NaN;
    private int activeAmount = 0;
    private double activeHourlyVol = Double.MAX_VALUE;
    private int activeStackSize = 64;         // for inventory-fit sizing
    private boolean activeBypassInv = false;  // essence bypasses inventory (shards do NOT)
    private double activeVolatility = 0;      // σ of the item being bought (for Kelly)
    private double activeMargin = 0;          // quoted margin of the item being bought

    // Cancel context (which side we're cancelling and why).
    private boolean cancelIsBuy = true;
    private String cancelItem = null;
    private int cancelAmount = 0;
    private boolean cancelBailInstasell = false;  // sell lost its relist war → instasell exit
    private boolean cancelSilent = false;         // duplicate merge: drop quietly, no blacklist

    // Bail-out: goods to dump via "Sell Instantly" (guaranteed immediate exit).
    private String pendingInstasell = null;
    private int pendingInstasellAmount = 0;

    /** Items claimed from filled buy orders, waiting to be listed for sale. */
    private final Deque<String> pendingSells = new ArrayDeque<>();
    private final Map<String, Integer> pendingSellAmounts = new HashMap<>();

    /** Our book: what we believe we have open, keyed by canonical item key. */
    private static final class OrderInfo {
        String name = "";           // display name (needed for /bz navigation)
        double buyPrice = Double.NaN;
        double sellPrice = Double.NaN;
        int amount;
        int claimedSoFar = 0;       // units already claimed from a partially-filled buy
        int soldSoFar = 0;          // units already sold+claimed from the sell offer
        double quotedMargin = Double.NaN;   // margin the API quoted when we bought (for efficiency)
        long placedAt = System.currentTimeMillis();
    }
    // ConcurrentHashMap: the web dashboard reads this (projectedProfit) from its
    // HTTP thread while the game thread mutates it — a plain HashMap CMEs.
    private final Map<String, OrderInfo> orders = new java.util.concurrent.ConcurrentHashMap<>();
    /** Realized-efficiency EMA per item (realized ÷ quoted profit); default 1.0. */
    private final Map<String, Double> efficiency = new HashMap<>();
    /** All-time realized profit per item (for the session report best/worst). */
    private final Map<String, Double> itemProfit = new HashMap<>();
    // ---- Auto-meta learner (persisted): lifetime flip count + a durable, self-
    //      updating avoid-list keyed by item. The bot's accumulated "meta". ----
    private final Map<String, Integer> itemFlips = new java.util.concurrent.ConcurrentHashMap<>();
    /** Item key → timestamp until which it's avoided (probation-refreshed). Read by
     *  the dashboard off-thread, so ConcurrentHashMap. */
    private final Map<String, Long> metaAvoidUntil = new java.util.concurrent.ConcurrentHashMap<>();
    // ---- Session breakdown (reset each start()) — powers the report's per-item
    //      CLAIMED / SOLD / PROFIT / PPI table. Keyed by display name.
    //      ConcurrentHashMap: the dashboard's /report endpoint reads these off the
    //      HTTP thread while the game thread accumulates them. ----
    private final Map<String, Integer> sessClaimedUnits = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Double>  sessClaimedCost  = new java.util.concurrent.ConcurrentHashMap<>();  // units × buy price
    private final Map<String, Integer> sessSoldUnits    = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Double>  sessSoldRevenue  = new java.util.concurrent.ConcurrentHashMap<>();  // units × net sell price
    /** Empirical sell fill-rate (units/hr EMA) per item — the measured throughput.
     *  ConcurrentHashMap: read by the dashboard (rank/order rows) off the game thread. */
    private final Map<String, Double> fillRate = new java.util.concurrent.ConcurrentHashMap<>();
    /** Empirical BUY-order fill-rate (units/hr EMA) per item — the other half of
     *  the round trip; ranking uses the series velocity of both legs. */
    private final Map<String, Double> buyFillRate = new java.util.concurrent.ConcurrentHashMap<>();
    /** Last fill observation per side+item ("B|key"/"S|key"): [epochMs, filledUnits]. */
    private final Map<String, double[]> fillObs = new HashMap<>();
    /** Learned capture: EMA of (our measured fill rate ÷ market leg flow) across
     *  every measurement — this account's real share of an item's volume. Fallback
     *  for unmeasured items once seeded (better than the static captureFraction
     *  guess). volatile: exposed on the dashboard off-thread. */
    private volatile double learnedCapture = 0;
    private int learnedCaptureN = 0;
    /** Canonical keys of everything we've ever bought — so leftover goods get
     *  sold even if their order was pruned/forgotten. Personal items are never here. */
    private final Set<String> boughtItems = new HashSet<>();

    /** Relist-war protection: per-item relist counts and temporary blacklist. */
    private final Map<String, Integer> relistCounts = new HashMap<>();
    // ConcurrentHashMap: read by the dashboard (benchedList) off the game thread.
    private final Map<String, Long> blacklistUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean cancelRebuy = true;   // whether pCancel should relist the buy

    // ---- Persistence (survives relogs: leftover goods get re-listed) ----
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .serializeSpecialFloatingPointValues().create();
    private boolean stateDirty = false;
    private long lastStateSave = 0;

    // ---- HUD/dashboard-exposed ----
    public volatile String statusLine = "idle";
    public volatile double allTimeProfit = 0;
    /** Rolling log for the web dashboard's Chat tab. */
    public final java.util.concurrent.ConcurrentLinkedDeque<String> logLines =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Dashboard → game-thread requests (handled in BzFlipper's tick). */
    public final java.util.concurrent.atomic.AtomicBoolean webToggle =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    public final java.util.concurrent.atomic.AtomicBoolean webPanic =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    public volatile double purse = Double.NaN;
    public volatile int buyCount = 0, sellCount = 0;
    public volatile int ordersPlaced = 0, buysFilled = 0, sellsFilled = 0, flipsCompleted = 0;
    public volatile String topCandidate = "—";
    public volatile List<ParsedOrder> lastOrders = List.of();
    private String lastNote = "";

    /** One row of the live ranking — the dashboard's window into the picker.
     *  Built by the SAME scoring code pickNextItem uses, so what you see in
     *  dry-run is exactly what the picker acts on (this is how ranking changes
     *  get verified before going live). */
    public record RankRow(String item, double ppu, double buyRate, boolean buyMeasured,
                          double sellRate, boolean sellMeasured, double velocity,
                          double eff, double cph, String state) {}
    public volatile List<RankRow> ranking = List.of();
    private long lastRankingBuild = 0;

    /** Aggregate market liquidity (coins/hr through the top candidates), sampled
     *  to the state file so a future regime-detection feature has a baseline to
     *  calibrate against. [epochMs, coinsPerHour] */
    final java.util.List<double[]> liquiditySamples = new java.util.ArrayList<>();
    private long lastLiquiditySample = 0;

    public BazaarMacro(FlipConfig config, BazaarApi api, ProfitTracker tracker) {
        this.config = config;
        this.api = api;
        this.tracker = tracker;
        this.orderLimit = Math.max(1, config.maxBazaarOrders);
        loadState();
    }

    /** Parse Hypixel Bazaar limit messages from chat (authoritative). */
    public void onChatMessage(String raw) {
        String m = raw.toLowerCase(Locale.ROOT);
        if (m.contains("maximum of") && m.contains("bazaar order")) {
            java.util.regex.Matcher mt =
                    java.util.regex.Pattern.compile("maximum of (\\d+)").matcher(m);
            if (mt.find()) {
                orderLimit = Integer.parseInt(mt.group(1));
                config.maxBazaarOrders = orderLimit;   // remember the real cap
            }
            buyCooldown = Math.max(buyCooldown, 120);   // back off creating for a bit
            note("§ebazaar order slots full (" + orderLimit + ") — waiting for fills");
        } else if (m.contains("daily limit") && m.contains("bazaar")) {
            dailyLimitUntil = System.currentTimeMillis()
                    + config.dailyLimitCooldownMinutes * 60_000L;
            note("§chit daily Bazaar coin limit§r — pausing new orders "
                    + config.dailyLimitCooldownMinutes + "m; exiting via instasell");
        } else if (m.contains("about to restart") || m.contains("server is restarting")
                || m.contains("server closing") || m.contains("restarting in")) {
            if (!serverClosing) { serverClosing = true; serverClosingSince = System.currentTimeMillis(); saveState(); note("§eserver restarting — pausing"); }
        } else if (m.contains("space required to claim") || m.contains("don't have the space")
                || m.contains("inventory is full")) {
            if (!inventoryFull) { inventoryFull = true; note("§einventory full — selling to free space before claiming"); }
            // The click that triggered this was a FAILED claim — undo its bookkeeping.
            rollbackRefusedClaim();
        } else if (m.contains("consumed") && m.contains("booster cookie")) {
            // Chat confirms the consume took — but we STILL re-read the footer timer
            // in COOKIE_VERIFY as the authoritative check (chat can be missed/spoofed).
            note("§acookie consumed — verifying buff…");
            if (phase == Phase.COOKIE_CONFIRM) phase = Phase.COOKIE_VERIFY;
        } else if (m.contains("stashed away")) {
            // A claim overflowed into the stash — stop claiming, sell, then recover.
            inventoryFull = true;
            if (!stashPending) { stashPending = true; note("§ematerials went to stash — will recover after selling"); }
        }
    }

    // ---- state persistence ----

    private static final class PersistState {
        Map<String, OrderInfo> orders = new HashMap<>();
        java.util.List<String> pendingSells = new java.util.ArrayList<>();
        Map<String, Integer> pendingSellAmounts = new HashMap<>();
        Map<String, Long> blacklistUntil = new HashMap<>();
        Set<String> boughtItems = new HashSet<>();
        Map<String, Double> efficiency = new HashMap<>();
        Map<String, Double> itemProfit = new HashMap<>();
        Map<String, Integer> itemFlips = new HashMap<>();
        Map<String, Long> metaAvoidUntil = new HashMap<>();
        Map<String, Double> fillRate = new HashMap<>();
        Map<String, Double> buyFillRate = new HashMap<>();
        double allTimeProfit = 0;
        double learnedCapture = 0;
        int learnedCaptureN = 0;
        /** [epochMs, coins/hr] market-liquidity samples — regime-detection baseline. */
        java.util.List<double[]> liquiditySamples = new java.util.ArrayList<>();
    }

    private java.nio.file.Path statePath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("bzflipper-state.json");
    }

    private void loadState() {
        try {
            java.nio.file.Path p = statePath();
            if (!java.nio.file.Files.exists(p)) return;
            PersistState s = GSON.fromJson(java.nio.file.Files.readString(p), PersistState.class);
            if (s == null) return;
            if (s.orders != null) orders.putAll(s.orders);
            if (s.pendingSells != null) for (String it : s.pendingSells) {
                if (!pendingSells.contains(it)) pendingSells.addLast(it);
            }
            if (s.pendingSellAmounts != null) pendingSellAmounts.putAll(s.pendingSellAmounts);
            if (s.blacklistUntil != null) blacklistUntil.putAll(s.blacklistUntil);
            if (s.boughtItems != null) boughtItems.addAll(s.boughtItems);
            if (s.itemFlips != null) itemFlips.putAll(s.itemFlips);
            if (s.metaAvoidUntil != null) metaAvoidUntil.putAll(s.metaAvoidUntil);
            if (s.efficiency != null) efficiency.putAll(s.efficiency);
            if (s.itemProfit != null) itemProfit.putAll(s.itemProfit);
            // Drop poisoned rates from the old per-pass sampling bug (rates decayed
            // to ~0, ranking those items dead-last forever) — remeasure instead.
            if (s.fillRate != null) s.fillRate.forEach((k, v) -> {
                if (v != null && v >= 1.0) fillRate.put(k, v);
            });
            if (s.buyFillRate != null) s.buyFillRate.forEach((k, v) -> {
                if (v != null && v >= 1.0) buyFillRate.put(k, v);
            });
            learnedCapture = s.learnedCapture;
            learnedCaptureN = s.learnedCaptureN;
            allTimeProfit = s.allTimeProfit;
            if (s.liquiditySamples != null) liquiditySamples.addAll(s.liquiditySamples);
            System.out.println("[bzflipper] restored state: " + orders.size()
                    + " orders, " + pendingSells.size() + " pending sells");
        } catch (Exception e) {
            System.err.println("[bzflipper] state load failed: " + e.getMessage());
        }
    }

    private void saveStateIfDue() {
        long now = System.currentTimeMillis();
        if (!stateDirty || now - lastStateSave < 10_000) return;
        saveState();
    }

    private void saveState() {
        try {
            PersistState s = new PersistState();
            s.orders.putAll(orders);
            s.pendingSells.addAll(pendingSells);
            s.pendingSellAmounts.putAll(pendingSellAmounts);
            s.blacklistUntil.putAll(blacklistUntil);
            s.boughtItems.addAll(boughtItems);
            s.itemFlips.putAll(itemFlips);
            s.metaAvoidUntil.putAll(metaAvoidUntil);
            s.efficiency.putAll(efficiency);
            s.itemProfit.putAll(itemProfit);
            s.fillRate.putAll(fillRate);
            s.buyFillRate.putAll(buyFillRate);
            s.learnedCapture = learnedCapture;
            s.learnedCaptureN = learnedCaptureN;
            s.allTimeProfit = allTimeProfit;
            s.liquiditySamples.addAll(liquiditySamples);
            java.nio.file.Files.writeString(statePath(), GSON.toJson(s));
            writeMetaConfig();
            stateDirty = false;
            lastStateSave = System.currentTimeMillis();
        } catch (Exception e) {
            lastStateSave = System.currentTimeMillis();   // throttle retries even if it failed
            System.err.println("[bzflipper] state save failed: " + e.getMessage());
        }
    }

    /** One row of the learned meta, most-profitable first. */
    private record MetaRow(String item, int flips, double totalProfit, double avgProfit,
                           double efficiency, boolean avoided) {}

    /** Export the accumulated meta to config/bzflipper-meta.json — a human-readable,
     *  ever-growing record of which items earn and which are avoided. This is the
     *  "meta config forever": it persists and sharpens every session. */
    private void writeMetaConfig() {
        long now = System.currentTimeMillis();
        java.util.List<MetaRow> rows = new java.util.ArrayList<>();
        for (var e : itemProfit.entrySet()) {
            String k = e.getKey();
            int flips = itemFlips.getOrDefault(k, 0);
            double avg = flips > 0 ? e.getValue() / flips : 0;
            rows.add(new MetaRow(k, flips, e.getValue(), avg,
                    efficiency.getOrDefault(k, 1.0), metaAvoidUntil.getOrDefault(k, 0L) > now));
        }
        rows.sort((a, b) -> Double.compare(b.totalProfit(), a.totalProfit()));
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("updated", now);
        meta.put("itemsTracked", rows.size());
        meta.put("avoided", rows.stream().filter(MetaRow::avoided).map(MetaRow::item).toList());
        meta.put("items", rows);
        try {
            java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("bzflipper-meta.json"), GSON.toJson(meta));
        } catch (Exception ignored) {
        }
    }

    /** Number of items currently on the learned avoid-list (for HUD/dashboard). */
    public int metaAvoidedCount() {
        long now = System.currentTimeMillis();
        return (int) metaAvoidUntil.values().stream().filter(v -> v > now).count();
    }

    /** Measured sell fill-rate (units/hr EMA) for an item, or 0 if never measured.
     *  Dashboard-safe: fillRate is a ConcurrentHashMap. */
    public double measuredSellRate(String item) {
        Double r = fillRate.get(key(item));
        return r != null ? r : 0;
    }

    /** Measured buy-order fill-rate (units/hr EMA) for an item, or 0. */
    public double measuredBuyRate(String item) {
        Double r = buyFillRate.get(key(item));
        return r != null ? r : 0;
    }

    public boolean isEnabled() { return enabled; }
    public Phase getState()    { return phase; }
    public BazaarApi getApi()  { return api; }
    public ProfitTracker getTracker() { return tracker; }

    public void toggle() { if (enabled) stop("toggled off"); else start(); }

    public void start() {
        enabled = true;
        phase = Phase.PLAN;
        stuckTicks = 0;
        openCooldown = 0;
        serverClosing = false;
        inventoryFull = false;
        stashPending = false;
        baseMinMargin = config.apiMinMargin;   // floor for the adaptive controller
        lastMarginAdjust = System.currentTimeMillis();
        api.setDynMinMargin(baseMinMargin);
        nextCookieCheck = System.currentTimeMillis() + 30_000L;   // check shortly after start
        // Clear any prior cookie refresh/hard-stop so a manual restart re-arms the guard.
        cookieError = null; cookieRefreshActive = false; cookieStage = 0;
        cookieRetries = 0; cookieSlotSelected = false; cookieHotbarSlot = -1; cookiePrevSelected = -1;
        // Fresh session breakdown.
        sessClaimedUnits.clear(); sessClaimedCost.clear();
        sessSoldUnits.clear();    sessSoldRevenue.clear();
        lastManageRefresh = System.currentTimeMillis();   // grid is fresh at start — no bounce
        resetDelay();

        GuiDump.reset();
        MinecraftClient mc = MinecraftClient.getInstance();
        purse = PurseReader.readPurse(mc);
        var cs = api.getCandidates();

        log(config.dryRun ? "started (DRY RUN — plans only, no orders)" : "started (LIVE)");
        log(String.format(Locale.ROOT, "purse=%s  flips=%d  api %ss old%s",
                Double.isNaN(purse) ? "UNKNOWN" : String.format(Locale.ROOT, "%,.0f", purse),
                cs.size(), api.ageSeconds(),
                api.lastError() != null ? "  §cerror=" + api.lastError() + "§r" : ""));
        for (int i = 0; i < Math.min(3, cs.size()); i++) {
            FlipCandidate c = cs.get(i);
            log(String.format(Locale.ROOT, "  #%d %s  %.1f%%  buy %.1f",
                    i + 1, c.displayName, c.margin(config.taxFraction) * 100, c.ourBuyPrice()));
        }
    }

    public void stop(String why) {
        enabled = false;
        phase = Phase.IDLE;
        // #8: if a refresh was mid-flight (incl. the End panic key firing while the
        // confirm popup is open), close any GUI and restore the selected slot so the
        // client is left safe — no held item, no half-open GUI, no dangling popup.
        cookieTeardown(MinecraftClient.getInstance());
        statusLine = "stopped: " + why;
        log("stopped: " + why);
        saveState();
        if (why.contains("toggled") || why.contains("panic")) reportSession();
    }

    /** Compact coin formatter for the report: B/M/K with 2 decimals. */
    private static String bCoins(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (a >= 1e3) return String.format(Locale.ROOT, "%.2fK", v / 1e3);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private record ProfitRow(String item, int claimedUnits, double claimedCost,
                             int soldUnits, double soldRevenue, double profit) {}

    /** The full session breakdown: open buy/sell exposure (from the live grid,
     *  aggregated per item) + a per-item CLAIMED / SOLD / PROFIT / PPI table.
     *  Public so it can also be dumped on demand. */
    public String buildReport() {
        long secs = tracker.elapsedSeconds();

        // Open exposure, aggregated by item: value = units × price/unit.
        Map<String, double[]> buyAgg = new HashMap<>();    // item -> {units, coins}
        Map<String, double[]> sellAgg = new HashMap<>();
        for (ParsedOrder o : lastOrders) {
            double units = o.amount();
            double val = Double.isNaN(o.pricePerUnit()) ? 0 : units * o.pricePerUnit();
            double[] a = (o.buy() ? buyAgg : sellAgg).computeIfAbsent(o.item(), k -> new double[2]);
            a[0] += units; a[1] += val;
        }
        double buyTotal = buyAgg.values().stream().mapToDouble(a -> a[1]).sum();
        double sellTotal = sellAgg.values().stream().mapToDouble(a -> a[1]).sum();

        StringBuilder f = new StringBuilder();
        f.append(String.format(Locale.ROOT, "BUY ORDERS TOTAL : %s coins%n", bCoins(buyTotal)));
        f.append(String.format(Locale.ROOT, "SELL OFFERS TOTAL : %s coins%n", bCoins(sellTotal)));
        f.append(String.format(Locale.ROOT, "OVERALL TOTAL    : %s coins%n", bCoins(buyTotal + sellTotal)));
        // Capital utilization — the metric that exposes idle coins.
        f.append(String.format(Locale.ROOT, "FREE PURSE : %s  ·  DEPLOYED (buys) : %s  ·  UTILIZATION : %.0f%%%n",
                bCoins(Double.isNaN(purse) ? 0 : purse), bCoins(deployedBuyCapital()), utilization() * 100));
        f.append(String.format(Locale.ROOT, "OPEN SLOTS : %d / %d used  ·  META-AVOIDED : %d items (learned)%n%n",
                lastOrders.size(), orderLimit, metaAvoidedCount()));

        f.append("=== BUY ORDERS ===\n");
        buyAgg.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[1], x.getValue()[1]))
                .forEach(e -> f.append(String.format(Locale.ROOT, "🟢 %s — %d× (%s)%n",
                        e.getKey().toUpperCase(Locale.ROOT), (long) e.getValue()[0], bCoins(e.getValue()[1]))));

        f.append("\n=== SELL OFFERS ===\n");
        sellAgg.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[1], x.getValue()[1]))
                .forEach(e -> f.append(String.format(Locale.ROOT, "🔴 %s — %d× (%s)%n",
                        e.getKey().toUpperCase(Locale.ROOT), (long) e.getValue()[0], bCoins(e.getValue()[1]))));

        // Per-item profit table: profit = net sold revenue − buy cost (cashflow),
        // so leftovers sold with no session buy still show as gain (matches how the
        // per-item lines are meant to add up). PPI = profit ÷ units sold.
        java.util.Set<String> items = new java.util.HashSet<>();
        items.addAll(sessClaimedUnits.keySet());
        items.addAll(sessSoldUnits.keySet());
        List<ProfitRow> rows = new java.util.ArrayList<>();
        double totalProfit = 0;
        for (String it : items) {
            int cu = sessClaimedUnits.getOrDefault(it, 0);
            double cc = sessClaimedCost.getOrDefault(it, 0.0);
            int su = sessSoldUnits.getOrDefault(it, 0);
            double sr = sessSoldRevenue.getOrDefault(it, 0.0);
            double p = sr - cc;
            totalProfit += p;
            rows.add(new ProfitRow(it, cu, cc, su, sr, p));
        }
        rows.sort((a, b) -> Double.compare(b.profit(), a.profit()));

        f.append(String.format(Locale.ROOT, "%n=== PROFIT ===%n"));
        f.append(String.format(Locale.ROOT, "TOTAL PROFIT : %s coins%n", bCoins(totalProfit)));
        f.append(String.format(Locale.ROOT, "REALIZED (matched) : %s  ·  %s/hr%n",
                bCoins(tracker.total()), bCoins(tracker.sessionPerHour())));
        f.append(String.format(Locale.ROOT, "SESSION DURATION : %d hours %d minutes%n%n",
                secs / 3600, (secs % 3600) / 60));
        for (ProfitRow r : rows) {
            double ppi = r.soldUnits() > 0 ? r.profit() / r.soldUnits() : 0;
            f.append(String.format(Locale.ROOT,
                    "💰 %s — CLAIMED: %d× (%s) | SOLD: %d× (%s) | PROFIT: %s | PPI: %s%n",
                    r.item().toUpperCase(Locale.ROOT), r.claimedUnits(), bCoins(r.claimedCost()),
                    r.soldUnits(), bCoins(r.soldRevenue()), bCoins(r.profit()), bCoins(ppi)));
        }
        return f.toString();
    }

    /** Write the breakdown to config/bzflipper-report.txt + a concise chat summary. */
    private void reportSession() {
        String report = buildReport();
        try {
            java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("bzflipper-report.txt"), report);
        } catch (Exception ignored) {
        }

        long secs = tracker.elapsedSeconds();
        var top = itemProfit.entrySet().stream()
                .max((a, b) -> Double.compare(a.getValue(), b.getValue()));
        log("§b── session report ──");
        log(String.format(Locale.ROOT, "uptime %dh%dm · profit §a%s§r (%s/hr) · %,.0f in open offers",
                secs / 3600, (secs % 3600) / 60, bCoins(tracker.total()),
                bCoins(tracker.sessionPerHour()), projectedProfit()));
        top.ifPresent(e -> log(String.format(Locale.ROOT, "best: §a%s§r %s",
                bCoins(e.getValue()), e.getKey())));
        log("full breakdown → config/bzflipper-report.txt");
    }

    // ---- main loop ----
    public void onTick(MinecraftClient mc) {
        if (!enabled || mc.player == null) return;
        if (delayTimer > 0) { delayTimer--; return; }
        resetDelay();

        // Server restart handling: pause on the way out; resume after rejoin.
        if (mc.world != lastWorld) { lastWorld = mc.world; serverClosing = false; }
        boolean sidebarClosing = serverClosingNow(mc);
        if (serverClosing || sidebarClosing) {
            if (!serverClosing) {
                serverClosing = true; serverClosingSince = System.currentTimeMillis();
                saveState(); log("§eserver restarting — paused until rejoin");
            }
            // Safety net: if we latched on a chat/sidebar match but no restart ever
            // happened (no world change) and the sidebar no longer shows it, resume
            // after SERVER_CLOSING_MAX_MS instead of freezing until a manual relog.
            if (!sidebarClosing && System.currentTimeMillis() - serverClosingSince > SERVER_CLOSING_MAX_MS) {
                serverClosing = false;
                log("§erestart pause expired with no restart — resuming");
            } else {
                statusLine = "server restarting — paused";
                return;
            }
        }

        if (buyCooldown > 0) buyCooldown--;

        purse = PurseReader.readPurse(mc);
        updateTopCandidate();
        adaptMargin();   // coins/hr controller: tune the margin gate to capacity
        // Cheap, GUI-free buff-timer read every tick — the "top of each flip cycle"
        // read the refresh guard is built on. Runs on the client thread; never
        // couples to the background API fetch thread (#7).
        updateCookieTimer(mc);

        // Sign popups (Custom Amount / Custom Price / search) aren't chest GUIs.
        if (SignFiller.isSignScreen(mc)) {
            if (pendingSignText != null) {
                SignFiller.fill(mc, pendingSignText);
                pendingSignText = null;
                phase = phaseAfterSign;
            }
            return;
        }
        // Stash pickup must NOT happen inside a GUI ("You cannot pick these items
        // up while in an inventory."). Close the bazaar first, then run the command.
        if (phase == Phase.PICKUP_STASH) {
            // closeHandledScreen (NOT setScreen(null)) — it sends the close packet,
            // otherwise the server still thinks a container is open and rejects
            // /pickupstash with "cannot pick these items up while in an inventory".
            if (GuiHelper.openChest(mc) != null) { mc.player.closeHandledScreen(); return; }
            var nh = mc.getNetworkHandler();
            if (nh != null) { nh.sendChatCommand("pickupstash"); log("recovering stashed materials (/pickupstash)"); }
            stashPending = false;
            phase = Phase.PLAN;
            return;
        }

        // Cookie check needs the SkyBlock Menu (/sbmenu), and consuming needs the
        // GUI CLOSED — both handled here, outside the open-bazaar flow. They skip
        // checkStuck(), so guard them with their own timeout: a /sbmenu that never
        // opens (limbo, rate-limit, blocking GUI) must not wedge the whole bot.
        // COOKIE_CHECK (sends /sbmenu), COOKIE_USE (stage 1: use item — needs the GUI
        // CLOSED), and COOKIE_VERIFY (re-read timer — no GUI) all run here, before
        // the open-bazaar gate. A stuck stage counts toward the retry cap (#3) so a
        // wedged /sbmenu or a confirm GUI that never opens can never money-bonfire.
        if (phase == Phase.COOKIE_CHECK || phase == Phase.COOKIE_USE || phase == Phase.COOKIE_VERIFY) {
            if (++cookiePhaseTicks > COOKIE_STUCK_LIMIT) {
                cookiePhaseTicks = 0;
                cookieStageTimedOut(mc, "stage wedged in " + phase);
                return;
            }
            switch (phase) {
                case COOKIE_CHECK  -> pCookieCheck(mc);
                case COOKIE_USE    -> pCookieUse(mc);
                case COOKIE_VERIFY -> pCookieVerify(mc);
                default -> {}
            }
            return;
        }
        cookiePhaseTicks = 0;

        // While a sign popup is pending, the chest may close a moment before the
        // sign opens — do NOT fire /bz into that gap (it cancels the sign).
        // The WAIT_SIGN case below times out if the sign truly never arrives.
        if (GuiHelper.openChest(mc) == null && phase != Phase.WAIT_SIGN) {
            openBazaar(mc);
            return;
        }

        GuiDump.autoDump(mc);   // capture each new Bazaar screen for string tuning
        checkStuck();

        switch (phase) {
            case PLAN         -> pPlan(mc);
            case NAV_SEARCH   -> pNavSearch(mc);
            case WAIT_SIGN    -> {
                statusLine = "waiting for sign…";
                // checkStuck ignores WAIT_SIGN, so time out here: if the sign
                // never opens (lag / dropped packet), recover instead of hanging.
                if (++signWaitTicks > STUCK_LIMIT) {
                    signWaitTicks = 0;
                    pendingSignText = null;
                    log("§esign never opened — recovering");
                    phase = Phase.PLAN;
                }
            }
            case BUY_OPEN     -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BUY_ORDER)) phase = Phase.BUY_AMOUNT; }
            case BUY_AMOUNT   -> pBuyAmount(mc);
            case BUY_PRICE    -> pBuyPrice(mc);
            case BUY_CONFIRM  -> pBuyConfirm(mc);
            // "Create Sell Offer" sells all held items and jumps straight to the
            // price screen — there is NO amount step on the sell side.
            case SELL_OPEN    -> pSellOpen(mc);
            case SELL_AMOUNT  -> phase = Phase.SELL_PRICE;   // defensive (unused)
            case SELL_PRICE   -> pSellPrice(mc);
            case SELL_CONFIRM -> pSellConfirm(mc);
            case SELL_INSTANT -> pInstasell(mc);
            case CANCEL       -> pCancel(mc);
            case PICKUP_STASH -> { /* handled before the openChest check */ }
            case COOKIE_INSTABUY -> pCookieInstabuy(mc);
            case COOKIE_AMOUNT   -> pCookieAmount(mc);
            case COOKIE_MOVE     -> pCookieMove(mc);
            case COOKIE_CONFIRM  -> pCookieConfirm(mc);   // stage 2 (verifies GUI first)
            case COOKIE_CHECK, COOKIE_USE, COOKIE_VERIFY -> { /* handled before the openChest check */ }
            case IDLE         -> { }
        }
        saveStateIfDue();
    }

    // ---- planning: parse grid, take highest-priority action ----

    private void pPlan(MinecraftClient mc) {
        if (!goToManage(mc)) { statusLine = "→ manage orders"; return; }

        List<ParsedOrder> grid = OrderParser.parse(mc);
        lastOrders = grid;
        buyCount = (int) grid.stream().filter(ParsedOrder::buy).count();
        sellCount = grid.size() - buyCount;

        Set<String> invNames = GuiHelper.playerInventoryNames(mc);
        Set<String> invKeys = new HashSet<>();
        for (String n : invNames) invKeys.add(key(n));
        // Break the deadlock: if 'inventory full' is set but there's actually space
        // now, clear it — otherwise it blocks the very claims needed to free space.
        if (inventoryFull && GuiHelper.freeInventorySlots(mc) > config.inventoryBuffer + 1) {
            inventoryFull = false;
        }
        adoptAndPrune(grid, invKeys);
        sweepLeftovers(invNames, grid);
        observeFills(grid);

        boolean slotFree = grid.size() < orderLimit;                       // 14/21/28
        boolean dailyOk = System.currentTimeMillis() >= dailyLimitUntil;   // daily coin limit

        // 0) Booster Cookie refresh guard — top of each flip cycle.
        if (maybeStartCookieRefresh(mc)) return;

        // 1) Claim goods/coins the moment they're claimable — Hypixel marks an
        //    order "Click to claim!" as soon as ANY of it fills, so this claims
        //    partial fills continuously (the order keeps working for the rest).
        for (ParsedOrder o : grid) {
            if (!o.claimable()) continue;
            if (config.dryRun) { note("DRY: would claim " + o.item()); continue; }
            if (o.buy()) {
                // Batch small partials — but never strand a small or stalled order.
                int ready = o.claimAmount() > 0 ? o.claimAmount()
                        : (int) Math.floor(o.amount() * o.filledPct() / 100.0);
                boolean small = o.amount() <= config.minClaimUnits;
                // PROGRESS-AWARE stall grace: the timer means "no NEW fills for
                // claimGraceSeconds", not "N seconds since first claimable". A
                // still-filling order keeps resetting the clock, so slow trickle
                // fills batch up to minClaimUnits instead of being claimed 3 at a
                // time; only a genuinely STOPPED partial is claimed early so it
                // isn't stranded below the threshold.
                long nowMs = System.currentTimeMillis();
                int prevReady = claimReadyMark.getOrDefault(o.key(), -1);
                if (prevReady < 0 || ready > prevReady) {
                    firstClaimable.put(o.key(), nowMs);      // new fills → restart the grace clock
                    claimReadyMark.put(o.key(), ready);
                }
                long firstSeen = firstClaimable.getOrDefault(o.key(), nowMs);
                boolean stalled = nowMs - firstSeen > config.claimGraceSeconds * 1000L;
                if (!o.filled() && !small && !stalled && ready < config.minClaimUnits) {
                    statusLine = "waiting for " + config.minClaimUnits + "× " + o.item() + " (" + ready + " ready)";
                    continue;
                }
                // BRAIN RULE: never claim more of an item we're still holding
                // unsold — list what we have first, then claim the next batch.
                // (Also closes the stale-inventory overflow → stash window.)
                boolean holdingUnsold = pendingSells.stream().anyMatch(s -> key(s).equals(o.key()));
                if (holdingUnsold) { statusLine = "listing " + o.item() + " before claiming more"; continue; }
                // Don't attempt a claim that won't fit ("You don't have the space
                // required to claim that!"). The sell steps free space first.
                // statusLine, not note(): this branch repeats every pass while
                // full — chat gets ONE message when the flag latches, not spam.
                if (!hasSpaceToClaim(mc, o)) {
                    statusLine = "inventory full — listing sells before claiming " + o.item();
                    continue;
                }
            }
            // The grid updates async after a claim click — don't re-click the same
            // item while stale data still shows it claimable (double-claiming
            // double-books the fill).
            if (key(o.item()).equals(lastClaimKey)
                    && System.currentTimeMillis() - lastClaimAt < 4_000) continue;
            statusLine = "claiming " + (o.buy() ? "bought " : "sold ") + o.item();
            if (GuiHelper.clickSlotIndex(mc, o.slot())) {
                lastClaimKey = key(o.item());
                lastClaimAt = System.currentTimeMillis();
                // Force the grid to reopen next idle pass: the just-claimed slot
                // stays "claimable" in the cached grid, and once the 4s cooldown
                // lapses a stale re-click would open the cancel menu while we'd
                // wrongly book another claim. A fresh grid clears that window.
                lastManageRefresh = 0;
                if (o.buy()) onBuyClaimed(o);
                else onSellClaimed(o, grid);
            }
            return; // one action per pass
        }

        // 1.2) Hypixel only updates fill % / "Click to claim!" when the Manage
        //      grid is RE-OPENED. Nothing actionable was claimed above, so on a
        //      timer force a genuine reopen so fresh fills surface and free their
        //      slots. Sits right after CLAIM: a real claim is never delayed, and
        //      a busy relist/merge pass below can never starve it (the old
        //      bottom-of-pass placement is why fills needed a manual refresh).
        if (System.currentTimeMillis() - lastManageRefresh > config.manageRefreshSeconds * 1000L) {
            lastManageRefresh = System.currentTimeMillis();
            statusLine = "refreshing orders…";
            if (!GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK)) openBazaar(mc);
            return;   // reopens fresh via goToManage next pass
        }

        // 1.5) Dump bail-out goods via Sell Instantly (guaranteed exit).
        if (pendingInstasell != null) {
            activeItem = pendingInstasell;
            startNav(pendingInstasell, Phase.SELL_INSTANT);
            return;
        }

        // 1.55) MERGE duplicate sell offers into ONE. Cancel every offer of an item
        //       that has 2+ (refund → inventory); once none remain, step 1.6 lists
        //       the combined held stack as a single offer. Aborts if it won't fit.
        if (mergingItem == null) {
            Map<String, Integer> sellCount = new HashMap<>();
            for (ParsedOrder o : grid) if (!o.buy() && !o.claimable()) sellCount.merge(o.key(), 1, Integer::sum);
            for (ParsedOrder o : grid) {
                if (!o.buy() && !o.claimable() && sellCount.getOrDefault(o.key(), 0) >= 2) {
                    mergingItem = o.item();
                    break;
                }
            }
        }
        if (mergingItem != null) {
            String mk = key(mergingItem);
            List<ParsedOrder> offs = grid.stream()
                    .filter(g -> !g.buy() && g.key().equals(mk) && !g.claimable()).toList();
            if (offs.isEmpty()) {
                mergingItem = null;                       // all cancelled → 1.6 lists the combined stack
            } else if (recentlyCancelled(mergingItem)) {
                statusLine = "merging offers: " + mergingItem;
                return;                                   // wait for the grid to reflect the last cancel
            } else if (config.dryRun || !refundFits(mc, offs.get(0))) {
                mergingItem = null;                       // dry run, or won't fit → leave as-is
            } else {
                ParsedOrder tc = offs.get(0);
                statusLine = "merging sell offers: " + mergingItem;
                cancelIsBuy = false; cancelRebuy = false; cancelBailInstasell = false; cancelSilent = false;
                cancelItem = tc.item(); cancelAmount = remainingUnits(tc);
                if (GuiHelper.clickSlotIndex(mc, tc.slot())) { markCancelled(tc.item()); phase = Phase.CANCEL; }
                return;
            }
        }

        // 1.6) LIST HELD GOODS FIRST — before any cancel. Anything we're holding
        //      (claimed or refunded from a cancel) gets a sell offer put up now, so
        //      it never sits unlisted in the inventory and can't pile up → stash.
        //      No cancel step runs while we're still holding goods to list.
        if (!pendingSells.isEmpty()) {
            String item = pendingSells.peekFirst();
            if (!dailyOk) {
                // Daily coin limit: a new sell offer would fail — instasell instead.
                pendingInstasell = item;
                pendingInstasellAmount = Math.max(1, pendingSellAmounts.getOrDefault(key(item), 1));
                dequeueSell(item);
                pendingSellAmounts.remove(key(item));
                return;
            }
            if (slotFree) {
                activeItem = item;
                activeAmount = pendingSellAmounts.getOrDefault(key(item), 0);
                startNav(item, Phase.SELL_OPEN);
            } else {
                statusLine = "order slots full — waiting to list " + item;
            }
            return;   // hold here; never cancel while goods wait to be listed
        }

        // 1.7) Merge duplicate orders (one item should be ONE order, not four).
        //      Buys: cancel the worse-priced twin — refund redeploys the coins.
        //      Sells: cancel one twin; its units rejoin via the consolidation
        //      path below and relist as a single combined offer.
        {
            Map<String, ParsedOrder> firstBuy = new HashMap<>();
            Map<String, ParsedOrder> firstSell = new HashMap<>();
            for (ParsedOrder o : grid) {
                Map<String, ParsedOrder> m = o.buy() ? firstBuy : firstSell;
                ParsedOrder prev = m.putIfAbsent(o.key(), o);
                if (prev == null) continue;
                // Only de-dup BUY orders here (they waste slots + split queue).
                // Duplicate SELL offers are consolidated into one by step 1.55
                // above (cancel all → refund → relist as a single combined offer).
                if (!o.buy()) continue;
                if (o.claimable() || prev.claimable()) continue;  // claim first, merge later
                if (config.dryRun) { note("DRY: would merge duplicate orders of " + o.item()); continue; }
                // keep the better (higher) priced buy — it's ahead in the queue
                boolean prevBetter = !Double.isNaN(prev.pricePerUnit())
                        && (Double.isNaN(o.pricePerUnit()) || prev.pricePerUnit() >= o.pricePerUnit());
                ParsedOrder toCancel = prevBetter ? o : prev;
                if (toCancel == prev) m.put(o.key(), o);
                cancelSilent = true;                     // just drop; twin stays
                cancelBailInstasell = false;
                statusLine = "merging duplicate buy orders: " + o.item();
                cancelIsBuy = o.buy();
                cancelRebuy = false;
                cancelItem = toCancel.item();
                cancelAmount = toCancel.amount();
                if (GuiHelper.clickSlotIndex(mc, toCancel.slot())) { markCancelled(toCancel.item()); phase = Phase.CANCEL; }
                return;
            }
        }

        // 3) Relist anything beaten on price — exact math vs live top-of-book.
        //    Skipped while daily-limited (relisting re-creates an order = burns limit).
        for (ParsedOrder o : dailyOk ? grid : List.<ParsedOrder>of()) {
            if (o.filled()) continue;
            // Clicking a claimable order CLAIMS it instead of opening the cancel
            // menu — never try to relist one (the claim step handles it first).
            if (o.claimable()) continue;
            FlipCandidate q = api.quote(o.key());
            if (q == null || Double.isNaN(o.pricePerUnit())) continue;
            // BRAIN: if I already sit AT the top of the book, the "competing" order
            // is my OWN (I listed this item twice) — that's not a war, don't relist
            // against myself. Also ignore my other order of the same item.
            boolean myTwin = grid.stream().anyMatch(g -> g != o && g.buy() == o.buy()
                    && g.key().equals(o.key())
                    && (o.buy() ? g.pricePerUnit() >= o.pricePerUnit() - EPS
                                : g.pricePerUnit() <= o.pricePerUnit() + EPS));
            boolean beaten = o.buy()
                    ? q.topBuyOrder > o.pricePerUnit() + EPS       // someone bids higher than us
                    : q.lowestSellOffer < o.pricePerUnit() - EPS;  // someone offers lower than us
            // If the book's best IS our own order it sits at ~our exact price (the
            // API includes our order), so an EPS tolerance identifies "that's me".
            // A genuine one-tick undercut/outbid is a full TICK away — must NOT be
            // swallowed here, or we'd never react to the most common competitive move.
            boolean bookIsUs = o.buy()
                    ? Math.abs(q.topBuyOrder - o.pricePerUnit()) <= EPS
                    : Math.abs(q.lowestSellOffer - o.pricePerUnit()) <= EPS;
            if (!beaten || myTwin || bookIsUs) continue;
            if (config.dryRun) { note("DRY: would relist " + o.item()); continue; }
            // Sell-side: don't chase the price DOWN past our cost. If we can't
            // undercut and still profit, hold the offer where it is.
            if (!o.buy() && config.neverSellAtLoss) {
                double floor = minProfitableSell(o.item());
                if (!Double.isNaN(floor) && q.lowestSellOffer - PriceMath.TICK < floor) continue;
            }
            // Buy-side: don't chase the price UP into an unprofitable buy during an
            // outbid war — if outbidding leaves no margin vs the sell side, hold.
            if (o.buy() && config.neverSellAtLoss) {
                double newBuy = q.topBuyOrder + PriceMath.TICK;
                double sellNet = q.lowestSellOffer * (1 - config.taxFraction);
                if (sellNet <= newBuy * (1 + config.minSellMargin)) continue;
            }
            // Sell-side cancel refunds items: skip until the refund fits, and
            // don't double-cancel the same offer while the grid catches up.
            if (!o.buy() && (!refundFits(mc, o) || recentlyCancelled(o.item()))) continue;
            // Prospective count for the decision — committed to relistCounts ONLY if
            // the cancel click actually lands, so a failed click can't inflate the
            // war counter and prematurely blacklist/instasell a healthy order.
            int relists = relistCounts.getOrDefault(o.key(), 0) + 1;
            statusLine = "beaten on " + o.item() + " — cancelling (relist #" + relists + ")";
            cancelIsBuy = o.buy();
            // Buy side: give up after too many relist wars (manipulated/contested item).
            cancelRebuy = !o.buy() || relists <= config.maxRelistsPerOrder;
            // Sell side: after too many wars stop relisting — instasell for a
            // guaranteed exit so the capital moves to the next flip.
            cancelBailInstasell = !o.buy() && relists > config.maxRelistsPerOrder;
            cancelSilent = false;
            cancelItem = o.item();
            cancelAmount = o.buy() ? o.amount() : remainingUnits(o);
            if (GuiHelper.clickSlotIndex(mc, o.slot())) {
                relistCounts.put(o.key(), relists);
                markCancelled(o.item());
                phase = Phase.CANCEL;
            }
            return;
        }

        long tNow = System.currentTimeMillis();

        // 3.6) Opportunity-cost recycle: a sell offer that's barely filling past
        //      maxHoldMinutes is dead capital (0 coins/hr). Exit it (bounded loss)
        //      so the coins move to higher-velocity flips. Opt-in (freeStuckCapital).
        if (config.freeStuckCapital && dailyOk) {
            for (ParsedOrder o : grid) {
                if (o.buy() || o.claimable() || o.filled()) continue;
                OrderInfo oi = orders.get(o.key());
                if (oi == null || Double.isNaN(oi.buyPrice)) continue;
                if (tNow - oi.placedAt < config.maxHoldMinutes * 60_000L) continue;
                if (o.filledPct() > 20) continue;                    // it IS selling — leave it
                FlipCandidate q = api.quote(o.key());
                if (q == null) continue;
                double exitNet = q.topBuyOrder * (1 - config.taxFraction);   // instasell proceeds/unit
                double lossFrac = (oi.buyPrice - exitNet) / oi.buyPrice;
                if (lossFrac > config.maxExitLossFraction) continue;         // loss too big — keep holding
                if (!refundFits(mc, o) || recentlyCancelled(o.item())) continue;
                statusLine = "recycling stuck capital: " + o.item();
                note(String.format(Locale.ROOT, "§erecycling %s (stuck %dm, exit loss %.1f%%)",
                        o.item(), (tNow - oi.placedAt) / 60_000, lossFrac * 100));
                cancelIsBuy = false; cancelRebuy = false; cancelBailInstasell = true; cancelSilent = false;
                cancelItem = o.item(); cancelAmount = remainingUnits(o);
                if (GuiHelper.clickSlotIndex(mc, o.slot())) { markCancelled(o.item()); phase = Phase.CANCEL; }
                return;
            }
        }

        // 3.5) Exit dead buy orders: 0% filled after buyStallMinutes means the
        //      coins are doing nothing — cancel, blacklist briefly, redeploy.
        //      PLUS opportunity exits: when the book is FULL the slot itself is
        //      the scarce resource — price a lagging buy against the best
        //      available candidate and exit early if it's clearly outclassed
        //      (buy cancels are lossless; churn is the only cost).
        double bestAvailCph = -1;   // lazily computed once per pass
        for (ParsedOrder o : grid) {
            if (!o.buy() || o.filledPct() > 0 || o.claimable()) continue;
            OrderInfo oi = orders.get(o.key());
            if (oi == null) continue;
            long age = tNow - oi.placedAt;
            boolean timerExit = age >= config.buyStallMinutes * 60_000L;
            boolean oppExit = false;
            double mine = 0;
            if (!timerExit && config.opportunityExitFactor > 0 && !slotFree
                    && age >= config.opportunityExitMinAgeMinutes * 60_000L) {
                if (bestAvailCph < 0) bestAvailCph = ranking.stream()
                        .filter(r -> "ok".equals(r.state()))
                        .mapToDouble(RankRow::cph).max().orElse(0);
                FlipCandidate q = api.quote(o.key());
                mine = q != null ? scoreCandidate(q)[4] : 0;
                oppExit = bestAvailCph > config.opportunityExitFactor * Math.max(1, mine);
            }
            if (!timerExit && !oppExit) continue;
            if (config.dryRun) {
                note("DRY: would exit " + (oppExit ? "outclassed " : "stalled ") + o.item());
                continue;
            }
            statusLine = (oppExit ? "slot has better use — exiting " : "stalled ")
                    + o.item() + " — freeing capital";
            if (oppExit) note(String.format(Locale.ROOT,
                    "§eopportunity exit %s (%dm old, ~%,.0f/hr vs best %,.0f/hr)",
                    o.item(), age / 60_000, mine, bestAvailCph));
            cancelIsBuy = true;
            cancelRebuy = false;               // pCancel blacklists + drops it
            cancelBailInstasell = false;
            cancelSilent = false;
            cancelItem = o.item();
            cancelAmount = o.amount();
            if (GuiHelper.clickSlotIndex(mc, o.slot())) phase = Phase.CANCEL;
            return;
        }

        // (Held goods were already listed at step 1.6, before any cancel.)

        // 4.5) Sells are done and space is free — recover anything the server
        //      stashed (/pickupstash); the sweep lists it next pass.
        if (stashPending && pendingSells.isEmpty() && pendingInstasell == null) {
            phase = Phase.PICKUP_STASH;   // handled in onTick (must close the GUI first)
            return;
        }

        // 5) Open a new buy order with the best-ranked flip we don't hold.
        //    Utilize the WHOLE Bazaar book (14 slots, or 21/28 with the perk):
        //    keep placing buys until only one slot is left, kept free so a freshly
        //    filled buy always has somewhere to list its sell. A buy converts to a
        //    sell in-place, so we don't need to pre-reserve half the book.
        //    Keep ONE slot free only while a sell is imminent (goods queued, or a
        //    buy that just became claimable) so that sell has somewhere to land —
        //    otherwise deploy into the entire book to keep it nearing full.
        boolean sellImminent = !pendingSells.isEmpty()
                || grid.stream().anyMatch(o -> o.buy() && o.claimable());
        int reserve = sellImminent ? 1 : 0;
        int flipCap = Math.max(1, orderLimit - reserve);
        boolean roomForBuy = grid.size() < orderLimit - reserve;
        if (orders.size() < flipCap && buyCooldown <= 0 && roomForBuy && dailyOk) {
            String pick = pickNextItem(grid);
            if (pick != null) {
                if (config.dryRun) {
                    // Pricing phases never run in dry-run — preview the depth
                    // decision here so the new behavior is watchable before live.
                    String pricing = depthCrowded(pick, true)
                            ? " §7(would price into spread — crowded book)§r" : "";
                    statusLine = "DRY: would buy " + pick;
                    note("§eDRY RUN§r — would buy §f" + pick + "§r" + pricing
                            + " (set dryRun:false to trade)");
                    return;
                }
                activeItem = pick;
                startNav(pick, Phase.BUY_OPEN);
                return;
            }
        }
        // (Periodic Manage refresh now runs up front, before the claim/relist
        //  steps, so it can't be starved by a busy pass — see top of pPlan.)
        String why = idleReason();
        statusLine = String.format(Locale.ROOT, "monitoring %dB/%dS — %s", buyCount, sellCount, why);
        // Surface the reason in the Activity feed too (readable on the dashboard
        // even while a Bazaar GUI hides the in-game HUD), but only when it changes
        // and only while the book isn't full — so it explains lulls, not silence.
        if (!why.equals(lastIdleLogged) && grid.size() < orderLimit - 1
                && why.startsWith("no new flip")) {
            lastIdleLogged = why;
            log("idle — " + why);
        }
    }

    /**
     * Re-list leftovers: goods we own (from this OR a previous session — state is
     * persisted) that sit in the inventory with no live sell offer get queued for
     * sale. Only items the flipper itself bought are touched; personal items are
     * never auto-sold.
     */
    /**
     * THE stash-overflow gate: cancelling a sell offer REFUNDS its unsold items
     * to the inventory — if they don't fit, the server dumps them in the stash.
     * Never cancel a sell offer unless the refund fits in free inventory space.
     */
    private boolean refundFits(MinecraftClient mc, ParsedOrder sellOrder) {
        FlipCandidate q = api.quote(sellOrder.item());
        String tag = q != null ? q.tag : null;
        // Essence refunds to essence storage, not the inventory — always fits, so
        // its sell offers can be merged/relisted regardless of free slots.
        // (Shards do NOT get this bypass: their refunds are real item stacks —
        // same wrong assumption that caused the claim-spam incident.)
        String lower = sellOrder.item().toLowerCase(Locale.ROOT);
        if ((tag != null && ItemNames.bypassesInventory(tag)) || lower.endsWith("essence")) {
            return true;
        }
        if (inventoryFull) return false;
        int stack = tag != null ? ItemNames.stackSize(tag) : 64;
        int remaining = Math.max(1, sellOrder.amount()
                - (int) Math.floor(sellOrder.amount() * sellOrder.filledPct() / 100.0));
        int needed = (int) Math.ceil(remaining / (double) stack);
        return GuiHelper.freeInventorySlots(mc) >= needed + 1;   // +1 slot safety
    }

    /** Units still UNSOLD in an order — what a cancel actually refunds. Using the
     *  full original amount would overstate instasell profit/loss and pending
     *  sell quantities on partially-filled offers. */
    private static int remainingUnits(ParsedOrder o) {
        return Math.max(1, o.amount()
                - (int) Math.floor(o.amount() * Math.min(100.0, o.filledPct()) / 100.0));
    }

    // Per-item cancel cooldown: a cancel takes a moment to reflect in the grid;
    // without this the same offer gets cancel-clicked twice.
    private String lastCancelKey = null;
    private long lastCancelAt = 0;

    // Same idea for claims: the slot stays "claimable" in the stale grid for a
    // moment after we claim it — re-clicking would double-book the fill.
    private String lastClaimKey = null;
    private long lastClaimAt = 0;

    // Rollback info for the last BUY claim: a successful click is NOT a successful
    // claim — the server can refuse it ("You don't have the space required to claim
    // that!"). When that message arrives, these deltas undo the bookkeeping the
    // click optimistically recorded, so refused claims can't queue phantom sells
    // or inflate the claim counters.
    private int lastClaimUnits = 0;        // units merged into pendingSellAmounts
    private int lastClaimApplied = 0;      // delta actually applied to claimedSoFar
    private boolean lastClaimAddedPending = false;  // did this claim add the pendingSells entry?

    private boolean recentlyCancelled(String item) {
        return key(item).equals(lastCancelKey)
                && System.currentTimeMillis() - lastCancelAt < 5_000;
    }

    private void markCancelled(String item) {
        lastCancelKey = key(item);
        lastCancelAt = System.currentTimeMillis();
    }

    /** Enough inventory space to claim this buy order? Essence bypasses it.
     *
     *  HARD GATE FIRST: once the server has said "no space", NO buy claim runs —
     *  not even a believed-bypassing one — until selling/listing frees space and
     *  clears the flag. The server's word always beats our storage heuristics;
     *  this is what stops any repeat of the shard claim-spam incident (shards were
     *  wrongly assumed to bypass, so the bypass short-circuit here kept re-clicking
     *  refused claims forever while the inventory stayed full). */
    private boolean hasSpaceToClaim(MinecraftClient mc, ParsedOrder o) {
        if (inventoryFull) return false;   // server-confirmed full — sell first, then claim
        FlipCandidate q = api.quote(o.item());
        String tag = q != null ? q.tag : null;
        if ((tag != null && ItemNames.bypassesInventory(tag))
                || o.item().toLowerCase(Locale.ROOT).endsWith("essence")) {
            return true;   // essence is a currency — never enters the inventory
        }
        // Lenient: just need SOME free space. Pre-computing exact stacks from an
        // uncertain stack size mis-blocked valid claims (e.g. Soulflow). If a claim
        // actually overflows, the 'no space'/'stashed' message sets inventoryFull.
        return GuiHelper.freeInventorySlots(mc) >= 1;
    }

    private void sweepLeftovers(Set<String> invNames, List<ParsedOrder> grid) {
        for (String name : invNames) {
            String k = key(name);
            // Never auto-sell a Booster Cookie — it was bought to CONSUME; if the
            // consume flow aborts partway, selling it back would eat the spread.
            if (k.equals(key(BazaarStrings.ITEM_COOKIE))) continue;
            // Sell items we bought, plus (optionally) any other bazaar-sellable item
            // found in inventory — but never personal/non-bazaar items.
            boolean ours = boughtItems.contains(k);
            boolean bazaarItem = config.sellAllBazaarItems && api.quote(name) != null;
            if (!ours && !bazaarItem) continue;
            boolean liveSell = grid.stream().anyMatch(g -> !g.buy() && g.key().equals(k));
            boolean queued = pendingSells.stream().anyMatch(s -> key(s).equals(k));
            boolean instasell = pendingInstasell != null && key(pendingInstasell).equals(k);
            boolean active = activeItem != null && key(activeItem).equals(k);
            if (liveSell || queued || instasell || active) continue;
            pendingSells.addLast(name);
            stateDirty = true;
            log("found leftover " + name + " in inventory — queueing sell");
        }
    }

    /** Adopt unknown grid orders (restart recovery) and prune vanished ones. */
    private void adoptAndPrune(List<ParsedOrder> grid, Set<String> invKeys) {
        Set<String> seen = new HashSet<>();
        for (ParsedOrder o : grid) {
            seen.add(o.key());
            OrderInfo oi = orders.get(o.key());
            if (oi == null) {
                oi = new OrderInfo();
                oi.name = o.item();
                oi.amount = o.amount();
                if (o.buy()) oi.buyPrice = o.pricePerUnit(); else oi.sellPrice = o.pricePerUnit();
                orders.put(o.key(), oi);
                stateDirty = true;
            } else {
                if (oi.name == null || oi.name.isEmpty()) oi.name = o.item();
                // The grid is the truth — keep our records synced to the actual
                // placed prices (preset buttons can land ±0.1 from our estimate).
                if (!Double.isNaN(o.pricePerUnit())) {
                    if (o.buy()) oi.buyPrice = o.pricePerUnit();
                    else oi.sellPrice = o.pricePerUnit();
                }
                if (oi.amount <= 0) oi.amount = o.amount();
            }
        }
        long now = System.currentTimeMillis();
        orders.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            if (invKeys.contains(e.getKey())) return false;   // still holding the goods — keep to sell
            if (pendingSells.stream().anyMatch(s -> key(s).equals(e.getKey()))) return false;
            if (pendingInstasell != null && key(pendingInstasell).equals(e.getKey())) return false;
            if (activeItem != null && key(activeItem).equals(e.getKey())) return false;
            return now - e.getValue().placedAt > PRUNE_AGE_MS;
        });
    }

    private void onBuyClaimed(ParsedOrder o) {
        OrderInfo oi = orders.computeIfAbsent(o.key(), k -> new OrderInfo());
        if (oi.name == null || oi.name.isEmpty()) oi.name = o.item();
        boughtItems.add(o.key());
        firstClaimable.remove(o.key());   // restart batching grace for the next fill
        claimReadyMark.remove(o.key());   // and its progress marker
        stateDirty = true;
        if (Double.isNaN(oi.buyPrice)) oi.buyPrice = o.pricePerUnit();
        if (oi.amount <= 0) oi.amount = o.amount();

        // Units this claim banks: the grid tells us exactly ("N items to claim").
        int newUnits = o.claimAmount() > 0 ? o.claimAmount()
                : Math.max(1, (int) Math.floor(o.amount() * o.filledPct() / 100.0) - oi.claimedSoFar);
        int before = oi.claimedSoFar;
        oi.claimedSoFar = Math.min(Math.max(o.amount(), 1), oi.claimedSoFar + newUnits);

        buysFilled++;
        // Session breakdown: claimed units + what they cost us to buy.
        if (newUnits > 0 && !Double.isNaN(oi.buyPrice)) {
            sessClaimedUnits.merge(o.item(), newUnits, Integer::sum);
            sessClaimedCost.merge(o.item(), newUnits * oi.buyPrice, Double::sum);
        }
        boolean addedPending = !queuedForSell(o.item());
        if (addedPending) pendingSells.addLast(o.item());
        pendingSellAmounts.merge(o.key(), newUnits, Integer::sum);
        // Remember exactly what this claim recorded, so a server refusal ("no
        // space") arriving moments later can roll it back (see rollbackRefusedClaim).
        lastClaimUnits = newUnits;
        lastClaimApplied = oi.claimedSoFar - before;
        lastClaimAddedPending = addedPending;
        log("claimed " + newUnits + "× " + o.item()
                + (o.filled() ? "" : String.format(Locale.ROOT, " (%.0f%% filled)", o.filledPct()))
                + " — will list for sale");
    }

    /** The server refused the claim we just clicked (no inventory space) — undo the
     *  bookkeeping onBuyClaimed recorded optimistically. Without this, every refused
     *  claim queues phantom units for sale and inflates the claim/fill counters
     *  (exactly what happened in the shard claim-spam incident). One-shot per claim. */
    private void rollbackRefusedClaim() {
        if (lastClaimKey == null || lastClaimUnits <= 0
                || System.currentTimeMillis() - lastClaimAt > 5_000) return;
        OrderInfo oi = orders.get(lastClaimKey);
        if (oi != null) oi.claimedSoFar = Math.max(0, oi.claimedSoFar - lastClaimApplied);
        Integer left = pendingSellAmounts.merge(lastClaimKey, -lastClaimUnits, Integer::sum);
        if (left == null || left <= 0) {
            pendingSellAmounts.remove(lastClaimKey);
            // Only drop the queue entry if THIS claim created it; sweepLeftovers
            // re-queues anything genuinely sitting in the inventory anyway.
            if (lastClaimAddedPending) {
                String k = lastClaimKey;
                pendingSells.removeIf(s -> key(s).equals(k));
            }
        }
        buysFilled = Math.max(0, buysFilled - 1);
        log("§eclaim refused (no space) — rolled back " + lastClaimUnits + "× " + lastClaimKey);
        lastClaimUnits = 0;   // one-shot: don't roll back twice on a repeated message
        stateDirty = true;
    }

    private void onSellClaimed(ParsedOrder o, List<ParsedOrder> grid) {
        OrderInfo oi = orders.get(o.key());
        double sellP = !Double.isNaN(o.pricePerUnit()) ? o.pricePerUnit()
                : (oi != null ? oi.sellPrice : Double.NaN);
        double buyP = oi != null ? oi.buyPrice : Double.NaN;
        int total = o.amount() > 0 ? o.amount() : (oi != null ? oi.amount : 0);

        // Book profit only on the units newly sold since the last claim.
        int estSold = o.filled() ? total : (int) Math.floor(total * o.filledPct() / 100.0);
        int prevSold = oi != null ? oi.soldSoFar : 0;
        int newSold = Math.max(0, estSold - prevSold);
        // If the fill % is unreadable on a partial claim (estSold==0 while the order
        // isn't reported full), we can't tell how many units sold — so book NOTHING
        // this pass rather than assume a full sale. Assuming full would over-count
        // profit AND abandon the unsold remainder's cost basis. A later Manage
        // refresh with a readable % books it correctly; if the order actually
        // completed, prune clears it. Conservative: undercount beats phantom profit.
        if (oi != null) oi.soldSoFar = prevSold + newSold;

        // Session breakdown: sold units + net revenue (independent of whether we
        // know the buy cost — e.g. leftovers from a prior session still count).
        if (newSold > 0 && !Double.isNaN(sellP)) {
            sessSoldUnits.merge(o.item(), newSold, Integer::sum);
            sessSoldRevenue.merge(o.item(), sellP * (1.0 - config.taxFraction) * newSold, Double::sum);
        }

        sellsFilled++;
        if (!Double.isNaN(buyP) && !Double.isNaN(sellP) && newSold > 0) {
            double profit = (sellP * (1.0 - config.taxFraction) - buyP) * newSold;
            tracker.addProfit(profit);
            allTimeProfit += profit;
            itemProfit.merge(o.item(), profit, Double::sum);
            stateDirty = true;
            // Feedback loop: how much of the QUOTED margin did we actually capture?
            if (oi != null && !Double.isNaN(oi.quotedMargin) && oi.quotedMargin > 0 && buyP > 0) {
                double realizedMargin = (sellP * (1.0 - config.taxFraction) - buyP) / buyP;
                double ratio = Math.max(0, Math.min(2.0, realizedMargin / oi.quotedMargin));
                efficiency.merge(o.key(), ratio, (old, r) -> 0.7 * old + 0.3 * r);   // EMA
            }
            // A losing flip = bad item right now — bench it for a while.
            if (profit < 0) {
                blacklistUntil.put(o.key(), System.currentTimeMillis()
                        + config.badItemBlacklistMinutes * 60_000L);
                note("§elost coins on " + o.item() + " — benched "
                        + config.badItemBlacklistMinutes + "m");
            }
            // Auto-meta: one completed flip's worth of evidence — update the item's
            // lifetime record and re-judge whether it belongs on the durable avoid-list.
            itemFlips.merge(o.key(), 1, Integer::sum);
            evaluateMeta(o.key(), o.item());
            log(String.format(Locale.ROOT, "§asold§r %d× %s  %+,.0f coins  (session %,.0f)",
                    newSold, o.item(), profit, tracker.total()));
        } else if (newSold > 0) {
            log("claimed sold " + o.item() + " (buy price unknown — profit not tracked)");
        }

        boolean buyStillOpen = grid.stream().anyMatch(g -> g.buy() && g.key().equals(o.key()));
        boolean stillPending = pendingSells.stream()
                .anyMatch(s -> key(s).equals(o.key()));
        boolean sellDone = o.filled() || (oi != null && total > 0 && oi.soldSoFar >= total);
        if (sellDone) {
            flipsCompleted++;
            if (oi != null) {
                oi.sellPrice = Double.NaN;
                oi.soldSoFar = 0;
                if (!buyStillOpen && !stillPending) {
                    orders.remove(o.key());
                    relistCounts.remove(o.key());
                }
            }
        }
    }

    /** Auto-meta learner: judge an item on its LIFETIME realized record and either
     *  add it to the durable avoid-list or clear it. Runs after each completed
     *  flip. Probation-based (not permanent) so a recovering item gets re-tested —
     *  the meta keeps improving instead of freezing. */
    private void evaluateMeta(String key, String item) {
        if (!config.autoBlacklist) return;
        int flips = itemFlips.getOrDefault(key, 0);
        if (flips < config.autoBlacklistMinFlips) return;   // not enough evidence yet
        double total = itemProfit.getOrDefault(key, 0.0);
        double avg = total / flips;
        double eff = efficiency.getOrDefault(key, 1.0);
        boolean bad = avg < config.autoBlacklistMinAvgProfit || eff < config.minEfficiency;
        long now = System.currentTimeMillis();
        if (bad) {
            boolean wasClear = metaAvoidUntil.getOrDefault(key, 0L) <= now;
            metaAvoidUntil.put(key, now + (long) (config.autoBlacklistProbationHours * 3_600_000L));
            stateDirty = true;
            if (wasClear) {
                note(String.format(Locale.ROOT,
                        "§cmeta: avoiding %s§r — avg %+,.0f/flip over %d flips (eff %.2f)",
                        item, avg, flips, eff));
            }
        } else if (metaAvoidUntil.remove(key) != null) {
            stateDirty = true;
            note(String.format(Locale.ROOT,
                    "§ameta: cleared %s§r — now avg %+,.0f/flip over %d flips", item, avg, flips));
        }
    }

    /** True if an item is on the learned avoid-list right now (probation not elapsed). */
    private boolean metaAvoided(String key) {
        return config.autoBlacklist && metaAvoidUntil.getOrDefault(key, 0L) > System.currentTimeMillis();
    }

    /** Why no new order is being opened right now — a precise breakdown so the
     *  dashboard/HUD explains itself instead of a vague "no new pick". */
    private String idleReason() {
        long now = System.currentTimeMillis();
        if (now < dailyLimitUntil)
            return "daily coin limit — paused " + ((dailyLimitUntil - now) / 60_000 + 1) + "m";
        if (serverClosing) return "server restarting — paused";
        if (orders.size() >= Math.max(1, orderLimit - 1)) return "book full (at order cap)";
        if (buyCooldown > 0) return "pacing (" + buyCooldown + "t)";
        var cs = api.getCandidates();
        if (cs.isEmpty()) return "no flips from API yet (age " + api.ageSeconds() + "s)";
        if (Double.isNaN(purse)) return "purse unknown";
        double spend = perOrderBudget();
        if (spend <= 0) return "no spendable coins (purse " + String.format(Locale.ROOT, "%,.0f", purse) + ")";

        // Replay pickNextItem's filters just to COUNT why each candidate is out.
        Set<String> held = new HashSet<>(orders.keySet());
        for (ParsedOrder o : lastOrders) held.add(o.key());
        for (String s : pendingSells) held.add(key(s));
        if (pendingInstasell != null) held.add(key(pendingInstasell));
        int nHeld = 0, nBench = 0, nPricey = 0, nThin = 0, nOk = 0;
        for (FlipCandidate c : cs) {
            String k = key(c.displayName);
            if (held.contains(k)) { nHeld++; continue; }
            if (blacklistUntil.getOrDefault(k, 0L) > now
                    || efficiency.getOrDefault(k, 1.0) < config.minEfficiency) { nBench++; continue; }
            if (c.ourBuyPrice() > spend) { nPricey++; continue; }
            // Same code path as pickNextItem's gate — keep the counts truthful.
            double[] s = scoreCandidate(c);
            double grossT = c.hourlyVolume() * config.orderVolumeFraction * c.ourBuyPrice();
            if (config.minOrderValue > 0 && grossT < config.minOrderValue
                    && s[0] * s[7] < config.minOrderValue * config.apiMinMargin) { nThin++; continue; }
            nOk++;
        }
        if (nOk > 0) return nOk + " ready — placing…";
        return String.format(Locale.ROOT,
                "no new flip: %d seen · %d held · %d benched · %d too pricey · %d too thin",
                cs.size(), nHeld, nBench, nPricey, nThin);
    }

    private String lastIdleLogged = "";

    /** Coins to commit to the next order: spendable purse split evenly across the
     *  remaining open slots, capped so no single item hogs the book. */
    private double perOrderBudget() {
        double spendable = purse - config.coinReserve;
        if (Double.isNaN(spendable) || spendable <= 0) return 0;
        int freeSlots = Math.max(1, (orderLimit - 1) - orders.size());
        double even = spendable / freeSlots;
        double cap = spendable * Math.max(0.05, Math.min(1.0, config.orderBudgetFraction));
        return Math.min(even, cap);
    }

    /** Coins currently escrowed in unfilled BUY orders — your money that IS working.
     *  (Filled buys convert to inventory/sells; those show as projected profit.) */
    public double deployedBuyCapital() {
        double sum = 0;
        for (ParsedOrder o : lastOrders) {
            if (!o.buy() || Double.isNaN(o.pricePerUnit())) continue;
            double unfilled = o.amount() * (1 - Math.min(100.0, o.filledPct()) / 100.0);
            sum += unfilled * o.pricePerUnit();
        }
        return sum;
    }

    /** Fraction of your liquid coins actually deployed in buy orders (0..1). The
     *  number that was invisible on the HUD while 100M sat idle. */
    public double utilization() {
        double deployed = deployedBuyCapital();
        double free = Double.isNaN(purse) ? 0 : Math.max(0, purse - config.coinReserve);
        double liquid = deployed + free;
        return liquid <= 0 ? 0 : deployed / liquid;
    }

    /** True when a big share of liquid coins sits idle AND the book has room — the
     *  cue to size orders up so the bankroll actually deploys. */
    private boolean capitalIdle() {
        return utilization() < (1 - config.idleDeployThreshold) && orders.size() < orderLimit;
    }

    /**
     * Adaptive margin controller — tunes the minimum-margin GATE to maximize
     * realized coins/hour. The gate is not the ranker (that already sorts by cph);
     * it sets the candidate POOL. The economics:
     *   • Slots/capital binding (book full, more good flips than slots) → RAISE the
     *     gate so each scarce slot lands a fatter flip. Higher coins/hr.
     *   • Idle capital / empty slots → LOWER toward the floor so the bankroll
     *     actually deploys (raising it here would under-deploy = lose money).
     * Only ever moves ABOVE the configured apiMinMargin (never trades below your
     * floor), by at most autoMarginMaxBonus, one small step per period. The value
     * lives on BazaarApi, never on the config, so it never persists.
     */
    private void adaptMargin() {
        if (!config.autoMargin) return;
        long now = System.currentTimeMillis();
        if (now - lastMarginAdjust < config.autoMarginPeriodSeconds * 1000L) return;
        lastMarginAdjust = now;

        int freeSlots = Math.max(0, orderLimit - orders.size());
        // Fresh, buyable flips right now (the ranking snapshot already applied the
        // gate + held/benched/meta filters; "ok" = we could place it).
        long qualifiers = ranking.stream().filter(r -> "ok".equals(r.state())).count();

        double cur = api.effectiveMinMargin();
        double lo = baseMinMargin, hi = baseMinMargin + Math.max(0, config.autoMarginMaxBonus);
        double step = 0.005;   // 0.5% per period — gentle
        double next = cur;

        if (freeSlots <= 1 && qualifiers > 2L * Math.max(1, freeSlots) + 2) {
            next = Math.min(hi, cur + step);          // capacity-bound + surplus → be pickier
        } else if (freeSlots >= 2 && qualifiers < freeSlots) {
            next = Math.max(lo, cur - step);          // empty slots + starving → admit more
        } else if (cur > lo) {
            next = Math.max(lo, cur - step / 2);      // neutral → drift back toward the floor
        }

        if (Math.abs(next - cur) > 1e-6) {
            api.setDynMinMargin(next);
            note(String.format(Locale.ROOT,
                    "§bauto-margin %.1f%%→%.1f%%§7 (%d flips ready, %d free slots)",
                    cur * 100, next * 100, qualifiers, freeSlots));
        }
    }

    /** Minimum window between fill-rate samples. Sampling every pass (~250ms)
     *  poisoned the EMA: most passes see 0 new fills, so rate=0 was merged in
     *  4×/second, decaying every listed item's measured rate to ~0 within
     *  seconds — which then ranked it dead-last forever. Sample over a real
     *  window instead so the rate reflects actual throughput. */
    private static final long FILL_WINDOW_MS = 60_000;

    /** Measure how fast our orders actually fill (units/hr), EMA per item and SIDE.
     *  Sell throughput turns capital over; buy throughput is the other half of the
     *  round trip — the ranker combines both legs into a series velocity. These
     *  ground-truth rates override the volume-based estimates as data accumulates. */
    private void observeFills(List<ParsedOrder> grid) {
        long now = System.currentTimeMillis();
        for (ParsedOrder o : grid) {
            Map<String, Double> target = o.buy() ? buyFillRate : fillRate;
            String obsKey = (o.buy() ? "B|" : "S|") + o.key();
            double filled = o.amount() * Math.min(100.0, o.filledPct()) / 100.0;
            double[] prev = fillObs.get(obsKey);
            if (prev == null) { fillObs.put(obsKey, new double[]{now, filled}); continue; }
            double dFilled = filled - prev[1];
            if (dFilled < 0) { fillObs.put(obsKey, new double[]{now, filled}); continue; }  // relisted — new baseline
            if (now - prev[0] < FILL_WINDOW_MS) continue;   // window not elapsed — keep accumulating
            fillObs.put(obsKey, new double[]{now, filled});
            double rate = dFilled / ((now - prev[0]) / 3_600_000.0);   // units per hour
            target.merge(o.key(), rate, (old, r) -> 0.75 * old + 0.25 * r);
            // Learn OUR real capture: measured rate ÷ this leg's market flow.
            // Pooled across items/sides into one EMA (clamped against outliers);
            // becomes the fallback estimate for items we haven't measured yet.
            if (config.learnCapture) {
                FlipCandidate q = api.quote(o.key());
                double legFlow = q == null ? 0 : (o.buy() ? q.buyLegHourly() : q.sellLegHourly());
                if (legFlow > 0) {
                    double ratio = Math.max(0.01, Math.min(2.0, rate / legFlow));
                    learnedCapture = learnedCaptureN == 0 ? ratio
                            : 0.9 * learnedCapture + 0.1 * ratio;
                    learnedCaptureN++;
                }
            }
            stateDirty = true;
        }
    }

    /** The capture fraction the estimators should use: the learned account-wide
     *  share once seeded (≥5 samples), else the configured cold-start guess. */
    public double captureEstimate() {
        return (config.learnCapture && learnedCaptureN >= 5)
                ? learnedCapture : config.captureFraction;
    }

    /** THE objective function, in one place: expected realized coins/hour for one
     *  order slot of this candidate. Used by pickNextItem to choose AND by the
     *  ranking snapshot the dashboard shows — never let the two drift apart.
     *
     *  TWO-SIDED: a flip's cycle time is buy-fill + sell-fill IN SERIES, so cph
     *  uses the series velocity of both legs — an item whose sells fly but whose
     *  buy order sits for hours parks capital and now ranks accordingly (ranking
     *  the sell leg alone overrated such items). Each leg falls back to the
     *  correct opposite-flow volume estimate until measured.
     *  Returns {ppu, sellRate, sellMeasured, eff, cph, buyRate, buyMeasured, velocity}. */
    private double[] scoreCandidate(FlipCandidate c) {
        String key = key(c.displayName);
        double ppu = Math.max(0, PriceMath.profitPerUnit(c.topBuyOrder, c.lowestSellOffer, config.taxFraction));
        double capture = captureEstimate();   // learned account-wide share, or the config guess
        Double sf = fillRate.get(key);
        boolean sellMeasured = sf != null && sf > 0;
        double sellRate = sellMeasured ? sf : c.sellLegHourly() * capture;
        Double bf = buyFillRate.get(key);
        boolean buyMeasured = bf != null && bf > 0;
        double buyRate = buyMeasured ? bf : c.buyLegHourly() * capture;
        double velocity = PriceMath.seriesVelocity(buyRate, sellRate);
        double eff = Math.max(0.1, efficiency.getOrDefault(key, 1.0));
        double trendF = Math.max(0.5, Math.min(1.5, 1 + config.trendWeight * c.trend));
        double cph = ppu * velocity * eff * trendF;
        return new double[]{ppu, sellRate, sellMeasured ? 1 : 0, eff, cph,
                buyRate, buyMeasured ? 1 : 0, velocity};
    }

    /** Rebuild the dashboard's ranking snapshot (throttled — cheap but no need to
     *  churn it every pass) and sample the aggregate liquidity index. */
    private void refreshRankingSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastRankingBuild < 2_000) return;
        lastRankingBuild = now;
        var cs = api.getCandidates();
        List<RankRow> rows = new java.util.ArrayList<>(cs.size());
        double liquidity = 0;
        for (FlipCandidate c : cs) {
            String k = key(c.displayName);
            double[] s = scoreCandidate(c);
            liquidity += c.hourlyVolume() * c.ourBuyPrice();
            String state = "ok";
            if (orders.containsKey(k) || lastOrders.stream().anyMatch(o -> o.key().equals(k))
                    || pendingSells.stream().anyMatch(p -> key(p).equals(k))) state = "held";
            else if (metaAvoided(k)) state = "meta-avoid";
            else if (blacklistUntil.getOrDefault(k, 0L) > now) state = "benched";
            else if (efficiency.getOrDefault(k, 1.0) < config.minEfficiency) state = "low-eff";
            else if (c.ourBuyPrice() > perOrderBudget()) state = "too pricey";
            rows.add(new RankRow(c.displayName, s[0], s[5], s[6] > 0, s[1], s[2] > 0,
                    s[7], s[3], s[4], state));
        }
        rows.sort((a, b) -> Double.compare(b.cph(), a.cph()));
        ranking = List.copyOf(rows);
        // Liquidity baseline: one sample per 10 min, capped ~2 weeks, persisted.
        if (now - lastLiquiditySample > 600_000 && liquidity > 0) {
            lastLiquiditySample = now;
            liquiditySamples.add(new double[]{now, liquidity});
            while (liquiditySamples.size() > 2016) liquiditySamples.remove(0);
            stateDirty = true;
        }
    }

    /** Best-ranked candidate we don't already hold, hold pending, or see in the grid. */
    private String pickNextItem(List<ParsedOrder> grid) {
        double spendablePerOrder = perOrderBudget();
        Set<String> held = new HashSet<>(orders.keySet());
        for (ParsedOrder o : grid) held.add(o.key());
        for (String s : pendingSells) held.add(key(s));
        if (pendingInstasell != null) held.add(key(pendingInstasell));

        if (config.useApiFlips) {
            if (spendablePerOrder <= 0) return null;
            long now = System.currentTimeMillis();
            FlipCandidate best = null;
            double bestCPH = -1;
            for (FlipCandidate c : api.getCandidates()) {
                String key = key(c.displayName);
                if (held.contains(key)) continue;
                if (blacklistUntil.getOrDefault(key, 0L) > now) continue; // short bench
                if (metaAvoided(key)) continue;                            // learned avoid-list
                // Chronically-bad item (captures too little of its quoted margin):
                // bench it TEMPORARILY with probation, not forever — markets change.
                if (efficiency.getOrDefault(key, 1.0) < config.minEfficiency) {
                    blacklistUntil.put(key, now + config.badItemBlacklistMinutes * 60_000L);
                    efficiency.put(key, 0.8);   // probation: fresh chance after the bench
                    note("§ebenched " + c.displayName + " " + config.badItemBlacklistMinutes
                            + "m (low realized efficiency)");
                    stateDirty = true;
                    continue;
                }
                if (c.ourBuyPrice() > spendablePerOrder) continue;

                // EMPIRICAL coins/hour — scoreCandidate() IS the objective (shared
                // with the dashboard's ranking snapshot; keep them one code path).
                double[] s = scoreCandidate(c);
                double ppu = s[0], cph = s[4], velocity = s[7];

                // Liquidity gate — keep an item if it moves enough COINS *or* earns
                // enough PROFIT/hr. The gross-volume-only floor discarded high-price,
                // high-margin flips (a 110k item at 6% is worth a slot at modest
                // volume); requiring one OR the other admits them without letting in
                // genuinely dead items.
                double grossThroughput  = c.hourlyVolume() * config.orderVolumeFraction * c.ourBuyPrice();
                double profitThroughput = ppu * velocity;   // coins/hr of realized profit (both legs)
                if (config.minOrderValue > 0 && grossThroughput < config.minOrderValue
                        && profitThroughput < config.minOrderValue * config.apiMinMargin) continue;

                if (cph > bestCPH) { bestCPH = cph; best = c; }
            }
            if (best == null) return null;
            activeHourlyVol = best.hourlyVolume();
            activeBypassInv = ItemNames.bypassesInventory(best.tag);
            activeStackSize = ItemNames.stackSize(best.tag);
            activeVolatility = best.volatility;
            activeMargin = best.margin(config.taxFraction);
            return best.displayName;
        }
        for (FlipTarget t : config.targets) {
            if (!held.contains(key(t.product))) {
                activeHourlyVol = Double.MAX_VALUE;
                activeBypassInv = false;
                activeStackSize = 64;
                // Reset the API-only sizing inputs so a stale volatility/margin from
                // a prior API pick can't leak into Kelly sizing or quotedMargin here.
                activeVolatility = 0;
                activeMargin = 0;
                return t.product;
            }
        }
        return null;
    }

    // ---- navigation ----

    private void pNavSearch(MinecraftClient mc) {
        statusLine = "→ " + navItem;
        if (arrivedAtProduct(mc, navItem)) {
            // Auto-detect the real bazaar tax ("current tax: 1.1%") so margin
            // math uses YOUR exact rate, not an assumption.
            double liveTax = GuiHelper.readTaxFraction(mc);
            if (!Double.isNaN(liveTax) && liveTax > 0 && liveTax < 0.05
                    && Math.abs(liveTax - config.taxFraction) > 1e-4) {
                config.taxFraction = liveTax;
                config.save();
                log(String.format(Locale.ROOT, "detected bazaar tax: %.2f%%", liveTax * 100));
            }
            double topBuy = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_BUY_ORDER, BazaarStrings.LORE_COINS);
            double lowSell = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_SELL_OFFER, BazaarStrings.LORE_COINS);
            // Clear first so a failed read can NEVER leak the previous item's price
            // into this item's sizing/margin math (a stale-low price would grossly
            // oversize the order).
            ourBuyPrice = Double.NaN; ourSellPrice = Double.NaN;
            if (!Double.isNaN(topBuy) && !Double.isNaN(lowSell)) {
                ourBuyPrice = PriceMath.buyOrderPrice(topBuy);
                ourSellPrice = PriceMath.sellOfferPrice(lowSell);
            } else {
                FlipCandidate q = api.quote(key(navItem));
                if (q != null) { ourBuyPrice = q.ourBuyPrice(); ourSellPrice = q.ourSellPrice(); }
            }
            // Couldn't price it from either source — don't proceed on nothing; stay
            // and retry (checkStuck recovers/benches if it never resolves).
            if (Double.isNaN(ourBuyPrice) || Double.isNaN(ourSellPrice)) {
                statusLine = "waiting for price on " + navItem;
                return;
            }
            // SAFETY NET: never BUY an item whose LIVE product margin is out of
            // range. Distinguish the two cases instead of benching both for 30m:
            //   • margin TOO LOW  → the spread is momentarily thin; it widens back
            //     in seconds, so just skip briefly (marginSkipMinutes) and retry.
            //   • margin TOO HIGH → that's the manipulation/illiquid trap; bench it
            //     for the full blacklist window.
            if (navAfter == Phase.BUY_OPEN && !Double.isNaN(ourBuyPrice) && ourBuyPrice > 0) {
                double m = (ourSellPrice * (1 - config.taxFraction) - ourBuyPrice) / ourBuyPrice;
                if (m < config.apiMinMargin || m > config.apiMaxMargin) {
                    boolean trap = m > config.apiMaxMargin;
                    long mins = trap ? config.blacklistMinutes : config.marginSkipMinutes;
                    note(String.format(Locale.ROOT, "§eskip %s§r: live margin %.1f%% %s — %s %dm",
                            navItem, m * 100, trap ? "(too wide/trap)" : "(thin right now)",
                            trap ? "benched" : "retry in", mins));
                    blacklistUntil.put(key(navItem), System.currentTimeMillis() + mins * 60_000L);
                    activeItem = null;
                    phase = Phase.PLAN;
                    return;
                }
            }
            phase = navAfter;
            return;
        }
        // On the search-results screen: click the EXACT item (avoids picking a
        // similarly-named item like "Lesser Soulflow Engine" for "Soulflow").
        if (!atProduct(mc) && GuiHelper.hasItemNamed(mc, navItem)) {
            GuiHelper.clickExactName(mc, navItem);
            return;
        }
        if (navCooldown > 0) { navCooldown--; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) nh.sendChatCommand("bz " + navItem);
        navCooldown = 4;
    }

    private void startNav(String item, Phase after) {
        navItem = item; navAfter = after; navCooldown = 0; phase = Phase.NAV_SEARCH;
    }

    private void openBazaar(MinecraftClient mc) {
        if (openCooldown > 0) { openCooldown--; statusLine = "opening Bazaar…"; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) { nh.sendChatCommand("bz"); statusLine = "opening Bazaar (/bz)"; }
        openCooldown = 4;
    }

    // ---- buy ----

    private void pBuyAmount(MinecraftClient mc) {
        double spendable = perOrderBudget();
        int byPurse = PriceMath.affordableUnits(spendable, ourBuyPrice, config.maxUnitsPerOrder);
        if (byPurse < 1) { log("insufficient purse for " + activeItem + " — skipping"); phase = Phase.PLAN; return; }
        // Capital-aware order size: when a big chunk of your liquid coins is sitting
        // IDLE and the book has open slots, let each order grow toward
        // maxOrderVolumeFraction so the bankroll actually deploys — but it stays
        // bounded by the item's own hourly volume, so it never dumps into a thin
        // market (that would just sit unfilled, the opposite of the goal).
        double volFrac = capitalIdle() ? Math.max(config.orderVolumeFraction, config.maxOrderVolumeFraction)
                                       : config.orderVolumeFraction;
        int byVolume = (activeHourlyVol == Double.MAX_VALUE)
                ? config.maxUnitsPerOrder
                : (int) Math.max(1, activeHourlyVol * volFrac);

        // Inventory-fit cap: essence bypasses inventory (unlimited); everything
        // else — INCLUDING shards, which land as real stacks — must fit in free
        // slots × stack size so a full claim never overflows or gets refused.
        int byInv = Integer.MAX_VALUE;
        if (config.capByInventory && !activeBypassInv) {
            int free = Math.max(0, GuiHelper.freeInventorySlots(mc) - config.inventoryBuffer);
            byInv = free * Math.max(1, activeStackSize);
            if (byInv < 1) { note("§einventory full§r — skipping " + activeItem); phase = Phase.PLAN; return; }
        }

        // Fractional-Kelly cap: risk-adjusted position size. f* = min(1, edge/σ²);
        // stake = bankroll · f* · kellyFraction. Low-vol/high-margin → big; risky → small.
        int byKelly = Integer.MAX_VALUE;
        if (config.kellyFraction > 0 && activeVolatility > 1e-6 && activeMargin > 0) {
            double bankroll = purse - config.coinReserve;
            double f = Math.min(1.0, activeMargin / (activeVolatility * activeVolatility)) * config.kellyFraction;
            byKelly = Math.max(1, (int) (bankroll * f / ourBuyPrice));
        }

        activeAmount = Math.max(1, Math.min(Math.min(byPurse, byVolume), Math.min(byInv, byKelly)));
        statusLine = "amount " + activeAmount + " × " + activeItem;
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_AMOUNT)) {
            requestSign(Integer.toString(activeAmount), Phase.BUY_PRICE);
        }
    }

    private void pBuyConfirm(MinecraftClient mc) {
        if (GuiHelper.clickByNameAndLore(mc, BazaarStrings.BTN_BUY_ORDER, BazaarStrings.LORE_SUBMIT)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_BUY)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            OrderInfo oi = new OrderInfo();
            oi.name = activeItem;
            oi.buyPrice = ourBuyPrice;
            oi.amount = activeAmount;
            oi.quotedMargin = activeMargin;
            orders.put(key(activeItem), oi);
            boughtItems.add(key(activeItem));
            ordersPlaced++;
            stateDirty = true;
            // No deliberate post-order cooldown: the multi-step nav/confirm flow
            // already spaces new orders ~1.5s apart naturally, so the book fills
            // briskly. Opt back in by setting orderCooldownTicks > 0 in config.
            if (config.orderCooldownTicks > 0) buyCooldown = config.orderCooldownTicks;
            log(String.format(Locale.ROOT, "§abuy§r %d× %s @ %.1f  (%,.0f coins)",
                    activeAmount, activeItem, ourBuyPrice, activeAmount * ourBuyPrice));
            activeItem = null;
            phase = Phase.PLAN;
        }
    }

    // ---- sell ----

    /** True when the current best price level is so crowded that joining the
     *  +0.1 game means re-undercut wars: the depth queued at top-of-book would
     *  take > depthWaitMinutes to clear at this leg's market flow. Margin guard:
     *  only give up the spread slice when the flip still clears 1.5× the minimum
     *  margin afterwards — never trade the whole edge for queue position. */
    private boolean depthCrowded(String item, boolean buySide) {
        if (config.depthWaitMinutes <= 0 || item == null) return false;
        FlipCandidate q = api.quote(item);
        if (q == null) return false;
        double depth = buySide ? q.buyDepth : q.sellDepth;
        double flow  = buySide ? q.buyLegHourly() : q.sellLegHourly();
        if (flow <= 0 || depth <= 0) return false;
        return depth / flow * 60.0 > config.depthWaitMinutes
                && q.margin(config.taxFraction) > config.apiMinMargin * 1.5;
    }

    private boolean priceAggressively(boolean buySide) {
        if (activeItem == null) return false;
        if (relistCounts.getOrDefault(key(activeItem), 0) >= config.aggressiveAfterRelists) return true;
        // Depth-aware: pay the spread slice up front on crowded books instead of
        // losing the same value to relist churn and queue time.
        if (depthCrowded(activeItem, buySide)) {
            log("crowded book on " + activeItem + " — pricing into the spread");
            return true;
        }
        return false;
    }

    /** Open a sell offer — but bail if we don't actually hold the item (e.g. the
     *  goods are in the stash), so we never get stuck clicking a dead
     *  "none in inventory!" button. */
    private void pSellOpen(MinecraftClient mc) {
        if (!atProduct(mc)) {
            if (activeItem != null) startNav(activeItem, Phase.SELL_OPEN); else phase = Phase.PLAN;
            return;
        }
        if (GuiHelper.loreOfNamedContains(mc, BazaarStrings.BTN_SELL_OFFER, "none in inventory")
                || GuiHelper.loreOfNamedContains(mc, BazaarStrings.BTN_SELL_OFFER, "none to sell")) {
            note("nothing to sell for " + activeItem + " (likely stashed) — recovering");
            if (activeItem != null) {
                dequeueSell(activeItem);
                pendingSellAmounts.remove(key(activeItem));
            }
            activeItem = null;
            stashPending = true;   // pull it back from the stash, then it re-lists
            phase = Phase.PLAN;
            return;
        }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_SELL_OFFER)) phase = Phase.SELL_PRICE;
    }

    /** Buy price: normally "top order +0.1"; after repeated relist wars, jump the
     *  queue with "5% of spread" so +0.1 ping-pong ends. */
    private void pBuyPrice(MinecraftClient mc) {
        if (priceAggressively(true) && GuiHelper.clickByName(mc, BazaarStrings.BTN_SPREAD_BUY)) {
            phase = Phase.BUY_CONFIRM; return;
        }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BEST_PRICE)) { phase = Phase.BUY_CONFIRM; return; }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) {
            requestSign(fmt1(ourBuyPrice), Phase.BUY_CONFIRM);   // fallback: type it
        }
    }

    /** Lowest price we can sell {@code item} at without losing money vs what we
     *  paid (after tax + minSellMargin). NaN if we don't know the buy cost. */
    private double minProfitableSell(String item) {
        OrderInfo oi = orders.get(key(item));
        if (oi == null || Double.isNaN(oi.buyPrice) || oi.buyPrice <= 0) return Double.NaN;
        return PriceMath.roundToTick(oi.buyPrice * (1 + config.minSellMargin) / (1 - config.taxFraction));
    }

    /** Sell price: competitive ("best offer -0.1" / "10% of spread"), UNLESS that
     *  would sell below our cost — then hold the line at a profitable price. */
    private void pSellPrice(MinecraftClient mc) {
        double floor = config.neverSellAtLoss ? minProfitableSell(activeItem) : Double.NaN;

        // Loss guard: the competitive price (ourSellPrice) is below our profit floor.
        if (!Double.isNaN(floor) && ourSellPrice < floor) {
            if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) {
                ourSellPrice = floor;
                note(String.format(Locale.ROOT, "§eholding %s at %.1f (cost-protected, market too low)",
                        activeItem, floor));
                requestSign(fmt1(floor), Phase.SELL_CONFIRM);
            }
            return;
        }

        if (priceAggressively(false) && GuiHelper.clickByName(mc, BazaarStrings.BTN_SPREAD_SELL)) {
            phase = Phase.SELL_CONFIRM; return;
        }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BEST_SELL)) { phase = Phase.SELL_CONFIRM; return; }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) {
            requestSign(fmt1(ourSellPrice), Phase.SELL_CONFIRM);
        }
    }

    private void pSellConfirm(MinecraftClient mc) {
        if (GuiHelper.clickByNameAndLore(mc, BazaarStrings.BTN_SELL_OFFER, BazaarStrings.LORE_SUBMIT)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_SELL)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            recordSell();
        } else if (atManage(mc) || atProduct(mc)) {
            // No confirm screen — the offer was placed directly. Record and move on.
            recordSell();
        }
    }

    private void recordSell() {
        if (activeItem == null) { phase = Phase.PLAN; return; }
        String key = key(activeItem);
        OrderInfo oi = orders.computeIfAbsent(key, k -> new OrderInfo());
        if (oi.name == null || oi.name.isEmpty()) oi.name = activeItem;
        oi.sellPrice = ourSellPrice;
        if (oi.amount <= 0) oi.amount = activeAmount;
        ordersPlaced++;
        stateDirty = true;
        inventoryFull = false;   // listing a sell offer moved items out of inventory → space freed
        dequeueSell(activeItem);
        pendingSellAmounts.remove(key);
        log(String.format(Locale.ROOT, "§6sell§r %s @ %.1f", activeItem, ourSellPrice));
        activeItem = null;
        phase = Phase.PLAN;
    }

    // ---- cancel/relist (order options screen is open at this point) ----

    private void pCancel(MinecraftClient mc) {
        statusLine = "cancelling " + cancelItem;
        // If clicking the order claimed it instead of opening "Order Options"
        // (it was claimable), we're still on the manage screen — bail to PLAN
        // immediately instead of hunting for a cancel button that isn't there.
        if (atManage(mc)) { phase = Phase.PLAN; return; }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CANCEL_ORDER)
                || GuiHelper.clickByName(mc, "cancel")) {
            String key = key(cancelItem);
            if (cancelIsBuy) {
                if (cancelRebuy) {
                    log("relisting buy: " + cancelItem);
                    activeItem = cancelItem;
                    startNav(cancelItem, Phase.BUY_OPEN);
                } else if (cancelSilent) {
                    // Duplicate merged: the better twin stays; refund redeploys.
                    log("merged duplicate buy of " + cancelItem + " (refund redeployed)");
                    phase = Phase.PLAN;
                } else {
                    // Relist war lost — walk away from this item for a while.
                    blacklistUntil.put(key, System.currentTimeMillis()
                            + config.blacklistMinutes * 60_000L);
                    relistCounts.remove(key);
                    orders.remove(key);
                    log("§e" + cancelItem + " is too contested — blacklisted "
                            + config.blacklistMinutes + "m");
                    phase = Phase.PLAN;
                }
            } else if (cancelBailInstasell) {
                // Sell war lost — dump the returned units instantly instead.
                pendingInstasell = cancelItem;
                pendingInstasellAmount = Math.max(1, cancelAmount);
                dequeueSell(cancelItem);
                pendingSellAmounts.remove(key);
                log("§esell war lost on " + cancelItem + " — instaselling for a clean exit");
                phase = Phase.PLAN;
            } else {
                // Unsold units return to the inventory — queue them (merged with
                // any newly claimed units) to be listed as ONE fresh offer.
                if (!queuedForSell(cancelItem)) pendingSells.addLast(cancelItem);
                pendingSellAmounts.merge(key, Math.max(0, cancelAmount), Integer::sum);
                log("relisting sell: " + cancelItem);
                phase = Phase.PLAN;
            }
        }
    }

    // ---- Booster Cookie refresh guard ----
    // The Bazaar is only reachable via /bz while the Cookie Buff is active, and the
    // buff counts down even while offline. The macro reads the remaining time every
    // cycle (cheap tab-list footer) and refreshes before it lapses. All Minecraft
    // interaction routes through the GuiHelper seam so the headless port can reuse
    // this logic. Each numbered comment below maps to a required failure-mode guard.

    private static final java.util.regex.Pattern DURATION_TOKEN =
            java.util.regex.Pattern.compile("(\\d+)\\s*([dhms])");

    /** Pure token sum: "1d 3h 22m" → ms. Minecraft-free so the headless port reuses it. */
    private static long parseDurationTokens(String s) {
        long ms = 0;
        java.util.regex.Matcher m = DURATION_TOKEN.matcher(s);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> ms += v * 86_400_000L;
                case "h" -> ms += v * 3_600_000L;
                case "m" -> ms += v * 60_000L;
                case "s" -> ms += v * 1_000L;
            }
        }
        return ms;
    }

    /** Remaining buff from the tab-list footer: >0 active, 0 expired, -1 unknown (#10). */
    private long readFooterRemain(MinecraftClient mc) {
        String footer = GuiHelper.tablistFooter(mc);
        if (footer.isEmpty() || !footer.contains(BazaarStrings.FOOTER_COOKIE)) return -1;
        for (String line : footer.split("\n")) {
            if (!line.contains(BazaarStrings.FOOTER_COOKIE)) continue;
            if (line.contains(BazaarStrings.COOKIE_EXPIRED)) return 0;
            long ms = parseDurationTokens(line);
            // Cookie line present but no parseable time and not "not active" → treat
            // as UNKNOWN, never as expired (#4: a blank read is not "safe").
            return ms > 0 ? ms : -1;
        }
        return -1;
    }

    /** Cheap GUI-free footer read run every tick. Keeps the last definite value on a
     *  transient blank, but forces UNKNOWN after a sustained blank so the guard can't
     *  coast on stale data (#4). */
    private void updateCookieTimer(MinecraftClient mc) {
        long r = readFooterRemain(mc);
        long now = System.currentTimeMillis();
        if (r >= 0) {
            cookieRemainMs = r;
            cookieStatus = r == 0 ? "§cEXPIRED" : fmtDuration(r);
            lastFooterOkAt = now;
        } else if (lastFooterOkAt != 0 && now - lastFooterOkAt > 120_000) {
            cookieRemainMs = -1;             // unreadable too long → don't trust the snapshot
            cookieStatus = "?";
        }
    }

    /** Top-of-cycle gate (#task). Returns true if it took over the tick. dryRun only
     *  reads + warns (#9). Distinguishes expired / low / unknown (#4). */
    private boolean maybeStartCookieRefresh(MinecraftClient mc) {
        if (!config.cookieRefreshEnabled || cookieRefreshActive || cookieError != null) return false;
        long remain = cookieRemainMs;
        long thr = (long) (config.cookieRefreshThresholdHours * 3_600_000L);

        if (remain < 0) {
            // #4: unknown is NOT safe. Resolve it authoritatively via /sbmenu on the
            // slow cadence rather than acting blind.
            if (System.currentTimeMillis() >= nextCookieCheck) { beginCookieRefresh(mc); return true; }
            return false;
        }
        if (remain == 0) {
            // #4: EXPIRED → /bz is unavailable. We can only recover by consuming a
            // cookie we already hold (consuming needs no Bazaar); buying can't work.
            if (config.dryRun) { statusLine = "DRY: cookie EXPIRED — would consume a held cookie"; return false; }
            if (GuiHelper.playerHasItem(mc, BazaarStrings.ITEM_COOKIE)) { beginCookieRefresh(mc); return true; }
            cookieHardStop("buff EXPIRED and no cookie held — Bazaar unreachable, cannot self-recover");
            return true;
        }
        if (remain <= thr) {
            if (config.dryRun) { statusLine = "DRY: cookie low (" + cookieStatus + ") — would refresh"; return false; }
            beginCookieRefresh(mc);
            return true;
        }
        return false;
    }

    /** Enter a refresh cycle: snapshot restore state, then drive COOKIE_CHECK.
     *  #1: order-monitoring state lives in the orders/pendingSells maps and is
     *  re-derived from the live grid on return to PLAN — closing/reopening GUIs for
     *  the refresh never drops it, so there is nothing heavier to snapshot. */
    private void beginCookieRefresh(MinecraftClient mc) {
        cookieRefreshActive = true;
        cookieStage = 0;
        cookieRetries = 0;
        cookieVerifyTicks = 0;
        cookieSlotSelected = false;
        cookieHotbarSlot = -1;
        cookieBeforeMs = cookieRemainMs;
        cookiePrevSelected = GuiHelper.selectedHotbarSlot(mc);   // restored afterward (#12)
        cookiePhaseTicks = 0;
        statusLine = "cookie: refreshing…";
        phase = Phase.COOKIE_CHECK;
    }

    /** Leave the refresh cycle cleanly and hand back to normal flipping (#1). */
    private void finishCookieRefresh(MinecraftClient mc, boolean success) {
        if (cookiePrevSelected >= 0) GuiHelper.setSelectedHotbarSlot(mc, cookiePrevSelected);   // #12
        cookieRefreshActive = false;
        cookieStage = 0;
        cookieSlotSelected = false;
        cookieHotbarSlot = -1;
        cookiePrevSelected = -1;
        cookieVerifyTicks = 0;
        // Slow cadence on success; short retry window if we bailed (e.g. couldn't afford).
        nextCookieCheck = System.currentTimeMillis()
                + (success ? config.cookieCheckHours * 3_600_000L : 10 * 60_000L);
        phase = Phase.PLAN;
    }

    /** HARD STOP on an unrecoverable cookie failure — surfaces on the HUD (#3/#4). */
    private void cookieHardStop(String reason) {
        cookieError = reason;
        note("§c§lCOOKIE REFRESH FAILED§r — " + reason);
        cookieTeardown(MinecraftClient.getInstance());   // #8
        stop("cookie: " + reason);
    }

    /** Bounded retry (#3): count an attempt; past the cap, HARD STOP — never an
     *  unbounded buy-and-consume loop (a cookie is ~13-14M coins). */
    private void retryOrAbortConsume(MinecraftClient mc, String reason) {
        cookiePhaseTicks = 0;
        cookieVerifyTicks = 0;
        cookieSlotSelected = false;
        if (mc != null && GuiHelper.openChest(mc) != null && mc.player != null) mc.player.closeHandledScreen();
        if (++cookieRetries > config.cookieConsumeMaxRetries) {
            // Stage in the message distinguishes "confirm popup never opened" (stage
            // 1) from "confirm click didn't register / buff didn't rise" (stage 2).
            cookieHardStop("consume failed after " + config.cookieConsumeMaxRetries
                    + " retries at stage " + cookieStage + " (" + reason + ")");
            return;
        }
        note("§econsume retry " + cookieRetries + "/" + config.cookieConsumeMaxRetries
                + " — stage " + cookieStage + " (" + reason + ")");
        cookieStage = 0;
        phase = Phase.COOKIE_CHECK;   // re-derive from scratch (re-read, re-locate cookie)
    }

    /** Coarse per-stage watchdog fired from the early-return block. */
    private void cookieStageTimedOut(MinecraftClient mc, String reason) {
        retryOrAbortConsume(mc, reason);
    }

    /** Close any GUI, restore the selected slot, leave no cursor item (#8). The
     *  cookie move uses SWAP (never PICKUP), so nothing is ever left on the cursor;
     *  a displaced item stays safely in inventory. */
    private void cookieTeardown(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) {
            mc.player.closeHandledScreen();
        }
        if (cookiePrevSelected >= 0) { GuiHelper.setSelectedHotbarSlot(mc, cookiePrevSelected); cookiePrevSelected = -1; }
        cookieRefreshActive = false;
        cookieStage = 0;
        cookieSlotSelected = false;
    }

    static boolean isCookiePhase(Phase p) {
        return p == Phase.COOKIE_CHECK || p == Phase.COOKIE_INSTABUY || p == Phase.COOKIE_AMOUNT
                || p == Phase.COOKIE_MOVE || p == Phase.COOKIE_USE || p == Phase.COOKIE_CONFIRM
                || p == Phase.COOKIE_VERIFY;
    }

    /** Authoritative timer read from the cookie item's lore in /sbmenu; 0 = "not
     *  active", -1 = unknown. Also opens /sbmenu (paced, not a fixed sleep — #7). */
    private void pCookieCheck(MinecraftClient mc) {
        statusLine = "cookie: reading buff…";
        var chest = GuiHelper.openChest(mc);
        if (chest != null && GuiHelper.screenTitle(mc).contains(BazaarStrings.TITLE_SBMENU)) {
            long remainMs = parseCookieRemaining(mc);
            if (remainMs > 0) { cookieRemainMs = remainMs; cookieStatus = fmtDuration(remainMs); }
            cookieBeforeMs = remainMs;   // baseline for the post-consume verify (#3)
            long thr = (long) (config.cookieRefreshThresholdHours * 3_600_000L);
            // -1 unknown is NOT treated as safe (#4): if we got here we already
            // believed it low, so proceed to consume rather than cancel on a blank.
            boolean stillLow = remainMs < 0 || remainMs <= thr;
            if (!stillLow) {   // recovered since the footer trigger — done
                if (mc.player != null) mc.player.closeHandledScreen();
                finishCookieRefresh(mc, true);
                return;
            }
            if (GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE) >= 0) {
                phase = Phase.COOKIE_MOVE;                       // consume a held cookie
            } else if (config.buyCookieWhenLow && remainMs != 0) {
                // #6: instant-buy one first (only when allowed and the Bazaar is still
                // reachable — a 0/expired buff means /bz is down, handled below).
                if (mc.player != null) mc.player.closeHandledScreen();
                startNav("Booster Cookie", Phase.COOKIE_INSTABUY);
            } else if (remainMs == 0) {
                if (mc.player != null) mc.player.closeHandledScreen();
                cookieHardStop("buff EXPIRED and none held — cannot buy (Bazaar needs an active buff)");
            } else {
                if (mc.player != null) mc.player.closeHandledScreen();
                cookieHardStop("buff low and no cookie held (buyCookieWhenLow=false) — refusing to churn");
            }
            return;
        }
        if (chest != null) { if (mc.player != null) mc.player.closeHandledScreen(); return; }
        // Not in /sbmenu yet — open it, paced by a cooldown (wait-for-condition, #7).
        if (cookieCmdCooldown > 0) { cookieCmdCooldown--; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) nh.sendChatCommand("sbmenu");
        cookieCmdCooldown = 6;
    }

    /** Parse the cookie lore duration in /sbmenu; 0 if "not active"; -1 unknown. */
    private long parseCookieRemaining(MinecraftClient mc) {
        int idx = GuiHelper.findSlotByName(mc, BazaarStrings.ITEM_COOKIE);
        var chest = GuiHelper.openChest(mc);
        if (idx < 0 || chest == null) return -1;
        for (String line : GuiHelper.lore(chest.getSlot(idx).getStack())) {
            if (line.contains(BazaarStrings.LORE_NOT_ACTIVE)) return 0;
            if (!line.contains(BazaarStrings.LORE_DURATION)) continue;
            return parseDurationTokens(line);
        }
        return -1;
    }

    /** Instant-buy ONE cookie (#6), with an escrow-aware affordability check (#5). */
    private void pCookieInstabuy(MinecraftClient mc) {
        statusLine = "cookie: buying (instant)…";
        double price = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_INSTABUY, "per unit");
        // #5: `purse` already reads NET of coins escrowed by open buy orders, so the
        // live figure is the truth. The cookie takes PRIORITY over coinReserve —
        // without it every open order is stranded — so we only refuse when the LIVE
        // price genuinely can't be paid (or exceeds the price cap), and then PAUSE
        // cleanly (retry soon) instead of churning. The purchase is a market buy and
        // is NEVER booked into the coins/hr tracker, so it can't count as a flip loss.
        if (!Double.isNaN(price)) {
            if (config.cookieMaxPrice > 0 && price > config.cookieMaxPrice) {
                note(String.format(Locale.ROOT, "§ecookie %,.0f over cap %,.0f — pausing refresh, retry soon",
                        price, config.cookieMaxPrice));
                if (mc.player != null) mc.player.closeHandledScreen();
                finishCookieRefresh(mc, false);
                return;
            }
            if (!Double.isNaN(purse) && purse < price) {
                note(String.format(Locale.ROOT, "§ecan't afford cookie (%,.0f > purse %,.0f) — pausing; open orders keep working",
                        price, purse));
                if (mc.player != null) mc.player.closeHandledScreen();
                finishCookieRefresh(mc, false);
                return;
            }
        }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_INSTABUY)) phase = Phase.COOKIE_AMOUNT;
    }

    private void pCookieAmount(MinecraftClient mc) {
        statusLine = "cookie: amount 1…";
        if (GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE) >= 0) {
            phase = Phase.COOKIE_MOVE;   // purchase landed in inventory
            return;
        }
        if (GuiHelper.clickByName(mc, "buy 1")) return;   // preset "Buy 1!"
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_AMOUNT)) requestSign("1", Phase.COOKIE_AMOUNT);
    }

    /** Move the cookie into the hotbar, preferring an EMPTY slot so no gear is
     *  displaced (#12). This SWAP happens inside the still-open /sbmenu chest, which
     *  exposes the player inventory — no separate survival-inventory open needed. */
    private void pCookieMove(MinecraftClient mc) {
        statusLine = "cookie: to hotbar…";
        // Already in the hotbar? use it in place (nothing to move/restore).
        int already = GuiHelper.hotbarSlotOf(mc, BazaarStrings.ITEM_COOKIE);
        if (already >= 0) { cookieHotbarSlot = already; phase = Phase.COOKIE_USE; return; }
        int idx = GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE);
        if (idx < 0) { retryOrAbortConsume(mc, "cookie not found to move"); return; }  // never consume nothing (#12)
        int empty = GuiHelper.firstEmptyHotbarSlot(mc);
        int target = empty >= 0 ? empty : 8;   // empty preferred; else displace slot 8
        if (GuiHelper.swapToHotbar(mc, idx, target)) { cookieHotbarSlot = target; phase = Phase.COOKIE_USE; }
    }

    /** Stage 1 (#11): select the cookie slot, brief paced wait, then USE it (a world
     *  interaction) which opens the confirmation GUI. */
    private void pCookieUse(MinecraftClient mc) {
        cookieStage = 1;
        // #1: consuming needs the container CLOSED — the use is a world interaction.
        if (GuiHelper.openChest(mc) != null) { if (mc.player != null) mc.player.closeHandledScreen(); return; }
        if (cookieHotbarSlot < 0) cookieHotbarSlot = GuiHelper.hotbarSlotOf(mc, BazaarStrings.ITEM_COOKIE);
        if (cookieHotbarSlot < 0) { retryOrAbortConsume(mc, "cookie left the hotbar"); return; }
        // #12: switch → (paced tick) → use, so it never looks like an instant robo-flick.
        if (!cookieSlotSelected) {
            GuiHelper.setSelectedHotbarSlot(mc, cookieHotbarSlot);
            cookieSlotSelected = true;
            statusLine = "cookie: consuming (1/2 selected)…";
            return;
        }
        statusLine = "cookie: consuming (1/2 use)…";
        GuiHelper.useHeldItem(mc);       // #2: sky-aimed item-use, never a block interaction
        cookieSlotSelected = false;
        phase = Phase.COOKIE_CONFIRM;    // #7/#11: stage 2 waits for the popup to open
    }

    /** Stage 2 (#11): only after VERIFYING the popup is the cookie-consume
     *  confirmation, click the CONFIRM slot by name (never a cancel/close). */
    private void pCookieConfirm(MinecraftClient mc) {
        cookieStage = 2;
        statusLine = "cookie: consuming (2/2 confirm)…";
        // #11: never blind-click — require the confirmation GUI's title first. If it
        // hasn't opened yet, wait; the coarse checkStuck watchdog forces a retry if
        // the popup never comes (stage-1 failure).
        if (!GuiHelper.handledScreenTitleContains(mc, BazaarStrings.TITLE_COOKIE_CONFIRM)) return;
        if (GuiHelper.clickByName(mc, BazaarStrings.CONFIRM_CONSUME_COOKIE)) {
            phase = Phase.COOKIE_VERIFY;   // re-read the timer to prove it took (#3/#11)
        }
    }

    /** Re-read the buff timer to confirm the consume actually took (#3/#11), giving
     *  the footer a few paced ticks to update before judging. */
    private void pCookieVerify(MinecraftClient mc) {
        statusLine = "cookie: verifying…";
        // #8: make sure no confirm popup lingers before the world read.
        if (GuiHelper.openChest(mc) != null) { if (mc.player != null) mc.player.closeHandledScreen(); return; }
        long remain = readFooterRemain(mc);
        long thr = (long) (config.cookieRefreshThresholdHours * 3_600_000L);
        boolean took = remain > thr || (cookieBeforeMs >= 0 && remain > cookieBeforeMs + 60_000L);
        if (took) {
            cookieRemainMs = remain;
            cookieStatus = fmtDuration(remain);
            note("§acookie refreshed — buff " + cookieStatus);
            finishCookieRefresh(mc, true);
            return;
        }
        // Not risen yet: let the footer catch up for a few paced ticks; only then
        // treat a definite no-increase as a failed consume and retry (#3).
        if (++cookieVerifyTicks < 8) return;
        if (remain < 0) return;                          // still unreadable — wait for the watchdog
        retryOrAbortConsume(mc, "buff didn't increase after consume");
    }

    static String fmtDuration(long ms) {
        long d = ms / 86_400_000L, h = (ms % 86_400_000L) / 3_600_000L;
        return d > 0 ? d + "d" + h + "h" : h + "h" + ((ms % 3_600_000L) / 60_000L) + "m";
    }

    /** On the product page: click "Sell Instantly" — an immediate guaranteed exit. */
    private void pInstasell(MinecraftClient mc) {
        statusLine = "instaselling " + pendingInstasell;
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_INSTASELL)) {
            String key = key(pendingInstasell);
            OrderInfo oi = orders.remove(key);
            FlipCandidate q = api.quote(key);
            if (oi != null && q != null && !Double.isNaN(oi.buyPrice)) {
                double profit = (q.topBuyOrder * (1.0 - config.taxFraction) - oi.buyPrice)
                        * pendingInstasellAmount;
                tracker.addProfit(profit);
                allTimeProfit += profit;
                stateDirty = true;
                log(String.format(Locale.ROOT, "§ebailed§r %d× %s  %+,.0f coins",
                        pendingInstasellAmount, pendingInstasell, profit));
            } else {
                log("instasold " + pendingInstasell);
            }
            relistCounts.remove(key);
            blacklistUntil.put(key, System.currentTimeMillis()
                    + config.blacklistMinutes * 60_000L);
            inventoryFull = false;   // instasell emptied those items from inventory
            pendingInstasell = null;
            activeItem = null;
            phase = Phase.PLAN;
        }
    }

    // ---- screen predicates ----

    private boolean atProduct(MinecraftClient mc) {
        return GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_BUY_ORDER)
                && GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_SELL_OFFER);
    }

    private boolean atManage(MinecraftClient mc) {
        return GuiHelper.screenTitle(mc).contains(BazaarStrings.TITLE_MANAGE);
    }

    private boolean atMain(MinecraftClient mc) {
        return GuiHelper.screenTitle(mc).contains(BazaarStrings.TITLE_BAZAAR)
                && !atProduct(mc) && !atManage(mc);
    }

    private boolean arrivedAtProduct(MinecraftClient mc, String item) {
        // The title is often truncated ("...➜ lesser soulfl"), so verify by the
        // product's own item icon on the page, which carries the full name.
        // Normalized match so symbol-prefixed items ("☘ Fine Peridot Gemstone",
        // "⚚ …") still register as arrived — an exact match misses the prefix.
        return atProduct(mc) && GuiHelper.hasNormItemNamed(mc, item);
    }

    private boolean goToManage(MinecraftClient mc) {
        if (atManage(mc)) return true;
        if (atMain(mc)) {
            if (!GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ORDERS)) {
                GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ALT);
            }
        } else {
            // Escape whatever screen this is. Some (e.g. confirm pages) have no
            // "Go Back" — try Close/Cancel, then hard-reset via /bz so PLAN can
            // never wedge here (checkStuck deliberately ignores PLAN).
            if (!GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK)
                    && !GuiHelper.clickByName(mc, "close")
                    && !GuiHelper.clickByName(mc, "cancel")) {
                openBazaar(mc);
            }
        }
        return false;
    }

    // ---- helpers ----

    /** Unrealized profit locked in open SELL offers — what we'd bank if every
     *  current offer filled (unsold portion × (sell·(1−tax) − buy)). */
    public double projectedProfit() {
        double sum = 0;
        for (ParsedOrder o : lastOrders) {
            if (o.buy()) continue;
            OrderInfo oi = orders.get(o.key());
            if (oi == null || Double.isNaN(oi.buyPrice)) continue;
            double sellP = !Double.isNaN(o.pricePerUnit()) ? o.pricePerUnit() : oi.sellPrice;
            if (Double.isNaN(sellP)) continue;
            int remaining = Math.max(0,
                    (int) Math.round(o.amount() * (1 - Math.min(100.0, o.filledPct()) / 100.0)));
            sum += (sellP * (1 - config.taxFraction) - oi.buyPrice) * remaining;
        }
        return sum;
    }

    private void updateTopCandidate() {
        refreshRankingSnapshot();
        var cs = api.getCandidates();
        if (!cs.isEmpty()) {
            FlipCandidate c = cs.get(0);
            topCandidate = String.format(Locale.ROOT, "%s %.1f%% σ%.1f%% %s%.1f%%%s",
                    c.displayName, c.margin(config.taxFraction) * 100, c.volatility * 100,
                    c.trend >= 0 ? "§a↑§7" : "§c↓§7", Math.abs(c.trend) * 100, topStatus(c));
        }
    }

    /** Why the top-ranked candidate isn't necessarily being bought right now —
     *  shown after "top:" so the HUD explains itself (it's usually "held": we
     *  already have an order for it, so we don't place a duplicate). "" if it's
     *  genuinely the next thing we'd buy. */
    private String topStatus(FlipCandidate c) {
        String k = key(c.displayName);
        long now = System.currentTimeMillis();
        boolean held = orders.containsKey(k)
                || lastOrders.stream().anyMatch(o -> o.key().equals(k))
                || pendingSells.stream().anyMatch(s -> key(s).equals(k));
        if (held) return " §8·held§7";
        long bl = blacklistUntil.getOrDefault(k, 0L);
        if (bl > now) return " §8·benched " + ((bl - now) / 60_000 + 1) + "m§7";
        if (efficiency.getOrDefault(k, 1.0) < config.minEfficiency) return " §8·low-eff§7";
        double spend = perOrderBudget();
        if (spend > 0 && c.ourBuyPrice() > spend) return " §8·too pricey§7";
        double volValue = c.hourlyVolume() * config.orderVolumeFraction * c.ourBuyPrice();
        if (config.minOrderValue > 0 && volValue < config.minOrderValue) return " §8·thin§7";
        return "";   // actionable — this really is the next buy
    }

    private int signWaitTicks = 0;

    private void requestSign(String text, Phase next) {
        pendingSignText = text; phaseAfterSign = next; signWaitTicks = 0; phase = Phase.WAIT_SIGN;
    }

    private void checkStuck() {
        boolean waiting = phase == Phase.PLAN || phase == Phase.WAIT_SIGN;
        if (phase == lastPhase && !waiting) {
            if (++stuckTicks > STUCK_LIMIT) {
                // A stuck COOKIE_* phase (e.g. the confirm popup never opened) must
                // go through the BOUNDED consume retry, NOT the generic PLAN reset
                // below — the reset would strand cookieRefreshActive=true and the
                // guard would never fire again. Stage in the message = #3 diagnosis.
                if (isCookiePhase(phase)) {
                    stuckTicks = 0;
                    retryOrAbortConsume(MinecraftClient.getInstance(), "stuck in " + phase);
                    return;
                }
                log("stuck in " + phase + " — recovering");
                // A BUY-side navigation dead-end (page never loads, unreachable
                // item) would re-pick the same item and loop forever — bench it.
                // Sell-side navs must keep retrying: we're holding the goods.
                if (phase == Phase.NAV_SEARCH && navAfter == Phase.BUY_OPEN && navItem != null) {
                    blacklistUntil.put(key(navItem),
                            System.currentTimeMillis() + config.blacklistMinutes * 60_000L);
                    note("§ecouldn't reach " + navItem + " — benched " + config.blacklistMinutes + "m");
                }
                activeItem = null;
                GuiHelper.clickByName(MinecraftClient.getInstance(), BazaarStrings.BTN_GO_BACK);
                phase = Phase.PLAN;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
            lastPhase = phase;
        }
    }

    private void resetDelay() {
        int jitter = config.actionJitterTicks > 0
                ? (int) (Math.random() * (config.actionJitterTicks + 1)) : 0;
        delayTimer = Math.max(1, config.actionDelayTicks + jitter);
    }

    /** True if the SkyBlock sidebar shows a restart countdown ("Server closing…"). */
    private boolean serverClosingNow(MinecraftClient mc) {
        for (String l : ScoreboardReader.sidebarLines(mc)) {
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains("closing") || low.contains("restarting")) return true;
        }
        return false;
    }

    private static String fmt1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static String longestToken(String s) {
        String best = "";
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) if (w.length() > best.length()) best = w;
        return best;
    }

    private void log(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("§b[BZ] §r" + msg), false);
        String plain = msg.replaceAll("§.", "");
        System.out.println("[bzflipper] " + plain);
        logLines.addLast(new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date()) + "  " + plain);
        while (logLines.size() > 300) logLines.pollFirst();
    }

    public FlipConfig getConfig() { return config; }

    /** Benched (blacklisted) items with minutes remaining, for the dashboard. */
    public List<String> benchedList() {
        long now = System.currentTimeMillis();
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : blacklistUntil.entrySet()) {
            long left = e.getValue() - now;
            if (left > 0) out.add(e.getKey() + " (" + (left / 60_000) + "m)");
        }
        return out;
    }

    /** Public so AutoReconnect can surface reconnect status in the Activity feed;
     *  log() is null-safe, so this works even while disconnected (player == null). */
    public void note(String msg) {
        if (msg.equals(lastNote)) return;
        lastNote = msg;
        log(msg);
    }
}
