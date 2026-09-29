package net.camacraft.grappleunbound.controllers;

import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.client.ClientProxyInterface;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.utils.GrappleCustomization;
import net.camacraft.grappleunbound.utils.Vec;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/*
 * This file is part of GrappleMod.

    GrappleMod is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GrappleMod is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GrappleMod.  If not, see <http://www.gnu.org/licenses/>.
 */

public class AirfrictionController extends GrappleController {
	public double playerMovementMult = 0.5;
	
	public int ignoreGroundCounter = 0;
	public boolean wasSliding = false;
	public boolean wasWallrunning = false;
	public boolean wasRocket = false;
	public boolean firstTickSinceCreated = true;
	
	// a sliding jump pressed between ticks, taken on the tick's last step (see lastStepOfTick)
	public boolean slidingJumpQueued = false;

	public AirfrictionController(int grapplehookEntityId, int entityId, Level world, Vec pos, int id, GrappleCustomization custom) {
		super(grapplehookEntityId, entityId, world, pos, id, custom);
	}
	
	/**
	 * Only its own moves are claimed: a wall-run, a slide, a rocket burn, and the few ticks after
	 * one of its jumps or launches. Plain air control after a rope release claims nothing, so
	 * another mod can take the player over (a WallJump cling or double jump), and this controller
	 * then stands back (beforeSteps).
	 */
	@Override
	public boolean ownsMotion() {
		if (this.wasWallrunning || this.wasSliding || this.wasRocket) {
			return true;
		}
		if (this.entity == null) {
			return false;
		}
		// (bounded, so a stale window from a world with a later clock can't hold on here)
		long ticksLeft = impulseClaimUntil - this.entity.level().getGameTime();
		return ticksLeft > 0 && ticksLeft <= MOTION_CLAIM_TICKS;
	}

	/**
	 * Start of a tick: this controller stands back (ends, without another write) when another mod
	 * claims the player's motion, or when something else has changed the velocity it last wrote —
	 * between ticks (a knockback, an explosion, a Full Stop bounce, a correction from the server:
	 * anything at all, since nothing in vanilla touches it there), or during the move beyond what
	 * vanilla's own collision, friction, drag and gravity could have made of it (a push, a bounce
	 * pad, a launch). Before this it rewrote the velocity every tick until landing, so a WallJump
	 * cling slid and wall and double jumps died after any rope release.
	 */
	@Override
	public void beforeSteps(Vec3 afterMove) {
		if (!this.attached || this.entity == null) {
			return;
		}
		if (PhysicsOwnership.claimedByOther(this.entity, PhysicsAspect.MOTION, GrappleMod.PHYSICS_OWNER)) {
			this.unattach();
			return;
		}
		if (afterMove != null && this.lastWritten != null
				&& (!this.entity.getDeltaMovement().equals(afterMove) || !vanillaMoveCouldGive(this.lastWritten, afterMove))) {
			this.unattach();
			return;
		}
		this.updateMotionClaim();
	}

	// room for rounding in the comparison below, blocks/tick
	private static final double MOVE_SLACK = 1.0E-4;

	/**
	 * Whether the player's own move could have turned the velocity {@code written} into
	 * {@code moved}. Collision can stop an axis, friction and drag only slow it, gravity only
	 * pulls down (a landing stops the fall first). A gain in speed, a turn or a lift is someone
	 * else. (Movement convention: under a gravity mod, x/z and y are the player's own.)
	 */
	static boolean vanillaMoveCouldGive(Vec3 written, Vec3 moved) {
		return axisOnlySlowed(written.x, moved.x) && axisOnlySlowed(written.z, moved.z)
				&& moved.y <= Math.max(written.y, 0) + MOVE_SLACK;
	}

	private static boolean axisOnlySlowed(double written, double moved) {
		if (Math.abs(moved) <= MOVE_SLACK) {
			return true;
		}
		return Math.signum(moved) == Math.signum(written) && Math.abs(moved) <= Math.abs(written) + MOVE_SLACK;
	}
	
