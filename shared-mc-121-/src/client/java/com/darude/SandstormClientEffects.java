package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.fluid.Fluids;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.reflect.Method;

public final class SandstormClientEffects {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final float STREAK_SPEED_MULTIPLIER = 2.5f;
	private static final double MIN_HORIZONTAL_SPEED = 3.0;
	private static final double MAX_HORIZONTAL_SPEED = 5.0;
	private static final double HORIZONTAL_JITTER = 0.5;
	private static final double STREAK_VERTICAL_VELOCITY = -0.01;
	private static final double PARTICLE_SPAWN_RADIUS = 56.0;
	private static final double UPWIND_SPAWN_BIAS = 24.0;
	private static final float PARTICLE_DENSITY_BOOST = 1.6f;
	private static final int PARTICLE_MIN_Y = 60;
	private static final int DEFAULT_PARTICLE_MAX_Y = 100;
	private static final double ABOVE_TERRAIN_TAPER_RANGE = 32.0;
	private static final double HIGH_ALTITUDE_TAPER_RANGE = 24.0;
	private static final int WIND_SHIFT_TICKS = 20 * 10;
	private static final int WIND_BLEND_TICKS = 10;
	private static final int BASE_PARTICLE_INTERVAL_TICKS = 3;
	private static final int BASE_MAX_PARTICLES_PER_TICK = 96;
	private static final float SANDSTORM_FOG_START = 16.0f;
	private static final float SANDSTORM_FOG_END = 64.0f;
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
	private static final WeakHashMap<ClientWorld, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();

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

		int particleMinY = Math.max(world.getBottomY(), PARTICLE_MIN_Y);
		int particleMaxY = resolveParticleMaxY(world);
		if (particleMaxY <= particleMinY) {
			return;
		}

		double terrainY = world.getTopY(Heightmap.Type.WORLD_SURFACE, BlockPos.ofFloored(origin).getX(), BlockPos.ofFloored(origin).getZ()) - 1;
		double altitudeTaper = computeAltitudeTaper(origin.y, terrainY, particleMaxY);
		if (altitudeTaper <= 0.0) {
			return;
		}

		int particleCount = Math.round((30 + 90.0f * rainGradient) * tuning.densityMultiplier * PARTICLE_DENSITY_BOOST * (float) altitudeTaper);
		particleCount = Math.min(particleCount, tuning.maxPerTick);
		if (particleCount <= 0) {
			return;
		}

		float blendProgress = Math.min(1.0f, (world.getTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS);
		double blendedWindX = lerp(previousWindDirection.getOffsetX(), windDirection.getOffsetX(), blendProgress);
		double blendedWindZ = lerp(previousWindDirection.getOffsetZ(), windDirection.getOffsetZ(), blendProgress);

		double baseVx = blendedWindX;
		double baseVz = blendedWindZ;

		for (int i = 0; i < particleCount; i++) {
			double xOffset = (random.nextDouble() - 0.5) * (PARTICLE_SPAWN_RADIUS * 2.0) - blendedWindX * UPWIND_SPAWN_BIAS;
			double zOffset = (random.nextDouble() - 0.5) * (PARTICLE_SPAWN_RADIUS * 2.0) - blendedWindZ * UPWIND_SPAWN_BIAS;

			double x = origin.x + xOffset;
			double y = origin.y + (random.nextDouble() - 0.5) * 24.0;
			double z = origin.z + zOffset;
			if (y < particleMinY || y > particleMaxY) {
				continue;
			}

			double columnTerrainY = world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z)) - 1;
			double terrainTaper = computeAboveTerrainTaper(y, columnTerrainY);
			if (terrainTaper <= 0.0 || random.nextDouble() > terrainTaper) {
				continue;
			}

			double horizontalSpeed = MIN_HORIZONTAL_SPEED + random.nextDouble() * (MAX_HORIZONTAL_SPEED - MIN_HORIZONTAL_SPEED);
			double vx = baseVx * horizontalSpeed + (random.nextDouble() - 0.5) * HORIZONTAL_JITTER;
			double vy = STREAK_VERTICAL_VELOCITY;
			double vz = baseVz * horizontalSpeed + (random.nextDouble() - 0.5) * HORIZONTAL_JITTER;

