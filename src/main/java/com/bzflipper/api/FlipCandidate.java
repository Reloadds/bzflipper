package com.bzflipper.api;

import com.bzflipper.config.FlipConfig;
import com.bzflipper.core.PriceMath;

/**
 * One rankable flip opportunity derived from the live Bazaar order book, enriched
 * with order-book depth and rolling price volatility so the strategy layer can do
 * proper fill-time, risk and position-sizing math.
 */
public class FlipCandidate {

    public final String tag;              // e.g. ENCHANTED_CACTUS_GREEN
    public final String displayName;      // e.g. Enchanted Cactus Green
    public final double topBuyOrder;      // highest existing buy order (we outbid)
    public final double lowestSellOffer;  // lowest existing sell offer (we undercut)
    public final double buyWeekVolume;    // liquidity signals (moving week)
    public final double sellWeekVolume;
    public final double buyDepth;         // units queued at the top buy level
    public final double sellDepth;        // units queued at the top sell level
    public final double volatility;       // coefficient of variation of mid price (σ/μ)
    public final double trend;            // relative price change over the window (+up/−down)

    public FlipCandidate(String tag, String displayName, double topBuyOrder,
                         double lowestSellOffer, double buyWeekVolume, double sellWeekVolume,
                         double buyDepth, double sellDepth, double volatility, double trend) {
        this.tag = tag;
        this.displayName = displayName;
        this.topBuyOrder = topBuyOrder;
        this.lowestSellOffer = lowestSellOffer;
        this.buyWeekVolume = buyWeekVolume;
        this.sellWeekVolume = sellWeekVolume;
        this.buyDepth = buyDepth;
        this.sellDepth = sellDepth;
        this.volatility = volatility;
        this.trend = trend;
    }

    public double ourBuyPrice()  { return PriceMath.buyOrderPrice(topBuyOrder); }
    public double ourSellPrice() { return PriceMath.sellOfferPrice(lowestSellOffer); }
    public double margin(double tax) { return PriceMath.netMarginFraction(topBuyOrder, lowestSellOffer, tax); }
    public double minWeeklyVolume()  { return Math.min(buyWeekVolume, sellWeekVolume); }
    public double hourlyVolume()     { return minWeeklyVolume() / 168.0; }

    /**
     * Per-leg flow estimates (units/hr). The two legs are fed by OPPOSITE market
     * flows, following the same inversion convention as quick_status prices
     * (buy* names ~ the sell-offer side):
     *   - our BUY order is consumed by INSTASELLERS  → sellMovingWeek
     *   - our SELL offer is consumed by INSTABUYERS  → buyMovingWeek
     * Estimates only — measured per-item EMAs override these once fills are
     * observed, so a wrong split self-corrects with data.
     */
    public double buyLegHourly()  { return sellWeekVolume / 168.0; }
    public double sellLegHourly() { return buyWeekVolume / 168.0; }

    /** Estimated hours for {@code units} to fill at the front of the book. */
    public double fillTimeHours(int units) {
        double v = hourlyVolume();
        return v <= 0 ? Double.MAX_VALUE : units / v;
    }

    /** Volatility-adjusted minimum margin: riskier items must clear a fatter cushion. */
    public double requiredMargin(FlipConfig cfg) {
        return cfg.apiMinMargin + cfg.volatilityLambda * volatility;
    }

    /**
     * Ranking score: expected profit throughput with a tunable volume exponent β.
     *   score = profitPerUnit · volume^β
     * β=1 is pure throughput; β&lt;1 tilts toward margin/ROI when capital is scarce.
     */
    public double score(FlipConfig cfg) {
        double ppu = Math.max(0, PriceMath.profitPerUnit(topBuyOrder, lowestSellOffer, cfg.taxFraction));
        double base = ppu * Math.pow(Math.max(1, minWeeklyVolume()), cfg.rankVolumeBeta);
        double trendFactor = Math.max(0.5, Math.min(1.5, 1 + cfg.trendWeight * trend));  // momentum tilt
        return base * trendFactor;
    }
}
