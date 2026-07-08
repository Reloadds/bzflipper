package com.bzflipper.track;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks realized profit and derives coins/hour two ways:
 *   - session rate: total profit over the whole session
 *   - recent rate: profit within a rolling window (default 10 min), which
 *     reacts quickly to how well it's flipping right now.
 *
 * Uses wall-clock time (System.currentTimeMillis) — fine here since this runs in
 * the live game JVM.
 */
public class ProfitTracker {

    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private final long startMillis = System.currentTimeMillis();
    private double total = 0;

    private record Event(long at, double amount) {}
    private final Deque<Event> window = new ArrayDeque<>();

    public synchronized void addProfit(double coins) {
        long now = System.currentTimeMillis();
        total += coins;
        window.addLast(new Event(now, coins));
        prune(now);
    }

    public synchronized double total() { return total; }

    public long elapsedSeconds() {
        return Math.max(1, (System.currentTimeMillis() - startMillis) / 1000);
    }

    /** Whole-session coins per hour. */
    public double sessionPerHour() {
        return sessionPerHour(0);
    }

    /** Session coins per hour including {@code extra} unrealized profit (e.g. the
     *  projected value of open sell offers), for an "if everything sells" rate. */
    public synchronized double sessionPerHour(double extra) {
        return (total + extra) / (elapsedSeconds() / 3600.0);
    }

    /** Coins per hour over the rolling window, extrapolated to an hourly rate. */
    public synchronized double recentPerHour() {
        long now = System.currentTimeMillis();
        prune(now);
        double sum = 0;
        for (Event e : window) sum += e.amount();
        long spanMs = Math.min(WINDOW_MS, now - startMillis);
        if (spanMs <= 0) return 0;
        return sum / (spanMs / 3600000.0);
    }

    private void prune(long now) {
        while (!window.isEmpty() && now - window.peekFirst().at() > WINDOW_MS) {
            window.removeFirst();
        }
    }
}
