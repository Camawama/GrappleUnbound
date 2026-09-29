package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.client.ClientProxyInterface;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.config.GrappleConfigUtils;
import net.camacraft.grappleunbound.integrations.ValkyrienSkiesIntegration;
import net.camacraft.grappleunbound.network.GrappleAttachMessage;
import net.camacraft.grappleunbound.network.GrappleAttachPosMessage;
import net.camacraft.grappleunbound.network.GrappleDetachMessage;
import net.camacraft.grappleunbound.server.ServerControllerManager;
import net.camacraft.grappleunbound.utils.GrappleCustomization;
import net.camacraft.grappleunbound.utils.GrapplemodUtils;
import net.camacraft.grappleunbound.utils.Vec;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.minigame.MatchPolicies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nonnull;
import java.util.HashMap;

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

public class GrapplehookEntity extends ThrowableItemProjectile implements IEntityAdditionalSpawnData {
	public GrapplehookEntity(EntityType<? extends GrapplehookEntity> type, Level world) {
		super(type, world);

		this.segmentHandler = new SegmentHandler(this.level(), this, Vec.positionVec(this), Vec.positionVec(this));
		this.customization = new GrappleCustomization();
	}

	public GrapplehookEntity(Level world, LivingEntity shooter,
							 boolean righthand, GrappleCustomization customization, boolean isdouble) {
		super(CommonSetup.grapplehookEntityType.get(), Vec.eyePos(shooter).x, Vec.eyePos(shooter).y, Vec.eyePos(shooter).z, world);

		this.shootingEntity = shooter;
		this.shootingEntityID = this.shootingEntity.getId();

		this.isDouble = isdouble;

		Vec pos = Vec.eyePos(this.shootingEntity);

		this.segmentHandler = new SegmentHandler(this.level(), this, new Vec(pos), new Vec(pos));

		this.customization = customization;
		this.r = customization.maxlen;

		this.rightHand = righthand;
	}

	public Entity shootingEntity = null;
	public int shootingEntityID;

	private boolean firstAttach = false;
	public Vec thisPos;

	public boolean rightHand = true;

	public boolean attached = false;

	public double pull;

	public double taut = 1;

	public boolean ignoreFrustumCheck = true;

	public boolean isDouble = false;

	public double r;

	public SegmentHandler segmentHandler = null;

	public GrappleCustomization customization = null;

	// magnet attract
	public Vec prevPos = null;
	public boolean foundBlock = false;
	public boolean wasInAir = false;
	public BlockPos magnetBlock = null;

	public Vec attach_dir = null;

	// Valkyrien Skies: ticks in a row the attached ship failed to resolve (unloaded/deleted)
	private static final int SHIP_UNRESOLVED_DETACH_TICKS = 60;
	private int shipUnresolvedTicks = 0;

	// lead-style entity attachment: the hook clings to this entity and follows it (server only)
	public Entity attachedEntity = null;
	private static final double ENTITY_ATTACH_MIN_ROPE = 2.0;
	// equivalent hook mass (kg) used to convert impact speed into a ship impulse
	private static final double HOOK_IMPULSE_MASS_KG = 15.0;

