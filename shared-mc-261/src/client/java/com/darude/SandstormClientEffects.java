package com.darude;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class SandstormClientEffects {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final DustParticleOptions SAND_DUST = new DustParticleOptions(0xD8C48C, 1.0f);
	private static final int WIND_SHIFT_TICKS = 20 * 6;
	private static final int WIND_BLEND_TICKS = 20;
	private static final int BASE_PARTICLE_INTERVAL_TICKS = 3;
	private static final int BASE_MAX_PARTICLES_PER_TICK = 48;
	private static final float BASE_FOG_START = 48.0f;
	private static final float BASE_FOG_END = 64.0f;
	private static final float GUST_FOG_START = 30.0f;
	private static final float GUST_FOG_END = 44.0f;
	private static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static Direction windDirection = Direction.NORTH;
	private static Direction previousWindDirection = Direction.NORTH;
	private static long nextWindShiftTick;
	private static long windBlendStartTick;
	private static ClientLevel currentWindWorld;
	private static ClientLevel cachedSandstormWorld;
	private static long cachedSandstormTick = Long.MIN_VALUE;
	private static long cachedSandstormCameraPos = Long.MIN_VALUE;
	private static boolean cachedSandstormActive;

	private SandstormClientEffects() {
	}

	public static void tick(Minecraft client) {
		ClientLevel world = client.level;
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

		RandomSource random = world.getRandom();
		Vec3 origin = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
		updateWindDirection(world, random);

		float rainGradient = world.getRainLevel(1.0f);
 
		ParticleTuning tuning = getParticleTuning(client);
		if (world.getTime() % tuning.intervalTicks != 0) {
			return;
		}

		int particleCount = Math.round((30 + 90.0f * rainGradient) * tuning.densityMultiplier);
		particleCount = Math.min(particleCount, tuning.maxPerTick);
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

			client.particleEngine.add(SAND_DUST, x, y, z, vx, vy, vz);
		}
	}

	private static void updateWindDirection(ClientLevel world, RandomSource random) {
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

	private static ParticleTuning getParticleTuning(Minecraft client) {
		Object mode = client.options.getParticles().getValue();
		if (mode instanceof Enum<?> modeEnum) {
			String name = modeEnum.name();
			if ("MINIMAL".equals(name)) {
				return new ParticleTuning(0.2f, BASE_PARTICLE_INTERVAL_TICKS * 3, 12);
			}

			if ("DECREASED".equals(name)) {
				return new ParticleTuning(0.5f, BASE_PARTICLE_INTERVAL_TICKS * 2, 24);
			}
		}

		return new ParticleTuning(1.0f, BASE_PARTICLE_INTERVAL_TICKS, BASE_MAX_PARTICLES_PER_TICK);
	}

	private record ParticleTuning(float densityMultiplier, int intervalTicks, int maxPerTick) {
	}

	private static void syncWindWorld(ClientLevel world) {
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

	public static float getWindTransitionProgress(Minecraft client) {
		ClientLevel world = client.level;
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

	public static float getWindTransitionProgressIfSandstormActive(Minecraft client) {
		if (!isSandstormActive(client)) {
			return -1.0f;
		}

		return getWindTransitionProgress(client);
	}

	public static List<String> getDebugLines(Minecraft client) {
		List<String> lines = new ArrayList<>(12);
		lines.add("[Darude] Sandstorm");

		ClientLevel world = client.level;
		if (world == null || client.getCameraEntity() == null) {
			lines.add("Active: false (no world)");
			return lines;
		}

		BlockPos cameraPos = BlockPos.ofFloored(
			client.getCameraEntity().getX(),
			client.getCameraEntity().getY(),
			client.getCameraEntity().getZ()
		);

		boolean biomeMatch = world.getBiome(cameraPos).is(SANDSTORM_BIOMES);
		boolean skyVisible = world.canSeeSkyFromBelowWater(cameraPos);
		boolean active = isSandstormActive(client);
		float windProgress = getWindTransitionProgress(client);
		ParticleTuning tuning = getParticleTuning(client);
		float rainGradient = world.getRainLevel(1.0f);
		int particleBudget = Math.min(Math.round((30 + 90.0f * rainGradient) * tuning.densityMultiplier), tuning.maxPerTick);

		lines.add("Active: " + active);
		lines.add("Biome Match: " + biomeMatch);
		lines.add("Sky Visible: " + skyVisible);
		lines.add("Wind Dir: " + windDirection.getName());
		lines.add(String.format("Wind Transition: %.2f", windProgress));
		lines.add("Particle Mode: " + getParticleModeName(client));
		lines.add("Particle Budget: " + Math.max(0, particleBudget) + " (cap=" + tuning.maxPerTick + ", interval=" + tuning.intervalTicks + "t)");
		lines.add(String.format("Fog Start/End: %.1f / %.1f", getAnimatedFogStart(client), getAnimatedFogEnd(client)));

		return lines;
	}

	public static float getAnimatedFogStart(Minecraft client) {
		return (float) lerp(BASE_FOG_START, GUST_FOG_START, getWindTransitionProgress(client));
	}

	public static float getAnimatedFogEnd(Minecraft client) {
		return (float) lerp(BASE_FOG_END, GUST_FOG_END, getWindTransitionProgress(client));
	}

	private static String getParticleModeName(Minecraft client) {
		Object mode = client.options.getParticles().getValue();
		if (mode instanceof Enum<?> modeEnum) {
			return modeEnum.name();
		}

		return "UNKNOWN";
	}

	public static boolean isSandstormActive(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null || client.getCameraEntity() == null) {
			return false;
		}

		long tick = world.getTime();
		BlockPos cameraPos = BlockPos.containing(client.getCameraEntity().getX(), client.getCameraEntity().getY(), client.getCameraEntity().getZ());
		long cameraPosLong = cameraPos.asLong();
		if (world == cachedSandstormWorld && tick == cachedSandstormTick && cameraPosLong == cachedSandstormCameraPos) {
			return cachedSandstormActive;
		}

		boolean active = isSandstormActive(world, new Vec3(
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

	public static boolean isSandstormActive(ClientLevel world, Vec3 cameraPos) {
		if (!world.isRaining()) {
			return false;
		}

		BlockPos pos = BlockPos.containing(cameraPos);
		if (!world.canSeeSkyFromBelowWater(pos)) {
			return false;
		}

		return world.getBiome(pos).is(SANDSTORM_BIOMES);
	}
}
