package com.darude;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

public class DarudeModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ParticleProviderRegistry.getInstance().register(DarudeParticles.SANDSTORM_STREAK, SandstormStreakParticle.Provider::new);
		ClientTickEvents.END_CLIENT_TICK.register(SandstormClientEffects::tick);
		HudRenderCallback.EVENT.register((drawContext, tickCounter) -> SandstormOverlayRenderer.render(drawContext, Minecraft.getInstance()));
		DarudeMod.LOGGER.info("Darude client initialized");
	}
}