	// synced so every client can pin the hook onto the (already interpolated) target entity and
	// compute rope sag locally, and so a grappled player's client can run its own swing physics
	private static final EntityDataAccessor<Integer> DATA_ATTACHED_ENTITY =
			SynchedEntityData.defineId(GrapplehookEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_ROPE_LENGTH =
			SynchedEntityData.defineId(GrapplehookEntity.class, EntityDataSerializers.FLOAT);
	// mass (kg) of the attached VS ship (0 = none/unknown): the owning client decides from this
	// whether the rope constrains the player or the ship yields instead
	private static final EntityDataAccessor<Float> DATA_SHIP_MASS =
			SynchedEntityData.defineId(GrapplehookEntity.class, EntityDataSerializers.FLOAT);

	public int getSyncedAttachedEntityId() {
		return this.entityData.get(DATA_ATTACHED_ENTITY);
	}

	public double getSyncedRopeLength() {
		return this.entityData.get(DATA_ROPE_LENGTH);
	}

	public double getSyncedShipMass() {
		return this.entityData.get(DATA_SHIP_MASS);
	}

	public void setSyncedRopeLength(double r) {
		this.entityData.set(DATA_ROPE_LENGTH, (float) r);
	}

	/** Ship id this hook is attached to, or -1. Safe to call without VS installed. */
	public long getAttachedShipId() {
		return this.getPersistentData().contains("vs_ship_id") ? this.getPersistentData().getLong("vs_ship_id") : -1;
	}

	@Override
	public void writeSpawnData(FriendlyByteBuf data)
	{
		data.writeInt(this.shootingEntity != null ? this.shootingEntity.getId() : 0);
		data.writeBoolean(this.rightHand);
		data.writeBoolean(this.isDouble);
		if (this.customization == null) {
			GrappleMod.LOGGER.error("Customization is null when writing spawn data");
		}
		this.customization.writeToBuf(data);
        
        if (this.getPersistentData().contains("vs_ship_id")) {
            data.writeLong(this.getPersistentData().getLong("vs_ship_id"));
            data.writeDouble(this.getPersistentData().getDouble("vs_local_x"));
            data.writeDouble(this.getPersistentData().getDouble("vs_local_y"));
            data.writeDouble(this.getPersistentData().getDouble("vs_local_z"));
        } else {
            data.writeLong(-1);
            data.writeDouble(0);
            data.writeDouble(0);
            data.writeDouble(0);
        }

        // a client re-tracking an attached hook must not resume projectile simulation for it
        // (client-side gravity would send the visual hook and rope through the floor)
        data.writeBoolean(this.attached);
	}

	@Override
	public void readSpawnData(FriendlyByteBuf data)
	{
		this.shootingEntityID = data.readInt();
		this.shootingEntity = this.level().getEntity(this.shootingEntityID);
		this.rightHand = data.readBoolean();
		this.isDouble = data.readBoolean();
		this.customization = new GrappleCustomization();
		this.customization.readFromBuf(data);
        
        long shipId = data.readLong();
        if (shipId != -1) {
            this.getPersistentData().putLong("vs_ship_id", shipId);
            this.getPersistentData().putDouble("vs_local_x", data.readDouble());
            this.getPersistentData().putDouble("vs_local_y", data.readDouble());
            this.getPersistentData().putDouble("vs_local_z", data.readDouble());
        } else {
            // Read and discard the doubles if shipId is -1
            data.readDouble();
            data.readDouble();
            data.readDouble();
        }

        if (data.readBoolean()) {
            this.setAttachPos(this.getX(), this.getY(), this.getZ());
        }
	}

	@Override
	public void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(DATA_ATTACHED_ENTITY, 0);
		this.entityData.define(DATA_ROPE_LENGTH, 0.0F);
		this.entityData.define(DATA_SHIP_MASS, 0.0F);
	}

	public void removeServer() {
		this.remove(RemovalReason.DISCARDED);
		this.shootingEntityID = 0;
	}

	public float getVelocity() {
		return (float) this.customization.throwspeed;
	}

