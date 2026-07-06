package com.bzflipper.hud;

import com.bzflipper.mc.BazaarMacro;
import com.bzflipper.mc.OrderParser.ParsedOrder;
import com.bzflipper.track.ProfitTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * HUD panel: state, purse, live coins/hour, session stats, API freshness, and
 * every open order with a fill bar. Drawn with a translucent backdrop and an
 * accent stripe so it reads as one clean widget.
 */
public class Overlay {

    private static final int PAD = 5, LINE = 11, WIDTH = 200;
    private static final int BG      = 0xC010101A;   // translucent near-black
    private static final int ACCENT  = 0xFF00D4FF;   // cyan stripe
    private static final int WHITE   = 0xFFFFFFFF;
    private static final int GREY    = 0xFFAAAAAA;
    private static final int GREEN   = 0xFF55FF55;
    private static final int RED     = 0xFFFF5555;
    private static final int GOLD    = 0xFFFFAA00;
    private static final int BAR_BG  = 0xFF2A2A38;

    private final BazaarMacro macro;

    public Overlay(BazaarMacro macro) {
        this.macro = macro;
    }

    public void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null) return;

        ProfitTracker t = macro.getTracker();
        List<ParsedOrder> ords = macro.lastOrders;
        int rows = 6 + (ords.isEmpty() ? 0 : ords.size() + 1);
        int x = 6, y = 6;
        int h = rows * LINE + PAD * 2;

        // Backdrop + accent stripe.
        ctx.fill(x - PAD, y - PAD, x + WIDTH, y - PAD + h, BG);
        ctx.fill(x - PAD, y - PAD, x - PAD + 2, y - PAD + h, ACCENT);

        int ly = y;
        // Title row.
        draw(ctx, mc, "§lBZ FLIPPER", x + 2, ly, WHITE);
        String state = macro.isEnabled() ? "● RUNNING" : "● STOPPED";
        draw(ctx, mc, state, x + WIDTH - mc.textRenderer.getWidth(state) - 8, ly,
                macro.isEnabled() ? GREEN : RED);
        ly += LINE;

        long age = macro.getApi().ageSeconds();
        draw(ctx, mc, String.format(Locale.ROOT, "purse %s   api %s",
                coins(macro.purse), age < 0 ? "…" : age + "s"), x + 2, ly, GREY);
        ly += LINE;

        draw(ctx, mc, String.format(Locale.ROOT, "§6%s/hr§7 now  ·  §6%s/hr§7 session",
                coins(t.recentPerHour()), coins(t.sessionPerHour())), x + 2, ly, WHITE);
        ly += LINE;

        draw(ctx, mc, String.format(Locale.ROOT, "profit %s   flips %d   fills %dB/%dS",
                coins(t.total()), macro.flipsCompleted, macro.buysFilled, macro.sellsFilled),
                x + 2, ly, GREY);
        ly += LINE;

        draw(ctx, mc, "top: " + macro.topCandidate, x + 2, ly, GREY);
        ly += LINE;

        draw(ctx, mc, macro.statusLine, x + 2, ly, WHITE);
        ly += LINE;

        if (!ords.isEmpty()) {
            draw(ctx, mc, "§8── orders ──", x + 2, ly, GREY);
            ly += LINE;
            for (ParsedOrder o : ords) {
                int color = o.buy() ? GREEN : GOLD;
                String tag = o.buy() ? "B" : "S";
                String name = o.item().length() > 18 ? o.item().substring(0, 18) : o.item();
                draw(ctx, mc, tag + " " + name, x + 2, ly, color);
                // Fill bar (right-aligned).
                int bx = x + WIDTH - 68, by = ly + 2, bw = 56, bh = 5;
                ctx.fill(bx, by, bx + bw, by + bh, BAR_BG);
                int fw = (int) (bw * Math.min(100, o.filledPct()) / 100.0);
                if (fw > 0) ctx.fill(bx, by, bx + fw, by + bh, o.filled() ? GREEN : ACCENT);
                ly += LINE;
            }
        }
    }

    private static void draw(DrawContext ctx, MinecraftClient mc, String s, int x, int y, int color) {
        ctx.drawText(mc.textRenderer, Text.literal(s), x, y, color, true);
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
