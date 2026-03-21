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
	private static volatile Values values = new Values(0.3f, 4);

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
		Values defaults = new Values(0.3f, 4);
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

					validSpotChance = clamp(validSpotChance, 0.0f, 1.0f);
					baseMaxLayers = clamp(baseMaxLayers, 0, 15);
					return new Values(validSpotChance, baseMaxLayers);
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

	public record Values(float validSpotChance, int baseMaxLayers) {
	}
}
