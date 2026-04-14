package com.darude;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.reflect.Method;

public final class SandstormClientEffects {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
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
	private static final double OCCLUSION_SAMPLE_Y_OFFSET = 1.2;
	private static final double WALL_MASK_DISTANCE = readDoubleProperty("darude.client.wall_mask_distance", 1.5);
	private static final double OCCLUSION_STRENGTH = clamp01(readDoubleProperty("darude.client.occlusion_strength", 0.75));
	private static final int OFF_MIN_PARTICLES_PER_SPAWN = 3;
	private static final int FAST_MIN_PARTICLES_PER_SPAWN = 6;
	private static final int FANCY_MIN_PARTICLES_PER_SPAWN = 10;
	private static final float THUNDERSTORM_VISUAL_INTENSITY = 1.5f;
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
	private static ClientLevel currentWindWorld;
	private static ClientLevel cachedSandstormWorld;
	private static long cachedSandstormTick = Long.MIN_VALUE;
	private static long cachedSandstormCameraPos = Long.MIN_VALUE;
	private static boolean cachedSandstormActive;
	private static final WeakHashMap<ClientLevel, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();

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
		float visualIntensity = getVisualIntensity(world);
 
		ParticleTuning tuning = getParticleTuning(client);
		OcclusionQuality occlusionQuality = resolveOcclusionQuality(client);
		int effectiveIntervalTicks = tuning.intervalTicks;
		if (world.getGameTime() % effectiveIntervalTicks != 0) {
			return;
		}

		float blendProgress = Math.min(1.0f, (world.getGameTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS);
		double blendedWindX = lerp(previousWindDirection.getStepX(), windDirection.getStepX(), blendProgress);
		double blendedWindZ = lerp(previousWindDirection.getStepZ(), windDirection.getStepZ(), blendProgress);

		int particleMinY = Math.max(world.getMinY(), PARTICLE_MIN_Y);
		int particleMaxY = resolveParticleMaxY(world);
		if (particleMaxY <= particleMinY) {
			return;
		}

		double terrainY = world.getHeight(Heightmap.Types.WORLD_SURFACE, BlockPos.containing(origin).getX(), BlockPos.containing(origin).getZ()) - 1;
		double altitudeTaper = computeAltitudeTaper(origin.y, terrainY, particleMaxY);
		if (altitudeTaper <= 0.0) {
			return;
		}

		double occlusionFactor = computeDirectionalOcclusion(world, origin, blendedWindX, blendedWindZ, occlusionQuality);
		if (occlusionFactor <= 0.0) {
			return;
		}

		int particleCount = Math.round((30 + 90.0f * rainGradient)
			* tuning.densityMultiplier
			* PARTICLE_DENSITY_BOOST
			* (float) altitudeTaper
			* (float) occlusionFactor
			* visualIntensity);
		particleCount = Math.min(particleCount, tuning.maxPerTick);
		particleCount = Math.max(particleCount, tuning.minPerSpawn);
		if (particleCount <= 0) {
			return;
		}

		double baseVx = blendedWindX;
		double baseVz = blendedWindZ;
		double spawnRadius = PARTICLE_SPAWN_RADIUS * resolveRenderDistanceMultiplier(client);
		double verticalSpan = tuning.verticalSpan;
		double qualityJitter = HORIZONTAL_JITTER;
		double speedMultiplier = 1.0;

		for (int i = 0; i < particleCount; i++) {
			double xOffset = (random.nextDouble() - 0.5) * (spawnRadius * 2.0) - blendedWindX * UPWIND_SPAWN_BIAS;
			double zOffset = (random.nextDouble() - 0.5) * (spawnRadius * 2.0) - blendedWindZ * UPWIND_SPAWN_BIAS;

			double x = origin.x + xOffset;
			double y = origin.y + (random.nextDouble() - 0.5) * verticalSpan;
			double z = origin.z + zOffset;
			if (y < particleMinY || y > particleMaxY) {
				continue;
			}

			double columnTerrainY = world.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z)) - 1;
			double terrainTaper = computeAboveTerrainTaper(y, columnTerrainY);
			if (terrainTaper <= 0.0 || random.nextDouble() > terrainTaper) {
				continue;
			}

			if (isWallMaskedSpawn(world, x, y, z, blendedWindX, blendedWindZ, random)) {
				continue;
			}

			double horizontalSpeed = (MIN_HORIZONTAL_SPEED + random.nextDouble() * (MAX_HORIZONTAL_SPEED - MIN_HORIZONTAL_SPEED)) * speedMultiplier;
			double vx = baseVx * horizontalSpeed + (random.nextDouble() - 0.5) * qualityJitter;
			double vy = STREAK_VERTICAL_VELOCITY;
			double vz = baseVz * horizontalSpeed + (random.nextDouble() - 0.5) * qualityJitter;

