package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.utils.Vec;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rope wrapping with a Valkyrien Skies ship in play, headless: {@link SegmentHandler} runs
 * against {@link FakeRopeWorld} (unit-cube blocks plus one rigid ship whose tick and previous-tick
 * poses the test sets). Every rope update is the call the owner's client makes each tick
 * ({@code movinghook = false}).
 */
class RopeShipWrapTest {

    private static final double ROPE_LENGTH = 100;
    private static final long SHIP = FakeRopeWorld.SHIP;

    /**
     * The reported bug: hooked to a ship, walk around a pillar and the rope should wrap it. Tried
     * with the ship still, and moving toward the player, away, sideways, up and turning - it must
     * wrap every time, the same as when hooked to a block.
     */
    @Test
    void shipHookedRopeWrapsAWorldPillarHoweverTheShipMoves() {
        Vec[] motions = {
                new Vec(0, 0, 0),      // still
                new Vec(0.3, 0, 0),    // toward the player (what the rope's pull does to a light ship)
                new Vec(-0.3, 0, 0),   // away
                new Vec(0, 0, 0.2),    // sideways
                new Vec(0, 0.2, 0),    // up
        };
        // the owner's client (movinghook false) and the server's ship-pull rope (true)
        for (boolean serverSide : new boolean[] {false, true}) {
            for (Vec motion : motions) {
                this.wrapPillarWhileShipMoves(motion, 0, serverSide);
            }
            this.wrapPillarWhileShipMoves(new Vec(0.1, 0, 0), 0.01, serverSide); // turning
        }
    }

    private void wrapPillarWhileShipMoves(Vec motion, double yawPerTick, boolean serverSide) {
        String scenario = (serverSide ? "server" : "client") + ", ship motion " + motion + " yaw/tick " + yawPerTick;
        FakeRopeWorld w = new FakeRopeWorld()
                .column(0, 0, 0, 13)
                .shipBlock(0, 0, 0)
                .placeShip(new Vec(-20, 5, 3), 0);
        Vec hookLocal = new Vec(1.05, 0.5, 0.5); // 0.05 off the ship's east face, like a real hook
        Vec hook = w.shipToWorldNow(hookLocal);
        Vec player = new Vec(10, 5.5, 3.5);
        SegmentHandler rope = new SegmentHandler(w, () -> SHIP, hook, player);

        boolean oldSweepWouldHaveBailed = false;
        int wrappedAt = -1;
        for (int tick = 1; tick <= 25; tick++) {
            Vec prevHook = hook;
            Vec prevPlayer = player;
            w.moveShip(motion, yawPerTick);
            hook = w.shipToWorldNow(hookLocal);
            player = new Vec(10, 5.5, 3.5 - 0.4 * tick);

            int before = rope.segments.size();
            rope.update(hook, player, ROPE_LENGTH, serverSide);
            assertRopeClear(rope, w, scenario + " tick " + tick);
            if (before == 2 && rope.segments.size() > 2 && wrappedAt < 0) {
                wrappedAt = tick;
                // The old sweep asked "was last tick's rope already blocked?" of every block and
                // ship at once: last tick's hook point is where the hull was, so a ship that moved
                // toward the rope has it inside its hull now and the sweep gave up.
                RopeWorld.Hit old = w.trace(prevPlayer, prevHook);
                oldSweepWouldHaveBailed = old != null && old.shipId() == SHIP;
            }
        }

        assertTrue(wrappedAt > 0, scenario + ": the rope never wrapped the pillar");
        assertEquals(3, rope.segments.size(), scenario + ": expected exactly one bend, got " + rope.segments);
        assertBendNear(rope, 1.05, 1.05, 0.1, RopeWorld.NO_SHIP, scenario);
        assertRopeClear(rope, w, scenario);
        if (motion.x > 0) {
            assertTrue(oldSweepWouldHaveBailed, scenario + ": expected last tick's rope to hit the moved ship (the old bail-out)");
        }
    }

