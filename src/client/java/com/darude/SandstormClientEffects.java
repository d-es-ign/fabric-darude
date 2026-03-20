package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.DustParticleEffect;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.Identifier;
import org.joml.Vector3f;

public final class SandstormClientEffects {
	private static final float SAND_R = 216.0f / 255.0f;
	private static final float SAND_G = 196.0f / 255.0f;
	private static final float SAND_B = 140.0f / 255.0f;
	private static final DustParticleEffect SANDSTORM_PARTICLE = new DustParticleEffect(new Vector3f(SAND_R, SAND_G, SAND_B), 1.0f);
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));

	private SandstormClientEffects() {
	}

	public static void tick(MinecraftClient client) {
		if (!isSandstormActive(client)) {
			return;
		}

		ClientWorld world = client.world;
		if (world == null || client.player == null) {
			return;
		}

		Vec3d origin = client.player.getPos();
		for (int i = 0; i < 12; i++) {
			double x = origin.x + (world.random.nextDouble() - 0.5) * 28.0;
			double y = origin.y + world.random.nextDouble() * 8.0;
			double z = origin.z + (world.random.nextDouble() - 0.5) * 28.0;

			double vx = (world.random.nextDouble() - 0.5) * 0.02;
			double vy = -0.02 - world.random.nextDouble() * 0.02;
			double vz = (world.random.nextDouble() - 0.5) * 0.02;

			world.addParticle(SANDSTORM_PARTICLE, x, y, z, vx, vy, vz);
		}

		if (world.random.nextFloat() < 0.35f) {
			double x = origin.x + (world.random.nextDouble() - 0.5) * 24.0;
			double y = origin.y + world.random.nextDouble() * 10.0;
			double z = origin.z + (world.random.nextDouble() - 0.5) * 24.0;
			world.addParticle(ParticleTypes.WHITE_ASH, x, y, z, 0.0, -0.01, 0.0);
		}
	}

	public static boolean isSandstormActive(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null || client.cameraEntity == null) {
			return false;
		}

		return isSandstormActive(world, client.cameraEntity.getPos());
	}

	public static boolean isSandstormActive(ClientWorld world, Vec3d cameraPos) {
		if (!world.isRaining()) {
			return false;
		}

		BlockPos pos = BlockPos.ofFloored(cameraPos);
		if (!world.isSkyVisible(pos)) {
			return false;
		}

		return world.getBiome(pos).isIn(SANDSTORM_BIOMES);
	}

	public static boolean isWithinThreeBlocksOfSandstormBiomeExcludingCurrent(ClientWorld world, BlockPos pos) {
		if (world.getBiome(pos).isIn(SANDSTORM_BIOMES)) {
			return false;
		}

		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}

					if (dx * dx + dy * dy + dz * dz > 9) {
						continue;
					}

					BlockPos nearbyPos = pos.add(dx, dy, dz);
					if (world.getBiome(nearbyPos).isIn(SANDSTORM_BIOMES)) {
						return true;
					}
				}
			}
		}

		return false;
	}
}