	@Override
	public void tick() {
		if (this.shootingEntityID == 0 || this.shootingEntity == null) { // removes ghost grappling hooks
			this.remove(RemovalReason.DISCARDED);
			return;
		}

		if (this.firstAttach) {
			this.setDeltaMovement(0, 0, 0);
			this.firstAttach = false;
			super.setPos(this.thisPos.x, this.thisPos.y, this.thisPos.z);
		}

		// attached hooks release when the owner gets absurdly far away (out of tracking range,
		// where the rope isn't even rendered any more)
		if (!this.level().isClientSide && this.attached && this.shootingEntity != null) {
			double releaseDist = GrappleConfig.getConf().grapplinghook.other.hook_release_distance;
			if (releaseDist > 0) {
				double threshold = Math.max(releaseDist,
						this.customization.maxlen + GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer);
				if (Vec.positionVec(this.shootingEntity).sub(Vec.positionVec(this)).length() > threshold) {
					this.serverRelease();
					return;
				}
			}
		}

		if (!this.level().isClientSide && this.attached && this.attachedEntity != null) {
			this.tickEntityAttachment();
			if (this.isRemoved()) {
				return;
			}
		}

		if (this.attached) {
			this.setDeltaMovement(0, 0, 0);
			if (Ships.available()) {
				boolean shipResolved = ValkyrienSkiesIntegration.updateShipAttachment(this, this.level());
				if (!this.level().isClientSide && shipResolved) {
					this.tickShipPull();
				}
				if (!this.level().isClientSide) {
					if (!shipResolved) {
						this.shipUnresolvedTicks++;
						if (this.shipUnresolvedTicks > SHIP_UNRESOLVED_DETACH_TICKS) {
							// the ship was unloaded or deleted: release the hook instead of leaving it frozen mid-air
							int ownerId = this.shootingEntityID;
							GrapplemodUtils.sendToCorrectClient(new GrappleDetachMessage(ownerId), ownerId, this.level());
							ServerControllerManager.attached.remove(ownerId);
							this.removeServer();
							return;
						}
					} else {
						this.shipUnresolvedTicks = 0;
					}
				}
			}
		}

		super.tick();

		if (this.level().isClientSide) {
			int syncedTarget = this.getSyncedAttachedEntityId();
			if (syncedTarget != 0) {
				this.clientTickEntityAttachment(syncedTarget);
			}
		}

		if (!this.level().isClientSide) {
			if (this.shootingEntity != null)  {
				if (!this.attached) {
					if (this.segmentHandler.hookPastBend(this.r)) {
						GrappleMod.LOGGER.debug("Hook thrown past bend, attaching at bend");
						Vec farthest = this.segmentHandler.getFarthest();
						this.serverAttach(this.segmentHandler.getBendBlock(1), farthest, null);
					}

					if (!this.customization.phaserope) {
						this.segmentHandler.update(Vec.positionVec(this), Vec.eyePos(this.shootingEntity), this.r, true);

						if (this.customization.sticky) {
							if (this.segmentHandler.segments.size() > 2) {
								int bendnumber = this.segmentHandler.segments.size() - 2;
								Vec closest = this.segmentHandler.segments.get(bendnumber);
								BlockPos blockpos = this.segmentHandler.getBendBlock(bendnumber);
								for (int i = 1; i <= bendnumber; i++) {
									this.segmentHandler.removeSegment(1);
								}
								this.serverAttach(blockpos, closest, null);
							}
						}
					} else {
						this.segmentHandler.updatePos(Vec.positionVec(this), Vec.eyePos(this.shootingEntity), this.r);
					}

					Vec farthest = this.segmentHandler.getFarthest();
					double distToFarthest = this.segmentHandler.getDistToFarthest();

					Vec ropevec = Vec.positionVec(this).sub(farthest);
					double d = ropevec.length();

					if (this.customization.reelin && this.shootingEntity.isCrouching()) {
						double newdist = d + distToFarthest - 0.4;
						if (newdist > 1 && newdist <= this.customization.maxlen) {
							this.r = newdist;
						}
					}


					if (d + distToFarthest > this.r) {
						Vec motion = Vec.motionVec(this);

						if (motion.dot(ropevec) > 0) {
							motion = motion.removeAlong(ropevec);
						}

						this.setVelocityActually(motion.x, motion.y, motion.z);

						ropevec.changeLen_ip(this.r - distToFarthest);
						Vec newpos = ropevec.add(farthest);

						this.setPos(newpos.x, newpos.y, newpos.z);
					}

				}
			}
		}

		// magnet attraction
		if (!this.attached && this.customization.attract && Vec.positionVec(this).sub(Vec.positionVec(this.shootingEntity)).length() > this.customization.attractradius) {
			if (!this.foundBlock) {
				if (!this.level().isClientSide) {
					Vec playerpos = Vec.positionVec(this.shootingEntity);
					Vec pos = Vec.positionVec(this);
					if (magnetBlock == null) {
						if (prevPos != null) {
							HashMap<BlockPos, Boolean> checkedset = new HashMap<BlockPos, Boolean>();
							Vec vector = pos.sub(prevPos);
							if (vector.length() > 0) {
								Vec normvector = vector.normalize();
								for (int i = 0; i < vector.length(); i++) {
									double dist = prevPos.sub(playerpos).length();
									int radius = (int) dist / 4;
									BlockPos found = this.check(prevPos, checkedset);
									if (found != null) {
//				    					if (wasinair) {
										Vec distvec = new Vec(found.getX(), found.getY(), found.getZ());
										distvec.sub_ip(prevPos);
										if (distvec.length() < radius) {
											this.setPosRaw(prevPos.x, prevPos.y, prevPos.z);
											pos = prevPos;

											magnetBlock = found;

											break;
										}
//				    					}
									} else {
										wasInAir = true;
									}

									prevPos.add_ip(normvector);
								}
							}
						}
					}

					if (magnetBlock != null) {
						BlockState blockstate = this.level().getBlockState(magnetBlock);
						VoxelShape BB = blockstate.getCollisionShape(this.level(), magnetBlock);

						Vec blockvec = new Vec(magnetBlock.getX() + (BB.max(Axis.X) + BB.min(Axis.X)) / 2, magnetBlock.getY() + (BB.max(Axis.Y) + BB.min(Axis.Y)) / 2, magnetBlock.getZ() + (BB.max(Axis.Z) + BB.min(Axis.Z)) / 2);
						Vec newvel = blockvec.sub(pos);

						double l = newvel.length();

						newvel.changeLen(this.getVelocity());

						this.setDeltaMovement(newvel.x, newvel.y, newvel.z);

						if (l < 0.2) {
							this.serverAttach(magnetBlock, blockvec, Direction.UP);
						}
					}

					prevPos = pos;
				}
			}
		}
	}

