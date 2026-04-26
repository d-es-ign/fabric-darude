package com.darude;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Field;

public final class SandstormStreakParticle extends BillboardParticle {
	private static final float[][] SAND_COLOR_PALETTE = {
		{216.0f / 255.0f, 196.0f / 255.0f, 140.0f / 255.0f},
		{228.0f / 255.0f, 210.0f / 255.0f, 166.0f / 255.0f},
		{204.0f / 255.0f, 184.0f / 255.0f, 138.0f / 255.0f},
		{236.0f / 255.0f, 223.0f / 255.0f, 186.0f / 255.0f}
	};

	private final SpriteProvider sprites;
	private static final float INSIDE_SOLID_KILL_CHANCE = readFloatProperty("darude.client.inside_solid_kill_chance", 0.35f);
	private static final Field COLLIDES_WITH_WORLD_FIELD = resolveBooleanField(BillboardParticle.class, "collidesWithWorld");
	private static final Field HAS_PHYSICS_FIELD = resolveBooleanField(BillboardParticle.class, "hasPhysics");

	private SandstormStreakParticle(
		ClientWorld world,
		double x,
		double y,
		double z,
		double velocityX,
		double velocityY,
		double velocityZ,
		SpriteProvider sprites
	) {
		super(world, x, y, z, sprites.getFirst());
		this.sprites = sprites;
		this.velocityX = velocityX;
		this.velocityY = velocityY;
		this.velocityZ = velocityZ;
		disableWorldCollision(this);
		this.velocityMultiplier = 0.98f;
		this.gravityStrength = 0.0f;
		this.maxAge = 8 + this.random.nextInt(13);
		this.scale = 0.14f + this.random.nextFloat() * 0.08f;
		applySandPaletteColor(this.random);
		this.updateSprite(this.sprites);
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.dead) {
			if (INSIDE_SOLID_KILL_CHANCE > 0.0f && isInsideSolid() && this.random.nextFloat() < INSIDE_SOLID_KILL_CHANCE) {
				this.markDead();
				return;
			}

			this.updateSprite(this.sprites);
		}
	}

	@Override
	public BillboardParticle.RenderType getRenderType() {
		return BillboardParticle.RenderType.PARTICLE_ATLAS_TRANSLUCENT;
	}

	private void applySandPaletteColor(Random random) {
		float[] color = SAND_COLOR_PALETTE[random.nextInt(SAND_COLOR_PALETTE.length)];
		this.setColor(color[0], color[1], color[2]);
	}

	private static void disableWorldCollision(BillboardParticle particle) {
		setBooleanFieldIfPresent(COLLIDES_WITH_WORLD_FIELD, particle, false);
		setBooleanFieldIfPresent(HAS_PHYSICS_FIELD, particle, false);
	}

	private static void setBooleanFieldIfPresent(Field field, Object target, boolean value) {
		if (field == null) {
			return;
		}

		try {
			field.setBoolean(target, value);
		} catch (IllegalAccessException ignored) {
		}
	}

	private static Field resolveBooleanField(Class<?> owner, String fieldName) {
		Class<?> current = owner;
		while (current != null) {
			try {
				Field field = current.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}

		return null;
	}

	private boolean isInsideSolid() {
		BlockPos pos = BlockPos.ofFloored(this.x, this.y, this.z);
		return !this.world.getBlockState(pos).isAir() && this.world.getFluidState(pos).isEmpty();
	}

	private static float readFloatProperty(String key, float fallback) {
		String value = System.getProperty(key);
		if (value == null) {
			return fallback;
		}

		try {
			return Math.max(0.0f, Math.min(1.0f, Float.parseFloat(value)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static final class Factory implements ParticleFactory<SimpleParticleType> {
		private final SpriteProvider sprites;

		public Factory(SpriteProvider sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(
			SimpleParticleType parameters,
			ClientWorld world,
			double x,
			double y,
			double z,
			double velocityX,
			double velocityY,
			double velocityZ,
			Random random
		) {
			return new SandstormStreakParticle(world, x, y, z, velocityX, velocityY, velocityZ, this.sprites);
		}
	}
}
