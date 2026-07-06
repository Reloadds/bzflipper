package com.bzflipper.hud;

import com.bzflipper.mc.BazaarMacro;
import com.bzflipper.track.ProfitTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Always-on HUD: macro state, purse, live coins/hour (session + rolling window),
 * open orders, and the current top flip the API is seeing.
 */
public class Overlay {

    private final BazaarMacro macro;

    public Overlay(BazaarMacro macro) {
        this.macro = macro;
    }

    public void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null) return;

        ProfitTracker t = macro.getTracker();
        int x = 4, y = 4, line = 10;
        int header = macro.isEnabled() ? 0x55FF55 : 0xFF5555, label = 0xFFFFFF, gold = 0xFFD700;

        String[] rows = {
            "§lBazaar Flipper§r  " + (macro.isEnabled() ? "§aON" : "§cOFF"),
            "phase: " + macro.getState(),
            macro.statusLine,
            "purse: " + coins(macro.purse) + "   open: " + macro.buyCount + "B/" + macro.sellCount + "S",
            "§6coins/hr: " + coins(t.recentPerHour()) + " §7(recent)",
            "§6total: " + coins(t.total()) + " §7in " + (t.elapsedSeconds() / 60) + "m"
                    + "  = " + coins(t.sessionPerHour()) + "/hr",
            "flips: " + macro.flipsCompleted + "  orders: " + macro.ordersPlaced,
            "top flip: " + macro.topCandidate,
        };

        for (int i = 0; i < rows.length; i++) {
            int color = i == 0 ? header : (i >= 4 && i <= 5 ? gold : label);
            ctx.drawText(mc.textRenderer, Text.literal(rows[i]), x, y + i * line, color, true);
        }
    }

    private static String coins(double v) {
        if (Double.isNaN(v)) return "—";
        double a = Math.abs(v);
        if (a >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", v / 1_000_000_000);
        if (a >= 1_000_000)     return String.format(Locale.ROOT, "%.2fM", v / 1_000_000);
        if (a >= 1_000)         return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        return String.format(Locale.ROOT, "%,.0f", v);
    }
}
