package com.bzflipper.api;

import com.bzflipper.config.FlipConfig;
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
 *   products.<TAG>.buy_summary[0].pricePerUnit  = highest buy order  (we outbid)
 *   products.<TAG>.sell_summary[0].pricePerUnit = lowest  sell offer (we undercut)
 *   products.<TAG>.quick_status.{buyMovingWeek, sellMovingWeek} = weekly volume
 *
 * NOTE: Hypixel's field naming is famously inverted; we deliberately use the
 * order-book summaries (unambiguous) rather than quick_status.buyPrice/sellPrice.
 */
public class BazaarApi {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";

    private final FlipConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile List<FlipCandidate> candidates = List.of();
    /** Live top-of-book for EVERY bazaar item, keyed by lowercase display name. */
    private volatile Map<String, FlipCandidate> quotes = Map.of();
    private volatile long lastUpdatedMillis = 0;
    private volatile String lastError = null;

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
                Math.max(15, config.apiRefreshSeconds), TimeUnit.SECONDS);
    }

    public void stop() {
        if (exec != null) { exec.shutdownNow(); exec = null; }
    }

    public List<FlipCandidate> getCandidates() { return candidates; }
    public long lastUpdatedMillis() { return lastUpdatedMillis; }
    public String lastError() { return lastError; }

    /** Live quote for an item by (lowercase) display name, or null. */
    public FlipCandidate quote(String nameLower) { return quotes.get(nameLower); }

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
            double buyMW = q.get("buyMovingWeek").getAsDouble();
            double sellMW = q.get("sellMovingWeek").getAsDouble();

            FlipCandidate c = new FlipCandidate(e.getKey(), ItemNames.name(e.getKey()),
                    topBuyOrder, lowestSellOffer, buyMW, sellMW);
            // Every item goes into the quote map (used for exact undercut checks
            // on orders we hold), regardless of the flip filters below.
            quoteMap.put(c.displayName.toLowerCase(Locale.ROOT), c);

            double margin = PriceMath.netMarginFraction(topBuyOrder, lowestSellOffer, config.taxFraction);
            // Skip thin spreads AND absurd ones (huge margins are illiquid/manipulation traps).
            if (margin < config.apiMinMargin || margin > config.apiMaxMargin) continue;
            if (Math.min(buyMW, sellMW) < config.apiMinWeeklyVolume) continue;
            if (config.apiMaxUnitPrice > 0 && topBuyOrder > config.apiMaxUnitPrice) continue;

            list.add(c);
        }

        list.sort((a, b) -> Double.compare(b.score(config.taxFraction), a.score(config.taxFraction)));
        candidates = List.copyOf(list.subList(0, Math.min(list.size(), 30)));
        quotes = Map.copyOf(quoteMap);
        lastUpdatedMillis = System.currentTimeMillis();
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
