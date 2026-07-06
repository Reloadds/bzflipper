package com.bzflipper;

import com.bzflipper.api.BazaarApi;
import com.bzflipper.config.FlipConfig;
import com.bzflipper.hud.Overlay;
import com.bzflipper.mc.BazaarMacro;
import com.bzflipper.track.ProfitTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Wires up config, the tick loop, two keybinds, and the HUD.
 *
 *   Toggle key (default \):   start/stop one flip cycle
 *   Panic key  (default END): hard-stop immediately
 */
public class BzFlipper implements ClientModInitializer {

    private FlipConfig config;
    private BazaarMacro macro;
    private Overlay overlay;

    private KeyBinding toggleKey;
    private KeyBinding panicKey;

    @Override
    public void onInitializeClient() {
        config = FlipConfig.load();
        BazaarApi api = new BazaarApi(config);
        ProfitTracker tracker = new ProfitTracker();
        macro = new BazaarMacro(config, api, tracker);
        overlay = new Overlay(macro);

        if (config.useApiFlips) api.start();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bzflipper.toggle", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH, KeyBinding.Category.MISC));

        panicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bzflipper.panic", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_END, KeyBinding.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> overlay.render(ctx));

        System.out.println("[bzflipper] initialized (dryRun=" + config.dryRun + ")");
    }

    private void onEndTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) macro.toggle();
        while (panicKey.wasPressed()) macro.stop("panic key");
        macro.onTick(mc);
    }
}
