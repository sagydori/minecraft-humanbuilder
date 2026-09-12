package com.humanbuilder.inventory;

import net.minecraft.item.Item;

/** Raised to the state machine when a required block/item is nowhere in the inventory. */
public class MissingItemException extends RuntimeException {
    private final transient Item item;

    public MissingItemException(Item item) {
        super("Required item not found in inventory: " + item);
        this.item = item;
    }

    public Item getItem() {
        return item;
    }
}
