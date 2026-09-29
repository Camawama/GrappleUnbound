package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.network.SegmentMessage;
import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.LongSupplier;

public class SegmentHandler {

	public static final long NO_SHIP = RopeWorld.NO_SHIP;

	public LinkedList<Vec> segments;
	public LinkedList<Direction> segmentBottomSides;
	public LinkedList<Direction> segmentTopSides;
	// Valkyrien Skies: per-bend ship id (NO_SHIP for world bends) and ship-local (shipyard) position.
	// Ship bends are re-projected to world space every tick so the rope follows the moving ship.
	public LinkedList<Long> segmentShipIds;
	public LinkedList<Vec> segmentShipLocals;
	public Level world;
	public GrapplehookEntity hookEntity;

	// ray traces, solid checks and ship transforms (the level, or a test's stand-in)
	private final RopeWorld rope;
	// the ship the hook end is glued to (NO_SHIP when the hook is on a block, an entity or flying)
	private final LongSupplier hookShip;

	Vec prevHookPos = null;
	Vec prevPlayerPos = null;

	final double bendOffset = 0.05;
	final double intoBlock = 0.05;

	public SegmentHandler(Level w, GrapplehookEntity hookEntity, Vec hookpos, Vec playerpos) {
		this(new LevelRopeWorld(w), hookEntity == null ? () -> NO_SHIP : hookEntity::getAttachedShipId, hookpos, playerpos);
		this.world = w;
		this.hookEntity = hookEntity;
	}

	/** A rope with no hook entity (nothing is sent over the network), for tests. */
	SegmentHandler(RopeWorld rope, LongSupplier hookShip, Vec hookpos, Vec playerpos) {
		segments = new LinkedList<Vec>();
		segments.add(hookpos);
		segments.add(playerpos);
		segmentBottomSides = new LinkedList<Direction>();
		segmentBottomSides.add(null);
		segmentBottomSides.add(null);
		segmentTopSides = new LinkedList<Direction>();
		segmentTopSides.add(null);
		segmentTopSides.add(null);
		segmentShipIds = new LinkedList<Long>();
		segmentShipIds.add(NO_SHIP);
		segmentShipIds.add(NO_SHIP);
		segmentShipLocals = new LinkedList<Vec>();
		segmentShipLocals.add(null);
		segmentShipLocals.add(null);
		this.rope = rope;
		this.hookShip = hookShip;
		this.prevHookPos = new Vec(hookpos);
		this.prevPlayerPos = new Vec(playerpos);
	}

	public void forceSetPos(Vec hookpos, Vec playerpos) {
		this.prevHookPos = new Vec(hookpos);
		this.prevPlayerPos = new Vec(playerpos);
		this.segments.set(0, new Vec(hookpos));
		this.segments.set(this.segments.size() - 1, new Vec(playerpos));
	}

	double ropeLen;

	/**
	 * Re-projects every ship-attached bend to its current world position. Bends whose ship can no
	 * longer be resolved (unloaded/deleted) are removed so the rope doesn't stay pinned in mid-air.
	 */
	public void refreshShipBends() {
		if (!this.rope.shipsAvailable()) {
			return;
		}
		for (int i = 1; i < segments.size() - 1; ) {
			long shipId = segmentShipIds.get(i);
			if (shipId == NO_SHIP) {
				i++;
				continue;
			}
			Vec worldPos = this.rope.toWorld(shipId, segmentShipLocals.get(i));
			if (worldPos == null) {
				this.removeSegment(i);
			} else {
				segments.set(i, worldPos);
				i++;
			}
		}
	}

	public void updatePos(Vec hookpos, Vec playerpos, double ropelen) {
		this.refreshShipBends();
		segments.set(0, hookpos);
		segments.set(segments.size() - 1, playerpos);
		this.ropeLen = ropelen;
	}

