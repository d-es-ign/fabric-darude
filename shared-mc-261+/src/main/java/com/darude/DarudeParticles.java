package com.darude;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class DarudeParticles {
	public static final SimpleParticleType SANDSTORM_STREAK = FabricParticleTypes.simple();
	private static boolean initialized;

	private DarudeParticles() {
	}

	public static void initialize() {
		if (initialized) {
			return;
		}

		initialized = true;
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_streak"), SANDSTORM_STREAK);
	}
}
