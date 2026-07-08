package com.bzflipper.mixin;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code info} field of the disconnect screen so we can read
 * WHY we were kicked and decide whether to reconnect.
 *
 * MAPPING NOTE (verified against Yarn 1.21.11): {@link DisconnectedScreen} holds
 * a {@link DisconnectionInfo info} field; {@code info.reason()} is the reason
 * Text shown to the player. If a future mapping renames the field, change the
 * {@code @Accessor} argument below.
 */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {

    @Accessor("info")
    DisconnectionInfo getInfo();
}
