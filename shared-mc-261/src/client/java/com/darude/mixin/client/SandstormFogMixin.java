package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(targets = "net.minecraft.client.render.fog.FogRenderer")
public abstract class SandstormFogMixin {
	private static final float SANDSTORM_FOG_END = 64.0f;
	private static final float SANDSTORM_FOG_START = 48.0f;
	private static final float GUST_FOG_END = 44.0f;
	private static final float GUST_FOG_START = 30.0f;

	@Inject(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
		at = @At("RETURN"),
		cancellable = true,
		require = 0
	)
	private void darude$darkenFogColor(
		Camera camera,
		int viewDistance,
		DeltaTracker tickCounter,
		float skyDarkness,
		ClientLevel clientWorld,
		CallbackInfoReturnable<?> cir
	) {
		Minecraft client = Minecraft.getInstance();
		float transitionProgress = SandstormClientEffects.getWindTransitionProgressIfSandstormActive(client);
		if (transitionProgress < 0.0f) {
			return;
		}
	}

	@ModifyVariable(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
		at = @At("HEAD"),
		ordinal = 0,
		argsOnly = true,
		require = 0
	)
	private float darude$clampRenderDistanceStart(float value) {
		Minecraft client = Minecraft.getInstance();
		float transitionProgress = SandstormClientEffects.getWindTransitionProgressIfSandstormActive(client);
		if (transitionProgress < 0.0f) {
			return value;
		}

		float animatedFogStart = lerp(SANDSTORM_FOG_START, GUST_FOG_START, transitionProgress);

		return Math.min(value, animatedFogStart);
	}

	@ModifyVariable(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
		at = @At("HEAD"),
		ordinal = 1,
		argsOnly = true,
		require = 0
	)
	private float darude$clampRenderDistanceEnd(float value) {
		Minecraft client = Minecraft.getInstance();
		float transitionProgress = SandstormClientEffects.getWindTransitionProgressIfSandstormActive(client);
		if (transitionProgress < 0.0f) {
			return value;
		}

		float animatedFogEnd = lerp(SANDSTORM_FOG_END, GUST_FOG_END, transitionProgress);

		return Math.min(value, animatedFogEnd);
	}

	private static float lerp(float start, float end, float progress) {
		return start + (end - start) * progress;
	}
}
