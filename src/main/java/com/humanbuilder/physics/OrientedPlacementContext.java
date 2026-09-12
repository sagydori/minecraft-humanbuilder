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
 * An {@link ItemPlacementContext} whose "player look" is overridden to a chosen
 * direction. This lets us evaluate {@code Block.getPlacementState(...)} for each
 * candidate facing without actually rotating the player, so we can find the look
 * direction that reproduces a directional block's exact schematic state
 * (observers, chests, furnaces, droppers, pistons, stairs, logs, …). The state
 * machine then aims the real player to that direction before placing.
 */
public class OrientedPlacementContext extends ItemPlacementContext {

    private final Direction look;

    public OrientedPlacementContext(PlayerEntity player, Hand hand, ItemStack stack,
                                    BlockHitResult hit, Direction look) {
        super(player, hand, stack, hit);
        this.look = look;
    }

    @Override
    public Direction getPlayerLookDirection() {
        return look;
    }

    @Override
    public Direction getHorizontalPlayerFacing() {
        return look.getAxis().isHorizontal() ? look : Direction.NORTH;
    }

    @Override
    public Direction[] getPlacementDirections() {
        // Standard ordering with our look first and its opposite last.
        List<Direction> order = new ArrayList<>(6);
        order.add(look);
        for (Direction d : Direction.values()) {
            if (d != look && d != look.getOpposite()) order.add(d);
        }
        order.add(look.getOpposite());
        return order.toArray(new Direction[0]);
    }
}
