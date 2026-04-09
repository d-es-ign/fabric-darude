package com.darude;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

public final class SandstormStreakParticle extends BillboardParticle {
	private static final float SAND_RED = 216.0f / 255.0f;
	private static final float SAND_GREEN = 196.0f / 255.0f;
	private static final float SAND_BLUE = 140.0f / 255.0f;
	private static final double COLLISION_MOVE_EPSILON_SQUARED = 1.0e-6;
	private static final double COLLISION_SPEED_EPSILON_SQUARED = 1.0e-4;

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
		this.setColor(SAND_RED, SAND_GREEN, SAND_BLUE);
		this.updateSprite(this.sprites);
	}

	@Override
	public void tick() {
		double previousX = this.x;
		double previousY = this.y;
		double previousZ = this.z;
		super.tick();
		if (!this.dead) {
			if (isInsideBlock()) {
				this.markDead();
				return;
			}

			double movedX = this.x - previousX;
			double movedY = this.y - previousY;
			double movedZ = this.z - previousZ;
			double movedDistanceSquared = movedX * movedX + movedY * movedY + movedZ * movedZ;
			double speedSquared = this.velocityX * this.velocityX + this.velocityY * this.velocityY + this.velocityZ * this.velocityZ;
			if (movedDistanceSquared <= COLLISION_MOVE_EPSILON_SQUARED && speedSquared >= COLLISION_SPEED_EPSILON_SQUARED) {
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

	private boolean isInsideBlock() {
		BlockPos pos = BlockPos.ofFloored(this.x, this.y, this.z);
		return !this.world.getBlockState(pos).isAir();
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
