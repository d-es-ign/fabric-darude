package com.darude.mixin;

import com.darude.renewal.SandLayerFarmingService;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
	@Shadow @Final private World world;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void darude$invalidateFarmingEmitterCache(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}

		if (cir.getReturnValue() == null) {
			return;
		}

		if (!SandLayerFarmingService.shouldInvalidateEmitterCache(serverWorld, pos, cir.getReturnValue(), state)) {
			return;
		}

		SandLayerFarmingService.onBlockChanged(serverWorld, pos);
	}
}