	@Override
	public void updatePlayerPos() {
		Entity entity = this.entity;
		
		if (entity == null) {return;}
		
		if (entity.getVehicle() != null) {
			this.unattach();
			this.updateServerPos();
			return;
		}

		// creative flight owns movement; this controller's gravity would drag the player down
		if (isFlying(entity)) {
			motion = Vec.motionVec(entity);
			this.unattach();
			return;
		}
		
		// allows safe releasing of grapple when on a ladder
		if(entity instanceof LivingEntity living && living.onClimbable()) {
			this.unattach();
			return;
		}

		Vec additionalmotion = new Vec(0,0,0);
		
		// hand air control back to the player (in zero gravity always: nothing ever lands, so the air
		// controller would otherwise carry every release for good and blank the movement keys)
		if ((GrappleConfig.getConf().other.dont_override_movement_in_air || net.camacraft.camafy.compat.gravity.Gravity.isWeightless(entity))
				&& !entity.onGround() && !wasSliding && !wasWallrunning && !wasRocket && !firstTickSinceCreated) {
			motion = Vec.motionVec(entity);
			this.unattach();
			return;
		}

		if (this.attached) {
			this.readFrame();

			if (this.slidingJumpQueued && lastStepOfTick) {
				this.slidingJumpQueued = false;
				this.slidingJump();
			}

			boolean issliding = ClientProxyInterface.proxy.isSliding(entity, motion);
			
			if (issliding && !wasSliding) {
				playSlideSound();
			}
			
			if (this.ignoreGroundCounter <= 0) {
				this.normalGround(issliding);					
				this.normalCollisions(issliding);
			}

			this.applyAirFriction();
			
			if (this.entity.isInWater() || this.entity.isInLava()) {
				this.unattach();
				return;
			}
			
			boolean doesrocket = false;
			if (this.custom != null) {
				if (this.custom.rocket) {
					Vec rocket = this.rocket(entity);
					this.motion.add_ip(rocket);
					if (rocket.length() > 0) {
						doesrocket = true;
					}
				}
			}

			if (issliding) {
				this.applySlidingFriction();
			}

			boolean wallrun = this.applyWallrun();

			if (!issliding && !wasSliding) {
				if (wallrun) {
					motion = motion.removeAlong(new Vec(0,1,0));
					if (this.wallDirection != null) {
						motion = motion.removeAlong(this.wallDirection);
					}

					Vec new_movement = this.playerMovement.changeLen(GrappleConfig.getConf().enchantments.wallrun.wallrun_speed*1.5);
					if (this.wallDirection != null) {
						new_movement = new_movement.removeAlong(this.wallDirection);
					}
					if (new_movement.length() > GrappleConfig.getConf().enchantments.wallrun.wallrun_speed) {
						new_movement.changeLen_ip(GrappleConfig.getConf().enchantments.wallrun.wallrun_speed);
					}
					Vec current_motion_along = this.motion.removeAlong(new Vec(0,1,0));
					Vec new_motion_along = this.motion.add(new_movement).removeAlong(new Vec(0,1,0));
					if (this.wallDirection != null) {
						current_motion_along = current_motion_along.removeAlong(this.wallDirection);
						new_motion_along = new_motion_along.removeAlong(this.wallDirection);
					}
					if (current_motion_along.length() <= GrappleConfig.getConf().enchantments.wallrun.wallrun_max_speed || current_motion_along.dot(new_movement) < 0) {
						motion.add_ip(new_movement);
						if (new_motion_along.length() > GrappleConfig.getConf().enchantments.wallrun.wallrun_max_speed) {
							this.motion.changeLen_ip(GrappleConfig.getConf().enchantments.wallrun.wallrun_max_speed);
						}
					}
					additionalmotion.add_ip(wallrunPressAgainstWall());
				} else {
					double max_motion = GrappleConfig.getConf().other.airstrafe_max_speed;
					double accel = GrappleConfig.getConf().other.airstrafe_acceleration;
					Vec motion_horizontal = motion.removeAlong(new Vec(0,1,0));
					double prev_motion = motion_horizontal.length();
					Vec new_motion_horizontal = motion_horizontal.add(this.playerMovement.changeLen(accel));
					double angle = motion_horizontal.angle(new_motion_horizontal);
					if (new_motion_horizontal.length() > max_motion && new_motion_horizontal.length() > prev_motion) {
						double ninety_deg = Math.PI / 2;
						double new_max_motion = max_motion;
						if (angle < ninety_deg && prev_motion > max_motion) {
							new_max_motion = prev_motion + ((max_motion - prev_motion) * (angle / (Math.PI / 2)));
						}
						new_motion_horizontal.changeLen_ip(new_max_motion);
					}
					motion.x = new_motion_horizontal.x;
					motion.z = new_motion_horizontal.z;
				}
			}
			
			if (entity instanceof LivingEntity) {
				LivingEntity entityliving = (LivingEntity) entity;
				if (entityliving.isFallFlying()) {
					this.unattach();
				}
			}
			
			Vec gravity = this.gravityStep();

			if (!wallrun) {
				motion.add_ip(gravity);
			}

			Vec newmotion;
			
			newmotion = motion.add(additionalmotion);
			
//			if (wallrun) {
//				newmotion.add_ip(this.walldirection);
//			}

			this.writeMotion(newmotion);
			
			this.updateServerPos();
			
			if (entity.onGround()) {
				if (!issliding) {
					if (!wallrun) {
						if (!doesrocket) {
							if (ignoreGroundCounter <= 0) {
								this.unattach();
							}
						} else {
							motion = Vec.motionVec(entity);
						}
					}
				}
			}
			if (ignoreGroundCounter > 0) { ignoreGroundCounter--; }
			
			wasSliding = issliding;
			wasWallrunning = wallrun;
			wasRocket = doesrocket;
			firstTickSinceCreated = false;

			if (this.attached) {
				this.updateMotionClaim();
			}
		}
	}

	public void receiveEnderLaunch(double x, double y, double z) {
		super.receiveEnderLaunch(x, y, z);
		this.ignoreGroundCounter = 2;
	}
	
	public void slidingJump() {
		super.slidingJump();
		this.ignoreGroundCounter = 2;
	}
	
	public void playSlideSound() {
		ClientProxyInterface.proxy.playSlideSound(this.entity);
	}
}
