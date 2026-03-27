package com.darude.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldEvents;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.stream.IntStream;

public class SandLayerBlock extends Block implements Waterloggable {
	public static final MapCodec<SandLayerBlock> CODEC = createCodec(SandLayerBlock::new);
	public static final int MAX_LAYERS = 16;
	public static final IntProperty LAYERS = IntProperty.of("layers", 1, MAX_LAYERS - 1);
	public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
	private static final VoxelShape[] SHAPES_BY_LAYER = IntStream.rangeClosed(0, MAX_LAYERS - 1)
		.mapToObj(layer -> Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, layer, 16.0))
		.toArray(VoxelShape[]::new);

	public SandLayerBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LAYERS, 1).with(WATERLOGGED, false));
	}

	@Override
	protected MapCodec<? extends SandLayerBlock> getCodec() {
		return CODEC;
	}

	@Override
	protected boolean canReplace(BlockState state, ItemPlacementContext context) {
		int layers = state.get(LAYERS);
		if (context.getStack().isOf(this.asItem()) && layers < MAX_LAYERS) {
			if (context.canReplaceExisting()) {
				return context.getSide() == Direction.UP;
			}

			return true;
		}

		return layers == 1 && !context.getStack().isOf(Items.WATER_BUCKET);
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

		FluidState fluidState = context.getWorld().getFluidState(context.getBlockPos());
		BlockState placementState = super.getPlacementState(context);
		if (placementState == null) {
			return null;
		}

		return placementState.with(WATERLOGGED, fluidState.getFluid() == Fluids.WATER);
	}

	@Override
	protected FluidState getFluidState(BlockState state) {
		return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
	}

	@Override
	protected BlockState getStateForNeighborUpdate(
		BlockState state,
		WorldView world,
		ScheduledTickView tickView,
		BlockPos pos,
		Direction direction,
		BlockPos neighborPos,
		BlockState neighborState,
		Random random
	) {
		if (world.getFluidState(pos).isIn(FluidTags.LAVA)) {
			if (world instanceof WorldAccess worldAccess) {
				worldAccess.syncWorldEvent(WorldEvents.LAVA_EXTINGUISHED, pos, 0);
			}
			return state.get(WATERLOGGED) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
		}

		if (state.get(WATERLOGGED)) {
			tickView.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
		}

		if (direction == Direction.DOWN && !state.canPlaceAt(world, pos)) {
			return state.get(WATERLOGGED) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
		}

		return super.getStateForNeighborUpdate(state, world, tickView, pos, direction, neighborPos, neighborState, random);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LAYERS, WATERLOGGED);
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

		return !belowState.getCollisionShape(world, belowPos).getFace(Direction.UP).isEmpty();
	}
}
