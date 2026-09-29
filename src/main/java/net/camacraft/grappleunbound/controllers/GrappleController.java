package net.camacraft.grappleunbound.controllers;

import net.camacraft.grappleunbound.client.ClientProxyInterface;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.integrations.ValkyrienSkiesIntegration;
import net.camacraft.grappleunbound.network.GrappleEndMessage;
import net.camacraft.grappleunbound.network.PlayerMovementMessage;
import net.camacraft.grappleunbound.utils.GrappleCustomization;
import net.camacraft.grappleunbound.utils.GrapplemodUtils;
import net.camacraft.grappleunbound.utils.Vec;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;


public class GrappleController {
	/**
	 * Physics steps per game tick. Every per-step constant here (gravity 0.05, drag, motor
	 * acceleration, air strafe, climb speed, wall-run and rocket timings) was tuned while the
	 * client tick handler ran on both phases of ClientTickEvent, i.e. two steps a tick. The
	 * handler now runs once a tick, on START, and takes both steps back to back there: the same
	 * steps in the same order around the player's move, so the feel is unchanged, but nothing
	 * another mod does to the player between the moves is written over by a second pass.
	 */
	public static final int STEPS_PER_TICK = 2;
	/**
	 * False on the first step of a tick. Key presses start jumps only on the last step, right
	 * before the move, which is where the START step of the old double tick first saw them: a
	 * jump leaves with the same speed it always did instead of losing a step of gravity.
	 */
	public static boolean lastStepOfTick = true;

	/** How long each MOTION claim lasts; renewed every step while a controller drives the player. */
	public static final int MOTION_CLAIM_TICKS = 3;

	public int entityId;
	public Level world;
	public Entity entity;

	public HashSet<GrapplehookEntity> grapplehookEntities = new HashSet<GrapplehookEntity>();
	public HashSet<Integer> grapplehookEntityIds = new HashSet<Integer>();

	public boolean attached = true;

	public Vec motion;

	public double playerForward = 0;
	public double playerStrafe = 0;
	public boolean playerJump = false;
	public boolean playerSneak = false;
	public Vec playerMovementUnrotated = new Vec(0,0,0);
	public Vec playerMovement = new Vec(0,0,0);

	public int onGroundTimer = 0;
	public int maxOnGroundTimer = 3;

	// a mid-air space press by a player who can fly might be half of a double-tap to toggle
	// creative flight; the rope jump waits out vanilla's double-tap window before firing
	int pendingRopeJumpTicks = 0;

	// last rope length reported to the server per hook (ship pulling needs a current r)
	private final java.util.HashMap<Integer, Double> lastSentRopeLen = new java.util.HashMap<>();

	/** The client owns r while swinging (climb/reel); mirror meaningful changes to the server. */
	protected void syncRopeLength(GrapplehookEntity hookEntity) {
		Double last = this.lastSentRopeLen.get(hookEntity.getId());
		if (last == null || Math.abs(hookEntity.r - last) > 0.1) {
			this.lastSentRopeLen.put(hookEntity.getId(), hookEntity.r);
			CommonSetup.network.sendToServer(new net.camacraft.grappleunbound.network.HookRopeLengthMessage(hookEntity.getId(), hookEntity.r));
		}
	}

	public double maxLen;

	public double playerMovementMult = 0;

	public int controllerId;

	public GrappleCustomization custom = null;

	/**
	 * The velocity this controller last gave the player (deltaMovement convention), or null while
	 * it isn't writing one. Compared at the start of each tick to see whether anything else has
	 * moved the player since (see beforeSteps).
	 */
	public Vec3 lastWritten = null;

	// GRAVITY: the controllers work in the player's deltaMovement convention, which under a
	// gravity mod (Gravity Unbound, through Camafy) is the player's own frame, +y being their up.
	// Rope geometry (hook, bends, eye) is world space and is turned into that frame before it
	// meets the motion. Read once per step. Without a gravity mod every conversion below hands
	// back its own argument, so the maths is exactly what it always was.
	protected GravityFrame frame = GravityFrame.DEFAULT;
	// ZERO GRAVITY (VS Genesis's space, an Ad Astra orbit, a zero-strength Gravity Unbound field):
	// nothing pulls the player, so there is no "up" to climb, jump or run along. Read with the frame.
	protected boolean weightless = false;

	protected void readFrame() {
		this.frame = this.entity == null ? GravityFrame.DEFAULT : Gravity.frame(this.entity);
		this.weightless = this.entity != null && Gravity.isWeightless(this.entity);
	}

	/** A world-space direction or offset in the player's movement convention. */
	public Vec toLocal(Vec world) {
		if (this.frame.isDefault()) {
			return world;
		}
		return new Vec(Gravity.worldToMovement(this.entity, world.toVec3d()));
	}

	/** A movement-convention vector in world space. */
	public Vec toWorld(Vec local) {
		if (this.frame.isDefault()) {
			return local;
		}
		return new Vec(Gravity.movementToWorld(this.entity, local.toVec3d()));
	}

	/**
	 * One step's gravity (0.05, two steps a tick), along the pull of whatever field the player is
	 * in, as strong as the local gravity (Camafy 0.1.7: none in VS Genesis's space or an Ad Astra
	 * orbit, a sixth on the Moon), so a rope in space is a tether, not a pendulum.
	 */
	protected Vec gravityStep() {
		return this.toLocal(new Vec(Gravity.pullDirection(this.entity))).mult(0.05 * Gravity.strength(this.entity));
	}

	/**
	 * Hands the player a velocity, remembering it as this controller's own. On an Ad Astra planet
	 * Ad Astra lifts the player at the start of the move instead of weakening its pull; this
	 * controller already applies the planet's gravity itself (gravityStep), so the lift is taken
	 * back out of the velocity the move starts from, or a player on a Moon rope drifts upward.
	 * Zero everywhere else.
	 */
	protected void writeMotion(Vec v) {
		v.checkNaN();
		Vec3 out = v.toVec3d();
		if (lastStepOfTick) {
			double lift = Gravity.preMoveLift(this.entity);
			if (lift != 0.0) {
				out = out.subtract(0.0, lift, 0.0);
			}
		}
		this.entity.setDeltaMovement(out);
		this.lastWritten = out;
	}

	/**
	 * Whether this controller is driving the player's motion outright. It then claims MOTION with
	 * Camafy's PhysicsOwnership, so other movement mods (WallJump Unbound's cling on the shared Left
	 * Shift) stand back. A rope, a forcefield and a grappled swing always do.
	 */
	public boolean ownsMotion() {
		return true;
	}

