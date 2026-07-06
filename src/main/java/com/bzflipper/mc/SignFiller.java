package com.bzflipper.mc;

import com.bzflipper.mixin.AbstractSignEditScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;

/**
 * Types a value into the Bazaar's "Custom Amount" / "Custom Price" sign popup.
 *
 * How it works: we write our text into the sign's line buffer (via the Mixin
 * accessor), then close the screen. Closing an edited sign makes the vanilla
 * client send the UpdateSign packet to the server with those lines — exactly as
 * if a human clicked "Done". No custom packets, so it looks like normal input.
 */
public final class SignFiller {

    private SignFiller() {}

    public static boolean isSignScreen(MinecraftClient mc) {
        return mc.currentScreen instanceof AbstractSignEditScreen;
    }

    /** Put {@code text} on line 1 of the open sign and submit it. */
    public static void fill(MinecraftClient mc, String text) {
        if (!(mc.currentScreen instanceof AbstractSignEditScreen screen)) return;

        String[] lines = ((AbstractSignEditScreenAccessor) (Object) screen).getMessages();
        if (lines != null && lines.length > 0) {
            lines[0] = text;
            for (int i = 1; i < lines.length; i++) lines[i] = "";
        }
        // Closing an edited sign sends the update to the server (vanilla behaviour).
        screen.close();
    }
}
