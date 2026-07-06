package com.bzflipper.mc;

import com.bzflipper.core.BazaarStrings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Manage Orders grid ("Your/Co-op Bazaar Orders") into structured
 * orders — side, item, price, amount, fill %, claimable — so the macro reasons
 * over real data instead of string spot-checks. Grid entries look like:
 *
 *   BUY Diamond Essence
 *     Order amount: 19,101x
 *     Price per unit: 262.0 coins
 *     Filled: 5,000/19,101 (26.2%)
 *     ...
 *     Click to claim!            (only when something is claimable)
 */
public final class OrderParser {

    private OrderParser() {}

    /** One parsed order from the grid. */
    public record ParsedOrder(int slot, boolean buy, String item, double pricePerUnit,
                              int amount, double filledPct, boolean claimable) {
        public String key() { return item.toLowerCase(Locale.ROOT); }
        public boolean filled() { return claimable || filledPct >= 99.99; }
    }

    private static final Pattern NUM = Pattern.compile("([0-9][0-9,]*\\.?[0-9]*)");
    private static final Pattern PCT = Pattern.compile("\\(([0-9.]+)%\\)");

    public static List<ParsedOrder> parse(MinecraftClient mc) {
        List<ParsedOrder> out = new ArrayList<>();
        GenericContainerScreenHandler chest = GuiHelper.openChest(mc);
        if (chest == null) return out;

        int n = GuiHelper.chestSlotCount(chest);
        for (int i = 0; i < n; i++) {
            ItemStack st = chest.getSlot(i).getStack();
            String name = GuiHelper.name(st);
            boolean buy = name.startsWith(BazaarStrings.NAME_BUY_PREFIX);
            boolean sell = name.startsWith(BazaarStrings.NAME_SELL_PREFIX);
            if (!buy && !sell) continue;

            String item = name.substring(buy
                    ? BazaarStrings.NAME_BUY_PREFIX.length()
                    : BazaarStrings.NAME_SELL_PREFIX.length()).trim();

            double price = Double.NaN, pct = 0;
            int amount = 0;
            boolean claimable = false;

            for (String line : GuiHelper.lore(st)) {
                if (line.contains(BazaarStrings.LORE_PRICE_UNIT)) {
                    price = firstNum(line, price);
                } else if (line.contains(BazaarStrings.LORE_ORDER_AMOUNT)
                        || line.contains(BazaarStrings.LORE_OFFER_AMOUNT)) {
                    amount = (int) firstNum(line, amount);
                } else if (line.contains(BazaarStrings.LORE_FILLED_LINE)) {
                    Matcher m = PCT.matcher(line);
                    if (m.find()) pct = safe(m.group(1), pct);
                    else if (line.contains(BazaarStrings.LORE_FILLED)) pct = 100;
                }
                if (line.contains(BazaarStrings.LORE_CLAIM)) claimable = true;
            }
            out.add(new ParsedOrder(i, buy, item, price, amount, pct, claimable));
        }
        return out;
    }

    private static double firstNum(String line, double dflt) {
        Matcher m = NUM.matcher(line);
        if (m.find()) return safe(m.group(1).replace(",", ""), dflt);
        return dflt;
    }

    private static double safe(String s, double dflt) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return dflt; }
    }
}
