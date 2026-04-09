package com.darude;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

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
		Registry.register(Registries.PARTICLE_TYPE, Identifier.of(DarudeMod.MOD_ID, "sandstorm_streak"), SANDSTORM_STREAK);
	}
}
