package com.darude.mixin.client;

import com.darude.SandstormClientEffects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(DebugHud.class)
public abstract class SandstormDebugHudMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@Inject(method = "getLeftText", at = @At("RETURN"), cancellable = true, require = 0)
	private void darude$appendSandstormDebug(CallbackInfoReturnable<List<String>> cir) {
		List<String> base = cir.getReturnValue();
		List<String> lines = new ArrayList<>(base.size() + 12);
		lines.addAll(base);
		lines.add("");
		lines.addAll(SandstormClientEffects.getDebugLines(client));
		cir.setReturnValue(lines);
	}
}