	public void setVelocityActually(double x, double y, double z) {
		this.setDeltaMovement(x, y, z);

		if (this.xRotO == 0.0F && this.yRotO == 0.0F)
		{
			double f = Math.sqrt(x * x + z * z);
			this.setYRot((float)(Mth.atan2(x, z) * (180D / Math.PI)));
			this.setXRot((float)(Mth.atan2(y, f) * (180D / Math.PI)));
			this.yRotO = this.getYRot();
			this.xRotO = this.getXRot();
		}
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double p_70112_1_) {
		return true;
	}

	@Override
	public boolean shouldRender(double p_145770_1_, double p_145770_3_, double p_145770_5_) {
		return true;
	}

	@Override
	public AABB getBoundingBoxForCulling() {
		if (this.shootingEntity == null) {
			return super.getBoundingBoxForCulling();
		}
		return this.segmentHandler.getBoundingBox(Vec.positionVec(this), Vec.eyePos(this.shootingEntity));
	}

	@Override
	protected void onHit(HitResult movingobjectposition) {
		if (!this.level().isClientSide) {
			if (this.attached) {
				return;
			}
			if (this.shootingEntity == null || this.shootingEntityID == 0) {
				return;
			}
			if (movingobjectposition == null) {
				return;
			}

			Vec vec3d = Vec.positionVec(this);
			Vec vec3d1 = vec3d.add(Vec.motionVec(this));

			if (movingobjectposition instanceof EntityHitResult && !GrappleConfig.getConf().grapplinghook.other.hookaffectsentities) {
				onHit(GrapplemodUtils.rayTraceBlocks(this.level(), vec3d, vec3d1));
				return;
			}

			BlockHitResult blockhit = null;
			if (movingobjectposition instanceof BlockHitResult) {
				blockhit = (BlockHitResult) movingobjectposition;
			}

			if (blockhit != null) {
				BlockPos blockpos = blockhit.getBlockPos();
				if (blockpos != null) {
					Block block = this.level().getBlockState(blockpos).getBlock();
					if (GrappleConfigUtils.breaksBlock(block)) {
						this.level().destroyBlock(blockpos, true);
						onHit(GrapplemodUtils.rayTraceBlocks(this.level(), vec3d, vec3d1));
						return;
					}
				}
			}

			if (movingobjectposition instanceof EntityHitResult) {
				// hit entity: damage it and cling to it like a lead
				EntityHitResult entityHit = (EntityHitResult) movingobjectposition;
				Entity entity = entityHit.getEntity();
				if (entity == this.shootingEntity || entity == null) {
					return;
				}
				if (entity instanceof GrapplehookEntity) {
					return;
				}
				// the host's rules (a match's pvp, teams or grace period, through Camafy's
				// MatchPolicies) may forbid touching this target: the hook then neither hurts nor
				// holds it, and carries on to whatever block is behind, as with hookaffectsentities off
				if (!MatchPolicies.canHarm(this.shootingEntity, entity)) {
					onHit(GrapplemodUtils.rayTraceBlocks(this.level(), vec3d, vec3d1));
					return;
				}

				float damage = (float) GrappleConfig.getConf().grapplinghook.other.hook_entity_damage;
				if (damage > 0) {
					entity.hurt(this.damageSources().thrown(this, this.shootingEntity), damage);
				}

				this.serverAttachToEntity(entity);
				return;
			} else if (blockhit != null) {
				BlockPos blockpos = blockhit.getBlockPos();

				Vec vec3 = new Vec(movingobjectposition.getLocation());

				this.serverAttach(blockpos, vec3, blockhit.getDirection());
			} else {
				GrappleMod.LOGGER.warn("Unknown grappling hook impact type");
			}
		}
	}

	@Override
	protected Item getDefaultItem() {
		return CommonSetup.grapplingHookItem.get();
	}

	/** Point on an entity the hook visually clings to. */
	private Vec hookPointOn(Entity target) {
		return new Vec(target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ());
	}

