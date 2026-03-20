package com.darude;

import net.fabricmc.api.ClientModInitializer;

public class DarudeModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DarudeMod.LOGGER.info("Darude client initialized");
	}
}
