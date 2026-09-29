package net.camacraft.grappleunbound.entities.grapplehook;

import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.core.Direction;

/**
 * The rope sweep as it was before the ship fix (GrappleVS 1.0.2 + the rounds in CHANGELOG), ported
 * line for line onto {@link RopeWorld} so the same scenarios can be run through it. Test-only:
 * it exists to show what the old logic did with a moving ship, next to what the new one does.
 * Differences from the original are only in how the world is asked: every
 * {@code GrapplemodUtils.rayTraceBlocks} is {@link RopeWorld#trace} (all frames at once, as
 * {@code Ships.clip} did), and the "reject foreign corners while spinning over 1 rad/s" guard is
 * left out (no test ship spins that fast).
 */
final class LegacyRopeSweep {

    private LegacyRopeSweep() {
    }

    static void update(SegmentHandler h, RopeWorld w, Vec hookpos, Vec playerpos, double ropelen, boolean movinghook) {
        if (h.prevHookPos == null) {
            h.prevHookPos = hookpos;
            h.prevPlayerPos = playerpos;
        }

        h.refreshShipBends();

        h.segments.set(0, hookpos);
        h.segments.set(h.segments.size() - 1, playerpos);
        h.ropeLen = ropelen;

        for (int i = 1; i < h.segments.size() - 1; ) {
            if (w.trace(h.segments.get(i - 1), h.segments.get(i + 1)) == null) {
                h.removeSegment(i);
            } else {
                i++;
            }
        }

        Vec closest = h.segments.get(h.segments.size() - 2);

        while (true) {
            if (h.segments.size() == 2) {
                break;
            }
            int index = h.segments.size() - 2;
            closest = h.segments.get(index);
            Direction bottomside = h.segmentBottomSides.get(index);
            Direction topside = h.segmentTopSides.get(index);
            Vec ropevec = playerpos.sub(closest);
            Vec beforepoint = h.segments.get(index - 1);
            long bendShipId = h.segmentShipIds.get(index);
            Vec edgevec = h.getWorldNormal(bottomside, bendShipId).cross(h.getWorldNormal(topside, bendShipId));
            Vec planenormal = beforepoint.sub(closest).cross(edgevec);
            if (ropevec.dot(planenormal) > 0) {
                h.removeSegment(index);
            } else {
                break;
            }
        }

        Vec farthest;
        if (movinghook) {
            while (true) {
                if (h.segments.size() == 2) {
                    break;
                }
                int index = 1;
                farthest = h.segments.get(index);
                Direction bottomside = h.segmentBottomSides.get(index);
                Direction topside = h.segmentTopSides.get(index);
                Vec ropevec = farthest.sub(hookpos);
                Vec beforepoint = h.segments.get(index + 1);
                long bendShipId = h.segmentShipIds.get(index);
                Vec edgevec = h.getWorldNormal(bottomside, bendShipId).cross(h.getWorldNormal(topside, bendShipId));
                Vec planenormal = beforepoint.sub(farthest).cross(edgevec);
                if (ropevec.dot(planenormal) > 0 || ropevec.length() < 0.1) {
                    h.removeSegment(index);
                } else {
                    break;
                }
            }
            while (h.getDistToFarthest() > ropelen) {
                h.removeSegment(1);
            }
        }

        if (movinghook) {
            farthest = h.segments.get(1);
            Vec prevfarthest = farthest;
            if (h.segments.size() == 2) {
                prevfarthest = h.prevPlayerPos;
            }
            updateSegment(h, w, hookpos, h.prevHookPos, farthest, prevfarthest, 1, 0);
        }

        Vec prevclosest = closest;
        if (h.segments.size() == 2) {
            prevclosest = h.prevHookPos;
        }
        updateSegment(h, w, closest, prevclosest, playerpos, h.prevPlayerPos, h.segments.size() - 1, 0);

        h.prevHookPos = hookpos;
        h.prevPlayerPos = playerpos;
    }

