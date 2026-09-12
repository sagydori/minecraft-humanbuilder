package com.humanbuilder.scanner;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/** A single block the schematic wants placed, resolved against the real world. */
public record Target(BlockPos pos, BlockState state) {}
