package com.bzflipper.mc;

import com.bzflipper.api.BazaarApi;
import com.bzflipper.api.FlipCandidate;
import com.bzflipper.config.FlipConfig;
import com.bzflipper.config.FlipTarget;
import com.bzflipper.core.BazaarStrings;
import com.bzflipper.core.PriceMath;
import com.bzflipper.track.ProfitTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v0.4 — purse-aware, multi-slot portfolio flipper.
 *
 * The Manage-Orders grid is the single source of truth. Each planning pass scans
 * it and takes ONE high-priority action (serialized, since there is one GUI):
 *   claim filled sells → claim filled buys → relist outbid → sell claimed buys →
 *   open a new buy (if under the order cap AND the purse allows it).
 *
 * Item selection comes from the live Bazaar API (best flips by margin×liquidity);
 * exact prices are read fresh from the product page at order time, and order size
 * is computed from the current purse — so it never over-commits.
 */
public class BazaarMacro {

    public enum Phase {
        IDLE, PLAN, NAV_SEARCH, WAIT_SIGN,
        BUY_OPEN, BUY_AMOUNT, BUY_PRICE, BUY_CONFIRM,
        SELL_OPEN, SELL_AMOUNT, SELL_PRICE, SELL_CONFIRM,
        CLAIM_BUY, CLAIM_SELL, CANCEL
    }

    private static final int STUCK_LIMIT = 40;

    private final FlipConfig config;
    private final BazaarApi api;
    private final ProfitTracker tracker;

    private boolean enabled = false;
    private Phase phase = Phase.IDLE;
    private int delayTimer = 0;
    private int openCooldown = 0;   // throttle for the "/bz" open command
    private int navCooldown = 0;    // throttle for the "/bz <item>" nav command

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
    private String pendingSellItem = null;
    private String cancelSide = BazaarStrings.LORE_SIDE_BUY;
    private boolean cancelRelistIsBuy = true;
    private String lastNote = "";   // de-dupes the self-diagnosis chat line

    // Per-item economics for accurate profit accounting.
    private static final class OrderInfo { double buyPrice; int amount; double sellPrice = Double.NaN; }
    private final Map<String, OrderInfo> orders = new HashMap<>();

    // ---- HUD-exposed ----
    public volatile String statusLine = "idle";
    public volatile double purse = Double.NaN;
    public volatile int buyCount = 0, sellCount = 0;
    public volatile int ordersPlaced = 0, buysFilled = 0, sellsFilled = 0, flipsCompleted = 0;
    public volatile String topCandidate = "—";
    public volatile double lastTopBuy = Double.NaN, lastLowSell = Double.NaN, lastMargin = Double.NaN;

    public BazaarMacro(FlipConfig config, BazaarApi api, ProfitTracker tracker) {
        this.config = config;
        this.api = api;
        this.tracker = tracker;
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
        resetDelay();

        MinecraftClient mc = MinecraftClient.getInstance();
        purse = PurseReader.readPurse(mc);
        var cs = api.getCandidates();

        // Debug: show what we read the purse from (visible in logs/latest.log).
        if (Double.isNaN(purse)) {
            System.out.println("[bzflipper] purse NOT found. Sidebar lines:");
            for (String l : ScoreboardReader.sidebarLines(mc)) System.out.println("  | " + l);
        } else {
            System.out.println("[bzflipper] purse from line: '" + PurseReader.lastRawLine() + "' -> " + purse);
        }

        log(config.dryRun ? "started (DRY RUN — plans only, no orders)" : "started (LIVE)");
        log(String.format(Locale.ROOT, "purse=%s  apiFlips=%b  candidates=%d%s",
                Double.isNaN(purse) ? "UNKNOWN (are you on SkyBlock?)" : String.format(Locale.ROOT, "%,.0f", purse),
                config.useApiFlips, cs.size(),
                api.lastError() != null ? "  §capiError=" + api.lastError() + "§r" : ""));
        for (int i = 0; i < Math.min(3, cs.size()); i++) {
            FlipCandidate c = cs.get(i);
            log(String.format(Locale.ROOT, "  #%d %s  margin %.1f%%  buy %.1f",
                    i + 1, c.displayName, c.margin(config.taxFraction) * 100, c.ourBuyPrice()));
        }
        log("§7now OPEN THE BAZAAR. Press §f]§7 on each Bazaar screen to dump its real button text for tuning.");
    }

    public void stop(String why) {
        enabled = false;
        phase = Phase.IDLE;
        statusLine = "stopped: " + why;
        log("stopped: " + why);
    }

