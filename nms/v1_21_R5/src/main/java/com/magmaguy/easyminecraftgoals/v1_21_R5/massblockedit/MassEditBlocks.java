package com.magmaguy.easyminecraftgoals.v1_21_R5.massblockedit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_21_R5.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R5.block.data.CraftBlockData;

public class MassEditBlocks {
    public static void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData blockData, boolean applyPhysics) {
        ServerLevel nmsWorld = ((CraftWorld) world).getHandle();
        BlockPos blockPos = new BlockPos(x, y, z);
        BlockState blockState = ((CraftBlockData) blockData).getState();

        int flags = Block.UPDATE_CLIENTS | (applyPhysics ? Block.UPDATE_NEIGHBORS : Block.UPDATE_KNOWN_SHAPE);
        if (!nmsWorld.setBlock(blockPos, blockState, flags, 512)
                && !nmsWorld.getBlockState(blockPos).equals(blockState))
            throw new IllegalStateException("Native block placement failed at " + x + "," + y + "," + z);
    }
}
