package com.darude;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.OPAQUE;
	}

	private void applySandPaletteColor(RandomSource random) {
		float[] color = SAND_COLOR_PALETTE[random.nextInt(SAND_COLOR_PALETTE.length)];
		this.setColor(color[0], color[1], color[2]);
	}

	private static void disableWorldCollision(SingleQuadParticle particle) {
		setBooleanFieldIfPresent(particle, "collidesWithWorld", false);
		setBooleanFieldIfPresent(particle, "hasPhysics", false);
	}

	private static void setBooleanFieldIfPresent(Object target, String fieldName, boolean value) {
		Class<?> owner = target.getClass();
		while (owner != null) {
			try {
				Field field = owner.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.setBoolean(target, value);
				return;
			} catch (NoSuchFieldException ignored) {
				owner = owner.getSuperclass();
			} catch (IllegalAccessException ignored) {
				return;
			}
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
