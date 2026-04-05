package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(targets = "net.minecraft.client.render.fog.FogRenderer")
public abstract class SandstormFogMixin {
	private static final float SANDSTORM_FOG_END = 64.0f;
	private static final float SANDSTORM_FOG_START = 16.0f;

	@Inject(
		method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
		at = @At("RETURN"),
		cancellable = true,
		require = 0
	)
	private void darude$darkenFogColor(
		Camera camera,
		int viewDistance,
		RenderTickCounter tickCounter,
		float skyDarkness,
		ClientWorld clientWorld,
		CallbackInfoReturnable<Vector4f> cir
	) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return;
		}

		Vector4f color = new Vector4f(cir.getReturnValue());
		float colorScale = 0.62f;
		cir.setReturnValue(color.mul(colorScale, colorScale * 0.92f, colorScale * 0.82f, 1.0f));
	}

	@ModifyVariable(
		method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
		at = @At("HEAD"),
		ordinal = 2,
		argsOnly = true,
		require = 0
	)
	private float darude$clampRenderDistanceStart(float value) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return value;
		}

		return Math.min(value, SANDSTORM_FOG_START);
	}

	@ModifyVariable(
		method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
		at = @At("HEAD"),
		ordinal = 3,
		argsOnly = true,
		require = 0
	)
	private float darude$clampRenderDistanceEnd(float value) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return value;
		}

		return Math.min(value, SANDSTORM_FOG_END);
	}
}
