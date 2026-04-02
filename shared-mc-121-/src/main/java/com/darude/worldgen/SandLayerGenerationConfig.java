package com.darude.worldgen;

import com.darude.DarudeMod;
import com.darude.DarudeDiagnostics;
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
					int farmingTickIntervalTicks = root.has("farming_tick_interval_ticks")
						? root.get("farming_tick_interval_ticks").getAsInt()
						: defaults.farmingTickIntervalTicks();
					float baseUnderGrateChance = root.has("base_under_grate_chance")
						? root.get("base_under_grate_chance").getAsFloat()
						: defaults.baseUnderGrateChance();
					float basePyramidSideChance = root.has("base_pyramid_side_chance")
						? root.get("base_pyramid_side_chance").getAsFloat()
						: defaults.basePyramidSideChance();
					float fullPyramidSideChance = root.has("full_pyramid_side_chance")
						? root.get("full_pyramid_side_chance").getAsFloat()
						: defaults.fullPyramidSideChance();
					float windwardSideMultiplier = root.has("windward_side_multiplier")
						? root.get("windward_side_multiplier").getAsFloat()
						: defaults.windwardSideMultiplier();
					float fullPyramidErodeToPyramidChance = root.has("full_pyramid_erode_to_pyramid_chance")
						? root.get("full_pyramid_erode_to_pyramid_chance").getAsFloat()
						: defaults.fullPyramidErodeToPyramidChance();
					float pyramidBreakChance = root.has("pyramid_break_chance")
						? root.get("pyramid_break_chance").getAsFloat()
						: defaults.pyramidBreakChance();
					int maxFallthroughDepth = root.has("max_fallthrough_depth")
						? root.get("max_fallthrough_depth").getAsInt()
						: defaults.maxFallthroughDepth();
					int maxFarmingOperationsPerTick = root.has("max_farming_operations_per_tick")
						? root.get("max_farming_operations_per_tick").getAsInt()
						: defaults.maxFarmingOperationsPerTick();
					int avalancheSlopeThreshold = root.has("avalanche_slope_threshold")
						? root.get("avalanche_slope_threshold").getAsInt()
						: defaults.avalancheSlopeThreshold();
					int maxTopplesPerTick = root.has("max_topples_per_tick")
						? root.get("max_topples_per_tick").getAsInt()
						: (root.has("avalanche_max_topples_per_increment")
							? root.get("avalanche_max_topples_per_increment").getAsInt()
							: defaults.maxTopplesPerTick());
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
					farmingTickIntervalTicks = clamp(farmingTickIntervalTicks, 1, 1200);
					baseUnderGrateChance = clamp(baseUnderGrateChance, 0.0f, 1.0f);
					basePyramidSideChance = clamp(basePyramidSideChance, 0.0f, 1.0f);
					fullPyramidSideChance = clamp(fullPyramidSideChance, 0.0f, 1.0f);
					windwardSideMultiplier = clamp(windwardSideMultiplier, 0.0f, 8.0f);
					fullPyramidErodeToPyramidChance = clamp(fullPyramidErodeToPyramidChance, 0.0f, 1.0f);
					pyramidBreakChance = clamp(pyramidBreakChance, 0.0f, 1.0f);
					maxFallthroughDepth = clamp(maxFallthroughDepth, 0, 128);
					maxFarmingOperationsPerTick = clamp(maxFarmingOperationsPerTick, 0, 4096);
					avalancheSlopeThreshold = clamp(avalancheSlopeThreshold, 1, 15);
					maxTopplesPerTick = clamp(maxTopplesPerTick, 0, 16384);
					if (nearDesertMinLayers > nearDesertMaxLayers) {
						int swap = nearDesertMinLayers;
						nearDesertMinLayers = nearDesertMaxLayers;
						nearDesertMaxLayers = swap;
					}
					if (!"full_block".equals(nearDesertSpawnableSupportMode) && !"tag_only".equals(nearDesertSpawnableSupportMode)) {
						nearDesertSpawnableSupportMode = defaults.nearDesertSpawnableSupportMode();
					}

					Values loaded = new Values(
						validSpotChance,
						baseMaxLayers,
						nearDesertDistance,
						nearDesertValidSpotChance,
						nearDesertMinLayers,
						nearDesertMaxLayers,
						farmingTickIntervalTicks,
						baseUnderGrateChance,
						basePyramidSideChance,
						fullPyramidSideChance,
						windwardSideMultiplier,
						fullPyramidErodeToPyramidChance,
						pyramidBreakChance,
						maxFallthroughDepth,
						maxFarmingOperationsPerTick,
						avalancheSlopeThreshold,
						maxTopplesPerTick,
						nearDesertColumnSampleNumerator,
						nearDesertColumnSampleDenominator,
						nearDesertSpawnableSupportMode
					);
					DarudeDiagnostics.logConfigReload(loaded, false);
					return loaded;
				} catch (Exception e) {
					DarudeMod.LOGGER.warn("Failed to parse {}. Using defaults.", CONFIG_ID, e);
					DarudeDiagnostics.logConfigReload(defaults, true);
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
		int farmingTickIntervalTicks,
		float baseUnderGrateChance,
		float basePyramidSideChance,
		float fullPyramidSideChance,
		float windwardSideMultiplier,
		float fullPyramidErodeToPyramidChance,
		float pyramidBreakChance,
		int maxFallthroughDepth,
		int maxFarmingOperationsPerTick,
		int avalancheSlopeThreshold,
		int maxTopplesPerTick,
		int nearDesertColumnSampleNumerator,
		int nearDesertColumnSampleDenominator,
		String nearDesertSpawnableSupportMode
	) {
		public static Values defaults() {
			return new Values(0.8f, 4, 2, 0.2f, 0, 2, 20, 0.03f, 0.02f, 0.024f, 1.25f, 0.005f, 0.002f, 12, 512, 3, 256, 6, 8, "full_block");
		}
	}
}
