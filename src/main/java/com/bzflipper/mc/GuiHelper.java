package com.bzflipper.mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin, mapping-stable helpers for reading and clicking chest-style GUIs.
 * Everything the macro does to the game funnels through here, which is also the
 * seam you reimplement for the headless (Mineflayer) port.
 */
public final class GuiHelper {

    private GuiHelper() {}

    /** The chest container currently open, or null if no chest GUI is open. */
    public static GenericContainerScreenHandler openChest(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?> hs
                && hs.getScreenHandler() instanceof GenericContainerScreenHandler gch) {
            return gch;
        }
        return null;
    }

    /** Title of the currently open screen (lowercased), or "" if none. */
    public static String screenTitle(MinecraftClient mc) {
        if (mc.currentScreen == null) return "";
        return mc.currentScreen.getTitle().getString().toLowerCase(Locale.ROOT);
    }

    /** Number of slots that belong to the chest (excludes the player inventory). */
    public static int chestSlotCount(GenericContainerScreenHandler chest) {
        return chest.getRows() * 9;
    }

    /** Plain, lowercased display name of a stack. */
    public static String name(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.getName().getString().toLowerCase(Locale.ROOT);
    }

    /** Lore lines of a stack as plain lowercased strings. */
    public static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return out;
        LoreComponent lc = stack.get(DataComponentTypes.LORE);
        if (lc == null) return out;
        for (Text line : lc.styledLines()) {
            out.add(line.getString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Find the first chest slot whose item name contains {@code needle}
     * (case-insensitive). Returns the slot index or -1 if not found.
     */
    public static int findSlotByName(MinecraftClient mc, String needle) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return -1;
        String n = needle.toLowerCase(Locale.ROOT);
        int count = chestSlotCount(chest);
        for (int i = 0; i < count; i++) {
            Slot slot = chest.getSlot(i);
            if (name(slot.getStack()).contains(n)) return i;
        }
        return -1;
    }

    /** True if any chest slot's name contains {@code needle}. */
    public static boolean hasItemNamed(MinecraftClient mc, String needle) {
        return findSlotByName(mc, needle) >= 0;
    }

    /** First slot whose name EXACTLY equals {@code needle} (case-insensitive), or -1. */
    public static int findSlotExact(MinecraftClient mc, String needle) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return -1;
        String n = needle.toLowerCase(Locale.ROOT).trim();
        int count = chestSlotCount(chest);
        for (int i = 0; i < count; i++) {
            if (name(chest.getSlot(i).getStack()).trim().equals(n)) return i;
        }
        return -1;
    }

    /** True if a slot's name exactly equals {@code needle}. */
    public static boolean hasExactItemNamed(MinecraftClient mc, String needle) {
        return findSlotExact(mc, needle) >= 0;
    }

    /** Click the exact-named slot; fall back to a substring match. True if clicked. */
    public static boolean clickExactName(MinecraftClient mc, String needle) {
        int idx = findSlotExact(mc, needle);
        if (idx < 0) idx = findSlotByName(mc, needle);
        return idx >= 0 && clickSlotIndex(mc, idx);
    }

    /**
     * Left-click the first chest slot whose name contains {@code needle}.
     * Returns true if something was clicked.
     */
    public static boolean clickByName(MinecraftClient mc, String needle) {
        int idx = findSlotByName(mc, needle);
        if (idx < 0) return false;
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null || mc.interactionManager == null || mc.player == null) return false;
        mc.interactionManager.clickSlot(chest.syncId, idx, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    /**
     * Find the first chest slot whose name contains {@code nameNeedle} AND whose
     * lore contains {@code loreNeedle}. Used to pick our buy order vs our sell
     * offer on the Manage Orders grid (same item name, different lore). -1 if none.
     */
    public static int findSlotByNameAndLore(MinecraftClient mc, String nameNeedle, String loreNeedle) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return -1;
        String n = nameNeedle.toLowerCase(Locale.ROOT);
        String l = loreNeedle.toLowerCase(Locale.ROOT);
        int count = chestSlotCount(chest);
        for (int i = 0; i < count; i++) {
            ItemStack st = chest.getSlot(i).getStack();
            if (!name(st).contains(n)) continue;
            for (String line : lore(st)) {
                if (line.contains(l)) return i;
            }
        }
        return -1;
    }

    /** Left-click the slot matched by name+lore. True if clicked. */
    public static boolean clickByNameAndLore(MinecraftClient mc, String nameNeedle, String loreNeedle) {
        int idx = findSlotByNameAndLore(mc, nameNeedle, loreNeedle);
        if (idx < 0) return false;
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null || mc.interactionManager == null || mc.player == null) return false;
        mc.interactionManager.clickSlot(chest.syncId, idx, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    /** True if the slot matched by name has any lore line containing {@code loreNeedle}. */
    public static boolean loreOfNamedContains(MinecraftClient mc, String nameNeedle, String loreNeedle) {
        int idx = findSlotByName(mc, nameNeedle);
        if (idx < 0) return false;
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return false;
        String l = loreNeedle.toLowerCase(Locale.ROOT);
        for (String line : lore(chest.getSlot(idx).getStack())) {
            if (line.contains(l)) return true;
        }
        return false;
    }

    /** Display name of the item in chest slot {@code idx}, or "". */
    public static String itemNameAt(MinecraftClient mc, int idx) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null || idx < 0 || idx >= chestSlotCount(chest)) return "";
        return name(chest.getSlot(idx).getStack());
    }

    /**
     * First chest slot whose lore contains ALL of {@code loreNeedles} (each needle
     * may match a different line). Used to find, e.g., a filled sell order. -1 if none.
     */
    public static int firstSlotWithLore(MinecraftClient mc, String... loreNeedles) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return -1;
        int count = chestSlotCount(chest);
        for (int i = 0; i < count; i++) {
            List<String> lore = lore(chest.getSlot(i).getStack());
            if (lore.isEmpty()) continue;
            boolean all = true;
            for (String needle : loreNeedles) {
                String n = needle.toLowerCase(Locale.ROOT);
                boolean found = false;
                for (String line : lore) if (line.contains(n)) { found = true; break; }
                if (!found) { all = false; break; }
            }
            if (all) return i;
        }
        return -1;
    }

    /** Count chest slots whose lore contains {@code loreNeedle}. */
    public static int countSlotsWithLore(MinecraftClient mc, String loreNeedle) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return 0;
        String n = loreNeedle.toLowerCase(Locale.ROOT);
        int count = chestSlotCount(chest), hits = 0;
        for (int i = 0; i < count; i++) {
            for (String line : lore(chest.getSlot(i).getStack())) {
                if (line.contains(n)) { hits++; break; }
            }
        }
        return hits;
    }

    /** Lowercased names of every non-empty chest slot (excludes player inventory). */
    public static Set<String> collectNames(MinecraftClient mc) {
        Set<String> out = new HashSet<>();
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null) return out;
        int count = chestSlotCount(chest);
        for (int i = 0; i < count; i++) {
            String nm = name(chest.getSlot(i).getStack());
            if (!nm.isEmpty()) out.add(nm);
        }
        return out;
    }

    /** Left-click a specific chest slot index. True if clicked. */
    public static boolean clickSlotIndex(MinecraftClient mc, int idx) {
        GenericContainerScreenHandler chest = openChest(mc);
        if (chest == null || idx < 0 || idx >= chestSlotCount(chest)
                || mc.interactionManager == null || mc.player == null) return false;
        mc.interactionManager.clickSlot(chest.syncId, idx, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    // Matches numbers like "1,234,567.8" possibly followed by "coins".
    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9,]*\\.?[0-9]*)");

    /**
     * Pull the first coin value out of the lore lines of the slot named
     * {@code needle}. Returns NaN if not found. Used for the HUD and margin math.
     */
    public static double readCoinPrice(MinecraftClient mc, String itemNeedle, String loreAnchor) {
        int idx = findSlotByName(mc, itemNeedle);
        if (idx < 0) return Double.NaN;
        ScreenHandler h = openChest(mc);
        if (h == null) return Double.NaN;
        for (String line : lore(h.getSlot(idx).getStack())) {
            if (loreAnchor == null || line.contains(loreAnchor.toLowerCase(Locale.ROOT))) {
                Matcher m = NUMBER.matcher(line);
                if (m.find()) {
                    try {
                        return Double.parseDouble(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return Double.NaN;
    }
}
