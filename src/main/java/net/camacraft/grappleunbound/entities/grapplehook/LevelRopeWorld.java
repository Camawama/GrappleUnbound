package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.integrations.ValkyrienSkiesIntegration;
import net.camacraft.grappleunbound.utils.Vec;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The rope's view of a real level: world blocks plus Valkyrien Skies ships (through Camafy).
 *
 * <p>Ships are traced in their own shipyard space with this game tick's pose, on the client too.
 * {@code Ships.clip} can't be used for the rope: on the client, Valkyrien Skies traces client
 * ships at their <em>render</em> pose (interpolated for the last frame, up to a tick behind),
 * while the hook, the ship bends and the previous-tick mapping all use the tick pose. On a moving
 * ship that put the hook (0.05 blocks off the hull) inside the hull the traces saw, and it made
 * bends land up to a tick of ship motion away from the corner they belong to.
 *
 * <p>World blocks are traced on their own (vanilla block traversal, no ships), since
 * {@code Level.clip} includes ships once Valkyrien Skies is installed.
 */
public final class LevelRopeWorld implements RopeWorld {

    /** Margin around a ship's world bounds (they may lag the tick pose slightly on a client). */
    private static final double SHIP_BOX_MARGIN = 2.0;

    private final Level level;

    public LevelRopeWorld(Level level) {
        this.level = level;
    }

    @Override
    public boolean shipsAvailable() {
        return Ships.available();
    }

    @Override
    public Hit trace(Vec from, Vec to) {
        Hit best = this.traceWorld(from, to);
        if (!Ships.available()) {
            return best;
        }
        double bestDist = best == null ? Double.MAX_VALUE : best.location().sub(from).length();
        AABB box = new AABB(from.toVec3d(), to.toVec3d()).inflate(SHIP_BOX_MARGIN);
        for (ShipRef ship : Ships.intersecting(this.level, box)) {
            Hit hit = this.traceShip(ship, from, to);
            if (hit != null) {
                double dist = hit.location().sub(from).length();
                if (dist < bestDist) {
                    best = hit;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    @Override
    public Hit traceIn(long frame, Vec from, Vec to) {
        if (frame == NO_SHIP) {
            return this.traceWorld(from, to);
        }
        ShipRef ship = ValkyrienSkiesIntegration.getShipById(this.level, frame);
        return ship == null ? null : this.traceShip(ship, from, to);
    }

    private Hit traceWorld(Vec from, Vec to) {
        BlockHitResult hit = clipBlocks(this.level, from.toVec3d(), to.toVec3d());
        return hit == null ? null : new Hit(new Vec(hit.getLocation()), hit.getDirection(), NO_SHIP);
    }

    /** Traces one ship in its shipyard space (tick pose); the hit comes back in world space. */
    private Hit traceShip(ShipRef ship, Vec from, Vec to) {
        Vec3 a = ship.toShip(from.toVec3d());
        Vec3 b = ship.toShip(to.toVec3d());
        BoundingBox blocks = ship.shipyardBlocks();
        if (a == null || b == null || blocks == null) {
            return null;
        }
        // only the stretch of the ray that runs through the ship's own blocks: nothing else in
        // shipyard space is the ship, and it keeps the trace off chunks the ship doesn't own
        AABB bounds = new AABB(blocks.minX(), blocks.minY(), blocks.minZ(),
                blocks.maxX() + 1, blocks.maxY() + 1, blocks.maxZ() + 1).inflate(1.0);
        Vec3 start = bounds.contains(a) ? a : bounds.clip(a, b).orElse(null);
        Vec3 end = bounds.contains(b) ? b : bounds.clip(b, a).orElse(null);
        if (start == null || end == null) {
            return null;
        }
        BlockHitResult hit = clipBlocks(this.level, start, end);
        if (hit == null) {
            return null;
        }
        Vec3 world = ship.toWorld(hit.getLocation());
        return world == null ? null : new Hit(new Vec(world), hit.getDirection(), ship.id());
    }

    @Override
    public boolean isSolidAt(Vec worldPoint, long frame) {
        Vec p = worldPoint;
        if (frame != NO_SHIP) {
            p = this.toShip(frame, worldPoint);
            if (p == null) {
                return false;
            }
        }
        BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
        return this.level.isLoaded(pos) && !this.level.getBlockState(pos).getCollisionShape(this.level, pos).isEmpty();
    }

    @Override
    public Vec toShip(long shipId, Vec worldPos) {
        return ValkyrienSkiesIntegration.worldToShip(this.level, shipId, worldPos);
    }

    @Override
    public Vec toWorld(long shipId, Vec shipPos) {
        return ValkyrienSkiesIntegration.shipToWorld(this.level, shipId, shipPos);
    }

    @Override
    public Vec dirToWorld(long shipId, Vec shipDir) {
        return ValkyrienSkiesIntegration.shipDirToWorld(this.level, shipId, shipDir);
    }

    @Override
    public Vec prevTickToCurrent(long shipId, Vec prevWorldPos) {
        return ValkyrienSkiesIntegration.prevTickWorldPosToCurrent(this.level, shipId, prevWorldPos);
    }

    @Override
    public Vec currentToPrevTick(long shipId, Vec worldPos) {
        return ValkyrienSkiesIntegration.currentWorldPosToPrevTick(this.level, shipId, worldPos);
    }

    /**
     * Vanilla's block ray trace (collision shapes, no fluids) over exactly the blocks of this
     * level, never ships: the same traversal {@code BlockGetter.clip} uses, which Valkyrien Skies
     * replaces with a ship-including version on {@code Level}. Unloaded chunks count as air (they
     * are never loaded for a rope). Null on a miss.
     */
    static BlockHitResult clipBlocks(Level level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (lvl, pos) -> {
            if (!lvl.isLoaded(pos)) {
                return null;
            }
            BlockState state = lvl.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(lvl, pos);
            return shape.isEmpty() ? null : shape.clip(from, to, pos);
        }, lvl -> null);
    }
}
