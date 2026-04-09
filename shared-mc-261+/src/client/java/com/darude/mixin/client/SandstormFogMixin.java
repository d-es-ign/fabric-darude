package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.fog.FogRenderer")
public abstract class SandstormFogMixin {
	@Inject(
		method = "computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IFLorg/joml/Vector4f;)V",
		at = @At("RETURN"),
		require = 0
	)
	private void darude$darkenFogColor(
		Camera camera,
		float tickDelta,
		ClientLevel clientWorld,
		int viewDistance,
		float skyDarkness,
		Vector4f color,
		CallbackInfo ci
	) {
		Minecraft client = Minecraft.getInstance();
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return;
		}

		float colorScale = 0.48f;
		color.mul(colorScale, colorScale * 0.92f, colorScale * 0.82f, 1.0f);
	}
}
