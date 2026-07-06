package com.bzflipper.mc;

import com.bzflipper.config.FlipConfig;
import com.bzflipper.config.FlipTarget;
import com.bzflipper.core.BazaarStrings;
import com.bzflipper.core.PriceMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * The flipper's brain: a tick-driven, self-healing state machine that runs a
 * full round-trip flip and then loops.
 *
 *   NAVIGATE_READ -> place BUY -> MONITOR_BUY (relist if outbid) -> CLAIM_BUY
 *                 -> place SELL -> MONITOR_SELL (relist if undercut) -> CLAIM_SELL
 *                 -> COOLDOWN -> (next target) NAVIGATE_READ ...
 *
 * Navigation is content-based: every step checks "what screen am I on?" by the
 * items present, and clicks by NAME (see GuiHelper + BazaarStrings). That makes
 * it resilient to layout shifts and lets it recover if a click is missed.
 *
 * NOTE: The Manage-Orders interaction (fill %, "outbid", claim, cancel) is the
 * part most likely to need live tuning of the BazaarStrings anchors — confirm
 * the exact wording in-game.
 */
public class BazaarMacro {

    /** Units bought per buy order (matches the "buy 1 stack" preset button). */
    private static final int STACK = 64;

    public enum Phase {
        IDLE,
        NAVIGATE_READ,
        BUY_OPEN, BUY_AMOUNT, BUY_PRICE, BUY_CONFIRM,
        MONITOR_BUY, CANCEL_BUY, CLAIM_BUY,
        SELL_OPEN, SELL_AMOUNT, SELL_PRICE, SELL_CONFIRM,
        MONITOR_SELL, CANCEL_SELL, CLAIM_SELL,
        WAIT_SIGN,
        COOLDOWN
    }

    private final FlipConfig config;

    private boolean enabled = false;
    private Phase phase = Phase.IDLE;
    private int targetIndex = 0;
    private int delayTimer = 0;

    // Stuck detection (skips the MONITOR phases, where waiting is expected).
    private Phase lastPhase = Phase.IDLE;
    private int stuckTicks = 0;
    private static final int STUCK_LIMIT = 40;

    // Prices we committed to this flip.
    private double ourBuyPrice = Double.NaN;
    private double ourSellPrice = Double.NaN;
    private int currentAmount = STACK;

    // Pending sign-popup input (Custom Amount / Custom Price).
    private String pendingSignText = null;
    private Phase phaseAfterSign = Phase.IDLE;

    // ---- HUD-exposed state ----
    public volatile String statusLine = "idle";
    public volatile double lastTopBuy = Double.NaN;
    public volatile double lastLowSell = Double.NaN;
    public volatile double lastMargin = Double.NaN;
    public volatile int ordersPlaced = 0;
    public volatile int buysFilled = 0;
    public volatile int sellsFilled = 0;
    public volatile int flipsCompleted = 0;
    public volatile double estProfit = 0;

    public BazaarMacro(FlipConfig config) {
        this.config = config;
    }

    public boolean isEnabled() { return enabled; }
    public Phase getState()    { return phase; }

    public void toggle() { if (enabled) stop("toggled off"); else start(); }

