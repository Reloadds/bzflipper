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
        COOKIE_CHECK, COOKIE_INSTABUY, COOKIE_AMOUNT, COOKIE_MOVE, COOKIE_USE, COOKIE_CONFIRM
    }

    private static final int STUCK_LIMIT = 40;
    /** Ignore price differences below half a tick (float noise). */
    private static final double EPS = PriceMath.TICK / 2;
    /** Don't prune an order from memory until it's had time to appear in the grid. */
    private static final long PRUNE_AGE_MS = 90_000;

    private int orderLimit;                 // total slots (learned from chat)
    private long dailyLimitUntil = 0;       // pause new orders until this time
    private boolean serverClosing = false;  // Hypixel restart in progress → pause
    private Object lastWorld = null;         // detect world change (rejoin)
    private boolean inventoryFull = false;   // server said "no space" → sell before claiming
    private boolean stashPending = false;    // materials went to the stash → recover them

    // Booster Cookie automation.
    private long nextCookieCheck = 0;
    private int cookieCmdCooldown = 0;
    public volatile String cookieStatus = "?";

    /** Canonical item key (shared with OrderParser/BazaarApi). */
    private static String key(String s) { return Keys.norm(s); }

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
    private boolean activeBypassInv = false;  // essence/shards bypass inventory
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
    private final Map<String, OrderInfo> orders = new HashMap<>();
    /** Realized-efficiency EMA per item (realized ÷ quoted profit); default 1.0. */
    private final Map<String, Double> efficiency = new HashMap<>();
    /** All-time realized profit per item (for the session report best/worst). */
    private final Map<String, Double> itemProfit = new HashMap<>();
    /** Canonical keys of everything we've ever bought — so leftover goods get
     *  sold even if their order was pruned/forgotten. Personal items are never here. */
    private final Set<String> boughtItems = new HashSet<>();

    /** Relist-war protection: per-item relist counts and temporary blacklist. */
    private final Map<String, Integer> relistCounts = new HashMap<>();
    private final Map<String, Long> blacklistUntil = new HashMap<>();
    private boolean cancelRebuy = true;   // whether pCancel should relist the buy

    // ---- Persistence (survives relogs: leftover goods get re-listed) ----
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().create();
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
            if (!serverClosing) { serverClosing = true; saveState(); note("§eserver restarting — pausing"); }
        } else if (m.contains("space required to claim") || m.contains("don't have the space")
                || m.contains("inventory is full")) {
            if (!inventoryFull) { inventoryFull = true; note("§einventory full — selling to free space before claiming"); }
        } else if (m.contains("consumed") && m.contains("booster cookie")) {
            note("§acookie consumed — buff renewed!");
            cookieStatus = "~4d";
            nextCookieCheck = System.currentTimeMillis() + 10 * 60_000L;   // verify soon
            if (phase == Phase.COOKIE_CONFIRM || phase == Phase.COOKIE_USE) phase = Phase.PLAN;
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
        double allTimeProfit = 0;
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
            if (s.efficiency != null) efficiency.putAll(s.efficiency);
            if (s.itemProfit != null) itemProfit.putAll(s.itemProfit);
            allTimeProfit = s.allTimeProfit;
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
            s.efficiency.putAll(efficiency);
            s.itemProfit.putAll(itemProfit);
            s.allTimeProfit = allTimeProfit;
            java.nio.file.Files.writeString(statePath(), GSON.toJson(s));
            stateDirty = false;
            lastStateSave = System.currentTimeMillis();
        } catch (Exception e) {
            System.err.println("[bzflipper] state save failed: " + e.getMessage());
        }
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
        nextCookieCheck = System.currentTimeMillis() + 30_000L;   // check shortly after start
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
        statusLine = "stopped: " + why;
        log("stopped: " + why);
        saveState();
        if (why.contains("toggled") || why.contains("panic")) reportSession();
    }

    /** Write a session summary to chat + config/bzflipper-report.txt. */
    private void reportSession() {
        long secs = tracker.elapsedSeconds();
        var top = itemProfit.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();

        StringBuilder f = new StringBuilder();
        f.append("=== bzflipper session report ===\n");
        f.append(String.format(Locale.ROOT, "uptime           %dh %dm%n", secs / 3600, (secs % 3600) / 60));
        f.append(String.format(Locale.ROOT, "session profit   %,.0f  (%,.0f/hr, %,.0f/hr recent)%n",
                tracker.total(), tracker.sessionPerHour(), tracker.recentPerHour()));
        f.append(String.format(Locale.ROOT, "all-time profit  %,.0f%n", allTimeProfit));
        f.append(String.format(Locale.ROOT, "open-offer value +%,.0f (unrealized)%n", projectedProfit()));
        f.append(String.format(Locale.ROOT, "flips %d   fills %dB/%dS   orders placed %d%n",
                flipsCompleted, buysFilled, sellsFilled, ordersPlaced));
        f.append(String.format(Locale.ROOT, "cookie buff      %s%n", cookieStatus));
        f.append("\n-- best items --\n");
        for (int i = 0; i < Math.min(6, top.size()); i++) {
            f.append(String.format(Locale.ROOT, "  %+,.0f  %s%n", top.get(i).getValue(), top.get(i).getKey()));
        }
        boolean anyLoss = !top.isEmpty() && top.get(top.size() - 1).getValue() < 0;
        if (anyLoss) {
            f.append("-- worst items --\n");
            for (int i = top.size() - 1; i >= 0 && top.get(i).getValue() < 0; i--) {
                f.append(String.format(Locale.ROOT, "  %+,.0f  %s%n", top.get(i).getValue(), top.get(i).getKey()));
            }
        }

        try {
            java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("bzflipper-report.txt"), f.toString());
        } catch (Exception ignored) {
        }

        log("§b── session report ──");
        log(String.format(Locale.ROOT, "uptime %dh%dm · profit §a%,.0f§r (%,.0f/hr) · all-time §a%,.0f",
                secs / 3600, (secs % 3600) / 60, tracker.total(), tracker.sessionPerHour(), allTimeProfit));
        log(String.format(Locale.ROOT, "flips %d · %,.0f in open offers · full report → config/bzflipper-report.txt",
                flipsCompleted, projectedProfit()));
        if (!top.isEmpty()) {
            log(String.format(Locale.ROOT, "best: §a%+,.0f§r %s", top.get(0).getValue(), top.get(0).getKey()));
        }
    }

    // ---- main loop ----
    public void onTick(MinecraftClient mc) {
        if (!enabled || mc.player == null) return;
        if (delayTimer > 0) { delayTimer--; return; }
        resetDelay();

        // Server restart handling: pause on the way out; resume after rejoin.
        if (mc.world != lastWorld) { lastWorld = mc.world; serverClosing = false; }
        if (serverClosing || serverClosingNow(mc)) {
            if (!serverClosing) { serverClosing = true; saveState(); log("§eserver restarting — paused until rejoin"); }
            statusLine = "server restarting — paused";
            return;
        }

        if (buyCooldown > 0) buyCooldown--;

        purse = PurseReader.readPurse(mc);
        updateTopCandidate();

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
            if (GuiHelper.openChest(mc) != null) { mc.setScreen(null); return; }
            var nh = mc.getNetworkHandler();
            if (nh != null) { nh.sendChatCommand("pickupstash"); log("recovering stashed materials (/pickupstash)"); }
            stashPending = false;
            phase = Phase.PLAN;
            return;
        }

        // Cookie check needs the SkyBlock Menu (/sbmenu), and consuming needs the
        // GUI CLOSED — both handled here, outside the open-bazaar flow.
        if (phase == Phase.COOKIE_CHECK) { pCookieCheck(mc); return; }
        if (phase == Phase.COOKIE_USE)   { pCookieUse(mc); return; }

        if (GuiHelper.openChest(mc) == null) {
            openBazaar(mc);
            return;
        }

        GuiDump.autoDump(mc);   // capture each new Bazaar screen for string tuning
        checkStuck();

        switch (phase) {
            case PLAN         -> pPlan(mc);
            case NAV_SEARCH   -> pNavSearch(mc);
            case WAIT_SIGN    -> statusLine = "waiting for sign…";
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
            case COOKIE_CONFIRM  -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CONSUME)) { nextCookieCheck = System.currentTimeMillis() + 10 * 60_000L; phase = Phase.PLAN; } }
            case COOKIE_CHECK, COOKIE_USE -> { /* handled before the openChest check */ }
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
        adoptAndPrune(grid, invKeys);
        sweepLeftovers(invNames, grid);

        boolean slotFree = grid.size() < orderLimit;                       // 14/21/28
        boolean dailyOk = System.currentTimeMillis() >= dailyLimitUntil;   // daily coin limit

        // 0) Booster Cookie upkeep (rare — every cookieCheckHours).
        if (config.autoCookie && !config.dryRun
                && System.currentTimeMillis() >= nextCookieCheck) {
            phase = Phase.COOKIE_CHECK;
            return;
        }

        // 1) Claim goods/coins the moment they're claimable — Hypixel marks an
        //    order "Click to claim!" as soon as ANY of it fills, so this claims
        //    partial fills continuously (the order keeps working for the rest).
        for (ParsedOrder o : grid) {
            if (!o.claimable()) continue;
            if (config.dryRun) { note("DRY: would claim " + o.item()); continue; }
            if (o.buy()) {
                // BRAIN RULE: never claim more of an item we're still holding
                // unsold — list what we have first, then claim the next batch.
                // (Also closes the stale-inventory overflow → stash window.)
                boolean holdingUnsold = pendingSells.stream().anyMatch(s -> key(s).equals(o.key()));
                if (holdingUnsold) { statusLine = "listing " + o.item() + " before claiming more"; continue; }
                // Don't attempt a claim that won't fit ("You don't have the space
                // required to claim that!"). The sell steps free space first.
                if (!hasSpaceToClaim(mc, o)) {
                    note("§einventory full§r — selling to free space before claiming " + o.item());
                    continue;
                }
            }
            statusLine = "claiming " + (o.buy() ? "bought " : "sold ") + o.item();
            if (GuiHelper.clickSlotIndex(mc, o.slot())) {
                if (o.buy()) onBuyClaimed(o);
                else onSellClaimed(o, grid);
            }
            return; // one action per pass
        }

        // 1.5) Dump bail-out goods via Sell Instantly (guaranteed exit).
        if (pendingInstasell != null) {
            activeItem = pendingInstasell;
            startNav(pendingInstasell, Phase.SELL_INSTANT);
            return;
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
                pendingSells.removeFirstOccurrence(item);
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
                // Only de-dup BUY orders (they waste order slots + split queue).
                // Multiple SELL offers per item are fine — they all sell — and
                // merging them means cancelling (refund → stash risk) for no gain.
                if (!o.buy()) continue;
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
            FlipCandidate q = api.quote(o.key());
            if (q == null || Double.isNaN(o.pricePerUnit())) continue;
            boolean beaten = o.buy()
                    ? q.topBuyOrder > o.pricePerUnit() + EPS       // someone bids higher than us
                    : q.lowestSellOffer < o.pricePerUnit() - EPS;  // someone offers lower than us
            if (!beaten) continue;
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
            int relists = relistCounts.merge(o.key(), 1, Integer::sum);
            statusLine = "beaten on " + o.item() + " — cancelling (relist #" + relists + ")";
            cancelIsBuy = o.buy();
            // Buy side: give up after too many relist wars (manipulated/contested item).
            cancelRebuy = !o.buy() || relists <= config.maxRelistsPerOrder;
            // Sell side: after too many wars stop relisting — instasell for a
            // guaranteed exit so the capital moves to the next flip.
            cancelBailInstasell = !o.buy() && relists > config.maxRelistsPerOrder;
            cancelSilent = false;
            cancelItem = o.item();
            cancelAmount = o.amount();
            if (GuiHelper.clickSlotIndex(mc, o.slot())) { markCancelled(o.item()); phase = Phase.CANCEL; }
            return;
        }

        // 3.5) Exit dead buy orders: 0% filled after buyStallMinutes means the
        //      coins are doing nothing — cancel, blacklist briefly, redeploy.
        long tNow = System.currentTimeMillis();
        for (ParsedOrder o : grid) {
            if (!o.buy() || o.filledPct() > 0 || o.claimable()) continue;
            OrderInfo oi = orders.get(o.key());
            if (oi == null || tNow - oi.placedAt < config.buyStallMinutes * 60_000L) continue;
            if (config.dryRun) { note("DRY: would exit stalled " + o.item()); continue; }
            statusLine = "stalled " + o.item() + " — freeing capital";
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
        //    Cap concurrent flips at half the order limit (each item = buy+sell),
        //    so it fills all your slots and scales with the Bazaar Flipper perk.
        int flipCap = Math.min(config.maxOpenOrders, Math.max(1, orderLimit / 2));
        if (orders.size() < flipCap && buyCooldown <= 0 && slotFree && dailyOk) {
            String pick = pickNextItem(grid);
            if (pick != null) {
                if (config.dryRun) {
                    statusLine = "DRY: would buy " + pick;
                    note("§eDRY RUN§r — would buy §f" + pick + "§r (set dryRun:false to trade)");
                    return;
                }
                activeItem = pick;
                startNav(pick, Phase.BUY_OPEN);
                return;
            }
        }
        statusLine = String.format(Locale.ROOT, "monitoring %dB/%dS — %s",
                buyCount, sellCount, idleReason());
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
        if (inventoryFull) return false;
        FlipCandidate q = api.quote(sellOrder.item());
        String tag = q != null ? q.tag : null;
        int stack = tag != null ? ItemNames.stackSize(tag) : 64;
        int remaining = Math.max(1, sellOrder.amount()
                - (int) Math.floor(sellOrder.amount() * sellOrder.filledPct() / 100.0));
        int needed = (int) Math.ceil(remaining / (double) stack);
        return GuiHelper.freeInventorySlots(mc) >= needed + 1;   // +1 slot safety
    }

    // Per-item cancel cooldown: a cancel takes a moment to reflect in the grid;
    // without this the same offer gets cancel-clicked twice.
    private String lastCancelKey = null;
    private long lastCancelAt = 0;

    private boolean recentlyCancelled(String item) {
        return key(item).equals(lastCancelKey)
                && System.currentTimeMillis() - lastCancelAt < 5_000;
    }

    private void markCancelled(String item) {
        lastCancelKey = key(item);
        lastCancelAt = System.currentTimeMillis();
    }

    /** Enough inventory space to claim this buy order? Essence/shards bypass it. */
    private boolean hasSpaceToClaim(MinecraftClient mc, ParsedOrder o) {
        FlipCandidate q = api.quote(o.item());
        String tag = q != null ? q.tag : null;
        String lower = o.item().toLowerCase(Locale.ROOT);
        if ((tag != null && ItemNames.bypassesInventory(tag))
                || lower.endsWith("essence") || lower.endsWith("shard")) {
            return true;   // goes to storage, not the inventory
        }
        if (inventoryFull) return false;   // server told us it's full — don't retry until we sell
        int stack = tag != null ? ItemNames.stackSize(tag) : 64;
        int amount = o.claimAmount() > 0 ? o.claimAmount() : stack;
        int needed = Math.max(1, (int) Math.ceil(amount / (double) stack));
        return GuiHelper.freeInventorySlots(mc) >= needed;
    }

    private void sweepLeftovers(Set<String> invNames, List<ParsedOrder> grid) {
        for (String name : invNames) {
            String k = key(name);
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
        stateDirty = true;
        if (Double.isNaN(oi.buyPrice)) oi.buyPrice = o.pricePerUnit();
        if (oi.amount <= 0) oi.amount = o.amount();

        // Units this claim banks: the grid tells us exactly ("N items to claim").
        int newUnits = o.claimAmount() > 0 ? o.claimAmount()
                : Math.max(1, (int) Math.floor(o.amount() * o.filledPct() / 100.0) - oi.claimedSoFar);
        oi.claimedSoFar = Math.min(Math.max(o.amount(), 1), oi.claimedSoFar + newUnits);

        buysFilled++;
        if (!pendingSells.contains(o.item())) pendingSells.addLast(o.item());
        pendingSellAmounts.merge(o.key(), newUnits, Integer::sum);
        log("claimed " + newUnits + "× " + o.item()
                + (o.filled() ? "" : String.format(Locale.ROOT, " (%.0f%% filled)", o.filledPct()))
                + " — will list for sale");
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
        if (newSold == 0) newSold = Math.max(1, total - prevSold);   // claimable but % unreadable
        if (oi != null) oi.soldSoFar = prevSold + newSold;

        sellsFilled++;
        if (!Double.isNaN(buyP) && !Double.isNaN(sellP)) {
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
            log(String.format(Locale.ROOT, "§asold§r %d× %s  %+,.0f coins  (session %,.0f)",
                    newSold, o.item(), profit, tracker.total()));
        } else {
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

    /** Why no new order is being opened right now (for the HUD/status). */
    private String idleReason() {
        if (orders.size() >= config.maxOpenOrders) return "at order cap";
        if (buyCooldown > 0) return "pacing";
        if (api.getCandidates().isEmpty()) return "no flips from API yet";
        if (Double.isNaN(purse)) return "purse unknown";
        double spend = (purse - config.coinReserve) * config.orderBudgetFraction;
        if (spend <= 0) return "nothing spendable";
        return "no new pick";
    }

    /** Coins to commit to the next order: spendable purse split evenly across the
     *  remaining open slots, capped so no single item hogs the book. */
    private double perOrderBudget() {
        double spendable = purse - config.coinReserve;
        if (Double.isNaN(spendable) || spendable <= 0) return 0;
        int freeSlots = Math.max(1, config.maxOpenOrders - orders.size());
        double even = spendable / freeSlots;
        double cap = spendable * Math.max(0.05, Math.min(1.0, config.orderBudgetFraction));
        return Math.min(even, cap);
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
            for (FlipCandidate c : api.getCandidates()) {
                String key = key(c.displayName);
                if (held.contains(key)) continue;
                if (blacklistUntil.getOrDefault(key, 0L) > now) continue; // benched item
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
                // Skip genuinely thin items (absolute floor, not purse-relative, so
                // a bigger purse never reduces how many items qualify).
                double volValue = c.hourlyVolume() * config.orderVolumeFraction * c.ourBuyPrice();
                if (config.minOrderValue > 0 && volValue < config.minOrderValue) continue;
                activeHourlyVol = c.hourlyVolume();
                activeBypassInv = ItemNames.bypassesInventory(c.tag);
                activeStackSize = ItemNames.stackSize(c.tag);
                activeVolatility = c.volatility;
                activeMargin = c.margin(config.taxFraction);
                return c.displayName;
            }
            return null;
        }
        for (FlipTarget t : config.targets) {
            if (!held.contains(key(t.product))) {
                activeHourlyVol = Double.MAX_VALUE;
                activeBypassInv = false;
                activeStackSize = 64;
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
            if (!Double.isNaN(topBuy) && !Double.isNaN(lowSell)) {
                ourBuyPrice = PriceMath.buyOrderPrice(topBuy);
                ourSellPrice = PriceMath.sellOfferPrice(lowSell);
            } else {
                FlipCandidate q = api.quote(key(navItem));
                if (q != null) { ourBuyPrice = q.ourBuyPrice(); ourSellPrice = q.ourSellPrice(); }
            }
            // SAFETY NET: never BUY an item whose LIVE product margin is out of
            // range (manipulation trap, or navigation landed on the wrong item).
            if (navAfter == Phase.BUY_OPEN && !Double.isNaN(ourBuyPrice) && ourBuyPrice > 0) {
                double m = (ourSellPrice * (1 - config.taxFraction) - ourBuyPrice) / ourBuyPrice;
                if (m < config.apiMinMargin || m > config.apiMaxMargin) {
                    note(String.format(Locale.ROOT, "§eskip %s§r: live margin %.0f%% out of range", navItem, m * 100));
                    blacklistUntil.put(key(navItem),
                            System.currentTimeMillis() + config.blacklistMinutes * 60_000L);
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
        int byVolume = (activeHourlyVol == Double.MAX_VALUE)
                ? config.maxUnitsPerOrder
                : (int) Math.max(1, activeHourlyVol * config.orderVolumeFraction);

        // Inventory-fit cap: essences/shards bypass inventory (unlimited); other
        // items must fit in free slots × stack size so a full claim never overflows
        // to the stash.
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
            buyCooldown = config.orderCooldownTicks;
            log(String.format(Locale.ROOT, "§abuy§r %d× %s @ %.1f  (%,.0f coins)",
                    activeAmount, activeItem, ourBuyPrice, activeAmount * ourBuyPrice));
            activeItem = null;
            phase = Phase.PLAN;
        }
    }

    // ---- sell ----

    private boolean priceAggressively() {
        return activeItem != null && relistCounts.getOrDefault(
                key(activeItem), 0) >= config.aggressiveAfterRelists;
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
                pendingSells.removeFirstOccurrence(activeItem);
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
        if (priceAggressively() && GuiHelper.clickByName(mc, BazaarStrings.BTN_SPREAD_BUY)) {
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

        if (priceAggressively() && GuiHelper.clickByName(mc, BazaarStrings.BTN_SPREAD_SELL)) {
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
        pendingSells.removeFirstOccurrence(activeItem);
        pendingSellAmounts.remove(key);
        log(String.format(Locale.ROOT, "§6sell§r %s @ %.1f", activeItem, ourSellPrice));
        activeItem = null;
        phase = Phase.PLAN;
    }

    // ---- cancel/relist (order options screen is open at this point) ----

    private void pCancel(MinecraftClient mc) {
        statusLine = "cancelling " + cancelItem;
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
                pendingSells.removeFirstOccurrence(cancelItem);
                pendingSellAmounts.remove(key);
                log("§esell war lost on " + cancelItem + " — instaselling for a clean exit");
                phase = Phase.PLAN;
            } else {
                // Unsold units return to the inventory — queue them (merged with
                // any newly claimed units) to be listed as ONE fresh offer.
                if (!pendingSells.contains(cancelItem)) pendingSells.addLast(cancelItem);
                pendingSellAmounts.merge(key, Math.max(0, cancelAmount), Integer::sum);
                log("relisting sell: " + cancelItem);
                phase = Phase.PLAN;
            }
        }
    }

    // ---- Booster Cookie automation ----
    // The buff halves bazaar tax (1.25% → 1.1%) and lasts 4 days per cookie; we
    // renew when remaining time drops under cookieRenewDays. Remaining time is
    // read from the cookie item's lore in the SkyBlock Menu (/sbmenu).

    private void pCookieCheck(MinecraftClient mc) {
        statusLine = "checking cookie buff…";
        var chest = GuiHelper.openChest(mc);
        String title = GuiHelper.screenTitle(mc);
        if (chest != null && title.contains(BazaarStrings.TITLE_SBMENU)) {
            long remainMs = parseCookieRemaining(mc);
            cookieStatus = remainMs < 0 ? "?" : fmtDuration(remainMs);
            boolean needRenew = remainMs >= 0 && remainMs <= (long) (config.cookieRenewDays * 86_400_000L);
            log("cookie buff: " + cookieStatus + (needRenew ? " — renewing" : ""));
            if (!needRenew) {
                nextCookieCheck = System.currentTimeMillis() + config.cookieCheckHours * 3_600_000L;
                if (mc.player != null) mc.player.closeHandledScreen();
                phase = Phase.PLAN;
                return;
            }
            // Renew: already holding a cookie? consume it; else instabuy one first.
            if (GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE) >= 0) {
                phase = Phase.COOKIE_MOVE;
            } else {
                if (mc.player != null) mc.player.closeHandledScreen();
                startNav("Booster Cookie", Phase.COOKIE_INSTABUY);
            }
            return;
        }
        if (chest != null) { if (mc.player != null) mc.player.closeHandledScreen(); return; }
        if (cookieCmdCooldown > 0) { cookieCmdCooldown--; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) nh.sendChatCommand("sbmenu");
        cookieCmdCooldown = 6;
    }

    /** Parse "duration: 3d 22h 17m" from the cookie lore; 0 if "not active"; -1 unknown. */
    private long parseCookieRemaining(MinecraftClient mc) {
        int idx = GuiHelper.findSlotByName(mc, BazaarStrings.ITEM_COOKIE);
        if (idx < 0) return -1;
        var chest = GuiHelper.openChest(mc);
        if (chest == null) return -1;
        for (String line : GuiHelper.lore(chest.getSlot(idx).getStack())) {
            if (line.contains(BazaarStrings.LORE_NOT_ACTIVE)) return 0;
            if (!line.contains(BazaarStrings.LORE_DURATION)) continue;
            long ms = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s*([dhms])").matcher(line);
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
        return -1;
    }

    private void pCookieInstabuy(MinecraftClient mc) {
        statusLine = "buying booster cookie";
        double price = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_INSTABUY, "per unit");
        if (config.cookieMaxPrice > 0 && !Double.isNaN(price) && price > config.cookieMaxPrice) {
            log(String.format(Locale.ROOT, "§ecookie too expensive (%,.0f > %,.0f) — retrying later",
                    price, config.cookieMaxPrice));
            nextCookieCheck = System.currentTimeMillis() + 3_600_000L;
            phase = Phase.PLAN;
            return;
        }
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_INSTABUY)) phase = Phase.COOKIE_AMOUNT;
    }

    private void pCookieAmount(MinecraftClient mc) {
        statusLine = "cookie amount: 1";
        if (GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE) >= 0) {
            phase = Phase.COOKIE_MOVE;   // purchase landed in inventory
            return;
        }
        if (GuiHelper.clickByName(mc, "buy 1")) return;   // preset "Buy 1!"
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_AMOUNT)) requestSign("1", Phase.COOKIE_AMOUNT);
    }

    private void pCookieMove(MinecraftClient mc) {
        statusLine = "moving cookie to hotbar";
        int idx = GuiHelper.findPlayerSlotByName(mc, BazaarStrings.ITEM_COOKIE);
        if (idx < 0) { phase = Phase.PLAN; return; }   // vanished? re-plan
        if (GuiHelper.swapToHotbar(mc, idx, 8)) phase = Phase.COOKIE_USE;
    }

    private void pCookieUse(MinecraftClient mc) {
        statusLine = "consuming cookie";
        if (GuiHelper.openChest(mc) != null) {
            if (mc.player != null) mc.player.closeHandledScreen();
            return;
        }
        if (mc.player == null || mc.interactionManager == null) return;
        mc.player.getInventory().setSelectedSlot(8);
        mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
        phase = Phase.COOKIE_CONFIRM;   // confirm GUI opens; we click "consume"
    }

    private static String fmtDuration(long ms) {
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
        return atProduct(mc) && GuiHelper.hasExactItemNamed(mc, item);
    }

    private boolean goToManage(MinecraftClient mc) {
        if (atManage(mc)) return true;
        if (atMain(mc)) {
            if (!GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ORDERS)) {
                GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ALT);
            }
        } else {
            GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK);
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
        var cs = api.getCandidates();
        if (!cs.isEmpty()) {
            FlipCandidate c = cs.get(0);
            topCandidate = String.format(Locale.ROOT, "%s %.1f%% σ%.1f%% %s%.1f%%",
                    c.displayName, c.margin(config.taxFraction) * 100, c.volatility * 100,
                    c.trend >= 0 ? "§a↑§7" : "§c↓§7", Math.abs(c.trend) * 100);
        }
    }

    private void requestSign(String text, Phase next) {
        pendingSignText = text; phaseAfterSign = next; phase = Phase.WAIT_SIGN;
    }

    private void checkStuck() {
        boolean waiting = phase == Phase.PLAN || phase == Phase.WAIT_SIGN;
        if (phase == lastPhase && !waiting) {
            if (++stuckTicks > STUCK_LIMIT) {
                log("stuck in " + phase + " — recovering");
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

    private void note(String msg) {
        if (msg.equals(lastNote)) return;
        lastNote = msg;
        log(msg);
    }
}