    /**
     * Wrapped once already, the ship carries the hook end of the rope over the pillar's far corner:
     * the stretch from the hook to the first bend must be swept too. The owner's client never
     * swept it, so it passed straight through the pillar.
     */
    @Test
    void shipCarryingTheHookEndAroundAPillarWrapsTheNextCorner() {
        FakeRopeWorld w = new FakeRopeWorld()
                .column(5, 1, 0, 13)
                .shipBlock(0, 0, 0)
                .placeShip(new Vec(14.05, 5, 7.5), 0);
        Vec hookLocal = new Vec(-0.05, 0.5, 0.5); // off the ship's west face
        Vec player = new Vec(0, 5.5, 0);
        Vec hook = w.shipToWorldNow(hookLocal);
        SegmentHandler rope = new SegmentHandler(w, () -> SHIP, hook, player);

        for (int tick = 1; tick <= 40; tick++) {
            w.moveShip(new Vec(0, 0, -0.25), 0);
            hook = w.shipToWorldNow(hookLocal);
            rope.update(hook, player, ROPE_LENGTH, false);
            assertRopeClear(rope, w, "two corners tick " + tick);
        }

        // hook -> north-east corner -> north-west corner -> player
        assertEquals(4, rope.segments.size(), "expected two bends, got " + rope.segments);
        assertBendNear(rope, 6.05, 2.05, 0.1, RopeWorld.NO_SHIP, "north-east corner");
        assertBendNear(rope, 4.95, 2.05, 0.1, RopeWorld.NO_SHIP, "north-west corner");
        assertRopeClear(rope, w, "two corners");
        // without the north-east bend, the rope from the hook to the first bend is inside the pillar
        assertNotNull(w.trace(hook, rope.segments.get(2)), "the hook-end stretch should need the second bend");
    }

    /**
     * Wrapping around the hooked ship's own hull while it moves and turns: the bend is a ship
     * bend, stays on the ship's corner (fixed ship-local position) and the rope stays clear.
     */
    @Test
    void ropeWrapsTheHookedShipsOwnCornerWhileItMoves() {
        FakeRopeWorld w = new FakeRopeWorld()
                .shipBlock(0, 0, 0).shipBlock(0, 0, 1).shipBlock(0, 0, 2)
                .placeShip(new Vec(0, 5, 0), 0);
        Vec hookLocal = new Vec(1.05, 0.5, 2.5); // east face, near the south end
        Vec hook = w.shipToWorldNow(hookLocal);
        IntFunction<Vec> path = tick -> tick <= 20
                ? new Vec(10, 5.5, 2.5 - 0.45 * tick)
                : new Vec(10 - 0.75 * (tick - 20), 5.5, 2.5 - 0.45 * 20);
        SegmentHandler rope = new SegmentHandler(w, () -> SHIP, hook, path.apply(0));

        Vec localAtWrap = null;
        for (int tick = 1; tick <= 45; tick++) {
            w.moveShip(new Vec(0, 0, 0.1), 0.004);
            hook = w.shipToWorldNow(hookLocal);
            rope.update(hook, path.apply(tick), ROPE_LENGTH, false);
            assertRopeClear(rope, w, "own hull tick " + tick);
            if (localAtWrap == null && rope.segments.size() > 2) {
                localAtWrap = new Vec(rope.segmentShipLocals.get(1));
            }
        }

        assertEquals(3, rope.segments.size(), "expected one bend, got " + rope.segments);
        assertEquals(SHIP, (long) rope.segmentShipIds.get(1), "the bend should be on the ship");
        Vec local = rope.segmentShipLocals.get(1);
        assertTrue(Math.abs(local.x - 1.05) < 0.1 && Math.abs(local.z + 0.05) < 0.1,
                "the bend should sit on the ship's north-east corner, local " + local);
        assertTrue(local.sub(localAtWrap).length() < 1e-9, "a ship bend must stay put on the ship");
        assertRopeClear(rope, w, "own hull");
    }

    /**
     * A ship moving into a still rope (hook on a block) catches it on both corners of its leading
     * face, and keeps pushing it along.
     */
    @Test
    void shipMovingIntoAStillRopeCatchesIt() {
        FakeRopeWorld w = new FakeRopeWorld()
                .shipBlock(0, 0, 0)
                .placeShip(new Vec(9.5, 5, 3), 0);
        Vec hook = new Vec(0, 5.5, 0);
        Vec player = new Vec(20, 5.5, 0);
        SegmentHandler rope = new SegmentHandler(w, () -> RopeWorld.NO_SHIP, hook, player);

        for (int tick = 1; tick <= 20; tick++) {
            w.moveShip(new Vec(0, 0, -0.3), 0);
            rope.update(hook, player, ROPE_LENGTH, false);
            assertRopeClear(rope, w, "pushed rope tick " + tick);
        }

        assertEquals(4, rope.segments.size(), "expected two bends, got " + rope.segments);
        assertEquals(SHIP, (long) rope.segmentShipIds.get(1));
        assertEquals(SHIP, (long) rope.segmentShipIds.get(2));
        assertBendNear(rope, 9.45, -3.05, 0.1, SHIP, "west corner");
        assertBendNear(rope, 10.55, -3.05, 0.1, SHIP, "east corner");
        assertRopeClear(rope, w, "pushed rope");
    }

