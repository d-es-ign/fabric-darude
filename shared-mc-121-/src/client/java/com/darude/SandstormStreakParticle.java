package com.darude;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

public final class SandstormStreakParticle extends BillboardParticle {
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
		this.velocityMultiplier = 0.98f;
		this.gravityStrength = 0.0f;
		this.maxAge = 8 + this.random.nextInt(13);
		this.scale = 0.14f + this.random.nextFloat() * 0.08f;
		applyDebugDirectionColor(velocityX, velocityZ);
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
