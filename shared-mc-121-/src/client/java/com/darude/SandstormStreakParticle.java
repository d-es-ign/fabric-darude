package com.darude;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
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
