package com.bzflipper.config;

/**
 * One item the flipper is allowed to trade.
 *
 * The macro reaches an item by clicking through the Bazaar menus, matching each
 * step's on-screen name against the fields below. Fill these with the exact
 * category and product names as they appear in-game.
 */
public class FlipTarget {

    /** Bazaar category tab, e.g. "Farming", "Mining", "Combat". */
    public String category = "Farming";

    /** Product name as shown in the category grid, e.g. "Enchanted Cactus Green". */
    public String product = "Enchanted Cactus Green";

    /** Skip this item unless the net margin (after tax) is at least this fraction. */
    public double minMargin = 0.04; // 4%

    /**
     * Units per buy order. 0 = use the "buy 1 stack" preset (64, no sign input).
     * Any value > 0 is typed into the "Custom Amount" sign popup.
     */
    public int amount = 0;

    /** Optional human note (ignored by the macro). */
    public String note = "";

    public FlipTarget() {}

    public FlipTarget(String category, String product, double minMargin) {
        this.category = category;
        this.product = product;
        this.minMargin = minMargin;
    }
}
