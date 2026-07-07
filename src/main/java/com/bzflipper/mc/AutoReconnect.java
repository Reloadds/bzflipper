package com.bzflipper.mc;

import com.bzflipper.config.FlipConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.util.function.Consumer;

/**
 * Auto-reconnect: rejoin the server after ANY involuntary disconnect.
 *
 * WHY THIS COVERS "no matter the close": in Minecraft every error close — kick,
 * ban, timeout, "you are lagging", connection lost, server restart, failed
 * login/transfer — ends on ONE screen, {@link DisconnectedScreen}. A deliberate
 * quit (pause menu → Disconnect) goes to the title/multiplayer screen instead.
 * So we reconnect whenever a DisconnectedScreen appears and never fight a manual
 * quit. In-server relocations (sent to a lobby/limbo, which keep the connection
 * alive) never hit this screen — the macro's own watchdog + AutoStart handle
 * those by walking SkyBlock → island → resume.
 *
 * Escape hatch: the first attempt waits {@code reconnectDelaySeconds}. Clicking
 * "Back to title" during that countdown moves off the DisconnectedScreen and
 * cancels the reconnect — so you can always stop it by hand.
 *
 * Backoff: repeated failures back off (delay·2^n, capped) so a genuinely-down
 * or restarting server isn't hammered; a successful join resets the counter.
 */
public class AutoReconnect {

    private final FlipConfig config;
    private final Consumer<String> logger;

    private ServerInfo lastServer;     // the server to rejoin (captured while connected)
    private int countdown = -1;        // ticks until the next attempt; <0 = idle
    private int attempts = 0;          // consecutive failed attempts
    private boolean reconnecting = false;
    private boolean gaveUp = false;

    public AutoReconnect(FlipConfig config, Consumer<String> logger) {
        this.config = config;
        this.logger = logger;
    }

    public void tick(MinecraftClient mc) {
        if (!config.autoReconnect) return;

        // Remember the server whenever the client knows one (set the moment a
        // connection is initiated, so even a failed FIRST join is retryable).
        ServerInfo cur = mc.getCurrentServerEntry();
        if (cur != null) lastServer = cur;

        // A live world means the (re)connect succeeded — reset the backoff.
        if (mc.world != null && cur != null) {
            if (reconnecting) log("reconnected to " + cur.address);
            reconnecting = false;
            attempts = 0;
            gaveUp = false;
            countdown = -1;
            return;
        }

        Screen s = mc.currentScreen;
        boolean disconnected = s instanceof DisconnectedScreen;
        if (!disconnected || lastServer == null) {
            // Off the disconnect screen (title / multiplayer / connecting) or
            // nothing to rejoin — stand down. Moving here mid-countdown (the user
            // clicked "Back to title", or a ConnectScreen is in progress) cancels.
            countdown = -1;
            return;
        }

        if (gaveUp) return;   // already exhausted attempts for this outage

        if (countdown < 0) {
            if (config.reconnectMaxAttempts > 0 && attempts >= config.reconnectMaxAttempts) {
                gaveUp = true;
                log("gave up reconnecting after " + attempts + " attempts");
                return;
            }
            int delay = reconnectDelaySeconds();
            countdown = delay * 20;
            log("connection lost — reconnecting in " + delay + "s (attempt " + (attempts + 1) + ")");
        }
        if (countdown > 0) { countdown--; return; }

        // Fire it.
        countdown = -1;
        attempts++;
        reconnecting = true;
        reconnect(mc);
    }

    /** delay·2^n with a hard cap, so a down/restarting server isn't hammered. */
    private int reconnectDelaySeconds() {
        int base = Math.max(1, config.reconnectDelaySeconds);
        long d = (long) base << Math.min(attempts, 4);   // 5,10,20,40,80…
        return (int) Math.min(Math.max(base, config.reconnectMaxDelaySeconds), d);
    }

    private void reconnect(MinecraftClient mc) {
        ServerInfo info = lastServer;
        if (info == null) return;
        log("reconnecting to " + info.address + "…");
        try {
            ServerAddress address = ServerAddress.parse(info.address);
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()),
                    mc, address, info, false, null);
        } catch (Throwable t) {
            log("reconnect failed to start: " + t.getMessage());
            reconnecting = false;   // let the DisconnectedScreen re-trigger a retry
        }
    }

    /** Manual override (e.g. panic): stop trying to reconnect this outage. */
    public void cancel() {
        countdown = -1;
        gaveUp = true;
    }

    private void log(String msg) {
        System.out.println("[bzflipper] autoreconnect: " + msg);
        if (logger != null) logger.accept(msg);
    }
}
