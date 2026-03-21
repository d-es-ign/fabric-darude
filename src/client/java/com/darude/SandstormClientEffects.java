package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.Identifier;

public final class SandstormClientEffects {
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
