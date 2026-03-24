package com.darude.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

public class FullPyramidBlock extends Block {
	private static final VoxelShape FULL_BLOCK_SHAPE = createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

	public FullPyramidBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return FULL_BLOCK_SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return FULL_BLOCK_SHAPE;
	}
}
