package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in world for rope tests: unit-cube world blocks plus one rigid "ship" (unit cubes in
 * ship-local space, a pose of translation + yaw for this tick and the previous tick), answering
 * the same questions a level with Valkyrien Skies does: traces report ship hits with the side in
 * ship space and the location in world space.
 */
final class FakeRopeWorld implements RopeWorld {

    static final long SHIP = 7;

    private final List<int[]> worldBlocks = new ArrayList<>();
    private final List<int[]> shipBlocks = new ArrayList<>();

    /** Ship pose: world = rotateY(yaw) * local + offset. */
    private Vec offset = new Vec(0, 0, 0);
    private double yaw;
    private Vec prevOffset = new Vec(0, 0, 0);
    private double prevYaw;

    FakeRopeWorld column(int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            this.worldBlocks.add(new int[] {x, y, z});
        }
        return this;
    }

    FakeRopeWorld shipBlock(int x, int y, int z) {
        this.shipBlocks.add(new int[] {x, y, z});
        return this;
    }

    FakeRopeWorld placeShip(Vec offset, double yaw) {
        this.offset = offset;
        this.yaw = yaw;
        this.prevOffset = offset;
        this.prevYaw = yaw;
        return this;
    }

    /** One game tick of ship motion: last tick's pose becomes the previous pose. */
    void moveShip(Vec delta, double deltaYaw) {
        this.prevOffset = this.offset;
        this.prevYaw = this.yaw;
        this.offset = this.offset.add(delta);
        this.yaw += deltaYaw;
    }

    /** One game tick with the ship standing still. */
    void holdShip() {
        this.moveShip(new Vec(0, 0, 0), 0);
    }

    // ---- ship transforms ---------------------------------------------------------------------

    private static Vec rotY(Vec v, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Vec(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);
    }

    private static Vec localToWorld(Vec local, Vec offset, double yaw) {
        return rotY(local, yaw).add(offset);
    }

    private static Vec worldToLocal(Vec world, Vec offset, double yaw) {
        return rotY(world.sub(offset), -yaw);
    }

    Vec shipToWorldNow(Vec local) {
        return localToWorld(local, this.offset, this.yaw);
    }

    // ---- RopeWorld ---------------------------------------------------------------------------

    @Override
    public boolean shipsAvailable() {
        return true;
    }

    @Override
    public Hit trace(Vec from, Vec to) {
        Hit world = this.traceIn(NO_SHIP, from, to);
        Hit ship = this.traceIn(SHIP, from, to);
        if (world == null) {
            return ship;
        }
        if (ship == null) {
            return world;
        }
        return ship.location().sub(from).length() < world.location().sub(from).length() ? ship : world;
    }

    @Override
    public Hit traceIn(long frame, Vec from, Vec to) {
        if (frame == NO_SHIP) {
            return traceCubes(this.worldBlocks, from, to, NO_SHIP);
        }
        Vec a = worldToLocal(from, this.offset, this.yaw);
        Vec b = worldToLocal(to, this.offset, this.yaw);
        Hit local = traceCubes(this.shipBlocks, a, b, SHIP);
        return local == null ? null : new Hit(localToWorld(local.location(), this.offset, this.yaw), local.side(), SHIP);
    }

    @Override
    public boolean isSolidAt(Vec worldPoint, long frame) {
        Vec p = frame == NO_SHIP ? worldPoint : worldToLocal(worldPoint, this.offset, this.yaw);
        for (int[] c : frame == NO_SHIP ? this.worldBlocks : this.shipBlocks) {
            if (p.x >= c[0] && p.x < c[0] + 1 && p.y >= c[1] && p.y < c[1] + 1 && p.z >= c[2] && p.z < c[2] + 1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Vec toShip(long shipId, Vec worldPos) {
        return worldToLocal(worldPos, this.offset, this.yaw);
    }

    @Override
    public Vec toWorld(long shipId, Vec shipPos) {
        return localToWorld(shipPos, this.offset, this.yaw);
    }

    @Override
    public Vec dirToWorld(long shipId, Vec shipDir) {
        return rotY(shipDir, this.yaw);
    }

    @Override
    public Vec prevTickToCurrent(long shipId, Vec prevWorldPos) {
        return localToWorld(worldToLocal(prevWorldPos, this.prevOffset, this.prevYaw), this.offset, this.yaw);
    }

    @Override
    public Vec currentToPrevTick(long shipId, Vec worldPos) {
        return localToWorld(worldToLocal(worldPos, this.offset, this.yaw), this.prevOffset, this.prevYaw);
    }

    // ---- ray vs unit cubes -------------------------------------------------------------------

    /** Nearest unit cube the segment enters (slab test), like a block ray trace. */
    private static Hit traceCubes(List<int[]> cubes, Vec from, Vec to, long shipId) {
        Vec d = to.sub(from);
        double best = Double.MAX_VALUE;
        Hit bestHit = null;
        for (int[] c : cubes) {
            double[] lo = {c[0], c[1], c[2]};
            double[] o = {from.x, from.y, from.z};
            double[] dir = {d.x, d.y, d.z};
            double tEnter = Double.NEGATIVE_INFINITY;
            double tExit = Double.POSITIVE_INFINITY;
            int enterAxis = -1;
            boolean miss = false;
            for (int axis = 0; axis < 3; axis++) {
                double min = lo[axis];
                double max = lo[axis] + 1;
                if (Math.abs(dir[axis]) < 1e-12) {
                    if (o[axis] < min || o[axis] > max) {
                        miss = true;
                        break;
                    }
                    continue;
                }
                double t1 = (min - o[axis]) / dir[axis];
                double t2 = (max - o[axis]) / dir[axis];
                double near = Math.min(t1, t2);
                double far = Math.max(t1, t2);
                if (near > tEnter) {
                    tEnter = near;
                    enterAxis = axis;
                }
                tExit = Math.min(tExit, far);
            }
            if (miss || tEnter > tExit || tExit < 0 || tEnter > 1) {
                continue;
            }
            Direction side;
            double t;
            if (tEnter < 0 || enterAxis < 0) {
                // starts inside the cube: a hit right at the start, facing back along the ray
                t = 0;
                side = Direction.getNearest(-d.x, -d.y, -d.z);
            } else {
                t = tEnter;
                double sign = -Math.signum(dir[enterAxis]);
                side = switch (enterAxis) {
                    case 0 -> sign > 0 ? Direction.EAST : Direction.WEST;
                    case 1 -> sign > 0 ? Direction.UP : Direction.DOWN;
                    default -> sign > 0 ? Direction.SOUTH : Direction.NORTH;
                };
            }
            if (t < best) {
                best = t;
                bestHit = new Hit(from.add(d.mult(t)), side, shipId);
            }
        }
        return bestHit;
    }
}
