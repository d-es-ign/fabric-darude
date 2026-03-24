package com.darude.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public class PyramidBlock extends Block {
	private static final VoxelShape SHAPE = VoxelShapes.union(
		createCuboidShape(0.0, 0.0, 0.0, 16.0, 1.0, 16.0),
		createCuboidShape(1.0, 1.0, 1.0, 15.0, 2.0, 15.0),
		createCuboidShape(2.0, 2.0, 2.0, 14.0, 3.0, 14.0),
		createCuboidShape(3.0, 3.0, 3.0, 13.0, 4.0, 13.0),
		createCuboidShape(4.0, 4.0, 4.0, 12.0, 5.0, 12.0),
		createCuboidShape(5.0, 5.0, 5.0, 11.0, 6.0, 11.0),
		createCuboidShape(6.0, 6.0, 6.0, 10.0, 7.0, 10.0),
		createCuboidShape(7.0, 7.0, 7.0, 9.0, 8.0, 9.0)
	);
	private static final VoxelShape COLLISION_SHAPE = createCuboidShape(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);

	public PyramidBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return COLLISION_SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return COLLISION_SHAPE;
	}
}
