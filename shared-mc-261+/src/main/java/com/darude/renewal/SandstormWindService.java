package com.darude.renewal;

import com.darude.DarudeDiagnostics;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Map;

public final class SandstormWindService {
	private static final int WIND_SHIFT_TICKS = 20 * 6;
	private static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static final Map<ServerLevel, WindState> STATES = new HashMap<>();
	private static boolean registered;

	private SandstormWindService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerLevel world : server.getAllLevels()) {
				tickWorld(world);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
		registered = true;
	}

	public static Direction getWindDirection(ServerLevel world) {
		WindState state = STATES.computeIfAbsent(world, ignored -> new WindState(Direction.NORTH, world.getGameTime()));
		return state.direction;
	}

	private static void tickWorld(ServerLevel world) {
		long time = world.getGameTime();
		WindState state = STATES.computeIfAbsent(world, ignored -> new WindState(Direction.NORTH, time));
		if (time < state.nextShiftTick) {
			return;
		}

		RandomSource random = world.getRandom();
		Direction next = state.direction;
		while (next == state.direction) {
			next = CARDINAL_DIRECTIONS[random.nextInt(CARDINAL_DIRECTIONS.length)];
		}

		Direction previous = state.direction;
		state.direction = next;
		state.nextShiftTick = time + WIND_SHIFT_TICKS;
		DarudeDiagnostics.logWindShift(world.dimension().location().toString(), previous.toString(), next.toString(), time);
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