	public void updateMotionClaim() {
		if (this.entity == null) {
			return;
		}
		if (this.ownsMotion()) {
			PhysicsOwnership.claim(this.entity, GrappleMod.PHYSICS_OWNER, PhysicsAspect.MOTION.bit, MOTION_CLAIM_TICKS);
		} else {
			PhysicsOwnership.release(this.entity, GrappleMod.PHYSICS_OWNER);
		}
	}

	// game time the MOTION claim of the last jump or launch runs to; the air controller that
	// carries the player on afterwards keeps it (only the local player has controllers)
	protected static long impulseClaimUntil = Long.MIN_VALUE;

	/**
	 * A jump or launch was just given: the player's motion is this mod's for a few ticks, whatever
	 * controller carries them on. Keeps WallJump's double jump from firing on the same key press.
	 */
	protected void claimImpulse() {
		if (this.entity != null) {
			impulseClaimUntil = this.entity.level().getGameTime() + MOTION_CLAIM_TICKS;
			PhysicsOwnership.claim(this.entity, GrappleMod.PHYSICS_OWNER, PhysicsAspect.MOTION.bit, MOTION_CLAIM_TICKS);
		}
	}

	/**
	 * Start of a tick, before its steps. Anything that changed the player's velocity from outside
	 * since the last move ended ({@code afterMove}, null when unknown) — a knockback, an explosion,
	 * a Full Stop bounce, a ship letting go — is taken into the rope's own motion. The old double
	 * tick wrote straight over it.
	 */
	public void beforeSteps(Vec3 afterMove) {
		if (!this.attached || this.entity == null) {
			return;
		}
		this.updateMotionClaim();
		if (afterMove == null || this.lastWritten == null) {
			return;
		}
		Vec3 now = this.entity.getDeltaMovement();
		if (!now.equals(afterMove)) {
			this.motion.add_ip(now.x - afterMove.x, now.y - afterMove.y, now.z - afterMove.z);
		}
	}

	/** How far the player moved last tick, in their movement convention. */
	protected Vec movedLastTick() {
		return this.toLocal(new Vec(this.entity.position().x - this.entity.xOld, this.entity.position().y - this.entity.yOld, this.entity.position().z - this.entity.zOld));
	}

	public GrappleController(int grapplehookEntityId, int entityId, Level world, Vec pos, int controllerid, GrappleCustomization custom) {
		this.entityId = entityId;
		this.world = world;
		this.custom = custom;

		if (this.custom != null) {
			this.playerMovementMult = this.custom.playermovementmult;
			this.maxLen = custom.maxlen;
		}

		this.controllerId = controllerid;

		this.entity = world.getEntity(entityId);
		this.readFrame();
		this.motion = Vec.motionVec(entity);

		// undo friction: use the actual position delta when it agrees with the reported motion
		// (same sign, within a factor of 2) on every axis
		Vec newmotion = this.movedLastTick();
		if (withinFactorTwo(newmotion.x, motion.x) && withinFactorTwo(newmotion.y, motion.y) && withinFactorTwo(newmotion.z, motion.z)) {
			this.motion = newmotion;
		}

		this.onGroundTimer = 0;

		if (grapplehookEntityId != -1) {
			Entity grapplehookEntity = world.getEntity(grapplehookEntityId);
			if (grapplehookEntity != null && grapplehookEntity.isAlive() && grapplehookEntity instanceof GrapplehookEntity) {
				this.addHookEntity((GrapplehookEntity)grapplehookEntity);
			} else {
				GrappleMod.LOGGER.warn("no hook entity");
				this.unattach();
			}
		}

		if (custom != null && custom.rocket) {
			ClientProxyInterface.proxy.updateRocketRegen(custom.rocket_active_time, custom.rocket_refuel_ratio);
		}
	}

	private static boolean withinFactorTwo(double a, double b) {
		double absA = Math.abs(a);
		double absB = Math.abs(b);
		if (absA < 1e-9 && absB < 1e-9) {
			return true;
		}
		if (a * b <= 0) {
			return false;
		}
		return absA / absB < 2 && absB / absA < 2;
	}

	public void unattach() {
		if (ClientProxyInterface.proxy.unregisterController(this.entityId) != null) {
			this.attached = false;
			PhysicsOwnership.release(this.entity, GrappleMod.PHYSICS_OWNER);

			if (this.controllerId != GrapplemodUtils.AIRID) {
				CommonSetup.network.sendToServer(new GrappleEndMessage(this.entityId, this.grapplehookEntityIds));
				// no air controller while flying: it applies gravity every tick, which reads as
				// being kicked out of creative flight the moment the rope is released
				if (!isFlying(this.entity)) {
					ClientProxyInterface.proxy.createControl(GrapplemodUtils.AIRID, -1, this.entityId, this.entity.level(), new Vec(0,0,0), null, this.custom);
				}
			}
		}
	}

	protected static boolean isFlying(Entity entity) {
		return entity instanceof Player && ((Player) entity).getAbilities().flying;
	}


	public void doClientTick() {
		if (this.attached) {
			if (this.entity == null || !this.entity.isAlive()) {
				this.unattach();
			} else {
				this.updatePlayerPos();
			}
		}
	}

	/**
	 * The hook's position for this tick's rope update. A hook on a Valkyrien Skies ship is re-read
	 * from the ship's current tick pose first, so the hook end, the ship's rope bends and the
	 * rope's ray traces all put the ship in the same place (the hook entity's own tick can run
	 * before the ship's transform advances for the tick).
	 */
	private Vec ropeHookPos(GrapplehookEntity hookEntity) {
		if (hookEntity.getAttachedShipId() != -1) {
			ValkyrienSkiesIntegration.updateShipAttachment(hookEntity, hookEntity.level());
		}
		return Vec.positionVec(hookEntity);
	}

	public void receivePlayerMovementMessage(float strafe,
											 float forward, boolean jump, boolean sneak) {
		playerForward = forward;
		playerStrafe = strafe;
		playerSneak = sneak;
		playerMovementUnrotated = new Vec(strafe, 0, forward);
		playerMovement = playerMovementUnrotated.rotateYaw((float) (this.entity.getYRot() * (Math.PI / 180.0)));
	}