	/**
	 * Attaches the hook to an entity, lead-style: the hook follows the entity, the rope drags the
	 * entity along when the player walks out of rope range, and crouching reels the entity in.
	 * The owning player is not rope-constrained (no client controller is created).
	 */
	public void serverAttachToEntity(Entity target) {
		if (this.attached) {
			return;
		}
		if (this.shootingEntity == null || this.shootingEntityID == 0) {
			return;
		}
		this.attached = true;
		this.attachedEntity = target;

		Vec pos = this.hookPointOn(target);
		this.setPosRaw(pos.x, pos.y, pos.z);
		this.setDeltaMovement(0, 0, 0);
		this.thisPos = pos;
		this.firstAttach = true;

		Vec playerpos = Vec.eyePos(this.shootingEntity);
		double dist = playerpos.sub(pos).length();
		this.r = Math.min(Math.max(dist, ENTITY_ATTACH_MIN_ROPE), this.customization.maxlen);

		// every client pins the hook onto the target and computes sag from these
		this.entityData.set(DATA_ATTACHED_ENTITY, target.getId());
		this.entityData.set(DATA_ROPE_LENGTH, (float) this.r);

		ServerControllerManager.attached.add(this.shootingEntityID);

		// pin the hook onto the entity for every nearby client; hook position afterwards flows
		// through normal entity tracking as the hook follows its target
		GrappleAttachPosMessage msg = new GrappleAttachPosMessage(this.getId(), pos.x, pos.y, pos.z);
		CommonSetup.network.send(PacketDistributor.TRACKING_CHUNK.with(() -> this.level().getChunkAt(BlockPos.containing(pos.x, pos.y, pos.z))), msg);
	}

	/**
	 * Server tick while clinging to an entity: follow it, keep the rope segments (bends) up to
	 * date, and swing-constrain the target the same way the player controller constrains a player
	 * hanging from a wall hook. Player targets run their own client-side swing physics
	 * (GrappledController), so the server only reels and rope-snaps for them.
	 */
	private void tickEntityAttachment() {
		Entity target = this.attachedEntity;
		if (target == null || !target.isAlive() || this.shootingEntity == null) {
			this.detachFromEntity();
			return;
		}
		// a player who turned spectator, or whom the host's rules no longer let us touch (pvp
		// switched off mid-rope), is let go
		if (target instanceof Player targetPlayer
				&& (targetPlayer.isSpectator() || !MatchPolicies.canHarm(this.shootingEntity, target))) {
			this.detachFromEntity();
			return;
		}

		Vec pos = this.hookPointOn(target);
		this.setPos(pos.x, pos.y, pos.z);
		this.thisPos = pos;

		// the rope owns this mob's movement: cancel pathfinding so it doesn't fight the rope
		// and doesn't march back to a stale destination the moment it's released
		if (target instanceof net.minecraft.world.entity.Mob) {
			((net.minecraft.world.entity.Mob) target).getNavigation().stop();
		}

		// a mob lowered gently on the rope must not bank fall damage; genuine fast drops
		// (falling faster than ~10 m/s) still count
		if (Vec.motionVec(target).y > -0.5) {
			target.fallDistance = 0;
		}

		Vec playerpos = Vec.eyePos(this.shootingEntity);

		// rope bends around terrain: hook end moves with the target, player end with the shooter
		if (!this.customization.phaserope) {
			this.segmentHandler.update(pos, playerpos, this.r, true);
		} else {
			this.segmentHandler.updatePos(pos, playerpos, this.r);
		}

		// the target swings around the bend nearest to it; the rest of the rope is spoken for
		Vec anchor = this.segmentHandler.getFarthest();
		double usedRope = this.segmentHandler.getDistToFarthest();

		Vec spherevec = pos.sub(anchor);
		double dist = spherevec.length();

		// too far for the rope to hold: it snaps
		if (dist + usedRope > this.customization.maxlen + GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer) {
			this.detachFromEntity();
			return;
		}

		// crouching reels the target in
		if (this.shootingEntity.isCrouching()) {
			this.r = Math.max(ENTITY_ATTACH_MIN_ROPE, this.r - GrappleConfig.getConf().grapplinghook.other.hook_entity_retract_speed);
		}
		this.entityData.set(DATA_ROPE_LENGTH, (float) this.r);

		if (target instanceof Player) {
			// client-authoritative movement: the grappled player's own client applies the
			// swing constraint (GrappledController), keyed off the synced entity data
			return;
		}

		double allowed = Math.max(1.0, this.r - usedRope);
		if (dist > allowed) {
			Vec motion = Vec.motionVec(target);
			// swinging: keep tangential velocity, drop the outward component
			if (anchor.sub(pos.add(motion)).length() > allowed) {
				motion = motion.removeAlong(spherevec);
			}
			// soften the positional correction into velocity so the drag looks smooth
			Vec spherechange = spherevec.changeLen(allowed).sub(spherevec);
			motion = motion.add(spherechange.mult(0.35));
			target.setDeltaMovement(motion.toVec3d());
			// force a velocity sync to clients; mobs otherwise ignore server-side motion changes
			target.hurtMarked = true;
		}
	}