    private static void updateSegment(SegmentHandler h, RopeWorld w, Vec top, Vec prevtop, Vec bottom, Vec prevbottom, int index, int numberrecursions) {
        RopeWorld.Hit bottomhit = w.trace(bottom, top);
        if (bottomhit == null) {
            return;
        }
        long bottomShipId = bottomhit.shipId();
        if (bottomShipId != RopeWorld.NO_SHIP) {
            Vec prevTopAdj = w.prevTickToCurrent(bottomShipId, prevtop);
            Vec prevBottomAdj = w.prevTickToCurrent(bottomShipId, prevbottom);
            if (prevTopAdj != null && prevBottomAdj != null) {
                prevtop = prevTopAdj;
                prevbottom = prevBottomAdj;
            }
        }

        // the old bail-out: last tick's rope traced against EVERYTHING as it is now
        if (w.trace(prevbottom, prevtop) != null) {
            return;
        }

        Vec bottomhitvec = bottomhit.location();
        Direction bottomside = bottomhit.side();
        Vec bottomnormal = h.getWorldNormal(bottomside, bottomShipId);
        double prevropelen = prevtop.sub(prevbottom).length();
        Vec cornerbound1 = bottomhitvec.add(bottomnormal.changeLen(-h.intoBlock));

        Vec bound_option1 = h.linePlaneIntersection(prevtop, prevbottom, cornerbound1, bottomnormal);
        Vec bound_option2 = h.linePlaneIntersection(top, prevtop, cornerbound1, bottomnormal);
        Vec bound_option3 = h.linePlaneIntersection(prevbottom, bottom, cornerbound1, bottomnormal);

        for (Vec cornerbound2 : new Vec[] {bound_option1, bound_option2, bound_option3}) {
            if (cornerbound2 == null) {
                continue;
            }
            RopeWorld.Hit cornerhit = w.trace(cornerbound2, cornerbound1);
            if (cornerhit != null) {
                Vec cornerhitpos = cornerhit.location();
                Direction cornerside = cornerhit.side();
                long cornerShipId = cornerhit.shipId();
                Vec cornernormal = h.getWorldNormal(cornerside, cornerShipId);
                if (Math.abs(cornernormal.dot(bottomnormal)) > 0.99) {
                    continue;
                }
                Vec actualcorner = cornerhitpos.add(bottomnormal.changeLen(h.intoBlock));
                Vec bend = actualcorner.add(bottomnormal.changeLen(h.bendOffset)).add(cornernormal.changeLen(h.bendOffset));
                Vec topropevec = bend.sub(top);
                Vec bottomropevec = bend.sub(bottom);

                Vec continueDir = top.sub(bend);
                if (continueDir.length() > 0.01) {
                    Vec probe = bend.add(continueDir.changeLen(0.35)).add(bottomnormal.changeLen(-0.2));
                    if (w.isSolidAt(probe, cornerShipId)) {
                        continue;
                    }
                }
                if (topropevec.length() < 0.05) {
                    if (h.segmentBottomSides.get(index - 1) == bottomside && h.segmentTopSides.get(index - 1) == cornerside) {
                        continue;
                    }
                }
                if (bottomropevec.length() < 0.05) {
                    if (h.segmentBottomSides.get(index) == bottomside && h.segmentTopSides.get(index) == cornerside) {
                        continue;
                    }
                }

                long shipId = cornerShipId;
                Vec shipLocal = null;
                if (shipId != RopeWorld.NO_SHIP) {
                    shipLocal = w.toShip(shipId, bend);
                    if (shipLocal == null) {
                        shipId = RopeWorld.NO_SHIP;
                    }
                }
                h.actuallyAddSegment(index, bend, bottomside, cornerside, shipId, shipLocal);
                if (h.getDistToAnchor() + .2 > h.ropeLen) {
                    h.removeSegment(index);
                    continue;
                }
                double newropelen = topropevec.length() + bottomropevec.length();
                double prevtoptobend = topropevec.length() * prevropelen / newropelen;
                Vec prevbend = prevtop.add(prevbottom.sub(prevtop).changeLen(prevtoptobend));
                if (numberrecursions < 10) {
                    updateSegment(h, w, top, prevtop, bend, prevbend, index, numberrecursions + 1);
                }
                break;
            }
        }
    }
}
