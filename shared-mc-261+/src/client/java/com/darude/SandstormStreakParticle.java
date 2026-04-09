package com.darude;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

public final class SandstormStreakParticle extends TextureSheetParticle {
	private final SpriteSet sprites;

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
		super(level, x, y, z, velocityX, velocityY, velocityZ);
		this.sprites = sprites;
		this.xd = velocityX;
		this.yd = velocityY;
		this.zd = velocityZ;
		this.friction = 0.98f;
		this.gravity = 0.0f;
		this.lifetime = 8 + this.random.nextInt(13);
		this.quadSize = 0.14f + this.random.nextFloat() * 0.08f;
		applyDebugDirectionColor(velocityX, velocityZ);
		this.setSpriteFromAge(sprites);
	}

	@Override
	public void tick() {
		super.tick();
		this.setSpriteFromAge(this.sprites);
	}

	@Override
	public ParticleRenderType getRenderType() {
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
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
			double velocityZ
		) {
			return new SandstormStreakParticle(level, x, y, z, velocityX, velocityY, velocityZ, this.sprites);
		}
	}
}
