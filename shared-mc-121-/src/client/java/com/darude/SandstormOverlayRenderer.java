package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.lang.reflect.Method;

public final class SandstormOverlayRenderer {
	private static final int OVERLAY_BASE_COLOR = 0x24D8C48C;
	private static final int NOISE_DARK_COLOR = 0x16B59B64;
	private static final int NOISE_LIGHT_COLOR = 0x18F0DEB2;
	private static final int NOISE_STEP = 10;
	private static final int NOISE_DOT_SIZE = 1;
	private static final float OVERLAY_FADE_STEP = 0.08f;
	private static final int NOISE_PHASE_TICKS = 2;
	private static final int[] NOISE_SEEDS = {
		0x2F6E2B1,
		0x91C3A57,
		0x4BB29D3,
		0xD17F58B,
		0x6A3CC11,
		0xB9E743D
	};
	private static final int[] NOISE_SHIFT_X = {0, 3, -2, 5, -4, 1};
	private static final int[] NOISE_SHIFT_Y = {0, -2, 4, -3, 1, 5};
	private static float overlayStrength;

	private SandstormOverlayRenderer() {
	}

	public static void render(DrawContext drawContext, MinecraftClient client) {
		OverlayQuality quality = resolveOverlayQuality(client);
		float targetStrength = SandstormClientEffects.isSandstormActive(client) ? 1.0f : 0.0f;
		overlayStrength = moveTowards(overlayStrength, targetStrength, OVERLAY_FADE_STEP * quality.fadeMultiplier);
		if (overlayStrength <= 0.001f) {
			return;
		}

		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();
		long gameTime = client.world != null ? client.world.getTime() : 0L;
		int phase = (int) ((gameTime / NOISE_PHASE_TICKS) % NOISE_SEEDS.length);
		int phaseSeed = NOISE_SEEDS[phase];
		int phaseShiftX = NOISE_SHIFT_X[phase];
		int phaseShiftY = NOISE_SHIFT_Y[phase];
		int baseColor = withScaledAlpha(OVERLAY_BASE_COLOR, overlayStrength * quality.baseAlphaMultiplier);
		int darkNoiseColor = withScaledAlpha(NOISE_DARK_COLOR, overlayStrength * quality.noiseAlphaMultiplier);
		int lightNoiseColor = withScaledAlpha(NOISE_LIGHT_COLOR, overlayStrength * quality.noiseAlphaMultiplier);
 
		int noiseStep = quality.noiseStep;
		int noiseDotSize = quality.noiseDotSize;

		fill(drawContext, 0, 0, width, height, baseColor);

		for (int y = 0; y < height; y += noiseStep) {
			for (int x = 0; x < width; x += noiseStep) {
				int hash = hash(x + phaseShiftX, y + phaseShiftY, phaseSeed);
				if ((hash & quality.darkMask) < quality.darkThreshold) {
					fill(drawContext, x, y, Math.min(width, x + noiseDotSize), Math.min(height, y + noiseDotSize), darkNoiseColor);
					continue;
				}

				if ((hash & quality.lightMask) == quality.lightMatch) {
					fill(drawContext, x, y, Math.min(width, x + noiseDotSize), Math.min(height, y + noiseDotSize), lightNoiseColor);
				}
			}
		}
	}

	private static OverlayQuality resolveOverlayQuality(MinecraftClient client) {
		Object graphicsOption = invokeAny(client.options, "getGraphicsMode", "graphicsMode");
		Object graphicsValue = invokeAny(graphicsOption, "getValue", "get");
		if (graphicsValue instanceof Enum<?> graphicsEnum) {
			String name = graphicsEnum.name();
			if ("FAST".equals(name)) {
				return OverlayQuality.OFF;
			}

			if ("FANCY".equals(name)) {
				return OverlayQuality.FAST;
			}
		}

		return OverlayQuality.FANCY;
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

	private static void fill(DrawContext drawContext, int x1, int y1, int x2, int y2, int color) {
		drawContext.fill(x1, y1, x2, y2, color);
	}

	private static int hash(int x, int y, int seed) {
		int h = x * 73428767 ^ y * 91228571 ^ seed;
		h ^= (h >>> 13);
		h *= 1274126177;
		h ^= (h >>> 16);
		return h;
	}

	private static float moveTowards(float current, float target, float maxDelta) {
		if (current < target) {
			return Math.min(current + maxDelta, target);
		}

		return Math.max(current - maxDelta, target);
	}

	private static int withScaledAlpha(int color, float strength) {
		int alpha = (color >>> 24) & 0xFF;
		int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * strength)));
		return (scaledAlpha << 24) | (color & 0x00FFFFFF);
	}

	private enum OverlayQuality {
		OFF(0.4f, 0.22f, 0.7f, 18, 1, 0x7F, 4, 0xFF, 222),
		FAST(0.7f, 0.6f, 0.85f, 14, 1, 0x3F, 4, 0x7F, 118),
		FANCY(1.0f, 1.0f, 1.0f, 10, 1, 0x1F, 4, 0x3F, 56);

		private final float baseAlphaMultiplier;
		private final float noiseAlphaMultiplier;
		private final float fadeMultiplier;
		private final int noiseStep;
		private final int noiseDotSize;
		private final int darkMask;
		private final int darkThreshold;
		private final int lightMask;
		private final int lightMatch;

		OverlayQuality(
			float baseAlphaMultiplier,
			float noiseAlphaMultiplier,
			float fadeMultiplier,
			int noiseStep,
			int noiseDotSize,
			int darkMask,
			int darkThreshold,
			int lightMask,
			int lightMatch
		) {
			this.baseAlphaMultiplier = baseAlphaMultiplier;
			this.noiseAlphaMultiplier = noiseAlphaMultiplier;
			this.fadeMultiplier = fadeMultiplier;
			this.noiseStep = noiseStep;
			this.noiseDotSize = noiseDotSize;
			this.darkMask = darkMask;
			this.darkThreshold = darkThreshold;
			this.lightMask = lightMask;
			this.lightMatch = lightMatch;
		}
	}
}