    /** The baseline the ship cases must match: a rope hooked to a still point wraps a pillar. */
    @Test
    void blockHookedRopeWrapsAPillar() {
        FakeRopeWorld w = new FakeRopeWorld().column(0, 0, 0, 13);
        Vec hook = new Vec(-18.95, 5.5, 3.5);
        Vec player = new Vec(10, 5.5, 3.5);
        SegmentHandler rope = new SegmentHandler(w, () -> RopeWorld.NO_SHIP, hook, player);

        for (int tick = 1; tick <= 25; tick++) {
            player = new Vec(10, 5.5, 3.5 - 0.4 * tick);
            rope.update(hook, player, ROPE_LENGTH, false);
            assertRopeClear(rope, w, "block hook tick " + tick);
        }

        assertEquals(3, rope.segments.size(), "expected one bend, got " + rope.segments);
        assertBendNear(rope, 1.05, 1.05, 0.1, RopeWorld.NO_SHIP, "pillar");
        assertRopeClear(rope, w, "block hook");
    }

    /** Walking back the way you came unwraps the rope again. */
    @Test
    void shipHookedRopeUnwrapsWhenWalkingBack() {
        FakeRopeWorld w = new FakeRopeWorld()
                .column(0, 0, 0, 13)
                .shipBlock(0, 0, 0)
                .placeShip(new Vec(-20, 5, 3), 0);
        Vec hookLocal = new Vec(1.05, 0.5, 0.5);
        SegmentHandler rope = new SegmentHandler(w, () -> SHIP, w.shipToWorldNow(hookLocal), new Vec(10, 5.5, 3.5));
        for (int tick = 1; tick <= 50; tick++) {
            w.moveShip(new Vec(0.05, 0, 0), 0);
            double z = tick <= 25 ? 3.5 - 0.4 * tick : 3.5 - 0.4 * (50 - tick);
            rope.update(w.shipToWorldNow(hookLocal), new Vec(10, 5.5, z), ROPE_LENGTH, false);
            if (tick == 25) {
                assertEquals(3, rope.segments.size(), "wrapped on the way out");
            }
        }
        assertEquals(2, rope.segments.size(), "unwrapped on the way back, got " + rope.segments);
    }

    /** A bend with nothing inside its turn (a stale seam catch) is still pruned. */
    @Test
    void staleBendInOpenAirIsPruned() {
        FakeRopeWorld w = new FakeRopeWorld().column(30, 30, 0, 3);
        Vec hook = new Vec(0, 5.5, 0);
        Vec player = new Vec(20, 5.5, 0);
        SegmentHandler rope = new SegmentHandler(w, () -> RopeWorld.NO_SHIP, hook, player);
        rope.actuallyAddSegment(1, new Vec(10, 5.5, 1.5), net.minecraft.core.Direction.UP, net.minecraft.core.Direction.EAST,
                RopeWorld.NO_SHIP, null);
        rope.update(hook, player, ROPE_LENGTH, false);
        assertEquals(2, rope.segments.size(), "a bend with only air inside its turn should go, got " + rope.segments);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void assertBendNear(SegmentHandler rope, double x, double z, double tolerance, long shipId, String what) {
        for (int i = 1; i < rope.segments.size() - 1; i++) {
            Vec bend = rope.segments.get(i);
            if (Math.abs(bend.x - x) < tolerance && Math.abs(bend.z - z) < tolerance) {
                assertEquals(shipId, (long) rope.segmentShipIds.get(i), what + ": bend on the wrong frame");
                return;
            }
        }
        fail(what + ": no bend near x " + x + " z " + z + " in " + rope.segments);
    }

    /** No stretch of rope passes through a block or the ship. */
    private static void assertRopeClear(SegmentHandler rope, FakeRopeWorld w, String what) {
        for (int i = 0; i < rope.segments.size() - 1; i++) {
            RopeWorld.Hit hit = w.trace(rope.segments.get(i), rope.segments.get(i + 1));
            assertNull(hit, what + ": stretch " + i + " (" + rope.segments.get(i) + " -> " + rope.segments.get(i + 1)
                    + ") passes through a block at " + (hit == null ? null : hit.location()));
        }
    }
}
