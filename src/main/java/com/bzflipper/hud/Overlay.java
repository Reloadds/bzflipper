package com.bzflipper.hud;

import com.bzflipper.mc.BazaarMacro;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Small always-on HUD so you can literally watch what the macro is thinking:
 * current state, the prices it read, the margin, and how many orders it placed.
 */
public class Overlay {

    private final BazaarMacro macro;

    public Overlay(BazaarMacro macro) {
        this.macro = macro;
    }

    public void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null) return;

        int x = 4, y = 4, line = 10;
        int on = 0x55FF55, off = 0xFF5555, label = 0xFFFFFF;

        String[] rows = new String[] {
            "§lBazaar Flipper§r  " + (macro.isEnabled() ? "§aON" : "§cOFF"),
            "phase: " + macro.getState(),
            macro.statusLine,
            fmt("top buy", macro.lastTopBuy),
            fmt("low sell", macro.lastLowSell),
            "margin: " + (Double.isNaN(macro.lastMargin) ? "—"
                    : String.format("%.2f%%", macro.lastMargin * 100)),
            "orders: " + macro.ordersPlaced
                    + "  filled B/S: " + macro.buysFilled + "/" + macro.sellsFilled,
            "flips: " + macro.flipsCompleted
                    + String.format("  ~%,.0f coins", macro.estProfit),
        };

        for (int i = 0; i < rows.length; i++) {
            ctx.drawText(mc.textRenderer, Text.literal(rows[i]), x, y + i * line,
                    i == 0 ? (macro.isEnabled() ? on : off) : label, true);
        }
    }

    private static String fmt(String label, double v) {
        return label + ": " + (Double.isNaN(v) ? "—" : String.format("%,.1f", v));
    }
}
