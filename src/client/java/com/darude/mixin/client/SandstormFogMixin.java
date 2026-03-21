package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.FogShape;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BackgroundRenderer.class)
public class SandstormFogMixin {
	private static final float SANDSTORM_FOG_END = 64.0f;
	private static final float SANDSTORM_FOG_START = 48.0f;

	@Inject(
		method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/render/Fog;",
		at = @At("RETURN"),
		cancellable = true,
		require = 0
	)
	private static void darude$limitFogReturn(
		Camera camera,
		BackgroundRenderer.FogType fogType,
		Vector4f color,
		float viewDistance,
		boolean thickenFog,
		float tickProgress,
		CallbackInfoReturnable<Fog> cir
	) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return;
		}

		Fog fog = cir.getReturnValue();
		float end = Math.min(fog.end(), SANDSTORM_FOG_END);
		float start = Math.min(fog.start(), SANDSTORM_FOG_START);
		if (start > end) {
			start = end * 0.75f;
		}

		cir.setReturnValue(new Fog(start, end, FogShape.CYLINDER, fog.red(), fog.green(), fog.blue(), fog.alpha()));
	}

	@Inject(
		method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;FZF)V",
		at = @At("TAIL"),
		require = 0
	)
	private static void darude$limitFogLegacy(
		Camera camera,
		BackgroundRenderer.FogType fogType,
		float viewDistance,
		boolean thickFog,
		float tickDelta,
		CallbackInfo ci
	) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return;
		}

		RenderSystem.setShaderFogStart(SANDSTORM_FOG_START);
		RenderSystem.setShaderFogEnd(SANDSTORM_FOG_END);
	}
}
