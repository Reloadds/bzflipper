package com.bzflipper.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private 4-line text buffer of the sign-edit screen so we can type
 * into the Bazaar's "Custom Amount" / "Custom Price" sign popups.
 *
 * MAPPING NOTE: the Yarn field name for the lines is "messages". If your mappings
 * call it "text", change the @Accessor argument below to "text". (This is the one
 * spot most likely to differ between mapping versions.)
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {

    @Accessor("messages")
    String[] getMessages();
}
