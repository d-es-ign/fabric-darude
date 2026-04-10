package com.darude;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class DarudeModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ParticleFactoryRegistry.getInstance().register(DarudeParticles.SANDSTORM_STREAK, SandstormStreakParticle.Factory::new);
		ClientTickEvents.END_CLIENT_TICK.register(SandstormClientEffects::tick);
		DarudeMod.LOGGER.info("Darude client initialized");
	}
}
