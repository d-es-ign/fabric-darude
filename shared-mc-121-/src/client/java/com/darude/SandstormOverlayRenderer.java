package com.darude;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

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
		float targetStrength = SandstormClientEffects.isSandstormActive(client) ? 1.0f : 0.0f;
		overlayStrength = moveTowards(overlayStrength, targetStrength, OVERLAY_FADE_STEP);
		if (overlayStrength <= 0.001f) {
			return;
		}

		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();
		long gameTime = client.level != null ? client.level.getTime() : 0L;
		int phase = (int) ((gameTime / NOISE_PHASE_TICKS) % NOISE_SEEDS.length);
		int phaseSeed = NOISE_SEEDS[phase];
		int phaseShiftX = NOISE_SHIFT_X[phase];
		int phaseShiftY = NOISE_SHIFT_Y[phase];
		int baseColor = withScaledAlpha(OVERLAY_BASE_COLOR, overlayStrength);
		int darkNoiseColor = withScaledAlpha(NOISE_DARK_COLOR, overlayStrength);
		int lightNoiseColor = withScaledAlpha(NOISE_LIGHT_COLOR, overlayStrength);

		fill(drawContext, 0, 0, width, height, baseColor);

		for (int y = 0; y < height; y += NOISE_STEP) {
			for (int x = 0; x < width; x += NOISE_STEP) {
				int hash = hash(x + phaseShiftX, y + phaseShiftY, phaseSeed);
				if ((hash & 0x1F) < 4) {
					fill(drawContext, x, y, Math.min(width, x + NOISE_DOT_SIZE), Math.min(height, y + NOISE_DOT_SIZE), darkNoiseColor);
					continue;
				}

				if ((hash & 0x3F) == 56) {
					fill(drawContext, x, y, Math.min(width, x + NOISE_DOT_SIZE), Math.min(height, y + NOISE_DOT_SIZE), lightNoiseColor);
				}
			}
		}
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
}
