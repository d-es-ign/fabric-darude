package com.darude.mixin;

import com.darude.renewal.SandLayerFarmingService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow @Final private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void darude$invalidateFarmingEmitterCache(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		if (cir.getReturnValue() == null) {
			return;
		}

		SandLayerFarmingService.onBlockChanged(serverLevel, pos);
	}
}
