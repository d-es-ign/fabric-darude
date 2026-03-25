package com.darude.worldgen;

import com.darude.DarudeMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class SandLayerGenerationConfig {
	private static final Identifier CONFIG_ID = Identifier.of(DarudeMod.MOD_ID, "worldgen/sand_layer_generation.json");
	private static final Identifier RELOAD_LISTENER_ID = Identifier.of(DarudeMod.MOD_ID, "sand_layer_generation_config");
	private static volatile Values values = Values.defaults();

	private SandLayerGenerationConfig() {
	}

	public static void registerReloadListener() {
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return RELOAD_LISTENER_ID;
			}

			@Override
			public void reload(ResourceManager manager) {
				values = load(manager);
			}
		});
	}

	public static Values get() {
		return values;
	}

	private static Values load(ResourceManager manager) {
		Values defaults = Values.defaults();
		return manager.getResource(CONFIG_ID)
			.map(resource -> {
				try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
					JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
					float validSpotChance = root.has("valid_spot_chance")
						? root.get("valid_spot_chance").getAsFloat()
						: defaults.validSpotChance();
					int baseMaxLayers = root.has("base_max_layers")
						? root.get("base_max_layers").getAsInt()
						: defaults.baseMaxLayers();
					int nearDesertDistance = root.has("near_desert_distance")
						? root.get("near_desert_distance").getAsInt()
						: defaults.nearDesertDistance();
					float nearDesertValidSpotChance = root.has("near_desert_valid_spot_chance")
						? root.get("near_desert_valid_spot_chance").getAsFloat()
						: defaults.nearDesertValidSpotChance();
					int nearDesertMinLayers = root.has("near_desert_min_layers")
						? root.get("near_desert_min_layers").getAsInt()
						: defaults.nearDesertMinLayers();
					int nearDesertMaxLayers = root.has("near_desert_max_layers")
						? root.get("near_desert_max_layers").getAsInt()
						: defaults.nearDesertMaxLayers();
					int nearDesertColumnSampleNumerator = root.has("near_desert_column_sample_numerator")
						? root.get("near_desert_column_sample_numerator").getAsInt()
						: defaults.nearDesertColumnSampleNumerator();
					int nearDesertColumnSampleDenominator = root.has("near_desert_column_sample_denominator")
						? root.get("near_desert_column_sample_denominator").getAsInt()
						: defaults.nearDesertColumnSampleDenominator();
					String nearDesertSpawnableSupportMode = root.has("near_desert_spawnable_support_mode")
						? root.get("near_desert_spawnable_support_mode").getAsString()
						: defaults.nearDesertSpawnableSupportMode();

					validSpotChance = clamp(validSpotChance, 0.0f, 1.0f);
					baseMaxLayers = clamp(baseMaxLayers, 0, 15);
					nearDesertDistance = clamp(nearDesertDistance, 0, 6);
					nearDesertValidSpotChance = clamp(nearDesertValidSpotChance, 0.0f, 1.0f);
					nearDesertMinLayers = clamp(nearDesertMinLayers, 0, 15);
					nearDesertMaxLayers = clamp(nearDesertMaxLayers, 0, 15);
					nearDesertColumnSampleDenominator = clamp(nearDesertColumnSampleDenominator, 1, 32);
					nearDesertColumnSampleNumerator = clamp(nearDesertColumnSampleNumerator, 0, nearDesertColumnSampleDenominator);
					if (nearDesertMinLayers > nearDesertMaxLayers) {
						int swap = nearDesertMinLayers;
						nearDesertMinLayers = nearDesertMaxLayers;
						nearDesertMaxLayers = swap;
					}
					if (!"full_block".equals(nearDesertSpawnableSupportMode) && !"tag_only".equals(nearDesertSpawnableSupportMode)) {
						nearDesertSpawnableSupportMode = defaults.nearDesertSpawnableSupportMode();
					}

					return new Values(
						validSpotChance,
						baseMaxLayers,
						nearDesertDistance,
						nearDesertValidSpotChance,
						nearDesertMinLayers,
						nearDesertMaxLayers,
						nearDesertColumnSampleNumerator,
						nearDesertColumnSampleDenominator,
						nearDesertSpawnableSupportMode
					);
				} catch (Exception e) {
					DarudeMod.LOGGER.warn("Failed to parse {}. Using defaults.", CONFIG_ID, e);
					return defaults;
				}
			})
			.orElse(defaults);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public record Values(
		float validSpotChance,
		int baseMaxLayers,
		int nearDesertDistance,
		float nearDesertValidSpotChance,
		int nearDesertMinLayers,
		int nearDesertMaxLayers,
		int nearDesertColumnSampleNumerator,
		int nearDesertColumnSampleDenominator,
		String nearDesertSpawnableSupportMode
	) {
		public static Values defaults() {
			return new Values(0.3f, 4, 2, 0.2f, 0, 2, 6, 8, "full_block");
		}
	}
}