    // ---- main loop ----
    public void onTick(MinecraftClient mc) {
        if (!enabled || mc.player == null) return;
        if (delayTimer > 0) { delayTimer--; return; }
        resetDelay();

        purse = PurseReader.readPurse(mc);
        updateTopCandidate();

        // Sign popups (Custom Amount / Custom Price / Search) aren't chest GUIs.
        if (SignFiller.isSignScreen(mc)) {
            if (pendingSignText != null) {
                SignFiller.fill(mc, pendingSignText);
                log("sign input: " + pendingSignText);
                pendingSignText = null;
                phase = phaseAfterSign;
            }
            return;
        }
        if (GuiHelper.openChest(mc) == null) {
            openBazaar(mc);   // run /bz ourselves instead of waiting for the user
            return;
        }

        checkStuck();

        switch (phase) {
            case PLAN         -> pPlan(mc);
            case NAV_SEARCH   -> pNavSearch(mc);
            case WAIT_SIGN    -> statusLine = "opening sign popup…";
            case BUY_OPEN     -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BUY_ORDER)) phase = Phase.BUY_AMOUNT; }
            case BUY_AMOUNT   -> pBuyAmount(mc);
            case BUY_PRICE    -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) requestSign(fmt1(ourBuyPrice), Phase.BUY_CONFIRM); }
            case BUY_CONFIRM  -> pBuyConfirm(mc);
            case SELL_OPEN    -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_SELL_OFFER)) phase = Phase.SELL_AMOUNT; }
            case SELL_AMOUNT  -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_SELL_INV)) phase = Phase.SELL_PRICE; }
            case SELL_PRICE   -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) requestSign(fmt1(ourSellPrice), Phase.SELL_CONFIRM); }
            case SELL_CONFIRM -> pSellConfirm(mc);
            case CLAIM_BUY    -> pClaimBuy(mc);
            case CLAIM_SELL   -> pClaimSell(mc);
            case CANCEL       -> pCancel(mc);
            case IDLE         -> { }
        }
    }

    // ---- planning ----

    private void pPlan(MinecraftClient mc) {
        if (!goToManage(mc)) { statusLine = "→ manage orders"; return; }

        Set<String> active = GuiHelper.collectNames(mc);
        buyCount = GuiHelper.countSlotsWithLore(mc, BazaarStrings.LORE_SIDE_BUY);
        sellCount = GuiHelper.countSlotsWithLore(mc, BazaarStrings.LORE_SIDE_SELL);

        int s = filledSlot(mc, BazaarStrings.LORE_SIDE_SELL);
        if (s >= 0) { activeItem = GuiHelper.itemNameAt(mc, s); phase = Phase.CLAIM_SELL; return; }

        int b = filledSlot(mc, BazaarStrings.LORE_SIDE_BUY);
        if (b >= 0) { activeItem = GuiHelper.itemNameAt(mc, b); phase = Phase.CLAIM_BUY; return; }

        int ob = GuiHelper.firstSlotWithLore(mc, BazaarStrings.LORE_OUTBID, BazaarStrings.LORE_SIDE_BUY);
        if (ob >= 0) { activeItem = GuiHelper.itemNameAt(mc, ob); cancelSide = BazaarStrings.LORE_SIDE_BUY; cancelRelistIsBuy = true; phase = Phase.CANCEL; return; }

        int os = GuiHelper.firstSlotWithLore(mc, BazaarStrings.LORE_OUTBID, BazaarStrings.LORE_SIDE_SELL);
        if (os >= 0) { activeItem = GuiHelper.itemNameAt(mc, os); cancelSide = BazaarStrings.LORE_SIDE_SELL; cancelRelistIsBuy = false; phase = Phase.CANCEL; return; }

        if (pendingSellItem != null) {
            activeItem = pendingSellItem;
            startNav(pendingSellItem, Phase.SELL_OPEN);
            return;
        }

        if (buyCount < config.maxOpenOrders) {
            String pick = pickNextItem(active);
            if (pick != null) {
                if (config.dryRun) {
                    statusLine = "DRY: would buy " + pick;
                    note("§eDRY RUN§r is on — would buy §f" + pick
                            + "§r. Set §fdryRun:false§r in config/bzflipper.json to actually trade.");
                    return;
                }
                activeItem = pick;
                startNav(pick, Phase.BUY_OPEN);
                return;
            }
        }
        String reason = diagnoseIdle();
        statusLine = reason;
        note(reason);
    }

    /** Explains, in one line, why the flipper isn't opening any orders right now. */
    private String diagnoseIdle() {
        var cs = api.getCandidates();
        if (Double.isNaN(purse)) return "idle: purse UNKNOWN — sidebar not read (are you on SkyBlock?)";
        if (cs.isEmpty()) return "idle: 0 flips from API"
                + (api.lastError() != null ? " (error: " + api.lastError() + ")" : " (still loading — wait ~1 min)");
        double spend = (purse - config.coinReserve) * config.orderBudgetFraction;
        if (spend <= 0) return String.format(Locale.ROOT,
                "idle: nothing spendable (purse %,.0f − reserve %,.0f)", purse, config.coinReserve);
        return String.format(Locale.ROOT,
                "idle: %d flips found but none affordable/free right now (per-order budget %,.0f)", cs.size(), spend);
    }

    /** First filled order on the given side (checks both "100%" and "filled!" wording). */
    private int filledSlot(MinecraftClient mc, String side) {
        int i = GuiHelper.firstSlotWithLore(mc, BazaarStrings.LORE_FILLED, side);
        if (i >= 0) return i;
        return GuiHelper.firstSlotWithLore(mc, BazaarStrings.LORE_FILLED_ALT, side);
    }

    /** Choose the next item to buy: best affordable, not-already-active flip. */
    private String pickNextItem(Set<String> activeLower) {
        double spendablePerOrder = (purse - config.coinReserve) * config.orderBudgetFraction;
        if (config.useApiFlips) {
            if (Double.isNaN(purse) || spendablePerOrder <= 0) return null;
            for (FlipCandidate c : api.getCandidates()) {
                if (activeLower.contains(c.displayName.toLowerCase(Locale.ROOT))) continue;
                if (c.ourBuyPrice() > spendablePerOrder) continue; // can't afford even one unit
                return c.displayName;
            }
            return null;
        }
        for (FlipTarget t : config.targets) {
            if (!activeLower.contains(t.product.toLowerCase(Locale.ROOT))) return t.product;
        }
        return null;
    }

    // ---- navigation ----

    private void pNavSearch(MinecraftClient mc) {
        statusLine = "→ " + navItem;
        if (arrivedAtProduct(mc, navItem)) {
            double topBuy = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_BUY_ORDER, BazaarStrings.LORE_COINS);
            double lowSell = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_SELL_OFFER, BazaarStrings.LORE_COINS);
            if (!Double.isNaN(topBuy) && !Double.isNaN(lowSell)) {
                ourBuyPrice = PriceMath.buyOrderPrice(topBuy);
                ourSellPrice = PriceMath.sellOfferPrice(lowSell);
                lastTopBuy = topBuy; lastLowSell = lowSell;
                lastMargin = PriceMath.netMarginFraction(topBuy, lowSell, config.taxFraction);
            }
            phase = navAfter;
            return;
        }
        if (GuiHelper.hasItemNamed(mc, navItem) && !atProduct(mc)) {
            GuiHelper.clickByName(mc, navItem);         // exact item shown in a results list
            return;
        }
        // Jump straight to the item with the Bazaar command (more reliable than the search GUI).
        if (navCooldown > 0) { navCooldown--; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) nh.sendChatCommand("bz " + navItem);
        navCooldown = 6;
    }

    private void startNav(String item, Phase after) {
        navItem = item; navAfter = after; navCooldown = 0; phase = Phase.NAV_SEARCH;
    }

    /** Open the Bazaar by running /bz (throttled so we don't spam the command). */
    private void openBazaar(MinecraftClient mc) {
        if (openCooldown > 0) { openCooldown--; statusLine = "opening Bazaar…"; return; }
        var nh = mc.getNetworkHandler();
        if (nh != null) { nh.sendChatCommand("bz"); statusLine = "opening Bazaar (/bz)"; }
        openCooldown = 6;
    }

    // ---- buy ----

    private void pBuyAmount(MinecraftClient mc) {
        double spendable = (purse - config.coinReserve) * config.orderBudgetFraction;
        activeAmount = PriceMath.affordableUnits(spendable, ourBuyPrice, config.maxUnitsPerOrder);
        if (activeAmount < 1) { log("insufficient purse for " + activeItem + " — skipping"); phase = Phase.PLAN; return; }
        statusLine = "amount " + activeAmount + " × " + activeItem;
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_AMOUNT)) {
            requestSign(Integer.toString(activeAmount), Phase.BUY_PRICE);
        }
    }

    private void pBuyConfirm(MinecraftClient mc) {
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_BUY)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            OrderInfo oi = new OrderInfo();
            oi.buyPrice = ourBuyPrice; oi.amount = activeAmount;
            orders.put(activeItem.toLowerCase(Locale.ROOT), oi);
            ordersPlaced++;
            log(String.format(Locale.ROOT, "buy order: %d × %s @ %.1f", activeAmount, activeItem, ourBuyPrice));
            phase = Phase.PLAN;
        }
    }

    // ---- sell ----

    private void pSellConfirm(MinecraftClient mc) {
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_SELL)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            OrderInfo oi = orders.get(activeItem.toLowerCase(Locale.ROOT));
            if (oi != null) oi.sellPrice = ourSellPrice;
            ordersPlaced++;
            log(String.format(Locale.ROOT, "sell offer: %s @ %.1f", activeItem, ourSellPrice));
            pendingSellItem = null;
            phase = Phase.PLAN;
        }
    }

    // ---- claim / cancel ----

    private void pClaimBuy(MinecraftClient mc) {
        if (!goToManage(mc)) return;
        int b = filledSlot(mc, BazaarStrings.LORE_SIDE_BUY);
        if (b >= 0) {
            String item = GuiHelper.itemNameAt(mc, b);
            if (GuiHelper.clickSlotIndex(mc, b)) {
                buysFilled++; pendingSellItem = item;
                log("claimed bought: " + item);
                phase = Phase.PLAN;
            }
        } else phase = Phase.PLAN;
    }

    private void pClaimSell(MinecraftClient mc) {
        if (!goToManage(mc)) return;
        int s = filledSlot(mc, BazaarStrings.LORE_SIDE_SELL);
        if (s >= 0) {
            String item = GuiHelper.itemNameAt(mc, s);
            if (GuiHelper.clickSlotIndex(mc, s)) {
                sellsFilled++; flipsCompleted++;
                OrderInfo oi = orders.remove(item.toLowerCase(Locale.ROOT));
                if (oi != null && !Double.isNaN(oi.sellPrice)) {
                    double profit = (oi.sellPrice * (1.0 - config.taxFraction) - oi.buyPrice) * oi.amount;
                    tracker.addProfit(profit);
                    log(String.format(Locale.ROOT, "flip done: %s ~%,.0f (total ~%,.0f)",
                            item, profit, tracker.total()));
                } else {
                    log("claimed sell: " + item + " (profit unknown — not tracked)");
                }
                phase = Phase.PLAN;
            }
        } else phase = Phase.PLAN;
    }

    private void pCancel(MinecraftClient mc) {
        if (!goToManage(mc)) return;
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CANCEL_ORDER)) {
            afterCancel();
            return;
        }
        int slot = GuiHelper.firstSlotWithLore(mc, BazaarStrings.LORE_OUTBID, cancelSide);
        if (slot >= 0) GuiHelper.clickSlotIndex(mc, slot); // open the order so Cancel appears
        else afterCancel();                                 // already gone
    }

    private void afterCancel() {
        log("relisting " + activeItem);
        if (cancelRelistIsBuy) { startNav(activeItem, Phase.BUY_OPEN); }
        else { pendingSellItem = activeItem; phase = Phase.PLAN; }
    }

    // ---- navigation predicates ----

    private boolean atProduct(MinecraftClient mc) {
        return GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_BUY_ORDER)
                && GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_SELL_OFFER);
    }

    private boolean atManage(MinecraftClient mc) {
        String t = GuiHelper.screenTitle(mc);
        return t.contains("your bazaar orders") || (t.contains("order") && !atProduct(mc));
    }

    private boolean atMain(MinecraftClient mc) {
        return GuiHelper.screenTitle(mc).contains(BazaarStrings.TITLE_BAZAAR)
                && !atProduct(mc) && !atManage(mc);
    }

    private boolean arrivedAtProduct(MinecraftClient mc, String item) {
        if (!atProduct(mc)) return false;
        String tok = longestToken(item);
        return tok.isEmpty() || GuiHelper.screenTitle(mc).contains(tok);
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

    private void updateTopCandidate() {
        var cs = api.getCandidates();
        if (!cs.isEmpty()) {
            FlipCandidate c = cs.get(0);
            topCandidate = String.format(Locale.ROOT, "%s (%.1f%%)",
                    c.displayName, c.margin(config.taxFraction) * 100);
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
                phase = Phase.PLAN; stuckTicks = 0;
            }
        } else {
            stuckTicks = 0; lastPhase = phase;
        }
    }

    private void resetDelay() {
        int jitter = config.actionJitterTicks > 0
                ? (int) (Math.random() * (config.actionJitterTicks + 1)) : 0;
        delayTimer = Math.max(1, config.actionDelayTicks + jitter);
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
        System.out.println("[bzflipper] " + msg);
    }

    /** Log to chat only when the message changes, so repeated idle states don't spam. */
    private void note(String msg) {
        if (msg.equals(lastNote)) return;
        lastNote = msg;
        log(msg);
    }
}