	/**
	 * Client tick while the hook clings to an entity (synced via DATA_ATTACHED_ENTITY): pin the
	 * hook onto the target every tick so rendering interpolates along the target's own smooth
	 * path, compute rope sag locally, and start swing physics if the local player is the target.
	 */
	private void clientTickEntityAttachment(int targetId) {
		Entity target = this.level().getEntity(targetId);
		if (target == null || !target.isAlive()) {
			return;
		}
		this.attached = true;
		Vec pos = this.hookPointOn(target);
		this.setPos(pos.x, pos.y, pos.z);
		this.thisPos = pos;

		if (this.shootingEntity != null) {
			Vec playerpos = Vec.eyePos(this.shootingEntity);
			this.segmentHandler.updatePos(pos, playerpos, this.getSyncedRopeLength());
			double dist = this.segmentHandler.getDist(pos, playerpos);
			double taut = 1 - ((this.getSyncedRopeLength() - dist) / 5);
			this.taut = Math.max(0, Math.min(1, taut));
		}

		ClientProxyInterface.proxy.updateGrappledControl(this, target);
	}

	/**
	 * Rope tension on a Valkyrien Skies ship the hook is attached to. While the rope is taut, a
	 * continuous force pulls the ship at the attach point toward the player (a hanging player's
	 * weight, plus a spring term when the rope is stretched by reeling or falling). Mass decides
	 * the outcome naturally: a one-block ship gets dragged along, a heavy hull barely moves and
	 * the player swings from it instead.
	 */
	private void tickShipPull() {
		if (this.shootingEntity == null) {
			return;
		}
		long shipId = this.getAttachedShipId();
		if (shipId == -1) {
			return;
		}
		double mult = GrappleConfig.getConf().grapplinghook.other.hook_ship_pull_force;
		if (mult <= 0) {
			return;
		}

		// you can't pull the ship you're standing on (the rope would just be pulling yourself)
		if (ValkyrienSkiesIntegration.isEntityStandingOnShip(this.level(), shipId, this.shootingEntity)) {
			return;
		}

		Vec hookPos = Vec.positionVec(this);
		Vec playerpos = Vec.eyePos(this.shootingEntity);

		// keep the server's rope path current: the ship must be pulled towards the nearest rope
		// bend (e.g. around a pillar), not straight at the player. Extra length is allowed so a
		// stretched (light-ship) rope doesn't get its bends pruned as "past rope length".
		double stretchAllowance = GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer * 3;
		if (!this.customization.phaserope) {
			this.segmentHandler.update(hookPos, playerpos, this.r + stretchAllowance, true);
		} else {
			this.segmentHandler.updatePos(hookPos, playerpos, this.r);
		}
		Vec anchor = this.segmentHandler.getFarthest();      // bend nearest the hook (or the player)
		double usedRope = this.segmentHandler.getDistToFarthest();

		Vec ropevec = anchor.sub(hookPos);
		double dist = ropevec.length();
		if (dist < 0.3 || (dist + usedRope) < 0.5) {
			return;
		}

		// the client keeps r in sync (HookRopeLengthMessage); taut = at/beyond rope length
		double excess = (dist + usedRope) - this.r;
		if (excess < -0.3) {
			return;
		}
		// the rope can only stretch so far before it snaps and the hook retracts
		// (the rendering tints the rope red as it approaches this point)
		if (excess > stretchAllowance) {
			this.serverRelease();
			return;
		}
		// a grounded player only pulls when actively stretching the rope (walking away, reeling)
		if (this.shootingEntity.onGround() && excess < 0.1) {
			return;
		}

		// a player hauling on a rope (their weight: none in zero gravity), plus a spring while the
		// rope is stretched (reeling/falling)
		double tension = (3000.0 * Math.min(1.0, net.camacraft.camafy.compat.gravity.Gravity.strength(this.shootingEntity)) + Math.max(0, excess) * 6000.0);
		tension = Math.min(tension, 30000.0) * mult;

		Vec impulse = ropevec.changeLen(tension / 20.0); // one game tick's worth, N*s
		Vec shipyardPos = new Vec(
				this.getPersistentData().getDouble("vs_local_x"),
				this.getPersistentData().getDouble("vs_local_y"),
				this.getPersistentData().getDouble("vs_local_z"));
		// full force at the attach point (a real rope): a pillar hooked at the top rights itself
		// when lifted; the rotational damping keeps the off-center force from pumping up a spin
		ValkyrienSkiesIntegration.queueShipImpulse(this.level(), shipId, impulse, shipyardPos, true);

		// rope damper: bleed the ship's velocity relative to the player while the rope is taut.
		// Without it the spring pumps energy every stretch-contract cycle (elastic pendulum
		// resonance) and a light ship orbits the player faster and faster. Measured where the
		// rope is tied, spin included: a turning ship's hook point moves faster than its center.
		Vec shipVel = ValkyrienSkiesIntegration.getShipVelocityAt(this.level(), shipId, hookPos);
		if (shipVel != null) {
			Vec playerVel = Vec.motionVec(this.shootingEntity).mult(20.0); // m/s
			Vec relVel = shipVel.sub(playerVel);
			if (relVel.length() > 0.5) {
				double dampForce = Math.min(relVel.length() * 500.0, 12000.0) * mult;
				Vec dampImpulse = relVel.changeLen(-dampForce / 20.0);
				// at the center of mass: damping must not introduce torque of its own
				ValkyrienSkiesIntegration.queueShipImpulse(this.level(), shipId, dampImpulse, null, false);
			}
		}
	}