	public void updatePlayerPos() {
		Entity entity = this.entity;

		if (this.attached) {
			if(entity != null) {
				if (true) {
					if (entity.getVehicle() != null) {
						this.unattach();
						this.updateServerPos();
						return;
					}

					this.readFrame();
					this.updateMotionClaim();

					// creative flight: stay attached, but let vanilla flight drive the player.
					// Rope physics, snapping, and space-detach are suspended; rope length pays
					// out so leaving flight doesn't yank the player back.
					if (entity instanceof Player && ((Player) entity).getAbilities().flying) {
						this.pendingRopeJumpTicks = 0; // the space press toggled flight, not a rope jump
						playerJump = ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach);
						Vec flyingplayerpos = Vec.eyePos(entity);
						for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
							Vec hookPos = this.ropeHookPos(hookEntity);
							if (this.custom.phaserope) {
								hookEntity.segmentHandler.updatePos(hookPos, flyingplayerpos, hookEntity.r);
							} else {
								hookEntity.segmentHandler.update(hookPos, flyingplayerpos, hookEntity.r, false);
							}
							Vec anchor = hookEntity.segmentHandler.getClosest(hookPos);
							double distToHook = flyingplayerpos.sub(anchor).length();
							// ship-attached ropes stay at their length while flying so tension
							// builds and pulls the ship; other ropes pay out to avoid a snap
							double needed = hookEntity.segmentHandler.getDistToAnchor() + distToHook;
							if (needed > hookEntity.r && hookEntity.getAttachedShipId() == -1) {
								hookEntity.r = needed;
							}
							this.calcTaut(distToHook, hookEntity);
							this.syncRopeLength(hookEntity);
						}
						this.motion = Vec.motionVec(entity);
						this.lastWritten = null; // vanilla flight writes the velocity, not the rope
						this.updateServerPos();
						return;
					}

					this.normalGround(false);
					this.normalCollisions(false);
					this.applyAirFriction();

					Vec playerpos = Vec.eyePos(entity);


					Vec additionalmotion = new Vec(0,0,0);

					Vec gravity = this.gravityStep();

					motion.add_ip(gravity);

					boolean doJump = false;
					double jumpSpeed = 0;
					boolean isClimbing = false;

					// is motor active? (check motorwhencrouching / motorwhennotcrouching)
					boolean motor = false;
					if (this.custom.motor) {
						if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_motoronoff) && this.custom.motorwhencrouching) {
							motor = true;
						} else if (!ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_motoronoff) && this.custom.motorwhennotcrouching) {
							motor = true;
						}
					}

					boolean close = false;

					Vec averagemotiontowards = new Vec(0, 0, 0);

					double min_spherevec_dist = 99999;

					// countdown for a rope jump deferred past the creative double-tap window
					boolean deferredJumpReady = false;
					if (this.pendingRopeJumpTicks > 0) {
						this.pendingRopeJumpTicks--;
						if (this.pendingRopeJumpTicks == 0) {
							deferredJumpReady = true;
						}
					}

					for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
						Vec hookPos = this.ropeHookPos(hookEntity);

						// Update segment handler (handles rope bends)
						if (this.custom.phaserope) {
							hookEntity.segmentHandler.updatePos(hookPos, playerpos, hookEntity.r);
						} else {
							hookEntity.segmentHandler.update(hookPos, playerpos, hookEntity.r, false);
						}

						// vectors along rope
						Vec anchor = hookEntity.segmentHandler.getClosest(hookPos);
						double distToAnchor = hookEntity.segmentHandler.getDistToAnchor();
						double remaininglength = motor ? Math.max(this.custom.maxlen, hookEntity.r) - distToAnchor : hookEntity.r - distToAnchor;

						Vec oldspherevec = playerpos.sub(anchor);
						Vec spherevec = oldspherevec.changeLen(remaininglength);
						Vec spherechange = spherevec.sub(oldspherevec);

						if (spherevec.length() < min_spherevec_dist) {min_spherevec_dist = spherevec.length();}

						averagemotiontowards.add_ip(this.toLocal(spherevec).changeLen(-1));

						if (motor) {
							hookEntity.r = distToAnchor + oldspherevec.length();
						}

						// a light VS ship yields to the rope instead of anchoring the player: no
						// constraint on the player (the server-side rope tension moves the ship)
						double shipMass = hookEntity.getSyncedShipMass();
						boolean lightShip = hookEntity.getAttachedShipId() != -1 && shipMass > 0
								&& shipMass < GrappleConfig.getConf().grapplinghook.other.hook_ship_anchor_mass;

						// a hook on a moving ship can outrun the player: pay out rope (up to maxlen)
						// instead of instantly snapping
						if (hookEntity.getAttachedShipId() != -1 && !lightShip
								&& oldspherevec.length() - remaininglength > GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer) {
							double needed = distToAnchor + oldspherevec.length();
							double cap = Math.max(this.maxLen, hookEntity.r);
							double newR = Math.min(needed, cap);
							if (newR > hookEntity.r) {
								hookEntity.r = newR;
								remaininglength = motor ? Math.max(this.custom.maxlen, hookEntity.r) - distToAnchor : hookEntity.r - distToAnchor;
								spherevec = oldspherevec.changeLen(remaininglength);
								spherechange = spherevec.sub(oldspherevec);
							}
						}

						// snap to rope length
						if (lightShip) {
							// the rope stretches instead of constraining; only give up when the
							// stretch is far beyond anything the tension could recover from
							if (oldspherevec.length() - remaininglength > GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer * 3) {
								this.unattach();
								this.updateServerPos();
								return;
							}
						} else if (oldspherevec.length() < remaininglength) {
						} else {
							if (oldspherevec.length() - remaininglength > GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer) {
								// if rope is too long, the rope snaps

								this.unattach();

								this.updateServerPos();
								return;
							} else {
								additionalmotion = this.toLocal(spherechange);
							}
						}

						double dist = oldspherevec.length();

						this.calcTaut(dist, hookEntity);

						// handle keyboard input (jumping and climbing)
						if (entity instanceof Player) {
							Player player = (Player) entity;
							boolean isjumping = false;
							if (lastStepOfTick) { // a press jumps right before the move (see lastStepOfTick)
								isjumping = ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach);
								isjumping = isjumping && !playerJump; // only jump once when key is first pressed
								playerJump = ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach);
							}
							if (isjumping) {
								// jumping
								if (onGroundTimer > 0) { // on ground: jump normally

								} else {
									double timer = ClientProxyInterface.proxy.getTimeSinceLastRopeJump(this.entity.level());
									if (timer > GrappleConfig.getConf().grapplinghook.other.rope_jump_cooldown_s * 20.0) {
										if (player.getAbilities().mayfly && !player.getAbilities().flying) {
											// might be half of a double-tap to start flying: wait it out
											this.pendingRopeJumpTicks = 8;
										} else {
											doJump = true;
											jumpSpeed = this.getJumpPower(player, this.toLocal(spherevec), hookEntity);
										}
									}
								}
							}
							if (deferredJumpReady) {
								// no flight toggle arrived within the double-tap window: jump now
								doJump = true;
								jumpSpeed = this.getJumpPower(player, this.toLocal(spherevec), hookEntity);
							}
							if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_slow)) {
								// slow down (across the ground; in zero gravity every way, as nothing
								// else would ever stop a swing up or down)
								Vec motiontorwards = this.toLocal(spherevec).changeLen(-0.1);
								if (!this.weightless) {
									motiontorwards = new Vec(motiontorwards.x, 0, motiontorwards.z);
								}
								if (motion.dot(motiontorwards) < 0) {
									motion.add_ip(motiontorwards);
								}

								Vec newmotion = dampenMotion(motion, motiontorwards);
								motion = this.weightless ? newmotion : new Vec(newmotion.x, motion.y, newmotion.z);

							}
							if ((ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climb) || ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climbup) || ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climbdown)) && !motor) {
								isClimbing = true;
								// anchor above the eye; in zero gravity there is no above, so any rope climbs
								if (this.weightless || this.toLocal(anchor.sub(playerpos)).y > 0) {
									// climb up/down rope
									double climbup = 0;
									if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climb)) {
										climbup = playerForward;
										if (ClientProxyInterface.proxy.isMovingSlowly(this.entity)) {
											climbup = climbup / 0.3D;
										}
										if (climbup > 1) {climbup = 1;} else if (climbup < -1) {climbup = -1;}
									}
									else if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climbup)) { climbup = 1.0; }
									else if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_climbdown)) { climbup = -1.0; }
									if (climbup != 0) {
										if (dist + distToAnchor < maxLen || climbup > 0 || maxLen == 0) {
											hookEntity.r = dist + distToAnchor;
											hookEntity.r -= climbup*GrappleConfig.getConf().grapplinghook.other.climb_speed;
											// never climb the eye right onto the hook: at near-zero
											// remaining rope the sphere constraint fights block/ship
											// collision and the camera jitters (clipping into VS hulls)
											if (hookEntity.r < distToAnchor + 1.0) {
												hookEntity.r = distToAnchor + 1.0;
											}

											Vec additionalmovementdown = this.toLocal(spherevec).changeLen(-climbup * GrappleConfig.getConf().grapplinghook.other.climb_speed);
											if (this.weightless) {
												// nothing lowers a weightless player: climbing down pushes out along the rope
												if (climbup < 0) {
													additionalmotion.add_ip(additionalmovementdown);
												}
											} else {
												additionalmovementdown = additionalmovementdown.proj(new Vec(0,1,0));
												if (additionalmovementdown.y < 0) {
													additionalmotion.add_ip(additionalmovementdown);
												}
											}
										}
									}
								}
							}
						}
						if (dist + distToAnchor < 2) {
							close = true;
						}

						// swing along max rope length (a light ship yields instead of anchoring)
						if (!lightShip && anchor.sub(playerpos.add(this.toWorld(motion))).length() > remaininglength) { // moving away
							motion = motion.removeAlong(this.toLocal(spherevec));
						}

						this.syncRopeLength(hookEntity);
					}
					averagemotiontowards.changeLen_ip(1);

					Vec facing = this.toLocal(new Vec(entity.getLookAngle()).normalize());

					// Motor
					if (motor) {
						boolean dopull = true;

						// if only one rope is pulling and not oneropepull, disable motor
						if (this.custom.doublehook && this.grapplehookEntities.size() == 1) {
							boolean isdouble = true;
							for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
								if (!hookEntity.isDouble) {
									isdouble = false;
								}
							}
							if (isdouble && !this.custom.oneropepull) {
								dopull = false;
							}
						}

						Vec totalpull = new Vec(0, 0, 0);

						double accel = this.custom.motoracceleration / this.grapplehookEntities.size();

						double minabssidewayspull = 999;

						boolean firstpull = true;
						boolean pullispositive = true;
						boolean pullissameway = true;

						// set all motors to maximum pull and precalculate some stuff for smart motor / smart double motor
						for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
							Vec hookPos = Vec.positionVec(hookEntity);//this.getPositionVector();
							Vec anchor = hookEntity.segmentHandler.getClosest(hookPos);
							Vec spherevec = this.toLocal(playerpos.sub(anchor));
							Vec pull = spherevec.mult(-1);

							hookEntity.pull = accel;

							totalpull.add_ip(pull.changeLen(accel));

							pull.changeLen_ip(hookEntity.pull);

							// precalculate some stuff for smart double motor
							// For smart double motor: the motors should pull left and right equally
							// one side will be less able to pull to its side due to the angle
							// therefore the other side should slow down in order to match and have both sides pull left/right equally
							// the amount each should pull (the lesser of the two) is minabssidewayspull
							if (pull.dot(facing) > 0 || this.custom.pullbackwards) {
								if (this.custom.smartdoublemotor && this.grapplehookEntities.size() > 1) {
									Vec facingxy = new Vec(facing.x, 0, facing.z);
									Vec facingside = facingxy.cross(new Vec(0, 1, 0)).normalize();
//									vec pullxy = new vec(pull.x, 0, pull.z);
									Vec sideways = pull.proj(facingside); // pullxy.removealong(facing);
									Vec currentsideways = motion.proj(facingside);
									sideways.add_ip(currentsideways);
									double sidewayspull = sideways.dot(facingside); // facingxy.cross(sideways).y;

									if (Math.abs(sidewayspull) < minabssidewayspull) {
										minabssidewayspull = Math.abs(sidewayspull);
									}

									if (firstpull) {
										firstpull = false;
										pullispositive = (sidewayspull >= 0);
									} else {
										if (pullispositive != (sidewayspull >= 0)) {
											pullissameway = false;
										}
									}
								}

							}
						}

						// Smart double motor - calculate the speed each motor should pull at
						if (this.custom.smartdoublemotor && this.grapplehookEntities.size() > 1) {
							totalpull = new Vec(0, 0, 0);

							for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
								Vec hookPos = Vec.positionVec(hookEntity);
								Vec anchor = hookEntity.segmentHandler.getClosest(hookPos);
								Vec spherevec = this.toLocal(playerpos.sub(anchor));
								Vec pull = spherevec.mult(-1);
								pull.changeLen_ip(hookEntity.pull);

								if (pull.dot(facing) > 0 || this.custom.pullbackwards) {
									Vec facingxy = new Vec(facing.x, 0, facing.z);
									Vec facingside = facingxy.cross(new Vec(0, 1, 0)).normalize();
									Vec sideways = pull.proj(facingside);
									Vec currentsideways = motion.proj(facingside);
									sideways.add_ip(currentsideways);
									double sidewayspull = sideways.dot(facingside);

									if (pullissameway) {
										// only 1 rope pulls
										if (Math.abs(sidewayspull) > minabssidewayspull+0.05) {
											hookEntity.pull = 0;
										}
									} else {
										hookEntity.pull = hookEntity.pull * minabssidewayspull / Math.abs(sidewayspull);
									}
									totalpull.add_ip(pull.changeLen(hookEntity.pull));
								} else {
									if (hookEntity.isDouble) {
										if (!this.custom.oneropepull) {
											dopull = false;
										}
									}
								}
							}
						}

						// smart motor - angle of motion = angle facing
						// match angle (the ratio of pulling upwards to pulling sideways)
						// between the motion (after pulling and gravity) vector and the facing vector
						// if double hooks, all hooks are scaled by the same amount (to prevent pulling to the left/right)
						double pullmult = 1;
						if (this.custom.smartmotor && totalpull.y > 0 && !(this.onGroundTimer > 0 || entity.onGround())) {
							Vec pullxzvector = new Vec(totalpull.x, 0, totalpull.z);
							double pullxz = pullxzvector.length();
							double motionxz = motion.proj(pullxzvector).dot(pullxzvector.normalize());
							double facingxz = facing.proj(pullxzvector).dot(pullxzvector.normalize());

							pullmult = (facingxz * (motion.y + gravity.y) - motionxz * facing.y)/(facing.y * pullxz - facingxz * totalpull.y); // (gravity.y * facingxz) / (facing.y * pullxz - facingxz * totalpull.y);

							if ((facing.y * pullxz - facingxz * totalpull.y) == 0) {
								// division by zero
								pullmult = 9999;
							}

							double pulll = pullmult * totalpull.length();

							if (pulll > this.custom.motoracceleration) {
								pulll = this.custom.motoracceleration;
							}
							if (pulll < 0) {
								pulll = 0;
							}

							pullmult = pulll / totalpull.length();
						}

						// Prevent motor from moving too fast (motormaxspeed)
						if (this.motion.dot(totalpull) > 0) {
							if (this.motion.proj(totalpull).length() + totalpull.mult(pullmult).length() > this.custom.motormaxspeed) {
								pullmult = (this.custom.motormaxspeed - this.motion.proj(totalpull).length()) / totalpull.length();
								if (pullmult < 0) {
									pullmult = 0;
								}
							}
						}

						// sideways dampener
						if (this.custom.motordampener && totalpull.length() != 0) {
							motion = dampenMotion(motion, totalpull);
						}

						// actually pull with the motor
						if (dopull) {
							for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
								Vec hookPos = Vec.positionVec(hookEntity);//this.getPositionVector();
								Vec anchor = hookEntity.segmentHandler.getClosest(hookPos);
								Vec spherevec = this.toLocal(playerpos.sub(anchor));
								Vec pull = spherevec.mult(-1);
								pull.changeLen_ip(hookEntity.pull * pullmult);

								if (pull.dot(facing) > 0 || this.custom.pullbackwards) {
									if (hookEntity.pull > 0) {
										motion.add_ip(pull);
									}
								}
							}
						}

						// if player is at the destination, slow down
						if (close && !(this.grapplehookEntities.size() > 1)) {
							if (entity.horizontalCollision || entity.verticalCollision || entity.onGround()) {
								motion.mult_ip(0.6);
							}
						}
					}

					// forcefield
					if (this.custom.repel) {
						Vec blockpush = this.toLocal(checkRepel(playerpos, entity.level()));
						blockpush.mult_ip(this.custom.repelforce * 0.5);
						double lift = repelLift(this.entity);
						blockpush = new Vec(blockpush.x*0.5, blockpush.y*lift, blockpush.z*0.5);
						this.motion.add_ip(blockpush);
					}

					// rocket
					if (this.custom.rocket) {
						this.motion.add_ip(this.rocket(entity));
					}

					// WASD movement
					if (!doJump && !isClimbing) {
						applyPlayerMovement();
					}

					// jump
					if (doJump) {
						if (jumpSpeed <= 0) {
							jumpSpeed = 0;
						}
						if (jumpSpeed > GrappleConfig.getConf().grapplinghook.other.rope_jump_power) {
							jumpSpeed = GrappleConfig.getConf().grapplinghook.other.rope_jump_power;
						}
						this.doJump(entity, jumpSpeed, averagemotiontowards, min_spherevec_dist);
						ClientProxyInterface.proxy.resetRopeJumpTime(this.entity.level());
						return;
					}

					// now to actually apply everything to the player
					Vec newmotion = motion.add(additionalmotion);

					if (Double.isNaN(newmotion.x) || Double.isNaN(newmotion.y) || Double.isNaN(newmotion.z)) {
						newmotion = new Vec(0, 0, 0);
						motion = new Vec(0, 0, 0);
						GrappleMod.LOGGER.error("Grapple controller motion is NaN, resetting");
					}

					this.writeMotion(newmotion);

					this.updateServerPos();
				}
			}
		}
	}

	public void calcTaut(double dist, GrapplehookEntity hookEntity) {
		if (hookEntity != null) {
			if (dist < hookEntity.r) {
				double taut = 1 - ((hookEntity.r - dist) / 5);
				if (taut < 0) {
					taut = 0;
				}
				hookEntity.taut = taut;
			} else {
				hookEntity.taut = 1;
			}
		}
	}

	public void normalCollisions(boolean sliding) {
		// stop if collided with object
		if (entity.horizontalCollision) {
			if (entity.getDeltaMovement().x == 0) {
				if (!sliding || this.tryStepUp(new Vec(this.motion.x, 0, 0))) {
					this.motion.x = 0;
				}
			}
			if (entity.getDeltaMovement().z == 0) {
				if (!sliding || this.tryStepUp(new Vec(0, 0, this.motion.z))) {
					this.motion.z = 0;
				}
			}
		}

		if (sliding && !entity.horizontalCollision) {
			Vec moved = this.movedLastTick();
			if (moved.x == 0) {
				this.motion.x = 0;
			}
			if (moved.z == 0) {
				this.motion.z = 0;
			}
		}

		if (entity.verticalCollision) {
			if (entity.onGround()) {
				if (!sliding && Minecraft.getInstance().options.keyJump.isDown()) {
					this.motion.y = entity.getDeltaMovement().y;
				} else {
					if (this.motion.y < 0) {
						this.motion.y = 0;
					}
				}
			} else {
				if (this.motion.y > 0) {
					if (this.movedLastTick().y == 0) {
						this.motion.y = 0;
					}
				}
			}
		}
	}

	public boolean tryStepUp(Vec collisionmotion) {
		if (collisionmotion.length() == 0) {return false;}
		Vec moveoffset = this.toWorld(collisionmotion.changeLen(0.05).add(0, entity.getStepHeight()+0.01, 0));
		Iterable<VoxelShape> collisions = this.entity.level().getCollisions(this.entity, this.entity.getBoundingBox().move(moveoffset.x, moveoffset.y, moveoffset.z));
		if (!collisions.iterator().hasNext()) {
			if (!this.entity.onGround()) {
				Vec pos = Vec.positionVec(entity);
				pos.add_ip(moveoffset);
				pos.setPos(entity);
				entity.xOld = pos.x;
				entity.yOld = pos.y;
				entity.zOld = pos.z;
			}
			entity.horizontalCollision = false;
			return false;
		}
		return true;
	}

	boolean prevOnGround = false;

	public void normalGround(boolean sliding) {
		if (entity.onGround()) {
			onGroundTimer = maxOnGroundTimer;
		} else {
			if (this.onGroundTimer > 0) {
				onGroundTimer--;
			}
		}
		if (entity.onGround() || onGroundTimer > 0) {
			if (!sliding) {
				this.motion = Vec.motionVec(entity);
				if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.McKeys.keyBindJump)) {
					this.motion.y += 0.05;
				}
			}
		}
		prevOnGround = entity.onGround();
	}

	private double getJumpPower(Entity player, double jumppower) {
		double maxjump = GrappleConfig.getConf().grapplinghook.other.rope_jump_power;
		if (onGroundTimer > 0) { // on ground: jump normally
			onGroundTimer = 20;
			return 0;
		}
		if (player.onGround()) {
			jumppower = 0;
		}
		if (player.horizontalCollision || player.verticalCollision) {
			jumppower = maxjump;
		}
		if (jumppower < 0) {
			jumppower = 0;
		}

		return jumppower;
	}

	public void doJump(Entity player, double jumppower, Vec averagemotiontowards, double min_spherevec_dist) {
		if (jumppower > 0) {
			if (GrappleConfig.getConf().grapplinghook.other.rope_jump_at_angle && min_spherevec_dist > 1) {
				motion.add_ip(averagemotiontowards.changeLen(jumppower));
			} else {
				if (jumppower > player.getDeltaMovement().y + jumppower) {
					motion.y = jumppower;
				} else {
					motion.y += jumppower;
				}
			}
			this.writeMotion(motion);
		}

		this.unattach();
		if (jumppower > 0) {
			this.claimImpulse();
		}

		this.updateServerPos();
	}

	/**
	 * How much harder a forcefield pushes up than sideways: 2 against 0.5, to hold the player up
	 * against gravity; as gravity weakens it evens out, and in zero gravity every way is the same.
	 */
	public static double repelLift(Entity entity) {
		return 0.5 + 1.5 * Math.min(1.0, Gravity.strength(entity));
	}

	public double getJumpPower(Entity player, Vec spherevec, GrapplehookEntity hookEntity) {
		if (this.weightless) {
			// no gravity: a rope jump would be a kick "up" that never ends; the key just lets go
			return 0;
		}
		double maxjump = GrappleConfig.getConf().grapplinghook.other.rope_jump_power;
		Vec jump = new Vec(0, maxjump, 0);
		if (spherevec != null && !GrappleConfig.getConf().grapplinghook.other.rope_jump_at_angle) {
			jump = jump.proj(spherevec);
		}
		double jumppower = jump.y;

		if (spherevec != null && spherevec.y > 0) {
			jumppower = 0;
		}
		if ((hookEntity != null) && hookEntity.r < 1 && this.toLocal(Vec.positionVec(hookEntity).sub(Vec.positionVec(player))).y > 0) { // hook above the player
			jumppower = maxjump;
		}

		jumppower = this.getJumpPower(player, jumppower);

		double current_speed = GrappleConfig.getConf().grapplinghook.other.rope_jump_at_angle ? -motion.distAlong(spherevec) : motion.y;
		if (current_speed > 0) {
			jumppower = jumppower - current_speed;
		}

		if (jumppower < 0) {jumppower = 0;}

		return jumppower;
	}

	public Vec dampenMotion(Vec motion, Vec forward) {
		Vec newmotion = motion.proj(forward);
		double dampening = 0.05;
		return newmotion.mult(dampening).add(motion.mult(1-dampening));
	}

	public void updateServerPos() {
		CommonSetup.network.sendToServer(new PlayerMovementMessage(this.entityId, this.entity.position().x, this.entity.position().y, this.entity.position().z, this.entity.getDeltaMovement().x, this.entity.getDeltaMovement().y, this.entity.getDeltaMovement().z));
	}

	// Vector stuff:

	public void receiveGrappleDetach() {
		this.unattach();
	}

	/** An ender-staff launch: a world-space kick (the look direction) added to the motion. */
	public void receiveEnderLaunch(double x, double y, double z) {
		this.motion.add_ip(this.toLocal(new Vec(x, y, z)));
		this.writeMotion(this.motion);
		this.claimImpulse();
	}

	public void applyAirFriction() {
		double dragforce = 1 / 200F;
		if (this.entity.isInWater() || this.entity.isInLava()) {
			dragforce = 1 / 4F;
		}

		double vel = this.motion.length();
		dragforce = vel * dragforce;

		Vec airfric = new Vec(this.motion.x, this.motion.y, this.motion.z);
		airfric.changeLen_ip(-dragforce);
		this.motion.add_ip(airfric);
	}

	public void applyPlayerMovement() {
		motion.add_ip(this.playerMovement.changeLen(0.015 + motion.length() * 0.01).mult(this.playerMovementMult));//0.02 * playermovementmult));
	}

	public void addHookEntity(GrapplehookEntity hookEntity) {
		this.grapplehookEntities.add(hookEntity);
		hookEntity.r = ((GrapplehookEntity) hookEntity).segmentHandler.getDist(Vec.positionVec(hookEntity), Vec.eyePos(entity));
		this.grapplehookEntityIds.add(hookEntity.getId());
	}

	public double repelMaxPush = 0.3;

	// repel stuff (a world-space push; "up" is the player's own)
	public Vec checkRepel(Vec p, Level w) {

		p = p.add(this.toWorld(new Vec(0.0, 0.75, 0.0)));
		Vec v = new Vec(0, 0, 0);

		double t = (1.0 + Math.sqrt(5.0)) / 2.0;

		BlockPos pos = new BlockPos(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
		if (hasBlock(pos, w)) {
			v.add_ip(this.toWorld(new Vec(0, 1, 0)));
		} else {
			v.add_ip(vecDist(p, new Vec(-1,  t,  0), w));
			v.add_ip(vecDist(p, new Vec( 1,  t,  0), w));
			v.add_ip(vecDist(p, new Vec(-1, -t,  0), w));
			v.add_ip(vecDist(p, new Vec( 1, -t,  0), w));
			v.add_ip(vecDist(p, new Vec( 0, -1,  t), w));
			v.add_ip(vecDist(p, new Vec( 0,  1,  t), w));
			v.add_ip(vecDist(p, new Vec( 0,  1,  t), w));
			v.add_ip(vecDist(p, new Vec( 0, -1, -t), w));
			v.add_ip(vecDist(p, new Vec( 0,  1, -t), w));
			v.add_ip(vecDist(p, new Vec( t,  0, -1), w));
			v.add_ip(vecDist(p, new Vec( t,  0,  1), w));
			v.add_ip(vecDist(p, new Vec(-t,  0, -1), w));
			v.add_ip(vecDist(p, new Vec(-t,  0,  1), w));
		}

		if (v.length() > repelMaxPush) {
			v.changeLen_ip(repelMaxPush);
		}

		return v;
	}

	public Vec vecDist(Vec p, Vec v, Level w) {
		for (double i = 0.5; i < 10; i += 0.5) {
			Vec v2 = v.changeLen(i);
			BlockPos pos = new BlockPos(Mth.floor(p.x + v2.x), Mth.floor(p.y + v2.y), Mth.floor(p.z + v2.z));
			if (hasBlock(pos, w)) {
				Vec v3 = new Vec(pos.getX() + 0.5 - p.x, pos.getY() + 0.5 - p.y, pos.getZ() + 0.5 - p.z);
				v3.changeLen_ip(-1 / Math.pow(v3.length(), 2));
				return v3;
			}
		}

		return new Vec(0, 0, 0);
	}

	public boolean hasBlock(BlockPos pos, Level w) {
		BlockState blockstate = w.getBlockState(pos);
//    	Block b = blockstate.getBlock();
		if (blockstate.isAir()) {
			return false;
		}

		return true;
	}

	public void receiveGrappleDetachHook(int hookid) {
		if (this.grapplehookEntityIds.contains(hookid)) {
			this.grapplehookEntityIds.remove(hookid);
		} else {
			GrappleMod.LOGGER.warn("Controller received hook detach, but hook id not in grapplehookEntityIds");
		}

		GrapplehookEntity hookToRemove = null;
		for (GrapplehookEntity hookEntity : this.grapplehookEntities) {
			if (hookEntity.getId() == hookid) {
				hookToRemove = hookEntity;
				break;
			}
		}

		if (hookToRemove != null) {
			this.grapplehookEntities.remove(hookToRemove);
		} else {
			GrappleMod.LOGGER.warn("Controller received hook detach, but hook entity not in grapplehookEntities");
		}
	}

	public boolean rocket_key = false;
	public double rocket_on = 0;
	public Vec rocket(Entity entity) {
		if (ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_rocket)) {
			rocket_on = ClientProxyInterface.proxy.getRocketFunctioning();
			double rocket_force = this.custom.rocket_force * 0.225 * rocket_on;
			double yaw = entity.getYRot();
			double pitch = -entity.getXRot();
			pitch += this.custom.rocket_vertical_angle;
			Vec force = new Vec(0, 0, rocket_force);
			force = force.rotatePitch(Math.toRadians(pitch));
			force = force.rotateYaw(Math.toRadians(yaw));

			rocket_key = true;
			return force;
		}
		rocket_key = false;
		rocket_on = 0F;
		return new Vec(0,0,0);
	}

	boolean isOnWall = false;
	Vec wallDirection = null;
	BlockHitResult wallrunRaytraceResult = null;

	public Vec getNearbyWall(Vec tryfirst, Vec trysecond, double extra) {
		float entitywidth = this.entity.getBbWidth();

		for (Vec direction : new Vec[] {tryfirst, trysecond, tryfirst.mult(-1), trysecond.mult(-1)}) {
			BlockHitResult raytraceresult = GrapplemodUtils.rayTraceBlocks(this.entity.level(), Vec.positionVec(this.entity), Vec.positionVec(this.entity).add(this.toWorld(direction.changeLen(entitywidth/2 + extra))));
			if (raytraceresult != null) {
				wallrunRaytraceResult = raytraceresult;
				return direction;
			}
		}

		return null;
	}

	public Vec getWallDirection() {
		Vec tryfirst = new Vec(0, 0, 0);
		Vec trysecond = new Vec(0, 0, 0);

		if (Math.abs(this.motion.x) > Math.abs(this.motion.z)) {
			tryfirst.x = (this.motion.x > 0) ? 1 : -1;
			trysecond.z = (this.motion.z > 0) ? 1 : -1;
		} else {
			tryfirst.z = (this.motion.z > 0) ? 1 : -1;
			trysecond.x = (this.motion.x > 0) ? 1 : -1;
		}

		return getNearbyWall(tryfirst, trysecond, 0.05);
	}

	public Vec getCorner(int cornernum, Vec facing, Vec sideways) {
		Vec corner = new Vec(0,0,0);
		if (cornernum / 2 == 0) {
			corner.add_ip(facing);
		} else {
			corner.add_ip(facing.mult(-1));
		}
		if (cornernum % 2 == 0) {
			corner.add_ip(sideways);
		} else {
			corner.add_ip(sideways.mult(-1));
		}
		return corner;
	}

	public boolean wallNearby(double dist) {
		float entitywidth = this.entity.getBbWidth();
		Vec v1 = new Vec(entitywidth/2 + dist, 0, 0);
		Vec v2 = new Vec(0, 0, entitywidth/2 + dist);

		for (int i = 0; i < 4; i++) {
			Vec corner1 = getCorner(i, v1, v2);
			Vec corner2 = getCorner((i + 1) % 4, v1, v2);

			BlockHitResult raytraceresult = GrapplemodUtils.rayTraceBlocks(this.entity.level(), Vec.positionVec(this.entity).add(this.toWorld(corner1)), Vec.positionVec(this.entity).add(this.toWorld(corner2)));
			if (raytraceresult != null) {
				return true;
			}
		}

		return false;
	}

	int ticksSinceLastWallrunSoundEffect = 0;

	public boolean isWallRunning() {
		double current_speed = Math.sqrt(Math.pow(this.motion.x, 2) + Math.pow(this.motion.z,  2));
		if (current_speed < GrappleConfig.getConf().enchantments.wallrun.wallrun_min_speed) {
			isOnWall = false;
			return false;
		}

		if (isOnWall) {
			ClientProxyInterface.proxy.setWallrunTicks(ClientProxyInterface.proxy.getWallrunTicks()+1);
		}

		if (ClientProxyInterface.proxy.getWallrunTicks() < GrappleConfig.getConf().enchantments.wallrun.max_wallrun_time * 40) {
			if (!(playerSneak)) {
				// continue wallrun (not in zero gravity, where there is no falling to run against)
				if (isOnWall && !this.entity.onGround() && this.entity.horizontalCollision && !Gravity.isWeightless(this.entity)) {
					if (entity instanceof LivingEntity && ((LivingEntity) entity).onClimbable()) {
						return false;
					}
					return true;
				}

				// start wallrun
				if (ClientProxyInterface.proxy.isWallRunning(this.entity, this.motion)) {
					isOnWall = true;
					return true;
				}
			}

			isOnWall = false;
		}

		if (ClientProxyInterface.proxy.getWallrunTicks() > 0 && (this.entity.onGround() || (!this.entity.horizontalCollision && !wallNearby(0.2)))) {
			ticksSinceLastWallrunSoundEffect = 0;
		}

		return false;
	}

	public boolean applyWallrun() {
		boolean wallrun = this.isWallRunning();

		if (playerJump) {
			if (wallrun) {
				return false;
			} else {
				playerJump = false;
			}
		}

		if (wallrun && !ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach)) {

			Vec wallside = this.getWallDirection();
			if (wallside != null) {
				this.wallDirection = wallside;
			}

			if (this.wallDirection == null) {
				return false;
			}

			if (!playerJump) {
				motion.y = 0;
			}

			// drag
			double dragforce = GrappleConfig.getConf().enchantments.wallrun.wallrun_drag;

			double vel = this.motion.length();

			if (dragforce > vel) {dragforce = vel;}

			Vec wallfric = new Vec(this.motion);
			if (wallside != null) {
				wallfric.removeAlong(wallside);
			}
			wallfric.changeLen_ip(-dragforce);
			this.motion.add_ip(wallfric);

			ticksSinceLastWallrunSoundEffect++;
			if (ticksSinceLastWallrunSoundEffect > GrappleConfig.getClientConf().sounds.wallrun_sound_effect_time_s * 20 * GrappleConfig.getConf().enchantments.wallrun.wallrun_max_speed / (vel + 0.00000001)) {
				if (wallrunRaytraceResult != null) {
					BlockPos blockpos = wallrunRaytraceResult.getBlockPos();

					BlockState blockState = this.entity.level().getBlockState(blockpos);
					Block blockIn = blockState.getBlock();

					SoundType soundtype = blockIn.getSoundType(blockState, world, blockpos, this.entity);

					this.entity.playSound(soundtype.getStepSound(), soundtype.getVolume() * 0.30F * GrappleConfig.getClientConf().sounds.wallrun_sound_volume, soundtype.getPitch());
					ticksSinceLastWallrunSoundEffect = 0;
				}
			}
		}

		// jump (a press jumps on the tick's last step, right before the move: see lastStepOfTick)
		boolean isjumping = false;
		if (lastStepOfTick) {
			isjumping = ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach) && isOnWall;
			isjumping = isjumping && !playerJump; // only jump once when key is first pressed
			playerJump = ClientProxyInterface.proxy.isKeyDown(ClientProxyInterface.GrappleKeys.key_jumpanddetach) && isOnWall;
		}
		if (isjumping && wallrun) {
			ClientProxyInterface.proxy.setWallrunTicks(0);
			Vec jump = new Vec(0, GrappleConfig.getConf().enchantments.wallrun.wall_jump_up, 0);
			if (wallDirection != null) {
				jump.add_ip(wallDirection.mult(-GrappleConfig.getConf().enchantments.wallrun.wall_jump_side));
			}
			motion.add_ip(jump);

			wallrun = false;
			this.claimImpulse();

			ClientProxyInterface.proxy.playWallrunJumpSound(entity);
		}

		return wallrun;
	}

	public Vec wallrunPressAgainstWall() {
		// press against wall
		if (this.wallDirection != null) {
			return this.wallDirection.changeLen(0.05);
		}
		return new Vec(0,0,0);
	}

	public void doubleJump() {
		if (-this.motion.y > GrappleConfig.getConf().enchantments.doublejump.dont_doublejump_if_falling_faster_than) {
			return;
		}
		if (Gravity.isWeightless(this.entity)) {
			return; // a jump off nothing in zero gravity would drift "up" for good
		}
		if (this.motion.y < 0 && !GrappleConfig.getConf().enchantments.doublejump.doublejump_relative_to_falling) {
			this.motion.y = 0;
		}
		this.motion.y += GrappleConfig.getConf().enchantments.doublejump.doublejumpforce;
		this.writeMotion(this.motion);
		this.claimImpulse();
		this.entity.resetFallDistance();
	}

	public void applySlidingFriction() {
		double dragforce = GrappleConfig.getConf().enchantments.slide.sliding_friction;

		if (dragforce > this.motion.length()) {dragforce = this.motion.length(); };

		Vec airfric = new Vec(this.motion.x, this.motion.y, this.motion.z);
		airfric.changeLen_ip(-dragforce);
		this.motion.add_ip(airfric);
	}

	public void slidingJump() {
		this.motion.y = GrappleConfig.getConf().enchantments.slide.slidingjumpforce;
		this.claimImpulse();
	}
}