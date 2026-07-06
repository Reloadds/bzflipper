package com.bzflipper.api;

import com.bzflipper.core.PriceMath;

/**
 * One rankable flip opportunity derived from the live Bazaar order book.
 * Prices are top-of-book; our competitive prices and margin are derived so all
 * the math flows from one source (PriceMath).
 */
public class FlipCandidate {

    public final String tag;              // e.g. ENCHANTED_CACTUS_GREEN
    public final String displayName;      // e.g. Enchanted Cactus Green
    public final double topBuyOrder;      // highest existing buy order (we outbid)
    public final double lowestSellOffer;  // lowest existing sell offer (we undercut)
    public final double buyWeekVolume;    // liquidity signals (moving week)
    public final double sellWeekVolume;

    public FlipCandidate(String tag, String displayName, double topBuyOrder,
                         double lowestSellOffer, double buyWeekVolume, double sellWeekVolume) {
        this.tag = tag;
        this.displayName = displayName;
        this.topBuyOrder = topBuyOrder;
        this.lowestSellOffer = lowestSellOffer;
        this.buyWeekVolume = buyWeekVolume;
        this.sellWeekVolume = sellWeekVolume;
    }

    public double ourBuyPrice()  { return PriceMath.buyOrderPrice(topBuyOrder); }
    public double ourSellPrice() { return PriceMath.sellOfferPrice(lowestSellOffer); }
    public double margin(double tax) { return PriceMath.netMarginFraction(topBuyOrder, lowestSellOffer, tax); }
    public double minWeeklyVolume()  { return Math.min(buyWeekVolume, sellWeekVolume); }

    /**
     * Expected profit throughput: net coins per unit × liquidity. This ranks by
     * how much money the flip can actually make per unit time — favoring high
     * demand AND good margin, which is what we want to prioritize.
     */
    public double score(double tax) {
        return Math.max(0, PriceMath.profitPerUnit(topBuyOrder, lowestSellOffer, tax)) * minWeeklyVolume();
    }
}
