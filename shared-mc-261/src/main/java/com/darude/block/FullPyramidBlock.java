package com.darude.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FullPyramidBlock extends Block {
	private static final VoxelShape SHAPE = Shapes.or(
		Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
		Block.box(1.0, 2.0, 1.0, 15.0, 4.0, 15.0),
		Block.box(2.0, 4.0, 2.0, 14.0, 6.0, 14.0),
		Block.box(3.0, 6.0, 3.0, 13.0, 8.0, 13.0),
		Block.box(4.0, 8.0, 4.0, 12.0, 10.0, 12.0),
		Block.box(5.0, 10.0, 5.0, 11.0, 12.0, 11.0),
		Block.box(6.0, 12.0, 6.0, 10.0, 14.0, 10.0),
		Block.box(7.0, 14.0, 7.0, 9.0, 16.0, 9.0)
	);
	private static final VoxelShape FULL_BLOCK_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

	public FullPyramidBlock(Properties settings) {
		super(settings);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return FULL_BLOCK_SHAPE;
	}
}