    public void start() {
        enabled = true;
        targetIndex = 0;
        phase = Phase.NAVIGATE_READ;
        stuckTicks = 0;
        resetDelay();
        log(config.dryRun ? "started (DRY RUN — reads only, no orders)" : "started (LIVE)");
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

        // Handle the Bazaar's "Custom Amount" / "Custom Price" sign popups first:
        // they aren't chest GUIs, so they'd otherwise trip the check below.
        if (SignFiller.isSignScreen(mc)) {
            if (pendingSignText != null) {
                SignFiller.fill(mc, pendingSignText);
                log("sign input: " + pendingSignText);
                pendingSignText = null;
                phase = phaseAfterSign;
            }
            return; // wait for the next chest GUI to open
        }

        if (GuiHelper.openChest(mc) == null) {
            statusLine = "waiting: open the Bazaar";
            return;
        }

        FlipTarget target = currentTarget();
        if (target == null) { stop("no targets configured"); return; }

        checkStuck();

        switch (phase) {
            case NAVIGATE_READ -> pNavigateRead(mc, target);
            case BUY_OPEN     -> { if (goToProduct(mc, target) && GuiHelper.clickByName(mc, BazaarStrings.BTN_BUY_ORDER)) phase = Phase.BUY_AMOUNT; }
            case BUY_AMOUNT   -> pBuyAmount(mc, target);
            case BUY_PRICE    -> pBuyPrice(mc);
            case WAIT_SIGN    -> { statusLine = "opening sign popup…"; }
            case BUY_CONFIRM  -> pBuyConfirm(mc, target);
            case MONITOR_BUY  -> pMonitorBuy(mc, target);
            case CANCEL_BUY   -> pCancel(mc, target, BazaarStrings.LORE_SIDE_BUY, Phase.BUY_OPEN);
            case CLAIM_BUY    -> pClaimBuy(mc, target);
            case SELL_OPEN    -> { if (goToProduct(mc, target) && GuiHelper.clickByName(mc, BazaarStrings.BTN_SELL_OFFER)) phase = Phase.SELL_AMOUNT; }
            case SELL_AMOUNT  -> { if (GuiHelper.clickByName(mc, BazaarStrings.BTN_SELL_INV)) phase = Phase.SELL_PRICE; }
            case SELL_PRICE   -> pSellPrice(mc);
            case SELL_CONFIRM -> pSellConfirm(mc);
            case MONITOR_SELL -> pMonitorSell(mc, target);
            case CANCEL_SELL  -> pCancel(mc, target, BazaarStrings.LORE_SIDE_SELL, Phase.SELL_OPEN);
            case CLAIM_SELL   -> pClaimSell(mc, target);
            case COOLDOWN     -> pCooldown();
            case IDLE         -> { }
        }
    }

    // ---- phase handlers ----

    private void pNavigateRead(MinecraftClient mc, FlipTarget target) {
        statusLine = "reading " + target.product;
        if (!goToProduct(mc, target)) return;

        double topBuy = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_BUY_ORDER, BazaarStrings.LORE_COINS);
        double lowSell = GuiHelper.readCoinPrice(mc, BazaarStrings.BTN_SELL_OFFER, BazaarStrings.LORE_COINS);
        lastTopBuy = topBuy;
        lastLowSell = lowSell;

        if (Double.isNaN(topBuy) || Double.isNaN(lowSell)) {
            log("price read failed for " + target.product + " (tune BazaarStrings) — skipping");
            skipToNext();
            return;
        }

        ourBuyPrice = PriceMath.buyOrderPrice(topBuy);
        ourSellPrice = PriceMath.sellOfferPrice(lowSell);
        double margin = PriceMath.netMarginFraction(topBuy, lowSell, config.taxFraction);
        lastMargin = margin;
        log(String.format("%s | buy %.1f  sell %.1f  net %.2f%%",
                target.product, ourBuyPrice, ourSellPrice, margin * 100));

