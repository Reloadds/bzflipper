package com.bzflipper.mc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captures Bazaar GUIs to config/bzflipper-dump.txt so the real button/tooltip
 * wording can be read and matched.
 *
 * Keybinds DON'T fire while a container GUI is open, so a manual dump key is
 * useless for this. Instead {@link #autoDump} runs from the macro's tick loop
 * and records each distinct screen the flipper visits — no keypress required.
 */
public final class GuiDump {

    private GuiDump() {}

    private static final Set<String> seen = new HashSet<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bzflipper-dump.txt");
    }

    /** Start a fresh capture (truncate file, clear seen-set). */
    public static void reset() {
        seen.clear();
        try {
            Files.writeString(file(), "=== bzflipper GUI capture ===\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    /** If a chest GUI with a not-yet-seen title is open, append its full dump. */
    public static void autoDump(MinecraftClient mc) {
        GenericContainerScreenHandler chest = GuiHelper.openChest(mc);
        if (chest == null) return;
        String title = GuiHelper.screenTitle(mc);
        if (title.isEmpty() || seen.contains(title)) return;
        seen.add(title);
        writeDump(mc, chest, title);
        chat(mc, "§e[BZ] captured '§f" + title + "§e' → config/bzflipper-dump.txt");
    }

    private static void writeDump(MinecraftClient mc, GenericContainerScreenHandler chest, String title) {
        StringBuilder sb = new StringBuilder();
        int n = GuiHelper.chestSlotCount(chest);
        sb.append("\n==== title='").append(title).append("'  slots=").append(n).append(" ====\n");
        for (int i = 0; i < n; i++) {
            String name = GuiHelper.itemNameAt(mc, i);
            if (name.isEmpty()) continue;
            sb.append("[").append(i).append("] ").append(name).append("\n");
            List<String> lore = GuiHelper.lore(chest.getSlot(i).getStack());
            for (String l : lore) sb.append("        | ").append(l).append("\n");
        }
        try {
            Files.writeString(file(), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            chat(mc, "§c[BZ] dump write failed: " + e.getMessage());
        }
    }

    private static void chat(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
        System.out.println("[bzflipper] " + msg.replaceAll("§.", ""));
    }
}
