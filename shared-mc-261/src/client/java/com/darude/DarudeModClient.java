package com.darude;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class DarudeModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(SandstormClientEffects::tick);
		DarudeMod.LOGGER.info("Darude client initialized");
	}
}
