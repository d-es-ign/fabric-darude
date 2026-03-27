package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class SandstormDebugHudMixin {
	@Shadow
	@Final
	private Minecraft client;

	@Inject(method = "extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V", at = @At("TAIL"), require = 0)
	private void darude$appendSandstormDebug(GuiGraphicsExtractor extractor, List<String> lines, boolean leftSide, CallbackInfo ci) {
		if (!leftSide) {
			return;
		}

		lines.add("");
		lines.addAll(SandstormClientEffects.getDebugLines(client));
	}
}
