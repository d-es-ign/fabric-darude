package com.darude;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

import java.lang.reflect.Field;

public final class SandstormStreakParticle extends SingleQuadParticle {
	private static final float[][] SAND_COLOR_PALETTE = {
		{216.0f / 255.0f, 196.0f / 255.0f, 140.0f / 255.0f},
		{228.0f / 255.0f, 210.0f / 255.0f, 166.0f / 255.0f},
		{204.0f / 255.0f, 184.0f / 255.0f, 138.0f / 255.0f},
		{236.0f / 255.0f, 223.0f / 255.0f, 186.0f / 255.0f}
	};

	private static final float INSIDE_SOLID_KILL_CHANCE = readFloatProperty("darude.client.inside_solid_kill_chance", 0.35f);
	private static final Field COLLIDES_WITH_WORLD_FIELD = resolveBooleanField(SingleQuadParticle.class, "collidesWithWorld");
	private static final Field HAS_PHYSICS_FIELD = resolveBooleanField(SingleQuadParticle.class, "hasPhysics");

	private SandstormStreakParticle(
		ClientLevel level,
		double x,
		double y,
		double z,
		double velocityX,
		double velocityY,
		double velocityZ,
		TextureAtlasSprite sprite
	) {
		super(level, x, y, z, sprite);
		this.xd = velocityX;
		this.yd = velocityY;
		this.zd = velocityZ;
		disableWorldCollision(this);
		this.friction = 0.98f;
		this.gravity = 0.0f;
		this.lifetime = 8 + this.random.nextInt(13);
		this.quadSize = 0.14f + this.random.nextFloat() * 0.08f;
		applySandPaletteColor(this.random);
	}

	@Override
	public void tick() {
		super.tick();
		if (INSIDE_SOLID_KILL_CHANCE > 0.0f && isInsideSolid() && this.random.nextFloat() < INSIDE_SOLID_KILL_CHANCE) {
			this.remove();
		}
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	private void applySandPaletteColor(RandomSource random) {
		float[] color = SAND_COLOR_PALETTE[random.nextInt(SAND_COLOR_PALETTE.length)];
		this.setColor(color[0], color[1], color[2]);
	}

	private static void disableWorldCollision(SingleQuadParticle particle) {
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
		BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
		return !this.level.getBlockState(pos).isAir() && this.level.getFluidState(pos).isEmpty();
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

	public static final class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(
			SimpleParticleType type,
			ClientLevel level,
			double x,
			double y,
			double z,
			double velocityX,
			double velocityY,
			double velocityZ,
			RandomSource random
		) {
			return new SandstormStreakParticle(level, x, y, z, velocityX, velocityY, velocityZ, this.sprites.get(random));
		}
	}
}
