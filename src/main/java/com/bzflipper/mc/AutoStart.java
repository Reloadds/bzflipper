package com.bzflipper.mc;

import com.bzflipper.config.FlipConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Hands-free startup: login → SkyBlock → island → flipper.
 *
 *   1. On a Hypixel server: send /skyblock (retries every 10s, max 5)
 *   2. SkyBlock detected (sidebar title): wait {@code autoDelaySeconds}
 *   3. Send /is; wait for the world object to change (server transfer),
 *      with a 15s timeout in case we're already on the island
 *   4. Wait {@code autoDelaySeconds} after the world change, then start the macro
 *
 * Re-arms automatically on disconnect, so every login is seamless. Any manual
 * key press (toggle/panic) disarms it for the session — the user is in control.
 */
public class AutoStart {

    private enum Stage {
        WAIT_WORLD, JOIN_SKYBLOCK, WAIT_SKYBLOCK,
        DELAY_SKYBLOCK, WAIT_WORLD_CHANGE, DELAY_ISLAND,
        DONE, DISARMED
    }

    private final FlipConfig config;
    private final BazaarMacro macro;

    private Stage stage = Stage.WAIT_WORLD;
    private int timer = 0;
    private int retries = 0;
    private ClientWorld worldBefore = null;

    public AutoStart(FlipConfig config, BazaarMacro macro) {
        this.config = config;
        this.macro = macro;
    }

    /** Restart the whole sequence (e.g., after a lobby kick mid-session). */
    public void rearm(String why) {
        System.out.println("[bzflipper] autostart re-armed: " + why);
        stage = Stage.WAIT_WORLD;
        timer = 0;
        retries = 0;
        worldBefore = null;
    }

    /** Manual key press → the user takes over; stay quiet until next login. */
    public void disarm(String why) {
        if (stage != Stage.DISARMED && stage != Stage.DONE) {
            System.out.println("[bzflipper] autostart disarmed: " + why);
        }
        stage = Stage.DISARMED;
    }

    public void tick(MinecraftClient mc) {
        if (!config.autoStart) return;

        // Disconnected → re-arm for the next login.
        if (mc.world == null || mc.player == null) {
            if (stage != Stage.WAIT_WORLD) {
                stage = Stage.WAIT_WORLD;
                timer = 0;
                retries = 0;
                worldBefore = null;
            }
            return;
        }

        switch (stage) {
            case WAIT_WORLD -> {
                if (!onHypixel(mc)) return;             // only automate on Hypixel
                if (onSkyBlock(mc)) {
                    chat(mc, "autostart: already on SkyBlock — heading to island in "
                            + config.autoDelaySeconds + "s");
                    timer = 0;
                    stage = Stage.DELAY_SKYBLOCK;
                } else {
                    sendCmd(mc, "skyblock");
                    chat(mc, "autostart: joining SkyBlock…");
                    timer = 0;
                    retries = 0;
                    stage = Stage.WAIT_SKYBLOCK;
                }
            }
            case JOIN_SKYBLOCK -> { /* unused (folded into WAIT_WORLD) */ }
            case WAIT_SKYBLOCK -> {
                if (onSkyBlock(mc)) {
                    chat(mc, "autostart: SkyBlock loaded — /is in " + config.autoDelaySeconds + "s");
                    timer = 0;
                    stage = Stage.DELAY_SKYBLOCK;
                    return;
                }
                if (++timer > 200) {                     // retry every ~10s
                    timer = 0;
                    if (++retries > 5) { disarm("couldn't reach SkyBlock"); return; }
                    sendCmd(mc, "skyblock");
                }
            }
            case DELAY_SKYBLOCK -> {
                if (++timer >= config.autoDelaySeconds * 20) {
                    worldBefore = mc.world;
                    sendCmd(mc, "is");
                    chat(mc, "autostart: warping to island…");
                    timer = 0;
                    stage = Stage.WAIT_WORLD_CHANGE;
                }
            }
            case WAIT_WORLD_CHANGE -> {
                // Confirm the island from the scoreboard ("⏣ Your Island") — reliable
                // even when /is doesn't swap the world object. Then start the 8s timer.
                if (onIsland(mc)) {
                    chat(mc, "autostart: island loaded — flipper in " + config.autoDelaySeconds + "s");
                    timer = 0;
                    stage = Stage.DELAY_ISLAND;
                    return;
                }
                if (++timer > 300) {                     // 15s fallback
                    chat(mc, "autostart: island not confirmed — starting anyway in "
                            + config.autoDelaySeconds + "s");
                    timer = 0;
                    stage = Stage.DELAY_ISLAND;
                }
            }
            case DELAY_ISLAND -> {
                // Start exactly autoDelaySeconds after the island was detected.
                if (++timer >= config.autoDelaySeconds * 20) {
                    stage = Stage.DONE;
                    if (!macro.isEnabled()) {
                        chat(mc, "autostart: §astarting the flipper!");
                        macro.start();
                    }
                }
            }
            case DONE, DISARMED -> { /* wait for disconnect to re-arm */ }
        }
    }

    // ---- helpers ----

    private static boolean onHypixel(MinecraftClient mc) {
        ServerInfo info = mc.getCurrentServerEntry();
        return info != null && info.address != null
                && info.address.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    private static boolean onSkyBlock(MinecraftClient mc) {
        return ScoreboardReader.title(mc).toLowerCase(Locale.ROOT).contains("skyblock");
    }

    /** True once the SkyBlock sidebar shows we're on an island ("⏣ Your Island"). */
    private static boolean onIsland(MinecraftClient mc) {
        for (String l : ScoreboardReader.sidebarLines(mc)) {
            if (l.toLowerCase(Locale.ROOT).contains("island")) return true;
        }
        return false;
    }

    private static void sendCmd(MinecraftClient mc, String cmd) {
        var nh = mc.getNetworkHandler();
        if (nh != null) nh.sendChatCommand(cmd);
    }

    private static void chat(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("§b[BZ] §r" + msg), false);
        System.out.println("[bzflipper] " + msg.replaceAll("§.", ""));
    }
}
