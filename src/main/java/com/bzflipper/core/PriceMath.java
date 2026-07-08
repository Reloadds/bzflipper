package com.bzflipper.core;

/**
 * Pure, dependency-free flipping math.
 *
 * This class deliberately has ZERO Minecraft imports. It is the reusable brain
 * of the flipper: when we build the headless Mineflayer version, this same logic
 * is ported 1:1 (it is trivial to translate to JS). Keep game/GUI concerns out
 * of here.
 */
public final class PriceMath {

    private PriceMath() {}

    /** Hypixel Bazaar prices tick in increments of 0.1 coins. */
    public static final double TICK = 0.1;

    /** Round a price to the nearest valid 0.1 increment. */
    public static double roundToTick(double price) {
        return Math.round(price / TICK) * TICK;
    }

    /**
     * Price for a competitive BUY order: outbid the current top buy order by one
     * tick so we sit at the front of the queue.
     *
     * @param topBuyOrder the highest existing buy order price (coins/unit)
     */
    public static double buyOrderPrice(double topBuyOrder) {
        return roundToTick(topBuyOrder + TICK);
    }

    /**
     * Price for a competitive SELL offer: undercut the current lowest sell offer
     * by one tick so we sell first.
     *
     * @param lowestSellOffer the lowest existing sell offer price (coins/unit)
     */
    public static double sellOfferPrice(double lowestSellOffer) {
        return roundToTick(lowestSellOffer - TICK);
    }

    /** Absolute spread per unit between our sell and buy prices. */
    public static double spread(double topBuyOrder, double lowestSellOffer) {
        return sellOfferPrice(lowestSellOffer) - buyOrderPrice(topBuyOrder);
    }

    /**
     * Net profit margin as a fraction of the buy price, AFTER Bazaar tax.
     * Hypixel charges ~1.25% on sell orders (reduced by Booster Cookie / medals);
     * we use a conservative default and let config override it.
     *
     * @return e.g. 0.05 for a 5% net margin. Negative means unprofitable.
     */
    public static double netMarginFraction(double topBuyOrder, double lowestSellOffer, double taxFraction) {
        double buy = buyOrderPrice(topBuyOrder);
        double sell = sellOfferPrice(lowestSellOffer);
        if (buy <= 0) return 0;
        double netSell = sell * (1.0 - taxFraction);
        return (netSell - buy) / buy;
    }

    /** True if someone has outbid our buy order (their price >= ours). */
    public static boolean buyOrderUndercut(double ourBuyPrice, double currentTopBuyOrder) {
        // "undercut" on the buy side means someone placed a HIGHER buy order.
        return currentTopBuyOrder > ourBuyPrice + 1e-9;
    }

    /** True if someone has undercut our sell offer (their price <= ours). */
    public static boolean sellOfferUndercut(double ourSellPrice, double currentLowestSell) {
        return currentLowestSell < ourSellPrice - 1e-9;
    }

    /** Net profit per unit after tax (coins), at competitive prices. */
    public static double profitPerUnit(double topBuyOrder, double lowestSellOffer, double taxFraction) {
        return sellOfferPrice(lowestSellOffer) * (1.0 - taxFraction) - buyOrderPrice(topBuyOrder);
    }

    /**
     * Ranking score for choosing between flips: net margin weighted by liquidity
     * (log of weekly volume) so we prefer items that actually fill. An illiquid
     * high-margin trap scores low. Bounded at 0 for unprofitable flips.
     */
    public static double flipScore(double margin, double buyWeekVol, double sellWeekVol) {
        double liquidity = Math.log1p(Math.max(0, Math.min(buyWeekVol, sellWeekVol)));
        return Math.max(0, margin) * liquidity;
    }

    /**
     * Capital velocity of a flip whose two legs run IN SERIES: units flow through
     * the buy leg at {@code buyRate} u/hr, then the sell leg at {@code sellRate}.
     * Steady-state throughput of a two-stage pipeline is the harmonic combination
     *   v = 1 / (1/buyRate + 1/sellRate)
     * A flip is only as fast as BOTH legs together: an item that sells instantly
     * but buys at a trickle (or vice versa) is correctly punished, where ranking
     * on one leg alone overrates it. Returns 0 if either leg is stalled.
     */
    public static double seriesVelocity(double buyRate, double sellRate) {
        if (buyRate <= 0 || sellRate <= 0) return 0;
        return 1.0 / (1.0 / buyRate + 1.0 / sellRate);
    }

    /** Max whole units affordable for {@code spendableCoins} at {@code unitBuyPrice}, capped. */
    public static int affordableUnits(double spendableCoins, double unitBuyPrice, int cap) {
        if (unitBuyPrice <= 0 || spendableCoins <= 0) return 0;
        long n = (long) Math.floor(spendableCoins / unitBuyPrice);
        return (int) Math.max(0, Math.min(n, cap));
    }
}
