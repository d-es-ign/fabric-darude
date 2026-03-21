package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
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
	private static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static Direction windDirection = Direction.NORTH;
	private static Direction previousWindDirection = Direction.NORTH;
	private static long nextWindShiftTick;
	private static long windBlendStartTick;
	private static ClientWorld currentWindWorld;

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
		int particleCount = 30 + Math.round(90.0f * rainGradient);

		float blendProgress = Math.min(1.0f, (world.getTime() - windBlendStartTick) / (float) WIND_BLEND_TICKS);
		double blendedWindX = lerp(previousWindDirection.getOffsetX(), windDirection.getOffsetX(), blendProgress);
		double blendedWindZ = lerp(previousWindDirection.getOffsetZ(), windDirection.getOffsetZ(), blendProgress);

		double baseVx = blendedWindX * 0.32;
		double baseVz = blendedWindZ * 0.32;

		for (int i = 0; i < particleCount; i++) {
			double x = origin.x + (random.nextDouble() - 0.5) * 34.0;
			double y = origin.y + random.nextDouble() * 10.0;
			double z = origin.z + (random.nextDouble() - 0.5) * 34.0;

			double vx = baseVx + (random.nextDouble() - 0.5) * 0.08;
			double vy = -0.10 - random.nextDouble() * 0.06;
			double vz = baseVz + (random.nextDouble() - 0.5) * 0.08;

			client.particleManager.addParticle(SAND_DUST, x, y, z, vx, vy, vz);
		}

		if (random.nextFloat() < 0.75f) {
			double x = origin.x + (random.nextDouble() - 0.5) * 24.0;
			double y = origin.y + random.nextDouble() * 10.0;
			double z = origin.z + (random.nextDouble() - 0.5) * 24.0;
			double vx = baseVx * 0.65 + (random.nextDouble() - 0.5) * 0.05;
			double vy = -0.07 - random.nextDouble() * 0.03;
			double vz = baseVz * 0.65 + (random.nextDouble() - 0.5) * 0.05;
			client.particleManager.addParticle(ParticleTypes.WHITE_ASH, x, y, z, vx, vy, vz);
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

	private static void syncWindWorld(ClientWorld world) {
		if (world == currentWindWorld) {
			return;
		}

		currentWindWorld = world;
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

		return isSandstormActive(world, new Vec3d(
			client.getCameraEntity().getX(),
			client.getCameraEntity().getY(),
			client.getCameraEntity().getZ()
		));
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
