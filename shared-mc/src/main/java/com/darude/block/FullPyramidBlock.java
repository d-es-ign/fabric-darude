package com.darude.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public class FullPyramidBlock extends Block {
	private static final VoxelShape SHAPE = VoxelShapes.union(
		createCuboidShape(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
		createCuboidShape(1.0, 2.0, 1.0, 15.0, 4.0, 15.0),
		createCuboidShape(2.0, 4.0, 2.0, 14.0, 6.0, 14.0),
		createCuboidShape(3.0, 6.0, 3.0, 13.0, 8.0, 13.0),
		createCuboidShape(4.0, 8.0, 4.0, 12.0, 10.0, 12.0),
		createCuboidShape(5.0, 10.0, 5.0, 11.0, 12.0, 11.0),
		createCuboidShape(6.0, 12.0, 6.0, 10.0, 14.0, 10.0),
		createCuboidShape(7.0, 14.0, 7.0, 9.0, 16.0, 9.0)
	);
	private static final VoxelShape FULL_BLOCK_SHAPE = createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

	public FullPyramidBlock(Settings settings) {
		super(settings);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return SHAPE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return FULL_BLOCK_SHAPE;
	}
}
