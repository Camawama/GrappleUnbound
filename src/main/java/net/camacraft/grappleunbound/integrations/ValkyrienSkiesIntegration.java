package net.camacraft.grappleunbound.integrations;

import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.utils.Vec;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grapple Unbound's Valkyrien Skies integration. Every VS access goes through Camafy's ship utilities
 * (net.camacraft.camafy.compat.vs), which name no VS classes and answer as if there were no ships
 * when VS is absent, so this class is safe to load in any pack. Callers still check
 * {@code Ships.available()} to skip the work when there can be no ships.
 */
public class ValkyrienSkiesIntegration {

    public static final long NO_SHIP = -1;

    /** Rotational damping per pull tick while a rope hauls on a ship (see queueShipImpulse). */
    private static final double PULL_SPIN_DAMPING = 0.08;

    /**
     * Attaches the hook to the ship managing blockPos, if any. The side-hit nudge is applied in
     * shipyard space (hit Directions on ships are shipyard-space), so it stays correct for rotated
     * ships. Returns true if a ship attachment was made; the caller applies the nudge in world
     * space itself when this returns false.
     */
    public static boolean attachToShip(GrapplehookEntity entity, Level level, Vec3 pos, BlockPos blockPos, Vec3 nudge) {
        if (blockPos == null || !Ships.inShipyard(level, blockPos)) {
            return false;
        }
        ShipRef ship = Ships.managing(level, blockPos);
        if (ship == null) {
            return false;
        }

        Vec3 local = ship.toShip(pos);
        if (local == null) {
            return false;
        }
        local = local.add(nudge);
        Vec3 world = ship.toWorld(local);
        if (world == null) {
            return false;
        }

        entity.setPos(world.x, world.y, world.z);
        entity.getPersistentData().putLong("vs_ship_id", ship.id());
        entity.getPersistentData().putDouble("vs_local_x", local.x);
        entity.getPersistentData().putDouble("vs_local_y", local.y);
        entity.getPersistentData().putDouble("vs_local_z", local.z);
        return true;
    }

    /**
     * Re-positions an attached hook onto its (possibly moving) ship. Returns false when the hook
     * is ship-attached but the ship can no longer be resolved (unloaded/deleted), so the caller
     * can decide to detach.
     */
    public static boolean updateShipAttachment(GrapplehookEntity entity, Level level) {
        if (!entity.getPersistentData().contains("vs_ship_id")) {
            return true;
        }
        long shipId = entity.getPersistentData().getLong("vs_ship_id");
        ShipRef ship = getShipById(level, shipId);
        if (ship == null) {
            return false;
        }

        double x = entity.getPersistentData().getDouble("vs_local_x");
        double y = entity.getPersistentData().getDouble("vs_local_y");
        double z = entity.getPersistentData().getDouble("vs_local_z");

        Vec3 worldPos = ship.toWorld(new Vec3(x, y, z));
        if (worldPos == null) {
            return false;
        }

        entity.setPos(worldPos.x, worldPos.y, worldPos.z);

        if (entity.thisPos != null) {
            entity.thisPos.x = worldPos.x;
            entity.thisPos.y = worldPos.y;
            entity.thisPos.z = worldPos.z;
        }
        return true;
    }

    /** The loaded ship with this id in the level's dimension, or null. */
    public static ShipRef getShipById(Level level, long shipId) {
        return shipId == NO_SHIP ? null : Ships.byId(level, shipId);
    }

    /** Ship id managing a (shipyard-space) block position, or NO_SHIP. Works on both sides. */
    public static long getShipIdManaging(Level level, BlockPos pos) {
        if (pos == null || !Ships.inShipyard(level, pos)) {
            return NO_SHIP;
        }
        ShipRef ship = Ships.managing(level, pos);
        return ship == null ? NO_SHIP : ship.id();
    }

