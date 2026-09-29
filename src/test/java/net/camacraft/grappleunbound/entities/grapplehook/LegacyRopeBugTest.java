package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.utils.Vec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug, reproduced: the pre-fix sweep ({@link LegacyRopeSweep}) run through the same scenarios
 * as {@link RopeShipWrapTest}. With the hooked ship still, it wraps the pillar; with the ship
 * moving, the rope passes through the pillar without ever wrapping. The new sweep wraps in both.
 */
class LegacyRopeBugTest {

    private static final double ROPE_LENGTH = 100;

    private record Outcome(boolean everWrapped, boolean ropeWentThroughAPillar) {
    }

    /** Hooked to a ship, walk past a pillar (the first scenario of RopeShipWrapTest). */
    private static Outcome walkPastPillar(Vec shipMotion, boolean legacy) {
        FakeRopeWorld w = new FakeRopeWorld()
                .column(0, 0, 0, 13)
                .shipBlock(0, 0, 0)
                .placeShip(new Vec(-20, 5, 3), 0);
        Vec hookLocal = new Vec(1.05, 0.5, 0.5);
        Vec hook = w.shipToWorldNow(hookLocal);
        Vec player = new Vec(10, 5.5, 3.5);
        SegmentHandler rope = new SegmentHandler(w, () -> FakeRopeWorld.SHIP, hook, player);
        boolean wrapped = false;
        boolean through = false;
        for (int tick = 1; tick <= 25; tick++) {
            w.moveShip(shipMotion, 0);
            hook = w.shipToWorldNow(hookLocal);
            player = new Vec(10, 5.5, 3.5 - 0.4 * tick);
            if (legacy) {
                LegacyRopeSweep.update(rope, w, hook, player, ROPE_LENGTH, false);
            } else {
                rope.update(hook, player, ROPE_LENGTH, false);
            }
            wrapped |= rope.segments.size() > 2;
            through |= throughWorldBlock(rope, w);
        }
        return new Outcome(wrapped, through);
    }

    /**
     * The ship drags the hook end of an already-wrapped rope over the far corner of a wall's end
     * (a wall, not a pillar: the straight line past it stays blocked, so only the sweep is tested).
     */
    private static Outcome carryHookAroundWallEnd(boolean legacy) {
        FakeRopeWorld w = new FakeRopeWorld().shipBlock(0, 0, 0).placeShip(new Vec(14.05, 5, 7.5), 0);
        for (int z = -12; z <= 1; z++) {
            w.column(5, z, 0, 13);
        }
        Vec hookLocal = new Vec(-0.05, 0.5, 0.5);
        Vec player = new Vec(0, 5.5, 0);
        SegmentHandler rope = new SegmentHandler(w, () -> FakeRopeWorld.SHIP, w.shipToWorldNow(hookLocal), player);
        boolean wrapped = false;
        boolean through = false;
        for (int tick = 1; tick <= 40; tick++) {
            w.moveShip(new Vec(0, 0, -0.25), 0);
            Vec hook = w.shipToWorldNow(hookLocal);
            if (legacy) {
                LegacyRopeSweep.update(rope, w, hook, player, ROPE_LENGTH, false);
            } else {
                rope.update(hook, player, ROPE_LENGTH, false);
            }
            wrapped |= rope.segments.size() > 3;
            through |= throughWorldBlock(rope, w);
        }
        return new Outcome(wrapped, through);
    }

    private static boolean throughWorldBlock(SegmentHandler rope, RopeWorld w) {
        for (int i = 0; i < rope.segments.size() - 1; i++) {
            if (w.traceIn(RopeWorld.NO_SHIP, rope.segments.get(i), rope.segments.get(i + 1)) != null) {
                return true;
            }
        }
        return false;
    }

    @Test
    void oldSweepWrapsWhenTheHookedShipIsStill() {
        Outcome old = walkPastPillar(new Vec(0, 0, 0), true);
        assertTrue(old.everWrapped(), "the old sweep did wrap with a still ship");
    }

    @Test
    void oldSweepPhasesThroughWhenTheHookedShipMovesTowardTheRope() {
        Outcome old = walkPastPillar(new Vec(0.3, 0, 0), true);
        assertFalse(old.everWrapped(), "the old sweep never wraps while the ship moves toward the player");
        assertTrue(old.ropeWentThroughAPillar(), "...so the rope went through the pillar");

        Outcome fixed = walkPastPillar(new Vec(0.3, 0, 0), false);
        assertTrue(fixed.everWrapped());
        assertFalse(fixed.ropeWentThroughAPillar(), "the fixed rope never passes through the pillar");
    }

    @Test
    void oldSweepLetsTheShipDragTheHookEndThroughAWall() {
        Outcome old = carryHookAroundWallEnd(true);
        assertFalse(old.everWrapped(), "the old sweep never wrapped the second corner");
        assertTrue(old.ropeWentThroughAPillar(), "the old sweep let the hook end of the rope pass through the wall");

        Outcome fixed = carryHookAroundWallEnd(false);
        assertTrue(fixed.everWrapped(), "the fixed sweep wraps both corners");
        assertFalse(fixed.ropeWentThroughAPillar(), "the fixed rope never passes through the wall");
    }

    /**
     * The old line-of-sight prune: hooked to a block, walk on past a thin pillar. Once the straight
     * line from the hook to the player passes the pillar's far side, the old prune dropped the wrap
     * although the rope was still held by the pillar (the player never came back): the rope
     * jumped through it. A ship carrying the hook end makes that happen without the player moving.
     */
    @Test
    void oldPruneDropsAWrapStillHeldByAThinPillar() {
        for (boolean legacy : new boolean[] {true, false}) {
            FakeRopeWorld w = new FakeRopeWorld().column(0, 0, 0, 13);
            Vec hook = new Vec(-18.95, 5.5, 3.5);
            SegmentHandler rope = new SegmentHandler(w, () -> RopeWorld.NO_SHIP, hook, new Vec(10, 5.5, 3.5));
            boolean wrapped = false;
            for (int tick = 1; tick <= 25; tick++) {
                Vec player = new Vec(10, 5.5, 3.5 - 0.4 * tick);
                if (legacy) {
                    LegacyRopeSweep.update(rope, w, hook, player, ROPE_LENGTH, false);
                } else {
                    rope.update(hook, player, ROPE_LENGTH, false);
                }
                wrapped |= rope.segments.size() > 2;
            }
            assertTrue(wrapped, "legacy " + legacy + ": wrapped on the way past");
            if (legacy) {
                assertTrue(rope.segments.size() == 2, "the old prune let go of the pillar");
            } else {
                assertTrue(rope.segments.size() == 3, "the fixed rope is still wrapped round the pillar");
            }
        }
    }
}