        if (margin < target.minMargin) { skipToNext(); return; }
        if (config.dryRun) { log("DRY RUN: profitable, not ordering"); skipToNext(); return; }
        phase = Phase.BUY_OPEN;
    }

    private void pBuyAmount(MinecraftClient mc, FlipTarget target) {
        currentAmount = target.amount > 0 ? target.amount : STACK;
        if (target.amount > 0) {
            statusLine = "custom amount: " + target.amount;
            if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_AMOUNT)) {
                requestSign(Integer.toString(target.amount), Phase.BUY_PRICE);
            }
        } else {
            statusLine = "amount: 1 stack";
            if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BUY_STACK)) phase = Phase.BUY_PRICE;
        }
    }

    private void pBuyPrice(MinecraftClient mc) {
        if (config.useCustomPrice) {
            statusLine = String.format(Locale.ROOT, "custom buy price %.1f", ourBuyPrice);
            if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) {
                requestSign(String.format(Locale.ROOT, "%.1f", ourBuyPrice), Phase.BUY_CONFIRM);
            }
            return;
        }
        statusLine = "buy price (top + 0.1)";
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BEST_PRICE)
                || GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_CREATE_BUY)
                || GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_CONFIRM)) {
            phase = Phase.BUY_CONFIRM;
        }
    }

    private void pBuyConfirm(MinecraftClient mc, FlipTarget target) {
        statusLine = "confirming buy";
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_BUY)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            ordersPlaced++;
            log("placed buy order: " + target.product + " @ " + String.format("%.1f", ourBuyPrice));
            phase = Phase.MONITOR_BUY;
        }
    }

    private void pMonitorBuy(MinecraftClient mc, FlipTarget target) {
        statusLine = "waiting for buy to fill…";
        if (!goToManage(mc, target)) return;

        if (GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_FILLED)
                || GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_FILLED_ALT)) {
            phase = Phase.CLAIM_BUY;
        } else if (GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_OUTBID)) {
            log("buy order outbid — relisting");
            phase = Phase.CANCEL_BUY;
        }
        // else keep waiting.
    }

    private void pClaimBuy(MinecraftClient mc, FlipTarget target) {
        statusLine = "claiming bought items";
        if (!goToManage(mc, target)) return;
        // Clicking a filled order claims it into the inventory.
        if (GuiHelper.clickByNameAndLore(mc, target.product, BazaarStrings.LORE_SIDE_BUY)
                || GuiHelper.clickByName(mc, target.product)) {
            buysFilled++;
            log("claimed bought items — creating sell offer");
            phase = Phase.SELL_OPEN;
        }
    }

    private void pSellPrice(MinecraftClient mc) {
        if (config.useCustomPrice) {
            statusLine = String.format(Locale.ROOT, "custom sell price %.1f", ourSellPrice);
            if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CUSTOM_PRICE)) {
                requestSign(String.format(Locale.ROOT, "%.1f", ourSellPrice), Phase.SELL_CONFIRM);
            }
            return;
        }
        statusLine = "sell price (low - 0.1)";
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_BEST_PRICE)
                || GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_CREATE_SELL)
                || GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_CONFIRM)) {
            phase = Phase.SELL_CONFIRM;
        }
    }

    private void pSellConfirm(MinecraftClient mc) {
        statusLine = "confirming sell";
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CREATE_SELL)
                || GuiHelper.clickByName(mc, BazaarStrings.BTN_CONFIRM)) {
            ordersPlaced++;
            log("placed sell offer @ " + String.format("%.1f", ourSellPrice));
            phase = Phase.MONITOR_SELL;
        }
    }

    private void pMonitorSell(MinecraftClient mc, FlipTarget target) {
        statusLine = "waiting for sell to fill…";
        if (!goToManage(mc, target)) return;

        if (GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_FILLED)
                || GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_FILLED_ALT)) {
            phase = Phase.CLAIM_SELL;
        } else if (GuiHelper.loreOfNamedContains(mc, target.product, BazaarStrings.LORE_OUTBID)) {
            log("sell offer undercut — relisting");
            phase = Phase.CANCEL_SELL;
        }
    }

    private void pClaimSell(MinecraftClient mc, FlipTarget target) {
        statusLine = "claiming coins";
        if (!goToManage(mc, target)) return;
        if (GuiHelper.clickByNameAndLore(mc, target.product, BazaarStrings.LORE_SIDE_SELL)
                || GuiHelper.clickByName(mc, target.product)) {
            sellsFilled++;
            flipsCompleted++;
            double profit = (ourSellPrice * (1.0 - config.taxFraction) - ourBuyPrice) * currentAmount;
            estProfit += profit;
            log(String.format("flip complete: ~%,.0f coins profit (total ~%,.0f)", profit, estProfit));
            phase = Phase.COOLDOWN;
        }
    }

    /** Generic cancel-then-relist: cancel the order on {@code side}, then go to {@code relistPhase}. */
    private void pCancel(MinecraftClient mc, FlipTarget target, String sideLore, Phase relistPhase) {
        statusLine = "cancelling order";
        if (!goToManage(mc, target)) return;
        if (GuiHelper.clickByName(mc, BazaarStrings.BTN_CANCEL_ORDER)) {
            phase = relistPhase; // cancelled; go relist at a fresh price
        } else if (GuiHelper.findSlotByNameAndLore(mc, target.product, sideLore) >= 0) {
            // Open the order so its cancel button appears next tick.
            GuiHelper.clickByNameAndLore(mc, target.product, sideLore);
        } else {
            // Order already gone — treat as relist.
            phase = relistPhase;
        }
    }

    private void pCooldown() {
        statusLine = "cooldown";
        skipToNext();
        // Re-read fresh prices for the next flip; delayTimer already throttles.
    }

    // ---- navigation (content-based, self-healing) ----

    private boolean atProduct(MinecraftClient mc) {
        return GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_BUY_ORDER)
                && GuiHelper.hasItemNamed(mc, BazaarStrings.BTN_SELL_OFFER);
    }

    private boolean atMain(MinecraftClient mc, FlipTarget t) {
        return GuiHelper.hasItemNamed(mc, t.category);
    }

    private boolean atManage(MinecraftClient mc) {
        String title = GuiHelper.screenTitle(mc);
        return title.contains("your bazaar orders")
                || (title.contains("order") && !atProduct(mc));
    }

    /** Walk toward the product page; returns true once we're there. */
    private boolean goToProduct(MinecraftClient mc, FlipTarget t) {
        if (atProduct(mc)) return true;
        if (GuiHelper.hasItemNamed(mc, t.product) && !atProduct(mc)) {
            GuiHelper.clickByName(mc, t.product);      // on category grid
        } else if (atMain(mc, t)) {
            GuiHelper.clickByName(mc, t.category);      // on main bazaar
        } else {
            GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK); // somewhere else
        }
        return false;
    }

    /** Walk toward the Manage Orders screen; returns true once we're there. */
    private boolean goToManage(MinecraftClient mc, FlipTarget t) {
        if (atManage(mc)) return true;
        if (atMain(mc, t)) {
            if (!GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ORDERS)) {
                GuiHelper.clickByName(mc, BazaarStrings.BTN_MANAGE_ALT);
            }
        } else {
            GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK);
        }
        return false;
    }

    // ---- helpers ----

    private FlipTarget currentTarget() {
        if (config.targets == null || config.targets.isEmpty()) return null;
        if (targetIndex >= config.targets.size()) targetIndex = 0;
        return config.targets.get(targetIndex);
    }

    /** Queue text for the next sign popup, then idle in WAIT_SIGN until it opens. */
    private void requestSign(String text, Phase next) {
        pendingSignText = text;
        phaseAfterSign = next;
        phase = Phase.WAIT_SIGN;
    }

    private void skipToNext() {
        targetIndex = (targetIndex + 1) % Math.max(1, config.targets.size());
        ourBuyPrice = ourSellPrice = Double.NaN;
        phase = Phase.NAVIGATE_READ;
    }

    private void checkStuck() {
        boolean monitoring = phase == Phase.MONITOR_BUY || phase == Phase.MONITOR_SELL;
        if (phase == lastPhase && !monitoring) {
            if (++stuckTicks > STUCK_LIMIT) {
                log("stuck in " + phase + " — recovering");
                MinecraftClient mc = MinecraftClient.getInstance();
                GuiHelper.clickByName(mc, BazaarStrings.BTN_GO_BACK);
                phase = Phase.NAVIGATE_READ;
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

    private void log(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("§b[BZ] §r" + msg), false);
        System.out.println("[bzflipper] " + msg);
    }
}
