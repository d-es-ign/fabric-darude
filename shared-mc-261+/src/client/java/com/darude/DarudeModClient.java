package com.darude;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import static com.darude.DarudeMod.MOD_ID;

public class DarudeModClient implements ClientModInitializer {
	private static final Identifier SANDSTORM_OVERLAY_LAYER = Identifier.fromNamespaceAndPath(MOD_ID, "sandstorm_overlay");

	@Override
	public void onInitializeClient() {
		ParticleProviderRegistry.getInstance().register(DarudeParticles.SANDSTORM_STREAK, SandstormStreakParticle.Provider::new);
		ClientTickEvents.END_CLIENT_TICK.register(SandstormClientEffects::tick);
		HudLayerRegistrationCallback.EVENT.register(layeredDrawer -> layeredDrawer.attachLayerBefore(
			IdentifiedLayer.CHAT,
			SANDSTORM_OVERLAY_LAYER,
			(drawContext, tickCounter) -> SandstormOverlayRenderer.render(drawContext, Minecraft.getInstance())
		));
		DarudeMod.LOGGER.info("Darude client initialized");
	}
}
