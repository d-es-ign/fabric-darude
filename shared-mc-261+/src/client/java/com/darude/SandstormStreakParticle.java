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

public final class SandstormStreakParticle extends SingleQuadParticle {
	private static final float SAND_RED = 216.0f / 255.0f;
	private static final float SAND_GREEN = 196.0f / 255.0f;
	private static final float SAND_BLUE = 140.0f / 255.0f;
	private static final double COLLISION_MOVE_EPSILON_SQUARED = 1.0e-6;
	private static final double COLLISION_SPEED_EPSILON_SQUARED = 1.0e-4;

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
		this.friction = 0.98f;
		this.gravity = 0.0f;
		this.lifetime = 8 + this.random.nextInt(13);
		this.quadSize = 0.14f + this.random.nextFloat() * 0.08f;
		this.setColor(SAND_RED, SAND_GREEN, SAND_BLUE);
	}

	@Override
	public void tick() {
		double previousX = this.x;
		double previousY = this.y;
		double previousZ = this.z;
		super.tick();
		if (this.removed) {
			return;
		}

		if (isInsideBlock()) {
			this.remove();
			return;
		}

		double movedX = this.x - previousX;
		double movedY = this.y - previousY;
		double movedZ = this.z - previousZ;
		double movedDistanceSquared = movedX * movedX + movedY * movedY + movedZ * movedZ;
		double speedSquared = this.xd * this.xd + this.yd * this.yd + this.zd * this.zd;
		if (movedDistanceSquared <= COLLISION_MOVE_EPSILON_SQUARED && speedSquared >= COLLISION_SPEED_EPSILON_SQUARED) {
			this.remove();
		}
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.OPAQUE;
	}

	private boolean isInsideBlock() {
		BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
		return !this.level.getBlockState(pos).isAir();
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
