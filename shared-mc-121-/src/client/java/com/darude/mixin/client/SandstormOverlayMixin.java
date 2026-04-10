package com.darude.mixin.client;

import com.darude.SandstormOverlayRenderer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class SandstormOverlayMixin {
	@Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", at = @At("TAIL"), require = 0)
	private void darude$renderSandstormOverlayModern(Object drawContext, Object tickCounter, CallbackInfo ci) {
		SandstormOverlayRenderer.render(drawContext, MinecraftClient.getInstance());
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("TAIL"), require = 0)
	private void darude$renderSandstormOverlayLegacy(Object drawContext, float tickDelta, CallbackInfo ci) {
		SandstormOverlayRenderer.render(drawContext, MinecraftClient.getInstance());
	}
}
