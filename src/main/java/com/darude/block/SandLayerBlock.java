package com.darude.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.stream.IntStream;

public class SandLayerBlock extends Block {
	public static final MapCodec<SandLayerBlock> CODEC = createCodec(SandLayerBlock::new);
	public static final int MAX_LAYERS = 16;
	public static final IntProperty LAYERS = IntProperty.of("layers", 1, MAX_LAYERS - 1);
	private static final VoxelShape[] SHAPES_BY_LAYER = IntStream.rangeClosed(0, MAX_LAYERS - 1)
		.mapToObj(layer -> Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, layer, 16.0))
		.toArray(VoxelShape[]::new);

	public SandLayerBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LAYERS, 1));
	}

	@Override
	protected MapCodec<? extends SandLayerBlock> getCodec() {
		return CODEC;
	}

	@Override
	protected boolean canReplace(BlockState state, ItemPlacementContext context) {
		int layers = state.get(LAYERS);
		if (context.getStack().isOf(this.asItem()) && layers < MAX_LAYERS - 1) {
			if (context.canReplaceExisting()) {
				return context.getSide() == Direction.UP;
			}

			return true;
		}

		return layers == 1;
	}

	@Override
	@Nullable
	public BlockState getPlacementState(ItemPlacementContext context) {
		BlockState currentState = context.getWorld().getBlockState(context.getBlockPos());
		if (currentState.isOf(this)) {
			int layers = currentState.get(LAYERS);
			if (layers >= MAX_LAYERS - 1) {
				return Blocks.SAND.getDefaultState();
			}

			return currentState.with(LAYERS, layers + 1);
		}

		return super.getPlacementState(context);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LAYERS);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return SHAPES_BY_LAYER[state.get(LAYERS)];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return SHAPES_BY_LAYER[state.get(LAYERS)];
	}

	@Override
	protected boolean canPathfindThrough(BlockState state, NavigationType type) {
		return type == NavigationType.LAND && state.get(LAYERS) < 5;
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		BlockPos belowPos = pos.down();
		BlockState belowState = world.getBlockState(belowPos);

		if (belowState.isOf(this)) {
			return belowState.get(LAYERS) == MAX_LAYERS - 1;
		}

		return Block.isFaceFullSquare(belowState.getCollisionShape(world, belowPos), Direction.UP);
	}
}
