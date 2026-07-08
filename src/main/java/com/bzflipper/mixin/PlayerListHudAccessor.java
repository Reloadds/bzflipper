package com.bzflipper.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the tab-list FOOTER text so we can read the SkyBlock "Cookie Buff: …"
 * countdown without opening any GUI — the cheap, every-cycle timer read that the
 * cookie refresh guard needs (and the only read available in dryRun, where we
 * must not open the Bazaar/SkyBlock menu).
 *
 * MAPPING NOTE (verified against Yarn 1.21.11): PlayerListHud (class_355) holds
 * the footer as field {@code footer} (field_2154, a Text). Reach the instance via
 * {@code MinecraftClient.getInstance().inGameHud.getPlayerListHud()}.
 */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {

    @Accessor("footer")
    Text getFooter();
}