			world.addParticle(DarudeParticles.SANDSTORM_STREAK, x, y, z, vx * STREAK_SPEED_MULTIPLIER, vy, vz * STREAK_SPEED_MULTIPLIER);
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

	private static int resolveParticleMaxY(ClientLevel world) {
		if (isAmplifiedWorld(world)) {
			return world.getMaxY() - 1;
		}

		return Math.min(world.getMaxY() - 1, DEFAULT_PARTICLE_MAX_Y);
	}

	private static double computeDirectionalOcclusion(ClientLevel world, Vec3 origin, double windX, double windZ, OcclusionQuality quality) {
		if (quality == OcclusionQuality.OFF) {
			return 1.0;
		}

		double magnitude = Math.sqrt(windX * windX + windZ * windZ);
		if (magnitude < 1.0e-4) {
			return 1.0;
		}

		double dirX = windX / magnitude;
		double dirZ = windZ / magnitude;
		double perpX = -dirZ;
		double perpZ = dirX;
		double[] laneOffsets = quality == OcclusionQuality.FANCY ? new double[]{-4.0, -2.0, 0.0, 2.0, 4.0} : new double[]{-2.0, 0.0, 2.0};
		double[] forwardSamples = quality == OcclusionQuality.FANCY ? new double[]{4.0, 8.0, 12.0, 16.0, 20.0, 24.0} : new double[]{6.0, 12.0, 18.0};

		int blockedSamples = 0;
		int totalSamples = 0;
		for (double laneOffset : laneOffsets) {
			for (double forwardDistance : forwardSamples) {
				totalSamples++;
				double sampleX = origin.x + dirX * forwardDistance + perpX * laneOffset;
				double sampleY = origin.y + OCCLUSION_SAMPLE_Y_OFFSET;
				double sampleZ = origin.z + dirZ * forwardDistance + perpZ * laneOffset;
				BlockPos samplePos = BlockPos.containing(sampleX, sampleY, sampleZ);
				if (isSolidOccluder(world, samplePos)) {
					blockedSamples++;
				}
			}
		}

		if (totalSamples == 0) {
			return 1.0;
		}

		double blockedRatio = blockedSamples / (double) totalSamples;
		double reducedByOcclusion = blockedRatio * OCCLUSION_STRENGTH;
		if (reducedByOcclusion >= 0.9) {
			return 0.0;
		}

		return Math.max(0.0, 1.0 - reducedByOcclusion);
	}

	private static boolean isWallMaskedSpawn(ClientLevel world, double x, double y, double z, double windX, double windZ, RandomSource random) {
		if (WALL_MASK_DISTANCE <= 0.0 || OCCLUSION_STRENGTH <= 0.0) {
			return false;
		}

		double magnitude = Math.sqrt(windX * windX + windZ * windZ);
		if (magnitude < 1.0e-4) {
			return false;
		}

		double dirX = windX / magnitude;
		double dirZ = windZ / magnitude;
		int samples = Math.max(1, (int) Math.ceil(WALL_MASK_DISTANCE / 0.5));
		for (int i = 1; i <= samples; i++) {
			double distance = i * 0.5;
			double sampleX = x - dirX * distance;
			double sampleY = y;
			double sampleZ = z - dirZ * distance;
			if (isSolidOccluder(world, BlockPos.containing(sampleX, sampleY, sampleZ))) {
				return random.nextDouble() < OCCLUSION_STRENGTH;
			}
		}

		return false;
	}

	private static boolean isSolidOccluder(ClientLevel world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}

		if (!world.getFluidState(pos).isEmpty()) {
			return false;
		}

