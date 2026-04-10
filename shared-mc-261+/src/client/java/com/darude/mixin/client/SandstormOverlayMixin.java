package com.darude.mixin.client;

import com.darude.SandstormOverlayRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class SandstormOverlayMixin {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"), require = 0)
	private void darude$renderSandstormOverlayModern(GuiGraphics drawContext, DeltaTracker tickCounter, CallbackInfo ci) {
		SandstormOverlayRenderer.render(drawContext, Minecraft.getInstance());
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("TAIL"), require = 0)
	private void darude$renderSandstormOverlayLegacy(GuiGraphics drawContext, float tickDelta, CallbackInfo ci) {
		SandstormOverlayRenderer.render(drawContext, Minecraft.getInstance());
	}
}
