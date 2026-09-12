package com.humanbuilder.physics;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link ItemPlacementContext} whose "player look" and "horizontal facing" are
 * overridden. This lets us evaluate {@code Block.getPlacementState(...)} as if the
 * player were looking in a chosen direction — from any hypothetical viewpoint —
 * without actually moving the player, so we can tell whether looking at a target
 * from a given spot would produce the schematic's exact facing (observers, chests,
 * furnaces, droppers, pistons, stairs, logs, hoppers, …).
 */
public class OrientedPlacementContext extends ItemPlacementContext {

    private final Direction look;      // full look direction (yaw+pitch), for 6-way FACING
    private final Direction horizontal; // horizontal facing (yaw only), for HORIZONTAL_FACING

    public OrientedPlacementContext(PlayerEntity player, Hand hand, ItemStack stack,
                                    BlockHitResult hit, Direction look, Direction horizontal) {
        super(player, hand, stack, hit);
        this.look = look;
        this.horizontal = horizontal.getAxis().isHorizontal() ? horizontal : Direction.NORTH;
    }

    @Override
    public Direction getPlayerLookDirection() {
        return look;
    }

    @Override
    public Direction getHorizontalPlayerFacing() {
        return horizontal;
    }

    @Override
    public Direction[] getPlacementDirections() {
        List<Direction> order = new ArrayList<>(6);
        order.add(look);
        for (Direction d : Direction.values()) {
            if (d != look && d != look.getOpposite()) order.add(d);
        }
        order.add(look.getOpposite());
        return order.toArray(new Direction[0]);
    }
}