	/**
	 * Advances the rope one tick: unwraps bends the rope no longer needs, then sweeps last tick's
	 * rope to this tick's and adds a bend wherever it newly crossed a block edge.
	 *
	 * @param movinghook the hook is flying or clinging to an entity: its end of the rope is swept,
	 *                   unwraps from its side, and bends beyond the rope length are dropped. A hook
	 *                   glued to a Valkyrien Skies ship also has its end swept and unwrapped (the
	 *                   ship carries it around), but its bends are never dropped for length.
	 */
	public void update(Vec hookpos, Vec playerpos, double ropelen, boolean movinghook) {
		if (prevHookPos == null) {
			prevHookPos = hookpos;
			prevPlayerPos = playerpos;
		}

		this.refreshShipBends();

		segments.set(0, hookpos);
		segments.set(segments.size() - 1, playerpos);
		this.ropeLen = ropelen;

		// A hook on a VS ship moves with the ship every tick, so both ends of the rope move. Its
		// position last tick is taken from the ship itself (exactly where the ship carried it
		// from), so last tick's rope glued back onto the ship puts the hook end exactly on it.
		long hookShipId = this.rope.shipsAvailable() ? this.hookShip.getAsLong() : NO_SHIP;
		boolean hookEndMoves = movinghook || hookShipId != NO_SHIP;
		Vec prevhook = this.prevHookPos;
		if (hookShipId != NO_SHIP) {
			Vec shipPrev = this.rope.currentToPrevTick(hookShipId, hookpos);
			if (shipPrev != null) {
				prevhook = shipPrev;
			}
		}

		// stale-bend prune: rope dragging across surfaces leaves bends the unwrap plane test
		// never releases. Only a line-of-sight test may prune — it removes bends the rope
		// geometrically no longer needs. (An angle-based prune is a trap: a fresh wrap is always
		// nearly straight, and once pruned the embedded rope fails the prev-tick-clear check and
		// can never re-wrap, phasing through the block forever.) The neighbours seeing each other
		// is not enough on its own: once the rope has swung far enough round a thin pillar, the
		// straight line between them passes the pillar's far side while the pillar still sits
		// inside the rope's turn - a moving ship does that to a hooked rope all the time.
		for (int i = 1; i < segments.size() - 1; ) {
			if (this.rope.trace(segments.get(i - 1), segments.get(i + 1)) == null && !this.blockInsideTurn(i)) {
				this.removeSegment(i);
			} else {
				i++;
			}
		}

		while (true) {
			if (segments.size() == 2) {
				break;
			}

			int index = segments.size()-2;
			Vec closest = segments.get(index);
			Direction bottomside = segmentBottomSides.get(index);
			Direction topside = segmentTopSides.get(index);
			Vec ropevec = playerpos.sub(closest);

			Vec beforepoint = segments.get(index-1);

			long bendShipId = segmentShipIds.get(index);
			Vec edgevec = getWorldNormal(bottomside, bendShipId).cross(getWorldNormal(topside, bendShipId));
			Vec planenormal = beforepoint.sub(closest).cross(edgevec);

			if (ropevec.dot(planenormal) > 0) {
				this.removeSegment(index);
			} else {
				break;
			}
		}

		// unwrap from the hook's side when that end of the rope moves: the hook itself, or a ship
		// carrying the bend nearest the hook
		while (true) {
			if (segments.size() == 2) {
				break;
			}
			if (!hookEndMoves && segmentShipIds.get(1) == NO_SHIP) {
				break;
			}

			int index = 1;
			Vec farthest = segments.get(index);
			Direction bottomside = segmentBottomSides.get(index);
			Direction topside = segmentTopSides.get(index);
			Vec ropevec = farthest.sub(hookpos);

			Vec beforepoint = segments.get(index+1);

			long bendShipId = segmentShipIds.get(index);
			Vec edgevec = getWorldNormal(bottomside, bendShipId).cross(getWorldNormal(topside, bendShipId));
			Vec planenormal = beforepoint.sub(farthest).cross(edgevec);

			if (ropevec.dot(planenormal) > 0 || ropevec.length() < 0.1) {
				this.removeSegment(index);
			} else {
				break;
			}
		}

		if (movinghook) {
			while (true) {
				if (this.getDistToFarthest() > ropelen) {
					this.removeSegment(1);
				} else {
					break;
				}
			}
		}

		// Every stretch that can have moved since last tick is swept. The stretches between two
		// bends: a ship bend moves with its ship, and a ship can move into a still stretch. Done
		// from the player's end backwards, so a bend added in one doesn't shift those still to do;
		// a still stretch in a still world hits nothing and costs one trace.
		for (int i = segments.size() - 3; i >= 1; i--) {
			updateSegment(segments.get(i), this.prevPosOf(i), segments.get(i + 1), this.prevPosOf(i + 1), i + 1, 0);
		}

		// the hook's end of the rope (hook -> nearest bend): the hook or the bend may have moved
		// (a hook on a ship always does). The owner's client never swept this stretch, so on a
		// ship-attached hook the rope phased through anything the ship dragged it across.
		if (segments.size() > 2) {
			updateSegment(hookpos, prevhook, segments.get(1), this.prevPosOf(1), 1, 0);
		}

		// the player's end (nearest bend -> player); with no bends this is the whole rope, both
		// ends' movement included
		int closestIndex = segments.size() - 2;
		Vec closest = segments.get(closestIndex);
		Vec prevclosest = closestIndex == 0 ? prevhook : this.prevPosOf(closestIndex);
		updateSegment(closest, prevclosest, playerpos, prevPlayerPos, segments.size() - 1, 0);

		prevHookPos = hookpos;
		prevPlayerPos = playerpos;
	}

