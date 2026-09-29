package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.core.Direction;

/**
 * Everything the rope's bend logic ({@link SegmentHandler}) asks of the world: ray traces, solid
 * checks and Valkyrien Skies ship transforms. The game implementation is {@link LevelRopeWorld};
 * tests supply their own.
 *
 * <p>A "frame" is either the world ({@link #NO_SHIP}) or one ship: the blocks that stay put
 * relative to each other. The rope logic measures last tick's rope in the frame of whatever it
 * hit, against that frame's blocks only, so a moving ship and the static world never get mixed
 * up in one test.
 *
 * <p>All ship queries use one pose, the ship's current game-tick pose, on both sides: the hook's
 * position, the ship bends and every trace must agree on where the ship is.
 */
public interface RopeWorld {

    long NO_SHIP = -1;

    /**
     * A block the rope hit. {@code location} is in world space; {@code side} is in the frame's
     * own space (shipyard space for a ship, see {@link #dirToWorld}).
     */
    record Hit(Vec location, Direction side, long shipId) {
    }

    /** False when there can be no ships (Valkyrien Skies absent), so ship work can be skipped. */
    boolean shipsAvailable();

    /** The nearest block of any frame (world or any ship) the segment from -> to hits, or null. */
    Hit trace(Vec from, Vec to);

    /** The nearest block of one frame (the world, or one ship) the segment hits, or null. */
    Hit traceIn(long frame, Vec from, Vec to);

    /** Whether a world-space point lies inside a solid block of the given frame. */
    boolean isSolidAt(Vec worldPoint, long frame);

    /** World position to ship-local (shipyard) position, or null if the ship isn't loaded. */
    Vec toShip(long shipId, Vec worldPos);

    /** Ship-local (shipyard) position to world position, or null if the ship isn't loaded. */
    Vec toWorld(long shipId, Vec shipPos);

    /** A shipyard-space direction rotated into world space, or null if the ship isn't loaded. */
    Vec dirToWorld(long shipId, Vec shipDir);

    /** Where a world point from last tick sits now if it were glued to the ship, or null. */
    Vec prevTickToCurrent(long shipId, Vec prevWorldPos);

    /** Where a world point glued to the ship was last tick, or null. */
    Vec currentToPrevTick(long shipId, Vec worldPos);
}