	/** Releases any attachment (block, ship, or entity) server-side and removes the hook. */
	private void serverRelease() {
		this.entityData.set(DATA_ATTACHED_ENTITY, 0);
		int ownerId = this.shootingEntityID;
		GrapplemodUtils.sendToCorrectClient(new GrappleDetachMessage(ownerId), ownerId, this.level());
		ServerControllerManager.attached.remove(ownerId);
		this.removeServer();
	}

	private void detachFromEntity() {
		this.serverRelease();
	}

	public void serverAttach(BlockPos blockpos, Vec pos, Direction sideHit) {
		if (this.attached) {
			return;
		}
		if (this.shootingEntity == null || this.shootingEntityID == 0) {
			return;
		}
		this.attached = true;

		// impact velocity, captured before the hook is frozen in place (blocks/tick)
		Vec impactVel = Vec.motionVec(this);

		if (blockpos != null) {
			Block block = this.level().getBlockState(blockpos).getBlock();

			if (!GrappleConfigUtils.attachesBlock(block)) {
				this.removeServer();
				return;
			}
		}

		Vec vec3 = Vec.positionVec(this);
		vec3.add_ip(Vec.motionVec(this));
		if (pos != null) {
			vec3 = pos;

			this.setPosRaw(vec3.x, vec3.y, vec3.z);
		}

		//west -x
		//north -z
		Vec nudge = new Vec(0, 0, 0);
		if (sideHit == Direction.DOWN) {
			nudge.y -= 0.3;
		} else if (sideHit == Direction.WEST) {
			nudge.x -= 0.05;
		} else if (sideHit == Direction.NORTH) {
			nudge.z -= 0.05;
		} else if (sideHit == Direction.SOUTH) {
			nudge.z += 0.05;
		} else if (sideHit == Direction.EAST) {
			nudge.x += 0.05;
		} else if (sideHit == Direction.UP) {
			nudge.y += 0.05;
		}

		// on ships the hit side is shipyard-space, so the nudge is applied in shipyard space there
		boolean attachedToShip = false;
		if (Ships.available()) {
			attachedToShip = ValkyrienSkiesIntegration.attachToShip(this, this.level(), Vec.positionVec(this).toVec3d(), blockpos, nudge.toVec3d());
		}
		if (!attachedToShip) {
			Vec curpos = Vec.positionVec(this).add(nudge);
			curpos.setPos(this);
		} else {
			// let the owning client know how heavy the ship is (rope constraint vs ship yielding)
			this.entityData.set(DATA_SHIP_MASS,
					(float) ValkyrienSkiesIntegration.getShipMass(this.level(), this.getPersistentData().getLong("vs_ship_id")));

			// give the ship a small knock at the impact point, scaled by impact speed:
			// light ships get visibly nudged, heavy ships barely notice
			double impulseMult = GrappleConfig.getConf().grapplinghook.other.hook_ship_impulse;
			double speedMps = impactVel.length() * 20.0;
			if (impulseMult > 0 && speedMps > 1.0) {
				Vec impulse = impactVel.changeLen(speedMps * HOOK_IMPULSE_MASS_KG * impulseMult);
				Vec shipyardPos = new Vec(
						this.getPersistentData().getDouble("vs_local_x"),
						this.getPersistentData().getDouble("vs_local_y"),
						this.getPersistentData().getDouble("vs_local_z"));
				ValkyrienSkiesIntegration.queueShipImpulse(this.level(), this.getPersistentData().getLong("vs_ship_id"), impulse, shipyardPos);
			}
		}

		this.setDeltaMovement(0, 0, 0);

		this.thisPos = Vec.positionVec(this);
		this.firstAttach = true;
		// all clients need the rope length for the stretch tint (kept current by the owner
		// through HookRopeLengthMessage afterwards)
		this.entityData.set(DATA_ROPE_LENGTH, (float) this.r);
		ServerControllerManager.attached.add(this.shootingEntityID);

        long shipId = -1;
        double localX = 0, localY = 0, localZ = 0;
        if (this.getPersistentData().contains("vs_ship_id")) {
            shipId = this.getPersistentData().getLong("vs_ship_id");
            localX = this.getPersistentData().getDouble("vs_local_x");
            localY = this.getPersistentData().getDouble("vs_local_y");
            localZ = this.getPersistentData().getDouble("vs_local_z");
        }

		GrapplemodUtils.sendToCorrectClient(new GrappleAttachMessage(this.getId(), this.position().x, this.position().y, this.position().z, this.getControlId(), this.shootingEntityID, blockpos, this.segmentHandler, this.customization, shipId, localX, localY, localZ), this.shootingEntityID, this.level());

		GrappleAttachPosMessage msg = new GrappleAttachPosMessage(this.getId(), this.position().x, this.position().y, this.position().z);
		CommonSetup.network.send(PacketDistributor.TRACKING_CHUNK.with(() -> this.level().getChunkAt(BlockPos.containing(this.position().x, this.position().y, this.position().z))), msg);
	}

