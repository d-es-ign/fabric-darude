package com.darude;

import com.darude.worldgen.SandLayerGenerationConfig;

public final class DarudeDiagnostics {
	private static final boolean DEBUG_HOTSPOTS = Boolean.getBoolean("darude.debug.hotspots");
	private static final long SLOW_CHUNK_GENERATION_MS = Long.getLong("darude.debug.slow_chunk_generation_ms", 30L);
	private static final long SLOW_WORLD_TICK_MS = Long.getLong("darude.debug.slow_world_tick_ms", 40L);

	private DarudeDiagnostics() {
	}

	public static void logChunkGeneration(String worldKey, String chunkKey, int placements, long startedAtNanos) {
		long elapsedMs = elapsedMs(startedAtNanos);
		if (!DEBUG_HOTSPOTS && elapsedMs < SLOW_CHUNK_GENERATION_MS) {
			return;
		}

		if (elapsedMs >= SLOW_CHUNK_GENERATION_MS) {
			DarudeMod.LOGGER.warn("Hotspot[chunk-generation] world={} chunk={} placements={} elapsedMs={}", worldKey, chunkKey, placements, elapsedMs);
		} else {
			DarudeMod.LOGGER.info("Hotspot[chunk-generation] world={} chunk={} placements={} elapsedMs={}", worldKey, chunkKey, placements, elapsedMs);
		}
	}

	public static void logFarmingTick(String worldKey, int candidateChunks, int operations, int verticalChecks, long startedAtNanos) {
		long elapsedMs = elapsedMs(startedAtNanos);
		if (!DEBUG_HOTSPOTS && elapsedMs < SLOW_WORLD_TICK_MS) {
			return;
		}

		if (elapsedMs >= SLOW_WORLD_TICK_MS) {
			DarudeMod.LOGGER.warn("Hotspot[farming] world={} candidateChunks={} operations={} verticalChecks={} elapsedMs={}", worldKey, candidateChunks, operations, verticalChecks, elapsedMs);
		} else {
			DarudeMod.LOGGER.info("Hotspot[farming] world={} candidateChunks={} operations={} verticalChecks={} elapsedMs={}", worldKey, candidateChunks, operations, verticalChecks, elapsedMs);
		}
	}

	public static void logAvalancheTick(String worldKey, int queuedBefore, int processedCenters, int processedTopples, int remainingBudget, long startedAtNanos) {
		long elapsedMs = elapsedMs(startedAtNanos);
		if (!DEBUG_HOTSPOTS && elapsedMs < SLOW_WORLD_TICK_MS) {
			return;
		}

		if (elapsedMs >= SLOW_WORLD_TICK_MS) {
			DarudeMod.LOGGER.warn("Hotspot[avalanche] world={} queuedBefore={} processedCenters={} processedTopples={} remainingBudget={} elapsedMs={}", worldKey, queuedBefore, processedCenters, processedTopples, remainingBudget, elapsedMs);
		} else {
			DarudeMod.LOGGER.info("Hotspot[avalanche] world={} queuedBefore={} processedCenters={} processedTopples={} remainingBudget={} elapsedMs={}", worldKey, queuedBefore, processedCenters, processedTopples, remainingBudget, elapsedMs);
		}
	}

	public static void logWindShift(String worldKey, String from, String to, long gameTime) {
		if (!DEBUG_HOTSPOTS) {
			return;
		}

		DarudeMod.LOGGER.info("Hotspot[wind-shift] world={} from={} to={} gameTime={}", worldKey, from, to, gameTime);
	}

	public static void logConfigReload(SandLayerGenerationConfig.Values values, boolean fallbackToDefaults) {
		if (!DEBUG_HOTSPOTS && !fallbackToDefaults) {
			return;
		}

		if (fallbackToDefaults) {
			DarudeMod.LOGGER.warn("Hotspot[config-reload] using defaults: {}", values);
		} else {
			DarudeMod.LOGGER.info("Hotspot[config-reload] loaded: {}", values);
		}
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
