package com.bzflipper.core;

/**
 * Every Hypixel-specific piece of on-screen text the macro matches against,
 * in ONE place. The GUI is navigated by matching these substrings against item
 * names / screen titles rather than hardcoding slot numbers, so if Hypixel
 * shifts the layout the macro still works — you only ever tweak strings here.
 *
 * IMPORTANT: These are best-effort defaults. Open the Bazaar in-game and CONFIRM
 * each string matches what you actually see, then adjust. They are intentionally
 * lowercase-compared (see GuiHelper) so capitalization doesn't matter.
 */
public final class BazaarStrings {

    private BazaarStrings() {}

    // --- Screen title fragments (to detect which screen we're on) ---
    public static final String TITLE_BAZAAR      = "bazaar";        // main bazaar / category screens
    public static final String TITLE_ORDER_SETUP = "order";         // "Order options", "Buy Order", etc.

    // --- Buttons / items we click (matched as "name contains ...") ---
    public static final String BTN_BUY_ORDER   = "buy order";
    public static final String BTN_SELL_OFFER  = "sell offer";
    public static final String BTN_CREATE_BUY  = "create buy order";
    public static final String BTN_CREATE_SELL = "create sell offer";

    // Preset amount buttons (avoid the sign-input popups)
    public static final String BTN_BUY_STACK   = "buy 1 stack";     // 64 units
    public static final String BTN_SELL_INV    = "sell inventory";  // sell everything we hold

    // Sign-input options (arbitrary amount / exact price)
    public static final String BTN_CUSTOM_AMOUNT = "custom amount";
    public static final String BTN_CUSTOM_PRICE  = "custom price";

    // Confirmation / "top of book" price buttons
    public static final String BTN_CONFIRM     = "confirm";
    public static final String BTN_BEST_PRICE  = "+0.1";            // "top order + 0.1"

    // Manage screen
    public static final String BTN_MANAGE_ORDERS  = "manage orders";
    public static final String BTN_MANAGE_ALT     = "your bazaar orders"; // alt wording
    public static final String BTN_CLAIM          = "claim";
    public static final String BTN_CANCEL_ORDER   = "cancel order";
    public static final String BTN_GO_BACK        = "go back";

    // --- Lore parsing anchors ---
    public static final String LORE_COINS        = "coins";
    public static final String LORE_TOP_BUY      = "buy price";     // fragment on product lore
    public static final String LORE_TOP_SELL     = "sell price";

    // Distinguish which side an order in the "Manage Orders" grid belongs to.
    public static final String LORE_SIDE_BUY     = "buy order";
    public static final String LORE_SIDE_SELL    = "sell offer";

    // Order status detection (from an order's lore on the Manage screen).
    public static final String LORE_FILLED       = "100%";          // fully filled
    public static final String LORE_FILLED_ALT   = "filled!";
    public static final String LORE_OUTBID       = "outbid";        // someone beat our price
}