			client.particleManager.addParticle(DarudeParticles.SANDSTORM_STREAK, x, y, z, vx * STREAK_SPEED_MULTIPLIER, vy, vz * STREAK_SPEED_MULTIPLIER);
		}
	}

	private static double computeAltitudeTaper(double cameraY, double terrainY, int particleMaxY) {
		if (cameraY <= terrainY) {
			return 1.0;
		}

		double taperByTerrain = 1.0 - Math.min(1.0, (cameraY - terrainY) / HIGH_ALTITUDE_TAPER_RANGE);
		double taperByCap = 1.0 - Math.min(1.0, Math.max(0.0, (cameraY - particleMaxY)) / HIGH_ALTITUDE_TAPER_RANGE);
		return Math.max(0.0, Math.min(taperByTerrain, taperByCap));
	}

	private static double computeAboveTerrainTaper(double particleY, double terrainY) {
		if (particleY <= terrainY) {
			return 1.0;
		}

		return Math.max(0.0, 1.0 - ((particleY - terrainY) / ABOVE_TERRAIN_TAPER_RANGE));
	}

	private static int resolveParticleMaxY(ClientWorld world) {
		if (isAmplifiedWorld(world)) {
			return world.getTopYInclusive();
		}

		return Math.min(world.getTopYInclusive(), DEFAULT_PARTICLE_MAX_Y);
	}

	private static boolean isAmplifiedWorld(ClientWorld world) {
		Boolean cached = AMPLIFIED_WORLD_CACHE.get(world);
		if (cached != null) {
			return cached;
		}

		boolean amplified = containsAmplifiedHint(world);
		AMPLIFIED_WORLD_CACHE.put(world, amplified);
		return amplified;
	}

	private static boolean containsAmplifiedHint(ClientWorld world) {
		if (containsAmplifiedText(world)) {
			return true;
		}

		Object manager = invokeAny(world, "getChunkManager", "getChunkSource");
		if (containsAmplifiedText(manager)) {
			return true;
		}

		Object generator = invokeAny(manager, "getChunkGenerator", "getGenerator");
		if (containsAmplifiedText(generator)) {
			return true;
		}

		Object properties = invokeAny(world, "getLevelProperties", "getProperties");
		return containsAmplifiedText(properties);
	}

	private static Object invokeAny(Object target, String... methodNames) {
		if (target == null) {
			return null;
		}

		for (String methodName : methodNames) {
			try {
				Method method = target.getClass().getMethod(methodName);
				return method.invoke(target);
			} catch (ReflectiveOperationException ignored) {
			}
		}

		return null;
	}

	private static boolean containsAmplifiedText(Object value) {
		if (value == null) {
			return false;
		}

		return value.toString().toLowerCase().contains("amplified");
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

	public static float getWindTransitionProgressIfSandstormActive(MinecraftClient client) {
		if (!isSandstormActive(client)) {
			return -1.0f;
		}

		return getWindTransitionProgress(client);
	}

	public static List<String> getDebugLines(MinecraftClient client) {
		List<String> lines = new ArrayList<>(12);
		lines.add("[Darude] Sandstorm");

		ClientWorld world = client.world;
		if (world == null || client.getCameraEntity() == null) {
			lines.add("Active: false (no world)");
			return lines;
		}

		BlockPos cameraPos = BlockPos.ofFloored(
			client.getCameraEntity().getX(),
			client.getCameraEntity().getY(),
			client.getCameraEntity().getZ()
		);

		boolean biomeMatch = world.getBiome(cameraPos).isIn(SANDSTORM_BIOMES);
		boolean skyVisible = world.isSkyVisible(cameraPos);
		boolean active = isSandstormActive(client);
		float windProgress = getWindTransitionProgress(client);
		ParticleTuning tuning = getParticleTuning(client);
		float rainGradient = world.getRainGradient(1.0f);
		int particleBudget = Math.min(Math.round((30 + 90.0f * rainGradient) * tuning.densityMultiplier), tuning.maxPerTick);

		lines.add("Active: " + active);
		lines.add("Biome Match: " + biomeMatch);
		lines.add("Sky Visible: " + skyVisible);
		lines.add("Wind Dir: " + windDirection.asString());
		lines.add(String.format("Wind Transition: %.2f", windProgress));
		lines.add("Particle Mode: " + getParticleModeName(client));
		lines.add("Particle Budget: " + Math.max(0, particleBudget) + " (cap=" + tuning.maxPerTick + ", interval=" + tuning.intervalTicks + "t)");
		lines.add(String.format("Fog Start/End: %.1f / %.1f", getAnimatedFogStart(client), getAnimatedFogEnd(client)));

		return lines;
	}

	public static float getAnimatedFogStart(MinecraftClient client) {
		return SANDSTORM_FOG_START;
	}

	public static float getAnimatedFogEnd(MinecraftClient client) {
		return SANDSTORM_FOG_END;
	}

	private static String getParticleModeName(MinecraftClient client) {
		Object mode = client.options.getParticles().getValue();
		if (mode instanceof Enum<?> modeEnum) {
			return modeEnum.name();
		}

		return "UNKNOWN";
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
		if (world.getFluidState(pos).isOf(Fluids.WATER)) {
			return false;
		}

		if (!world.isSkyVisible(pos)) {
			return false;
		}

		return world.getBiome(pos).isIn(SANDSTORM_BIOMES);
	}
}
