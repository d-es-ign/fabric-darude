package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
public abstract class SandstormDebugHudMixin {
	@Shadow
	@Final
	private Minecraft client;

	@Inject(method = "showDebugScreen", at = @At("RETURN"), require = 0)
	private void darude$appendSandstormDebug(CallbackInfoReturnable<Boolean> cir) {
		SandstormClientEffects.getDebugLines(client);
	}
}