		return true;
	}

	private static OcclusionQuality resolveOcclusionQuality(Minecraft client) {
		Object graphicsOption = invokeAny(client.options, "graphicsMode", "getGraphicsMode");
		Object graphicsValue = invokeAny(graphicsOption, "get", "getValue");
		if (graphicsValue instanceof Enum<?> valueEnum) {
			String name = valueEnum.name();
			if ("FAST".equals(name)) {
				return OcclusionQuality.FAST;
			}

			if ("FANCY".equals(name) || "FABULOUS".equals(name)) {
				return OcclusionQuality.FANCY;
			}
		}

		return OcclusionQuality.OFF;
	}

	private static boolean isAmplifiedWorld(ClientLevel world) {
		Boolean cached = AMPLIFIED_WORLD_CACHE.get(world);
		if (cached != null) {
			return cached;
		}

		boolean amplified = containsAmplifiedHint(world);
		AMPLIFIED_WORLD_CACHE.put(world, amplified);
		return amplified;
	}

	private static boolean containsAmplifiedHint(ClientLevel world) {
		if (containsAmplifiedText(world)) {
			return true;
		}

		Object source = invokeAny(world, "getChunkSource", "getChunkManager");
		if (containsAmplifiedText(source)) {
			return true;
		}

		Object generator = invokeAny(source, "getGenerator", "getChunkGenerator");
		if (containsAmplifiedText(generator)) {
			return true;
		}

		Object data = invokeAny(world, "getLevelData", "getLevelProperties");
		return containsAmplifiedText(data);
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

	private static float resolveRenderDistanceMultiplier(Minecraft client) {
		int renderDistance = resolveRenderDistance(client);
		if (renderDistance <= 8) {
			return 0.7f;
		}

		if (renderDistance <= 12) {
			return 0.85f;
		}

		if (renderDistance <= 20) {
			return 1.0f;
		}

		return 1.15f;
	}

	private static int resolveRenderDistance(Minecraft client) {
		Object renderDistanceOption = invokeAny(client.options, "getViewDistance", "renderDistance");
		Object renderDistanceValue = invokeAny(renderDistanceOption, "getValue", "get");
		if (renderDistanceValue instanceof Number number) {
			return number.intValue();
		}

		return 12;
	}

	private static double readDoubleProperty(String key, double fallback) {
		String value = System.getProperty(key);
		if (value == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private enum OcclusionQuality {
		OFF,
		FAST,
		FANCY
	}

	private static void updateWindDirection(ClientLevel world, RandomSource random) {
		long gameTime = world.getGameTime();
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
		Object mode = client.options.particles().get();
		if (mode instanceof Enum<?> modeEnum) {
			String name = modeEnum.name();
			if ("MINIMAL".equals(name)) {
				return new ParticleTuning(0.2f, BASE_PARTICLE_INTERVAL_TICKS * 3, 12, OFF_MIN_PARTICLES_PER_SPAWN, 16.0);
			}

			if ("DECREASED".equals(name)) {
				return new ParticleTuning(0.5f, BASE_PARTICLE_INTERVAL_TICKS * 2, 24, FAST_MIN_PARTICLES_PER_SPAWN, 20.0);
			}
		}

		return new ParticleTuning(1.0f, BASE_PARTICLE_INTERVAL_TICKS, BASE_MAX_PARTICLES_PER_TICK, FANCY_MIN_PARTICLES_PER_SPAWN, 24.0);
	}

	private record ParticleTuning(float densityMultiplier, int intervalTicks, int maxPerTick, int minPerSpawn, double verticalSpan) {
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

		long gameTime = world.getGameTime();
		nextWindShiftTick = gameTime;
		windBlendStartTick = gameTime;
	}

	public static float getWindTransitionProgress(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null) {
			return 0.0f;
		}

		float progress = (world.getGameTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS;
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

		BlockPos cameraPos = BlockPos.containing(
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
		lines.add("Graphics Mode: " + getGraphicsModeName(client));
		lines.add("Render Distance: " + resolveRenderDistance(client));
		lines.add("Particle Budget: " + Math.max(0, particleBudget) + " (cap=" + tuning.maxPerTick + ", interval=" + tuning.intervalTicks + "t)");
		lines.add(String.format("Fog Start/End: %.1f / %.1f", getAnimatedFogStart(client), getAnimatedFogEnd(client)));

		return lines;
	}

	public static float getAnimatedFogStart(Minecraft client) {
		return SANDSTORM_FOG_START;
	}

	public static float getAnimatedFogEnd(Minecraft client) {
		return SANDSTORM_FOG_END;
	}

	public static float getVisualIntensity(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null || !isSandstormActive(client)) {
			return 0.0f;
		}

		return getVisualIntensity(world);
	}

	private static float getVisualIntensity(ClientLevel world) {
		return world.isThundering() ? THUNDERSTORM_VISUAL_INTENSITY : 1.0f;
	}

	private static String getParticleModeName(Minecraft client) {
		Object mode = client.options.particles().get();
		if (mode instanceof Enum<?> modeEnum) {
			return modeEnum.name();
		}

		return "UNKNOWN";
	}

	private static String getGraphicsModeName(Minecraft client) {
		Object graphicsOption = invokeAny(client.options, "graphicsMode", "getGraphicsMode");
		Object graphicsValue = invokeAny(graphicsOption, "get", "getValue");
		if (graphicsValue instanceof Enum<?> graphicsEnum) {
			return graphicsEnum.name();
		}

		return "UNKNOWN";
	}

	public static boolean isSandstormActive(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null || client.getCameraEntity() == null) {
			return false;
		}

		long tick = world.getGameTime();
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
		if (world.getFluidState(pos).is(Fluids.WATER)) {
			return false;
		}

		if (!world.canSeeSkyFromBelowWater(pos)) {
			return false;
		}

		return world.getBiome(pos).is(SANDSTORM_BIOMES);
	}
}
