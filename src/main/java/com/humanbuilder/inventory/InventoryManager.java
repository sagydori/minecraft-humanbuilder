package com.humanbuilder.inventory;

import com.humanbuilder.stochastic.StochasticEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Module C — Inventory & Hotbar Manager.
 *
 * <p>Ticked by the state machine during FETCHING_ITEM. Flow:</p>
 * <ol>
 *   <li>Already holding the item → done.</li>
 *   <li>In the hotbar → set the selected slot (vanilla emits the
 *       UpdateSelectedSlot packet natively) and wait 1–2 ticks for the server.</li>
 *   <li>In the main inventory → hotbar-swap it into the held slot via a simulated
 *       window click ({@link SlotActionType#SWAP} on the always-open player
 *       screen handler), spaced by Gaussian delays (mean 220 ms, std 40 ms).</li>
 *   <li>Nowhere → throw {@link MissingItemException}.</li>
 * </ol>
 */
public final class InventoryManager {

    public static final InventoryManager INSTANCE = new InventoryManager();

    public enum Status { WORKING, DONE }

    private Item target;
    private long nextActionMs;

    private InventoryManager() {}

    public void begin(Item item) {
        this.target = item;
        this.nextActionMs = 0L;
    }

    /** @throws MissingItemException if the item cannot be found anywhere. */
    public Status tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || target == null || client.interactionManager == null) {
            return Status.WORKING;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionMs) return Status.WORKING;

        // 1) Already in hand?
        ItemStack held = player.getMainHandStack();
        if (held.getItem() == target) return Status.DONE;

        PlayerInventory inv = player.getInventory();

        // 2) In the hotbar (slots 0..8)?
        int hotbar = findSlot(inv, 0, 9);
        if (hotbar >= 0) {
            setSelectedSlot(inv, hotbar);
            // Let the server register the slot change (1–2 ticks).
            nextActionMs = now + (long) (50 + StochasticEngine.INSTANCE.raw().nextDouble() * 50);
            return Status.WORKING; // becomes DONE next tick when held == target
        }

        // 3) In the main inventory (slots 9..35)? Swap it into the held hotbar slot.
        int mainSlot = findSlot(inv, 9, 36);
        if (mainSlot >= 0) {
            int destHotbar = getSelectedSlot(inv);
            client.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    mainSlot,          // source slot in the player screen handler (= inv index for 9..35)
                    destHotbar,        // SWAP "button" is the destination hotbar index 0..8
                    SlotActionType.SWAP,
                    player);
            nextActionMs = now + (long) StochasticEngine.INSTANCE.inventoryClickMs();
            return Status.WORKING;
        }

        // 4) Unavailable.
        throw new MissingItemException(target);
    }

    private static int findSlot(PlayerInventory inv, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (!inv.getStack(i).isEmpty() && inv.getStack(i).getItem() == INSTANCE.target) {
                return i;
            }
        }
        return -1;
    }

    // Selected-slot accessors. The backing field was encapsulated in the 1.21.x
    // line, so we go through the getter/setter (vanilla still emits the
    // UpdateSelectedSlot packet natively from ClientPlayerEntity#tick).
    private static void setSelectedSlot(PlayerInventory inv, int slot) {
        inv.setSelectedSlot(slot);
    }

    private static int getSelectedSlot(PlayerInventory inv) {
        return inv.getSelectedSlot();
    }
}
