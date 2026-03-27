package com.darude.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.stream.IntStream;

public class SandLayerBlock extends Block implements SimpleWaterloggedBlock {
	public static final int MAX_LAYERS = 16;
	public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX_LAYERS - 1);
	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
	private static final VoxelShape[] SHAPES_BY_LAYER = IntStream.rangeClosed(0, MAX_LAYERS - 1)
		.mapToObj(layer -> Block.box(0.0, 0.0, 0.0, 16.0, layer, 16.0))
		.toArray(VoxelShape[]::new);

	public SandLayerBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(LAYERS, 1).setValue(WATERLOGGED, false));
	}

	@Override
	protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
		int layers = state.getValue(LAYERS);
		if (context.getItemInHand().is(this.asItem()) && layers < MAX_LAYERS) {
			if (context.replacingClickedOnBlock()) {
				return context.getClickedFace() == Direction.UP;
			}

			return true;
		}

		return layers == 1 && !context.getItemInHand().is(Items.WATER_BUCKET);
	}

	@Override
	@Nullable
 	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState currentState = context.getLevel().getBlockState(context.getClickedPos());
		if (currentState.is(this)) {
			int layers = currentState.getValue(LAYERS);
			if (layers >= MAX_LAYERS - 1) {
				return Blocks.SAND.defaultBlockState();
			}

			return currentState.setValue(LAYERS, layers + 1);
		}

		FluidState fluidState = context.getLevel().getFluidState(context.getClickedPos());
		BlockState placementState = super.getStateForPlacement(context);
		if (placementState == null) {
			return null;
		}

		return placementState.setValue(WATERLOGGED, fluidState.getType() == Fluids.WATER);
	}

	@Override
	protected FluidState getFluidState(BlockState state) {
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
	}

	@Override
	protected BlockState updateShape(
		BlockState state,
		LevelReader world,
		ScheduledTickAccess tickView,
		BlockPos pos,
		Direction direction,
		BlockPos neighborPos,
		BlockState neighborState,
		RandomSource random
	) {
		if (world.getFluidState(pos).getType().is(FluidTags.LAVA)) {
			if (world instanceof LevelAccessor worldAccess) {
				worldAccess.levelEvent(LevelEvent.LAVA_FIZZ, pos, 0);
			}
			return state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}

		if (state.getValue(WATERLOGGED)) {
			tickView.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
		}

		if (direction == Direction.DOWN && !state.canSurvive(world, pos)) {
			return state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}

		return super.updateShape(state, world, tickView, pos, direction, neighborPos, neighborState, random);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS, WATERLOGGED);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPES_BY_LAYER[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPES_BY_LAYER[state.getValue(LAYERS)];
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType type) {
		return type == PathComputationType.LAND && state.getValue(LAYERS) < 5;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
		BlockPos belowPos = pos.below();
		BlockState belowState = world.getBlockState(belowPos);

		if (belowState.is(this)) {
			return belowState.getValue(LAYERS) == MAX_LAYERS - 1;
		}

		return belowState.isFaceSturdy(world, belowPos, Direction.UP);
	}
}
