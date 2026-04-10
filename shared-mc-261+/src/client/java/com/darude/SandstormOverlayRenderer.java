package com.darude;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

public final class SandstormOverlayRenderer {
	private static final int OVERLAY_BASE_COLOR = 0x33D8C48C;
	private static final int NOISE_DARK_COLOR = 0x24B59B64;
	private static final int NOISE_LIGHT_COLOR = 0x26F0DEB2;
	private static final int NOISE_STEP = 4;
	private static final int NOISE_DOT_SIZE = 2;
	private static Method fillMethod;
	private static boolean fillMethodUsesZ;
	private static Class<?> fillMethodOwner;

	private SandstormOverlayRenderer() {
	}

	public static void render(Object drawContext, Minecraft client) {
		if (!SandstormClientEffects.isSandstormActive(client)) {
			return;
		}

		int width = client.getWindow().getGuiScaledWidth();
		int height = client.getWindow().getGuiScaledHeight();

		fill(drawContext, 0, 0, width, height, OVERLAY_BASE_COLOR);

		for (int y = 0; y < height; y += NOISE_STEP) {
			for (int x = 0; x < width; x += NOISE_STEP) {
				int hash = hash(x, y);
				if ((hash & 0x7) < 2) {
					fill(drawContext, x, y, Math.min(width, x + NOISE_DOT_SIZE), Math.min(height, y + NOISE_DOT_SIZE), NOISE_DARK_COLOR);
					continue;
				}

				if ((hash & 0xF) == 12) {
					fill(drawContext, x, y, Math.min(width, x + NOISE_DOT_SIZE), Math.min(height, y + NOISE_DOT_SIZE), NOISE_LIGHT_COLOR);
				}
			}
		}
	}

	private static void fill(Object drawContext, int x1, int y1, int x2, int y2, int color) {
		Method method = resolveFillMethod(drawContext);
		if (method == null) {
			return;
		}

		try {
			if (fillMethodUsesZ) {
				method.invoke(drawContext, x1, y1, x2, y2, 0, color);
				return;
			}

			method.invoke(drawContext, x1, y1, x2, y2, color);
		} catch (ReflectiveOperationException ignored) {
			fillMethod = null;
			fillMethodOwner = null;
		}
	}

	private static Method resolveFillMethod(Object drawContext) {
		Class<?> owner = drawContext.getClass();
		if (fillMethod != null && fillMethodOwner == owner) {
			return fillMethod;
		}

		fillMethod = null;
		fillMethodOwner = null;
		Method[] methods = owner.getMethods();
		for (Method method : methods) {
			if (!"fill".equals(method.getName())) {
				continue;
			}

			Class<?>[] parameterTypes = method.getParameterTypes();
			if (parameterTypes.length == 5) {
				fillMethod = method;
				fillMethodUsesZ = false;
				fillMethodOwner = owner;
				return fillMethod;
			}

			if (parameterTypes.length == 6) {
				fillMethod = method;
				fillMethodUsesZ = true;
				fillMethodOwner = owner;
				return fillMethod;
			}
		}

		return null;
	}

	private static int hash(int x, int y) {
		int h = x * 73428767 ^ y * 91228571;
		h ^= (h >>> 13);
		h *= 1274126177;
		h ^= (h >>> 16);
		return h;
	}
}
