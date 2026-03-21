package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.Identifier;

public final class SandstormClientEffects {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final DustParticleEffect SAND_DUST = new DustParticleEffect(0xD8C48C, 1.0f);
	private static final int WIND_SHIFT_TICKS = 20 * 6;
	private static final int WIND_BLEND_TICKS = 20;
	private static final int BASE_PARTICLE_INTERVAL_TICKS = 2;
	private static final int BASE_MAX_PARTICLES_PER_TICK = 60;
	private static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static Direction windDirection = Direction.NORTH;
	private static Direction previousWindDirection = Direction.NORTH;
	private static long nextWindShiftTick;
	private static long windBlendStartTick;
	private static ClientWorld currentWindWorld;
	private static ClientWorld cachedSandstormWorld;
	private static long cachedSandstormTick = Long.MIN_VALUE;
	private static long cachedSandstormCameraPos = Long.MIN_VALUE;
	private static boolean cachedSandstormActive;

	private SandstormClientEffects() {
	}

	public static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		syncWindWorld(world);
		if (world == null) {
			return;
		}

		if (!isSandstormActive(client)) {
			return;
		}

		if (world == null || client.player == null) {
			return;
		}

		Random random = world.getRandom();
		Vec3d origin = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		updateWindDirection(world, random);

		float rainGradient = world.getRainGradient(1.0f);
 
		ParticleTuning tuning = getParticleTuning(client);
		if (world.getTime() % tuning.intervalTicks != 0) {
			return;
		}

		int particleCount = Math.round((30 + 90.0f * rainGradient) * tuning.densityMultiplier);
		particleCount = Math.min(particleCount, BASE_MAX_PARTICLES_PER_TICK);
		if (particleCount <= 0) {
			return;
		}

		float blendProgress = Math.min(1.0f, (world.getTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS);
		double blendedWindX = lerp(previousWindDirection.getOffsetX(), windDirection.getOffsetX(), blendProgress);
		double blendedWindZ = lerp(previousWindDirection.getOffsetZ(), windDirection.getOffsetZ(), blendProgress);

		double baseVx = blendedWindX * 0.32;
		double baseVz = blendedWindZ * 0.32;

		for (int i = 0; i < particleCount; i++) {
			double xOffset = (random.nextDouble() - 0.5) * 34.0;
			double zOffset = (random.nextDouble() - 0.5) * 34.0;
			double distanceRatio = (xOffset * xOffset + zOffset * zOffset) / (34.0 * 34.0);
			if (random.nextDouble() > (1.0 - Math.min(1.0, distanceRatio))) {
				continue;
			}

			double x = origin.x + xOffset;
			double y = origin.y + random.nextDouble() * 10.0;
			double z = origin.z + zOffset;

			double vx = baseVx + (random.nextDouble() - 0.5) * 0.08;
			double vy = -0.10 - random.nextDouble() * 0.06;
			double vz = baseVz + (random.nextDouble() - 0.5) * 0.08;

			client.particleManager.addParticle(SAND_DUST, x, y, z, vx, vy, vz);
		}
	}

	private static void updateWindDirection(ClientWorld world, Random random) {
		long gameTime = world.getTime();
		if (gameTime < windBlendStartTick || gameTime < nextWindShiftTick - WIND_SHIFT_TICKS) {
			windBlendStartTick = gameTime;
			nextWindShiftTick = gameTime;
		}

		if (gameTime < nextWindShiftTick) {
			return;
		}

		Direction nextDirection = windDirection;
		while (nextDirection == windDirection) {
			nextDirection = CARDINAL_DIRECTIONS[random.nextInt(CARDINAL_DIRECTIONS.length)];
		}

		previousWindDirection = windDirection;
		windDirection = nextDirection;
		windBlendStartTick = gameTime;
		nextWindShiftTick = gameTime + WIND_SHIFT_TICKS;
	}

	private static double lerp(double start, double end, float progress) {
		return start + (end - start) * progress;
	}

	private static ParticleTuning getParticleTuning(MinecraftClient client) {
		Object mode = client.options.getParticles().getValue();
		if (mode instanceof Enum<?> modeEnum) {
			String name = modeEnum.name();
			if ("MINIMAL".equals(name)) {
				return new ParticleTuning(0.2f, BASE_PARTICLE_INTERVAL_TICKS * 3);
			}

			if ("DECREASED".equals(name)) {
				return new ParticleTuning(0.5f, BASE_PARTICLE_INTERVAL_TICKS * 2);
			}
		}

		return new ParticleTuning(1.0f, BASE_PARTICLE_INTERVAL_TICKS);
	}

	private record ParticleTuning(float densityMultiplier, int intervalTicks) {
	}

	private static void syncWindWorld(ClientWorld world) {
		if (world == currentWindWorld) {
			return;
		}

		currentWindWorld = world;
		resetSandstormActiveCache();
		windDirection = Direction.NORTH;
		previousWindDirection = Direction.NORTH;
		if (world == null) {
			nextWindShiftTick = 0;
			windBlendStartTick = 0;
			return;
		}

		long gameTime = world.getTime();
		nextWindShiftTick = gameTime;
		windBlendStartTick = gameTime;
	}

	public static float getWindTransitionProgress(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null) {
			return 0.0f;
		}

		float progress = (world.getTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS;
		if (progress < 0.0f) {
			return 0.0f;
		}

		if (progress > 1.0f) {
			return 1.0f;
		}

		return progress;
	}

	public static boolean isSandstormActive(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null || client.getCameraEntity() == null) {
			return false;
		}

		long tick = world.getTime();
		BlockPos cameraPos = BlockPos.ofFloored(client.getCameraEntity().getX(), client.getCameraEntity().getY(), client.getCameraEntity().getZ());
		long cameraPosLong = cameraPos.asLong();
		if (world == cachedSandstormWorld && tick == cachedSandstormTick && cameraPosLong == cachedSandstormCameraPos) {
			return cachedSandstormActive;
		}

		boolean active = isSandstormActive(world, new Vec3d(
			client.getCameraEntity().getX(),
			client.getCameraEntity().getY(),
			client.getCameraEntity().getZ()
		));
		cachedSandstormWorld = world;
		cachedSandstormTick = tick;
		cachedSandstormCameraPos = cameraPosLong;
		cachedSandstormActive = active;
		return active;
	}

	private static void resetSandstormActiveCache() {
		cachedSandstormWorld = null;
		cachedSandstormTick = Long.MIN_VALUE;
		cachedSandstormCameraPos = Long.MIN_VALUE;
		cachedSandstormActive = false;
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
}
