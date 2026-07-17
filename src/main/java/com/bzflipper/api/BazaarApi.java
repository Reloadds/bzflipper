package com.bzflipper.api;

import com.bzflipper.config.FlipConfig;
import com.bzflipper.core.Keys;
import com.bzflipper.core.PriceMath;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pulls the live Bazaar order book from Hypixel's public API (no key needed) on
 * a background thread and ranks the best flips with PriceMath. The rest of the
 * mod reads {@link #getCandidates()} — a cheap volatile snapshot — so nothing
 * touches the network on the render/tick thread.
 *
 * Endpoint: https://api.hypixel.net/v2/skyblock/bazaar
 *   products.<TAG>.buy_summary[0].pricePerUnit  = lowest  SELL offer (we undercut)
 *   products.<TAG>.sell_summary[0].pricePerUnit = highest BUY order  (we outbid)
 *   products.<TAG>.quick_status.{buyMovingWeek, sellMovingWeek} = weekly volume
 *
 * NOTE: Hypixel's field naming is famously INVERTED — `buy_summary` holds the
 * SELL offers and `sell_summary` holds the BUY orders. The code below swaps them
 * accordingly (see the assignments in refresh()). Do NOT "correct" the swap to
 * match the field names, or every margin goes negative and no flips are found.
 */
public class BazaarApi {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";

    private final FlipConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** Runtime-adaptive minimum margin gate (the auto-margin controller sets this).
     *  volatile: written on the game thread, read here on the API thread. -1 = use
     *  the configured apiMinMargin. Kept OFF the config object so it never persists. */
    private volatile double dynMinMargin = -1;
    public void setDynMinMargin(double m) { dynMinMargin = m; }
    public double effectiveMinMargin() { return dynMinMargin > 0 ? dynMinMargin : config.apiMinMargin; }

    private volatile List<FlipCandidate> candidates = List.of();
    /** Live top-of-book for EVERY bazaar item, keyed by lowercase display name. */
    private volatile Map<String, FlipCandidate> quotes = Map.of();
    private volatile long lastUpdatedMillis = 0;
    private volatile String lastError = null;

    /** Rolling mid-price history per item → volatility (coefficient of variation). */
    private static final int HISTORY_LEN = 20;
    private final Map<String, java.util.Deque<Double>> priceHistory =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ScheduledExecutorService exec;

    public BazaarApi(FlipConfig config) {
        this.config = config;
    }

    public void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bzflipper-api");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::refreshSafe, 0,
                Math.max(10, config.apiRefreshSeconds), TimeUnit.SECONDS);
    }

    public void stop() {
        if (exec != null) { exec.shutdownNow(); exec = null; }
    }

    public List<FlipCandidate> getCandidates() { return candidates; }
    public long lastUpdatedMillis() { return lastUpdatedMillis; }
    public String lastError() { return lastError; }

    /** Live quote for an item by display name (any casing/punctuation), or null. */
    public FlipCandidate quote(String name) { return quotes.get(Keys.norm(name)); }

    /** Seconds since the last successful API refresh, or -1 if never. */
    public long ageSeconds() {
        return lastUpdatedMillis == 0 ? -1 : (System.currentTimeMillis() - lastUpdatedMillis) / 1000;
    }

    private void refreshSafe() {
        try { refresh(); lastError = null; }
        catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[bzflipper] bazaar API refresh failed: " + lastError);
        }
    }

    private void refresh() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .header("User-Agent", "bzflipper")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            throw new IllegalStateException("API success=false");
        }
        ensureItemNames();
        JsonObject products = root.getAsJsonObject("products");
        List<FlipCandidate> list = new ArrayList<>();
        Map<String, FlipCandidate> quoteMap = new java.util.HashMap<>();

        for (Map.Entry<String, com.google.gson.JsonElement> e : products.entrySet()) {
          // One malformed product (missing quick_status / fields, e.g. during a
          // fresh item launch) must not abort the whole refresh — skip just it.
          try {
            JsonObject p = e.getValue().getAsJsonObject();
            // Hypixel's naming is INVERTED: buy_summary holds the SELL offers,
            // sell_summary holds the BUY orders. Use them accordingly.
            JsonArray sellOffers = p.getAsJsonArray("buy_summary");
            JsonArray buyOrders  = p.getAsJsonArray("sell_summary");
            if (sellOffers == null || buyOrders == null || sellOffers.isEmpty() || buyOrders.isEmpty()) continue;

            double topBuyOrder     = buyOrders.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();   // we outbid this
            double lowestSellOffer = sellOffers.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();  // we undercut this
            if (topBuyOrder <= 0 || lowestSellOffer <= 0) continue;

            JsonObject q = p.getAsJsonObject("quick_status");
            if (q == null || !q.has("buyMovingWeek") || !q.has("sellMovingWeek")) continue;
            double buyMW = q.get("buyMovingWeek").getAsDouble();
            double sellMW = q.get("sellMovingWeek").getAsDouble();

            double buyDepth  = buyOrders.get(0).getAsJsonObject().has("amount")
                    ? buyOrders.get(0).getAsJsonObject().get("amount").getAsDouble() : 0;
            double sellDepth = sellOffers.get(0).getAsJsonObject().has("amount")
                    ? sellOffers.get(0).getAsJsonObject().get("amount").getAsDouble() : 0;
            double[] stats = updateAndGetStats(e.getKey(), (topBuyOrder + lowestSellOffer) / 2.0);
            double volatility = stats[0], trend = stats[1];

            FlipCandidate c = new FlipCandidate(e.getKey(), ItemNames.name(e.getKey()),
                    topBuyOrder, lowestSellOffer, buyMW, sellMW, buyDepth, sellDepth, volatility, trend);
            // Every item goes into the quote map (used for exact undercut checks
            // on orders we hold), regardless of the flip filters below.
            quoteMap.put(Keys.norm(c.displayName), c);

            double minMargin = effectiveMinMargin();   // adaptive gate (auto-margin) or config floor
            double margin = PriceMath.netMarginFraction(topBuyOrder, lowestSellOffer, config.taxFraction);
            // Volatility-adjusted floor: riskier items must clear a fatter cushion.
            double requiredMargin = minMargin + config.volatilityLambda * volatility;
            if (margin < requiredMargin || margin > config.apiMaxMargin) continue;
            if (Math.min(buyMW, sellMW) < config.apiMinWeeklyVolume) continue;
            // Momentum: don't buy into a crash (protects the sell side).
            if (trend < -config.crashFilter) continue;
            if (config.apiMaxUnitPrice > 0 && topBuyOrder > config.apiMaxUnitPrice) continue;

            // --- Anti-manipulation guards ---
            // 1) Lone-outlier top order: if the #1 book entry sits far away from
            //    #2, someone is spoofing the top of the book. Skip.
            if (buyOrders.size() > 1) {
                double second = buyOrders.get(1).getAsJsonObject().get("pricePerUnit").getAsDouble();
                if (second > 0 && (topBuyOrder - second) / second > config.apiMaxTopGap) continue;
            }
            if (sellOffers.size() > 1) {
                double second = sellOffers.get(1).getAsJsonObject().get("pricePerUnit").getAsDouble();
                if (second > 0 && (second - lowestSellOffer) / lowestSellOffer > config.apiMaxTopGap) continue;
            }
            // 2) Weighted-average cross-check: quick_status prices are volume-
            //    weighted averages of the book (naming inverted like the summaries:
            //    qs.buyPrice ~ sell-offer side, qs.sellPrice ~ buy-order side).
            //    If the spread collapses on averages, the top-of-book spread is fake.
            double avgSellOffer = q.has("buyPrice") ? q.get("buyPrice").getAsDouble() : 0;
            double avgBuyOrder  = q.has("sellPrice") ? q.get("sellPrice").getAsDouble() : 0;
            if (avgSellOffer > 0 && avgBuyOrder > 0) {
                double weightedMargin = PriceMath.netMarginFraction(avgBuyOrder, avgSellOffer, config.taxFraction);
                if (weightedMargin < minMargin * 0.4) continue;
            }

            list.add(c);
          } catch (RuntimeException ex) {
            // Malformed/partial product JSON — skip this one, keep the rest.
          }
        }

        list.sort((a, b) -> Double.compare(b.score(config), a.score(config)));
        candidates = List.copyOf(list.subList(0, Math.min(list.size(), 30)));
        quotes = Map.copyOf(quoteMap);
        lastUpdatedMillis = System.currentTimeMillis();
    }

    /** Append the item's mid price; return {volatility σ/μ, trend %-change over window}. */
    private double[] updateAndGetStats(String tag, double mid) {
        java.util.Deque<Double> hist = priceHistory.computeIfAbsent(tag, k -> new java.util.ArrayDeque<>());
        synchronized (hist) {
            hist.addLast(mid);
            while (hist.size() > HISTORY_LEN) hist.removeFirst();
            int n = hist.size();
            if (n < 4) return new double[]{0, 0};
            double[] a = new double[n];
            int i = 0;
            double mean = 0;
            for (double v : hist) { a[i++] = v; mean += v; }
            mean /= n;
            if (mean <= 0) return new double[]{0, 0};
            double var = 0;
            for (double v : a) var += (v - mean) * (v - mean);
            double vol = Math.sqrt(var / n) / mean;
            // Trend: mean of the newest third vs the oldest third.
            int k = Math.max(1, n / 3);
            double first = 0, last = 0;
            for (int j = 0; j < k; j++) { first += a[j]; last += a[n - 1 - j]; }
            first /= k; last /= k;
            double trend = first > 0 ? (last - first) / first : 0;
            return new double[]{vol, trend};
        }
    }

    /** Fetch the real item-name table once (resources/skyblock/items). */
    private void ensureItemNames() {
        if (ItemNames.loaded()) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://api.hypixel.net/v2/resources/skyblock/items"))
                    .header("User-Agent", "bzflipper").timeout(Duration.ofSeconds(40)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ItemNames.load(JsonParser.parseString(resp.body()).getAsJsonObject());
                System.out.println("[bzflipper] loaded real item names");
            } else {
                System.err.println("[bzflipper] item-names HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            System.err.println("[bzflipper] item-names load failed: " + e.getMessage());
        }
    }
}