    /** World-space position -> ship-local (shipyard) position, or null if the ship isn't loaded. */
    public static Vec worldToShip(Level level, long shipId, Vec worldPos) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.toShip(worldPos.toVec3d());
        return v == null ? null : new Vec(v);
    }

    /** Ship-local (shipyard) position -> world-space position, or null if the ship isn't loaded. */
    public static Vec shipToWorld(Level level, long shipId, Vec localPos) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.toWorld(localPos.toVec3d());
        return v == null ? null : new Vec(v);
    }

    public static boolean isBlockInShipyard(Level level, BlockPos pos) {
        return Ships.inShipyard(level, pos);
    }

    /**
     * Rotates a shipyard-space direction (e.g. a block hit side) into world space, without scaling.
     * Ship raytraces report hit Directions in shipyard space; any math done in world space (rope
     * bend planes, corner offsets) must use the rotated normal or it silently assumes the ship is
     * axis-aligned. Returns null if the ship isn't loaded.
     */
    public static Vec shipDirToWorld(Level level, long shipId, Vec dir) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.dirToWorld(dir.toVec3d());
        return v == null ? null : new Vec(v);
    }

    /**
     * Where a world-space point from the previous tick sits now, if it were glued to the ship.
     * Mapping last tick's rope through this puts the rope and the ship in a shared reference
     * frame, so a ship moving or rotating into a stationary rope still shows up as the rope
     * sweeping across the hull. Returns null if the ship isn't loaded.
     */
    public static Vec prevTickWorldPosToCurrent(Level level, long shipId, Vec prevWorldPos) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.prevTickToCurrent(prevWorldPos.toVec3d());
        return v == null ? null : new Vec(v);
    }

    /**
     * Where a world-space point glued to the ship was on the previous tick (the inverse of
     * {@link #prevTickWorldPosToCurrent}). Returns null if the ship isn't loaded.
     */
    public static Vec currentWorldPosToPrevTick(Level level, long shipId, Vec worldPos) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.currentToPrevTick(worldPos.toVec3d());
        return v == null ? null : new Vec(v);
    }

    /** Total mass (kg) of the ship, or 0 if it can't be resolved (server side only). */
    public static double getShipMass(Level level, long shipId) {
        ShipRef ship = getShipById(level, shipId);
        double mass = ship == null ? Double.NaN : ship.mass();
        // NaN on the client (VS doesn't sync mass) and for an unresolved ship
        return Double.isFinite(mass) ? mass : 0;
    }

    /**
     * True when the entity is standing on the given ship (grounded and overlapping its world
     * bounds). Used to stop a player from pulling the very ship they are standing on.
     */
    public static boolean isEntityStandingOnShip(Level level, long shipId, Entity entity) {
        if (!entity.onGround()) {
            return false;
        }
        ShipRef ship = getShipById(level, shipId);
        AABB shipBox = ship == null ? null : ship.worldAabb();
        if (shipBox == null) {
            return false;
        }
        AABB box = entity.getBoundingBox().inflate(0.6);
        return box.minX <= shipBox.maxX && box.maxX >= shipBox.minX
                && box.minY <= shipBox.maxY + 0.5 && box.maxY >= shipBox.minY - 0.5
                && box.minZ <= shipBox.maxZ && box.maxZ >= shipBox.minZ;
    }

    /**
     * Velocity (m/s) of the ship's hull at a world-space point: its linear velocity plus the
     * spin's share (v + omega x r), so a point far from the center of a turning ship reads as
     * moving as fast as it really does. Null if the ship can't be resolved.
     */
    public static Vec getShipVelocityAt(Level level, long shipId, Vec worldPos) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 v = ship == null ? null : ship.velocityAt(worldPos.toVec3d());
        return v == null ? null : new Vec(v);
    }

    /** Magnitude of the ship's angular velocity (rad/s), or 0 if it can't be resolved. */
    public static double getShipAngularSpeed(Level level, long shipId) {
        ShipRef ship = getShipById(level, shipId);
        Vec3 omega = ship == null ? null : ship.angularVelocity();
        return omega == null ? 0 : omega.length();
    }

    // ---- hook impact impulses -------------------------------------------------------------
    // Queued with Camafy's shared ship force hook (server side), which delivers each impulse
    // as a force over exactly one physics step at the next step.

    /**
     * Queues a world-space impulse (N&middot;s) on the given ship. Applied at shipyardPos when
     * given (imparts torque, like a real rope at an attach point), or at the center of mass when
     * shipyardPos is null (pure translation).
     */
    public static void queueShipImpulse(Level level, long shipId, Vec worldImpulseNs, Vec shipyardPos) {
        queueShipImpulse(level, shipId, worldImpulseNs, shipyardPos, false);
    }

    /**
     * As above; dampRotation additionally bleeds off a slice of the ship's angular momentum on
     * the same physics tick. A force at an off-center attach point with no damping pumps spin
     * (the point whirls, the force direction chases the player) until the ship tumbles and
     * orbits; damping while the rope pulls turns that into a settled hang / upright pendulum.
     */
    public static void queueShipImpulse(Level level, long shipId, Vec worldImpulseNs, Vec shipyardPos, boolean dampRotation) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 impulse = worldImpulseNs.toVec3d();
        if (shipyardPos != null) {
            ShipForces.applyImpulseAtShipPos(server, shipId, impulse, shipyardPos.toVec3d());
        } else {
            ShipForces.applyImpulse(server, shipId, impulse, null);
        }
        if (dampRotation) {
            // shed ~8% of the angular momentum this physics step (settles in ~0.5s at 60 Hz)
            ShipForces.dampSpin(server, shipId, PULL_SPIN_DAMPING);
        }
    }
}
