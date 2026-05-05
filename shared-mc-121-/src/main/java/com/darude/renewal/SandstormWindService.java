package com.darude.renewal;

import com.darude.DarudeDiagnostics;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;

public final class SandstormWindService {
	private static final int WIND_SHIFT_TICKS = 20 * 6;
	private static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static final Map<String, WindState> STATES = new HashMap<>();
	private static boolean registered;

	private SandstormWindService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_WORLD_TICK.register(SandstormWindService::tickWorld);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
		registered = true;
	}

	public static Direction getWindDirection(ServerWorld world) {
		WindState state = STATES.computeIfAbsent(worldKey(world), ignored -> new WindState(Direction.NORTH, world.getTime()));
		return state.direction;
	}

	private static void tickWorld(ServerWorld world) {
		if (!world.isRaining()) {
			return;
		}

		long time = world.getTime();
		String key = worldKey(world);
		WindState state = STATES.computeIfAbsent(key, ignored -> new WindState(Direction.NORTH, time));
		if (time < state.nextShiftTick) {
			return;
		}

		Random random = world.getRandom();
		Direction next = state.direction;
		while (next == state.direction) {
			next = CARDINAL_DIRECTIONS[random.nextInt(CARDINAL_DIRECTIONS.length)];
		}

		Direction previous = state.direction;
		state.direction = next;
		state.nextShiftTick = time + WIND_SHIFT_TICKS;
		DarudeDiagnostics.logWindShift(worldKey(world), previous.toString(), next.toString(), time);
	}

	private static String worldKey(ServerWorld world) {
		return world.getRegistryKey().getValue().toString();
	}

	private static final class WindState {
		private Direction direction;
		private long nextShiftTick;

		private WindState(Direction direction, long now) {
			this.direction = direction;
			this.nextShiftTick = now + WIND_SHIFT_TICKS;
		}
	}
}