	/**
	 * Whether a block sits inside the rope's turn at bend {@code index}: between the rope and the
	 * straight line its neighbours would pull it to, so the rope can't straighten and the bend is
	 * still wrapped round it. Checked with lines parallel to that straight line, each half as far
	 * from the bend as the last: one of them runs through the corner the bend is wrapped round,
	 * however sharp or shallow the turn. A stale bend (a seam catch, or ground a rolling ship's
	 * rope has lifted off) has only air inside its turn.
	 */
	private boolean blockInsideTurn(int index) {
		Vec bend = segments.get(index);
		Vec toPrev = segments.get(index - 1).sub(bend);
		Vec toNext = segments.get(index + 1).sub(bend);
		double longest = Math.max(toPrev.length(), toNext.length());
		for (double f = 0.5; f * longest > 0.1; f *= 0.5) {
			if (this.rope.trace(bend.add(toPrev.mult(f)), bend.add(toNext.mult(f))) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * World position of bend {@code index} on the previous tick: a world bend never moves, a ship
	 * bend was wherever its ship was.
	 */
	private Vec prevPosOf(int index) {
		Vec pos = segments.get(index);
		long shipId = segmentShipIds.get(index);
		if (shipId != NO_SHIP) {
			Vec prev = this.rope.currentToPrevTick(shipId, pos);
			if (prev != null) {
				return prev;
			}
		}
		return pos;
	}

	public void removeSegment(int index) {
		segments.remove(index);
		segmentBottomSides.remove(index);
		segmentTopSides.remove(index);
		segmentShipIds.remove(index);
		segmentShipLocals.remove(index);

		if (this.syncsToClients()) {
			SegmentMessage addmessage = new SegmentMessage(this.hookEntity.getId(), false, index, new Vec(0, 0, 0), Direction.DOWN, Direction.DOWN, NO_SHIP, new Vec(0, 0, 0));
			CommonSetup.network.send(this.segmentMessageTarget(), addmessage);
		}
	}

	/** Server-side ropes of a hook entity broadcast their bend changes. */
	private boolean syncsToClients() {
		return this.hookEntity != null && this.world != null && !this.world.isClientSide && this.hookEntity.shootingEntity != null;
	}

	/**
	 * Block-attached hooks: the owner simulates its own segments client-side, so TRACKING_ENTITY
	 * (which excludes the owner) avoids double-applying. Entity-attached hooks are simulated by
	 * the server only, so the owner must be included.
	 */
	private PacketDistributor.PacketTarget segmentMessageTarget() {
		if (this.hookEntity.attachedEntity != null) {
			return PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.hookEntity.shootingEntity);
		}
		return PacketDistributor.TRACKING_ENTITY.with(() -> this.hookEntity.shootingEntity);
	}

	/**
	 * Sweeps one stretch of rope from last tick (prevtop -> prevbottom) to this tick (top ->
	 * bottom) and adds a bend where it newly crossed a block edge. {@code prevtop} and
	 * {@code prevbottom} are last tick's WORLD positions.
	 *
	 * <p>Whatever the rope now hits decides the frame the sweep is measured in: world blocks
	 * stay put, so last tick's rope is used as it was; a Valkyrien Skies ship moved since last
	 * tick, so last tick's rope is glued to the ship's previous pose and carried along to its
	 * current one (a ship moving or turning into a still rope then shows up as the rope sweeping
	 * across the hull). Either way, "was the rope already through this last tick?" and the corner
	 * search ask that frame's blocks only. Asking every frame at once was the bug that made ropes
	 * hooked to a moving ship phase through pillars: last tick's hook point sits where the hull
	 * was, which the ship has since moved over, so last tick's rope "hit" the hooked ship itself,
	 * the sweep gave up, and once the rope was inside the pillar it could never wrap again.
	 */
	public void updateSegment(Vec top, Vec prevtop, Vec bottom, Vec prevbottom, int index, int numberrecursions) {
		RopeWorld.Hit bottomhit = this.rope.trace(bottom, top);

		// if rope hit block
		if (bottomhit == null) {
			return;
		}

		long frame = bottomhit.shipId();
		Vec ptop = prevtop;
		Vec pbottom = prevbottom;
		if (frame != NO_SHIP) {
			ptop = this.rope.prevTickToCurrent(frame, prevtop);
			pbottom = this.rope.prevTickToCurrent(frame, prevbottom);
			if (ptop == null || pbottom == null) {
				return;
			}
		}

		// last tick's rope already went through this frame's blocks: no crossing to find
		if (this.rope.traceIn(frame, pbottom, ptop) != null) {
			return;
		}

		Vec bottomhitvec = bottomhit.location();

		Direction bottomside = bottomhit.side();
		Vec bottomnormal = this.getWorldNormal(bottomside, frame);

		// calculate where bottomhitvec was along the rope in the previous tick
		double prevropelen = ptop.sub(pbottom).length();

		Vec cornerbound1 = bottomhitvec.add(bottomnormal.changeLen(-intoBlock));

		Vec bound_option1 = linePlaneIntersection(ptop, pbottom, cornerbound1, bottomnormal);
		Vec bound_option2 = linePlaneIntersection(top, ptop, cornerbound1, bottomnormal);
		Vec bound_option3 = linePlaneIntersection(pbottom, bottom, cornerbound1, bottomnormal);

		for (Vec cornerbound2 : new Vec[] {bound_option1, bound_option2, bound_option3}) {
			if (cornerbound2 == null) {
				continue;
			}

			// the corner must be in the line (cornerbound2, cornerbound1), on the same frame's
			// blocks as the face the rope hit
			RopeWorld.Hit cornerhit = this.rope.traceIn(frame, cornerbound2, cornerbound1);
			if (cornerhit != null) {
				Vec cornerhitpos = cornerhit.location();
				Direction cornerside = cornerhit.side();

				Vec cornernormal = this.getWorldNormal(cornerside, frame);

				// world-space parallelism check: on rotated ships the corner face can be
				// (anti)parallel to the bottom face without the Direction enums matching
				if (Math.abs(cornernormal.dot(bottomnormal)) > 0.99) {
					// this should not happen
					continue;
				} else {
					// add a bend around the corner
					Vec actualcorner = cornerhitpos.add(bottomnormal.changeLen(intoBlock));
					Vec bend = actualcorner.add(bottomnormal.changeLen(bendOffset)).add(cornernormal.changeLen(bendOffset));
					Vec topropevec = bend.sub(top);
					Vec bottomropevec = bend.sub(bottom);

					// Seam catches (rope sliding along a flat surface, snagging the edge between
					// two coplanar faces) look exactly like fresh wraps up close: tiny angle,
					// tiny penetration. What distinguishes them is what lies BEYOND the edge —
					// a seam has more of the same surface there, a real corner has air. Probe
					// just past the corner, slightly below the surface the rope slid along.
					Vec continueDir = top.sub(bend);
					if (continueDir.length() > 0.01) {
						Vec probe = bend.add(continueDir.changeLen(0.35)).add(bottomnormal.changeLen(-0.2));
						if (this.rope.isSolidAt(probe, frame)) {
							continue;
						}
					}

					// ignore bends that are too close to another bend
					if (topropevec.length() < 0.05) {
						if (this.segmentBottomSides.get(index - 1) == bottomside && this.segmentTopSides.get(index - 1) == cornerside) {
							continue;
						}
					}
					if (bottomropevec.length() < 0.05) {
						if (this.segmentBottomSides.get(index) == bottomside && this.segmentTopSides.get(index) == cornerside) {
							continue;
						}
					}

					// if the corner block belongs to a Valkyrien Skies ship, remember the ship
					// and the bend's ship-local position so it can follow the ship
					long shipId = frame;
					Vec shipLocal = null;
					if (shipId != NO_SHIP) {
						shipLocal = this.rope.toShip(shipId, bend);
						if (shipLocal == null) {
							shipId = NO_SHIP;
						}
					}

					this.actuallyAddSegment(index, bend, bottomside, cornerside, shipId, shipLocal);

					// if not enough rope length left, undo
					if(this.getDistToAnchor() + .2 > this.ropeLen) {
						this.removeSegment(index);
						continue;
					}

					// now to recurse on top section of rope
					double newropelen = topropevec.length() + bottomropevec.length();

					double prevtoptobend = topropevec.length() * prevropelen / newropelen;
					Vec prevbend = ptop.add(pbottom.sub(ptop).changeLen(prevtoptobend));
					// back from the ship's frame to where that point of the rope was last tick
					if (frame != NO_SHIP) {
						Vec worldPrev = this.rope.currentToPrevTick(frame, prevbend);
						if (worldPrev != null) {
							prevbend = worldPrev;
						}
					}

					if (numberrecursions < 10) {
						updateSegment(top, prevtop, bend, prevbend, index, numberrecursions+1);
					} else {
						GrappleMod.LOGGER.warn("Rope segment recursion limit exceeded");
					}
					break;
				}
			}
		}
	}

	public Vec linePlaneIntersection(Vec linepoint1, Vec linepoint2, Vec planepoint, Vec planenormal) {
		// calculate the intersection of a line and a plane
		// formula: https://en.wikipedia.org/wiki/Line%E2%80%93plane_intersection#Algebraic_form

		Vec linevec = linepoint2.sub(linepoint1);

		if (linevec.dot(planenormal) == 0) {
			return null;
		}

		double d = planepoint.sub(linepoint1).dot(planenormal) / linevec.dot(planenormal);
		return linepoint1.add(linevec.mult(d));
	}

	public Vec getNormal(Direction facing) {
		Vec3i facingvec = facing.getNormal();
		return new Vec(facingvec.getX(), facingvec.getY(), facingvec.getZ());
	}

	/**
	 * World-space normal of a hit side. Sides reported by ship raytraces are shipyard-space
	 * Directions, so for ship bends the normal must be rotated by the ship's current transform;
	 * world bends pass NO_SHIP and get the plain axis-aligned normal.
	 */
	public Vec getWorldNormal(Direction facing, long shipId) {
		Vec normal = getNormal(facing);
		if (shipId != NO_SHIP && this.rope.shipsAvailable()) {
			Vec world = this.rope.dirToWorld(shipId, normal);
			if (world != null) {
				return world;
			}
		}
		return normal;
	}

	public boolean hookPastBend(double ropelen) {
		return (this.getDistToFarthest() > ropelen);
	}

	public BlockPos getBendBlock(int index) {
		Vec inwardOffset = this.getNormal(this.segmentBottomSides.get(index)).changeLen(-this.intoBlock * 2)
				.add(this.getNormal(this.segmentTopSides.get(index)).changeLen(-this.intoBlock * 2));

		// ship bends: compute the block in shipyard space, where the bend's block actually lives
		long shipId = this.segmentShipIds.get(index);
		if (shipId != NO_SHIP) {
			Vec local = new Vec(this.segmentShipLocals.get(index));
			local.add_ip(inwardOffset);
			return BlockPos.containing(local.x, local.y, local.z);
		}

		Vec bendpos = new Vec(this.segments.get(index));
		bendpos.add_ip(inwardOffset);
		return BlockPos.containing(bendpos.x, bendpos.y, bendpos.z);
	}

	public void actuallyAddSegment(int index, Vec bendpoint, Direction bottomside, Direction topside, long shipId, Vec shipLocal) {
		segments.add(index, bendpoint);
		segmentBottomSides.add(index, bottomside);
		segmentTopSides.add(index, topside);
		segmentShipIds.add(index, shipId);
		segmentShipLocals.add(index, shipLocal);

		if (this.syncsToClients()) {
			SegmentMessage addmessage = new SegmentMessage(this.hookEntity.getId(), true, index, bendpoint, topside, bottomside, shipId, shipLocal != null ? shipLocal : new Vec(0, 0, 0));
			CommonSetup.network.send(this.segmentMessageTarget(), addmessage);
		}
	}

	public void print() {
		for (int i = 1; i < segments.size() - 1; i++) {
			GrappleMod.LOGGER.debug("segment {} {} {} {}", i, segmentTopSides.get(i), segmentBottomSides.get(i), segments.get(i));
		}
	}

	public Vec getClosest(Vec hookpos) {
		segments.set(0, hookpos);

		return segments.get(segments.size() - 2);
	}

	public double getDistToAnchor() {
		double dist = 0;
		for (int i = 0; i < segments.size() - 2; i++) {
			dist += segments.get(i).sub(segments.get(i+1)).length();
		}

		return dist;
	}

	public Vec getFarthest() {
		return segments.get(1);
	}

	public double getDistToFarthest() {
		double dist = 0;
		for (int i = 1; i < segments.size() - 1; i++) {
			dist += segments.get(i).sub(segments.get(i+1)).length();
		}

		return dist;
	}

	public double getDist(Vec hookpos, Vec playerpos) {
		segments.set(0, hookpos);
		segments.set(segments.size() - 1, playerpos);
		double dist = 0;
		for (int i = 0; i < segments.size() - 1; i++) {
			dist += segments.get(i).sub(segments.get(i+1)).length();
		}

		return dist;
	}

	/**
	 * Read-only: called from the render thread (culling), so it must not mutate the segment lists
	 * the tick threads are working with.
	 */
	public AABB getBoundingBox(Vec hookpos, Vec playerpos) {
		Vec minvec = new Vec(hookpos);
		Vec maxvec = new Vec(hookpos);
		List<Vec> points = new ArrayList<>(segments);
		points.add(playerpos);
		for (Vec segpos : points) {
			if (segpos == null) {
				continue;
			}
			if (segpos.x < minvec.x) {
				minvec.x = segpos.x;
			} else if (segpos.x > maxvec.x) {
				maxvec.x = segpos.x;
			}
			if (segpos.y < minvec.y) {
				minvec.y = segpos.y;
			} else if (segpos.y > maxvec.y) {
				maxvec.y = segpos.y;
			}
			if (segpos.z < minvec.z) {
				minvec.z = segpos.z;
			} else if (segpos.z > maxvec.z) {
				maxvec.z = segpos.z;
			}
		}
		return new AABB(minvec.x, minvec.y, minvec.z, maxvec.x, maxvec.y, maxvec.z);
	}
}