	public void clientAttach(double x, double y, double z) {
		this.setAttachPos(x, y, z);

		if (this.shootingEntity instanceof Player) {
			ClientProxyInterface.proxy.resetLauncherTime(this.shootingEntityID);
		}
	}


	/**
	 * The hook's drop in flight, as strong as the local gravity (Camafy 0.1.7): Ad Astra's and
	 * Gravity Unbound's hooks on {@code ThrowableProjectile.getGravity} don't reach this override,
	 * so without it the hook arced "down" in an Ad Astra orbit or a zero-strength field.
	 */
	@Override
	protected float getGravity() {
		if (this.attached) {
			return 0.0F;
		}
		float drop = (float) this.customization.hookgravity * 0.1F;
		double strength = net.camacraft.camafy.compat.gravity.Gravity.strength(this);
		return strength == 1.0 ? drop : (float) (drop * strength);
	}

	public int getControlId() {
		return GrapplemodUtils.GRAPPLEID;
	}

	public void setAttachPos(double x, double y, double z) {
		this.setPosRaw(x, y, z);

		this.setDeltaMovement(0, 0, 0);
		this.firstAttach = true;
		this.attached = true;
		this.thisPos = new Vec(x, y, z);
	}

	// used for magnet attraction
	public BlockPos check(Vec p, HashMap<BlockPos, Boolean> checkedset) {
		int radius = (int) Math.floor(this.customization.attractradius);
		double radiusSq = this.customization.attractradius * this.customization.attractradius;
		BlockPos closestpos = null;
		double closestdist = 0;
		for (int x = (int)p.x - radius; x <= (int)p.x + radius; x++) {
			for (int y = (int)p.y - radius; y <= (int)p.y + radius; y++) {
				for (int z = (int)p.z - radius; z <= (int)p.z + radius; z++) {
					// sphere culling: skip cube corners outside the attraction radius
					double dx = x - p.x, dy = y - p.y, dz = z - p.z;
					if (dx * dx + dy * dy + dz * dz > radiusSq) {
						continue;
					}
					BlockPos pos = new BlockPos(x, y, z);
					if (hasBlock(pos, checkedset)) {
						Vec distvec = new Vec(pos.getX(), pos.getY(), pos.getZ());
						distvec.sub_ip(p);
						double dist = distvec.length();
						if (closestpos == null || dist < closestdist) {
							closestpos = pos;
							closestdist = dist;
						}
					}
				}
			}
		}
		return closestpos;
	}

	// used for magnet attraction
	public boolean hasBlock(BlockPos pos, HashMap<BlockPos, Boolean> checkedset) {
		if (!checkedset.containsKey(pos)) {
			boolean isblock = false;
			BlockState blockstate = this.level().getBlockState(pos);
			Block b = blockstate.getBlock();
			if (GrappleConfigUtils.attachesBlock(b)) {
				if (!(blockstate.isAir())) {
					VoxelShape BB = blockstate.getCollisionShape(this.level(), pos);
					if (BB != null && !BB.isEmpty()) {
						isblock = true;
					}
				}
			}

			checkedset.put(pos, (Boolean) isblock);
			return isblock;
		} else {
			return checkedset.get(pos);
		}
	}

	@Nonnull
	@Override
	public Packet<ClientGamePacketListener> getAddEntityPacket() {
		return NetworkHooks.getEntitySpawningPacket(this);
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(this.getDefaultItem());
	}
}