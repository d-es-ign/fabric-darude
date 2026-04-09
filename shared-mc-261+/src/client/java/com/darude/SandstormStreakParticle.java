package com.darude;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

public final class SandstormStreakParticle extends SingleQuadParticle {
	private SandstormStreakParticle(
		ClientLevel level,
		double x,
		double y,
		double z,
		double velocityX,
		double velocityY,
		double velocityZ,
		SpriteSet sprites
	) {
		super(level, x, y, z, sprites.get(level.random));
		this.xd = velocityX;
		this.yd = velocityY;
		this.zd = velocityZ;
		this.friction = 0.98f;
		this.gravity = 0.0f;
		this.lifetime = 8 + this.random.nextInt(13);
		this.quadSize = 0.14f + this.random.nextFloat() * 0.08f;
		applyDebugDirectionColor(velocityX, velocityZ);
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.OPAQUE;
	}

	private void applyDebugDirectionColor(double velocityX, double velocityZ) {
		if (Math.abs(velocityX) >= Math.abs(velocityZ)) {
			if (velocityX >= 0.0) {
				this.setColor(1.0f, 0.0f, 1.0f);
				return;
			}

			this.setColor(1.0f, 1.0f, 0.0f);
			return;
		}

		if (velocityZ >= 0.0) {
			this.setColor(0.0f, 1.0f, 0.0f);
			return;
		}

		this.setColor(0.0f, 0.0f, 1.0f);
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
			return new SandstormStreakParticle(level, x, y, z, velocityX, velocityY, velocityZ, this.sprites);
		}
	}
}
