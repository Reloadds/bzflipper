package com.bzflipper.mc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Debug helper (default key: ] ). Dumps the currently open Bazaar GUI — its title
 * and every slot's item name + lore — to chat and to config/bzflipper-dump.txt.
 * This is how we discover the EXACT Hypixel wording so the BazaarStrings anchors
 * (and navigation) can be made to match reality.
 */
public final class GuiDump {

    private GuiDump() {}

    public static void dump(MinecraftClient mc) {
        String title = GuiHelper.screenTitle(mc);
        GenericContainerScreenHandler chest = GuiHelper.openChest(mc);
        if (chest == null) {
            chat(mc, "§e[BZ-DUMP] §rno chest GUI open (screen title='" + title + "')");
            return;
        }
        int n = GuiHelper.chestSlotCount(chest);
        chat(mc, "§e[BZ-DUMP] §rtitle='§f" + title + "§r'  slots=" + n);

        StringBuilder file = new StringBuilder();
        file.append("==== title='").append(title).append("'  slots=").append(n).append(" ====\n");
        for (int i = 0; i < n; i++) {
            String name = GuiHelper.itemNameAt(mc, i);
            if (name.isEmpty()) continue;
            List<String> lore = GuiHelper.lore(chest.getSlot(i).getStack());
            chat(mc, "§7#" + i + " §f" + name);
            file.append("[").append(i).append("] ").append(name).append("\n");
            for (String l : lore) file.append("        | ").append(l).append("\n");
        }
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("bzflipper-dump.txt");
            Files.writeString(p, file.toString());
            chat(mc, "§a[BZ-DUMP] §rfull dump (with lore) -> config/bzflipper-dump.txt");
        } catch (Exception e) {
            chat(mc, "§c[BZ-DUMP] file write failed: " + e.getMessage());
        }
    }

    private static void chat(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
        System.out.println("[bzflipper] " + msg.replaceAll("§.", ""));
    }
}
