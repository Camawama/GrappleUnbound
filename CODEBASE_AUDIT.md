# Codebase Audit — Grappling Hook Mod Reforged (GrappleVS)

**Date:** 2026-07-18
**Target:** Forge 1.20.1 fork of yyon's Grappling Hook Mod with Valkyrien Skies (VS2) compatibility
**Scope:** All Java sources under `src/main/java`, resources, build scripts, and mod metadata. VS API usage was verified against the actual `valkyrienskies-120-forge-2.4.0` jar from the Gradle cache.

Severity key: 🔴 **Critical** (broken feature / crash / exploit) · 🟠 **Major** (visible bug or design gap) · 🟡 **Minor** (polish, perf, hygiene)

---

## 1. Executive summary

The fork is in good shape overall: the VS attach/track flow (hook → ship-local coordinates → re-transform each tick) is fundamentally correct, networking runs on the main thread via `enqueueWork`, and the VS dependency is properly optional (guarded by `ModList.isLoaded`, `mandatory=false` in mods.toml).

The biggest finding explains the issue you already know about: **rope wrapping around VS ships is not broken — it is deliberately skipped** in `SegmentHandler`, because rope bend points are stored in world coordinates and would go stale the moment a ship moves. Fixing it properly requires storing bends in ship-local space (design sketch in §2.1).

Beyond that, the audit found: a coordinate-space bug in the VS ship-lookup fallback, ship raycast hits being requested in shipyard coordinates, no rope-length handling when a ship moves away (rope silently snaps), one genuine copy-paste bug (`onDroppedByPlayer` never clears the right-hand hook), an NPE-shaped ordering bug in `GrapplehookEntity.tick()`, a config-cache that never caches (re-parses the block list on *every* attach check, including inside the magnet's O(r³) scan), two server-side exploit vectors inherited from upstream, and assorted hygiene items.

---

## 2. Valkyrien Skies integration

### 2.1 🔴 Rope does not wrap around ships — wrapping is explicitly skipped

`SegmentHandler.updateSegment()` bails out whenever the rope's raytrace hits a ship block, in both the primary hit and the corner-finding step:

- `SegmentHandler.java:172-176` — if the segment raytrace hit is in the shipyard, `return` (no bend created).
- `SegmentHandler.java:204-208` — same skip for the corner raytrace.

So the rope passes straight through ship hulls instead of bending around them. This is the root cause of the behavior you described. It was almost certainly done defensively, and for a real reason: bend points are stored as world-space `Vec`s in `segments`, and `getBendBlock()` maps a bend back to a `BlockPos` by nudging into the block. For a ship that translates/rotates, a world-space bend point and a shipyard `BlockPos` both go stale within one tick, which would cause phantom bends, wrong rope-length math, and false "hook past bend" attachments.

**Fix design (proper):** make bends ship-aware.

1. Extend the per-bend data (currently three parallel `LinkedList`s: `segments`, `segmentBottomSides`, `segmentTopSides`) into a small `Bend` record: `{Vec pos, Direction bottom, Direction top, long shipId (or -1), Vec shipLocalPos}`.
2. When a bend is created from a raytrace that hit a shipyard block, resolve the ship via `VSGameUtilsKt.getShipManagingPos(level, hitBlockPos)` and store `shipId` + `worldToShip.transformPosition(bendWorldPos)`.
3. At the top of `SegmentHandler.update()` (and before any distance math: `getDistToAnchor`, `getDistToFarthest`, `getClosest`), refresh every ship-bend's world position from `shipToWorld.transformPosition(shipLocalPos)`. If the ship is no longer loaded, drop the bend.
4. The unwrap plane test (`ropevec.dot(planenormal) > 0`) also uses the two `Direction` normals, which are world-axis-aligned. For a *rotated* ship these normals are wrong. For a first iteration you can accept this (VS ships near identity rotation behave fine); the complete fix stores the edge vector `getNormal(bottom).cross(getNormal(top))` in ship-local space too and rotates it with `shipToWorld` each tick.
5. Sync: `SegmentMessage` and the segment block in `GrappleAttachMessage` need the extra `shipId`/local-pos fields so clients rebuild the same state (client can look the ship up with the ClientLevel overloads of the same VS utils).
6. Remove both shipyard skips in `updateSegment()`; also change `getBendBlock()` to return the *shipyard* `BlockPos` for ship bends (it already will if computed from the shipyard-space hit, see §2.3).

This is the single largest piece of work in the audit, but everything needed is already verified present in the VS 2.4.0 API (`getShipManagingPos(Level, BlockPos)`, `Ship.getWorldToShip()`/`getShipToWorld()`).

**Interim mitigation (cheap):** while ships don't create bends, the rope also shouldn't *attach* through them — that part already works because hook impact uses vanilla `Level.clip`, which VS replaces via its `clip_replace/MixinLevel`. No action needed there.

### 2.2 🟠 Ship-lookup fallback uses the wrong coordinate space (dead/incorrect code)

`ValkyrienSkiesIntegration.attachToShip()` (`ValkyrienSkiesIntegration.java:28-31`):

```java
// 2. Fallback: Try finding ship by World Position
if (ship == null) {
    ship = VSGameUtilsKt.getShipObjectManagingPos(serverLevel, jomlPos);
}
```

Verified against the VS jar: every `getShipObjectManagingPos`/`getShipManagingPos` overload maps **shipyard** coordinates (internally: chunk position → ship allocation) to a ship. Passing a world-space position can never match a ship unless that position already lies inside the shipyard region — in which case branch 1 (`isBlockInShipyard(blockPos)`) already handled it. The fallback is dead code at best, and at worst could mis-attach if world coordinates ever coincide with the shipyard region.

**Fix:** delete the fallback, or replace it with a real world-space query (iterate `getShipObjectWorld(level).getLoadedShips()` and test `ship.getWorldAABB().containsPoint(jomlPos)`).

### 2.3 🟠 Mod raytraces request ship hits in shipyard coordinates (`shouldTransformHitPos = false`)

`ValkyrienSkiesIntegration.rayTraceBlocks()` calls `RaycastUtilsKt.clipIncludeShips(level, context, false)`. The third argument (verified overload `clipIncludeShips(Level, ClipContext, boolean)`) is `shouldTransformHitPos`; with `false`, hits on ships keep their **shipyard-space** hit location instead of being transformed back to world space.

Every rope/wallrun raytrace goes through this helper (`GrapplemodUtils.rayTraceBlocks`). Consequences today:

- `SegmentHandler` can't use ship hit locations for corner math (currently moot because of the §2.1 skip, but this must change together with that fix — for bend geometry you want the *world-space* location plus the *shipyard* `BlockPos`, i.e. `shouldTransformHitPos = true`).
- `GrappleController.getNearbyWall()`/`wallNearby()`: a ship wall is "detected" (non-null result), but `wallrunRaytraceResult.getBlockPos()` is shipyard-space — fine for the sound-effect block lookup, and the direction logic only uses the probe direction, so wallrunning on ships mostly works by luck.
- `GrapplehookEntity.onHit()`'s entity-hit fallback re-raytrace (`GrapplehookEntity.java:375`) would attach the hook at a shipyard-space location if the fallback ray crosses a ship.

**Fix:** pass `true` (or use the 2-arg overload, whose default is `true`) and rely on `isBlockInShipyard(hit.getBlockPos())`/`getShipManagingPos` when you need the ship, once §2.1 is implemented.

### 2.4 🟠 Rope length is never reconciled with ship movement — rope snaps or drags the player without slack handling

When attached to a moving ship, `updateShipAttachment()` moves the hook every tick, but the client controller (`GrappleController.updatePlayerPos`) keeps `hookEntity.r` fixed:

- Ship moves **away** → `oldspherevec.length() - remaininglength` grows past `rope_snap_buffer` (`GrappleController.java:204`) → the rope silently detaches ("rope snaps") even though the player did nothing.
- Ship moves **toward** the player → rope goes slack; player gets no impulse (fine), but a fast ship can overrun the player with no coupling at all.
- The player never inherits ship velocity, so being "towed" is a rubber-band experience: the sphere constraint teleport-corrects position each tick instead of adding the anchor's velocity to the player's frame.

**Fix options** (in increasing quality):
1. When the anchor is a ship, allow `r` to grow up to `maxlen` before snapping (treat ship recession like a motor letting out rope).
2. Track anchor velocity (`currentAnchorPos - previousAnchorPos`) in the controller and add it to the constraint frame — i.e., apply the sphere constraint to *relative* motion, then add anchor velocity back. This makes swinging from a moving ship feel correct and is how most VS-aware grapple/tether mods behave.

### 2.5 🟠 Hooked ship feels no force (one-way constraint)

The player is constrained to the rope, but the ship never receives a reaction force — a small dinghy behaves like bedrock when grappled. If two-way physics is desired, on the server apply a force at the attachment point each tick the rope is taut: cast the ship to `ServerShip`, attach/lookup a force-applying attachment (VS 2.4 exposes `LoadedServerShip.getAttachment`/`saveAttachment` and impulse APIs) with `force = tautRopeTension`, `position = shipLocalAttachPos`. This is an enhancement, not a bug — but worth recording as a known gap.

### 2.6 🟡 Ship unload leaves the hook frozen mid-air

`updateShipAttachment()` does nothing when `getLoadedShips().getById(shipId)` returns null (ship unloaded/deleted). The hook then hangs at its last world position with a taut rope to nowhere. Consider detaching the hook (server side) when the ship id is set but no longer resolvable for N consecutive ticks.

### 2.7 🟡 `ModList.get().isLoaded("valkyrienskies")` on every raytrace

`GrapplemodUtils.rayTraceBlocks`, `SegmentHandler.updateSegment` (twice), `GrapplehookEntity.tick/serverAttach` all query the mod list per call — this happens many times per tick per player. It's a map lookup, so not tragic, but trivially cacheable:

```java
public static final boolean VS_LOADED = ModList.get().isLoaded("valkyrienskies");
```

(computed once after mod construction). Cleaner and free.

### 2.8 🟡 Attach offset assumes world-axis-aligned block faces

`GrapplehookEntity.serverAttach()` (`GrapplehookEntity.java:458-471`) nudges the hook by ±0.05/0.3 along the world axis of `sideHit`. On a rotated ship the hit `Direction` is shipyard-space, so the nudge is applied along the wrong world axis. Cosmetic (hook slightly embedded/floating on rotated ships). Correct fix: apply the nudge in shipyard space *before* transforming to world/local storage.

### 2.9 🟡 Dependency/version hygiene for VS

- `build.gradle:163-165` uses dynamic versions: `org.valkyrienskies.core:api:+` / `impl:+` / `util:+`. Builds are non-reproducible and can silently break when VS Core publishes a new snapshot. Pin the versions matching VS 2.4.0 (they are printed in the VS jar's `META-INF` / dependency metadata).
- `ValkyrienSkiesIntegration.updateShipAttachment` returns `VsiShipWorld` from `getShipObjectWorld(Level)` — an `org.valkyrienskies.core.internal` type. Internal APIs are fair game to change between VS patch releases (cache shows 2.4.10/2.4.11 already exist). Prefer the stable api-package surface where possible, and pin the tested VS version range in `mods.toml` more tightly than `[2.4.0,)` unless you actually test newer releases.
- `implementation fg.deobf(...)` for VS/KFF is correct for dev; just note the shipped jar correctly does **not** bundle them (only `shadow` deps are bundled — see §5.3).

---

## 3. Correctness bugs (general)

### 3.1 🔴 `onDroppedByPlayer` never clears the right-hand hook (copy-paste bug)

`GrapplehookItem.java:533-539`:

```java
if (grapplehookEntitiesRight.containsKey(player)) {
    GrapplehookEntity hookRight = grapplehookEntitiesRight.get(player);
    setHookEntityLeft(player, null);   // <-- should be setHookEntityRight
    ...
}
```

The right-hand map keeps its entry after the item is dropped (Q). The entity itself is removed, so the stale entry is mostly masked by the `isAlive()` check in `getHookEntityRight`, but the map retains a strong reference to a dead entity+hook pair (leak) and `hasHookEntity` semantics rely on the alive-check to accidentally work. One-word fix.

### 3.2 🟠 `GrapplehookEntity.tick()` — missing `return` after ghost-removal, and an NPE-ordered condition

- `GrapplehookEntity.java:184-186`: when `shootingEntityID == 0 || shootingEntity == null`, the entity is discarded but `tick()` **continues executing** with `shootingEntity == null`.
- `GrapplehookEntity.java:264`: the magnet branch condition dereferences `this.shootingEntity` (`Vec.positionVec(this.shootingEntity)`) *before* the null check on line 265, which is inside the block. With a magnet hook (`attract=true`) and a despawned owner, this NPEs before the guard can run.

Fix: `return;` after the removal on line 185 (this also makes the line-265 guard redundant instead of misplaced). This is the same class of bug that upstream credits Random832 for fixing once already — the fork's ordering regressed it for the magnet path.

### 3.3 🟠 `GrappleConfigUtils` block-list cache never caches

`GrappleConfigUtils.java:71-102`: `prevGrapplingBlocks`, `prevGrapplingNonBlocks`, and `prevGrapplingBreakBlocks` are declared but **never assigned**, so the `equals` guard is always false-ish (`"...".equals(null)` → `false`) and `updateGrapplingBlocks()` re-runs on *every* call to `attachesBlock()`/`breaksBlock()` — string splitting plus registry lookups. `attachesBlock` is called from `hasBlock`, which the magnet feature calls for every block in an O((2r+1)³) volume scan per tick (§4.1). Set the `prev*` fields inside `updateGrapplingBlocks()`.

Also: `stringToBlocks` adds `null` to the set when a block id doesn't resolve (`blocks.add(b)` without a null check), which turns "typo in config" into "air matches the list" (`grapplingBlocks.contains(air-default)` behavior via the registry default). Skip unresolved entries and log a warning.

### 3.4 🟠 `LoggedInMessage` config sync corrupts on non-ASCII strings / mixed platform charsets

`LoggedInMessage.java:59-62, 101-104`: strings are written as `str.length()` (UTF-16 code units) + `Charset.defaultCharset()` bytes. Two independent bugs:
- Byte length ≠ `String.length()` for any non-ASCII character → the reader consumes the wrong number of bytes and every subsequent field in the stream desyncs (the whole config is one flat reflection-encoded stream).
- `defaultCharset()` differs between a Linux server (UTF-8) and a Windows client (often Cp1252) → mojibake even when lengths happen to match.

The config's string fields (`grapplingBlocks` etc.) are user-supplied, so "someone puts a modded block id with an odd character in the server config" breaks every client's synced config. Fix: `buf.writeUtf(str)` / `buf.readUtf()` (FriendlyByteBuf already does length-prefixed UTF-8).

### 3.5 🟠 `GrappleAttachMessage.processMessage` NPE when the owner entity isn't resolved

`GrappleAttachMessage.java:176-177`: `world.getEntity(this.entityId)` can return null (dimension change, respawn race) and is passed straight into `Vec.positionVec(player)` → client crash. Guard and fall back to the hook's position.

### 3.6 🟡 `ClientControllerManager.onClientTick` catches `ConcurrentModificationException`

`ClientControllerManager.java:82-88`: `controller.doClientTick()` can call `unattach()` → `unregisterController()` → mutate `controllers` while it's being iterated; the CME is caught and ignored, which means *some controllers silently skip their tick that frame*. Iterate a copy (`new ArrayList<>(controllers.values())`) instead of catching.

### 3.7 🟡 `isWallRunning` only inspects the first enchantment of each armor piece

`ClientControllerManager.java:171-190`: the `break` at line 188 is inside the `for (Enchantment enchant : ...)` loop, so if an armor piece's first-iterated enchantment isn't Wallrun (e.g. boots with Unbreaking + Wallrun), wallrun is not detected for that piece. Enchantment map iteration order makes this intermittent. Compare with `wearingDoubleJumpEnchant`/`isWearingSlidingEnchant`, which are written correctly. Replace the loop with `EnchantmentHelper.getItemEnchantmentLevel(CommonSetup.wallrunEnchantment.get(), stack) >= 1`.

### 3.8 🟡 Server-side static maps leak across player sessions

- `GrapplehookItem.grapplehookEntitiesLeft/Right` are `HashMap<Entity, GrapplehookEntity>` keyed by strong entity references; cleared only on death and (buggily, §3.1) on drop — **not** on logout or dimension change.
- `ServerControllerManager.attached` and `allGrapplehookEntities` similarly lack a `PlayerLoggedOutEvent` cleanup.

Add a `PlayerEvent.PlayerLoggedOutEvent` handler that removes the player from all four structures and kills any live hooks (otherwise a hook entity can persist, and `shootingEntity` keeps a whole player instance reachable).

### 3.9 🟡 Segment double-application on the owning client

Server-side `SegmentHandler` changes broadcast `SegmentMessage` to everyone tracking the player's chunk — including the owning client, which *also* simulates segments locally every tick (`GrappleController.updatePlayerPos` → `segmentHandler.update`). The two sources race; in practice the owner's local sim usually wins/reconciles, but it can produce transient duplicate/removed bends (visible as rope flicker around corners). Consider excluding the owner (`PacketDistributor.TRACKING_ENTITY` semantics, or filter by player id in the handler).

### 3.10 🟡 `CommonSetup` creative tab lambda uses `ClientProxy` directly

`CommonSetup.java:135` calls `ClientProxy.proxy.fillGrappleVariants(output)` — referencing the client-only class from common code. It only avoids a dedicated-server crash because `displayItems` lambdas never execute there and class resolution is lazy. Use `ClientProxyInterface.proxy` (with a null check, since it's null on servers) like the README's own architecture rule says. Same file, `eventHandlers` field: registration via static-initializer side effect (`= new CommonEventHandlers();;`) is fragile ordering-wise — move to the mod constructor.

---

## 4. Performance

### 4.1 🟠 Magnet block scan is O((2r+1)³) per step, compounded by §3.3

`GrapplehookEntity.check()` (`GrapplehookEntity.java:530-553`) scans a full cube of radius `attractradius` (default scans easily 10k+ blocks) and is called from `tick()` once per step *inside* a loop over the hook's travel vector (`tick()` line 276: `for i < vector.length()`), each block doing `hasBlock` → `attachesBlock` → (currently) a full config re-parse. With the §3.3 fix this becomes tolerable; further wins: early-out using `checkedset` (already partially there), scan a sphere shell instead of a cube, or use `BlockPos.betweenClosed` with squared-distance culling.

### 4.2 🟡 Render-thread/tick-thread sharing of `SegmentHandler`

`getBoundingBoxForCulling()` → `segmentHandler.getBoundingBox()` → `updatePos()` **mutates** `segments` from the render thread while the client tick mutates the same `LinkedList`s. Rare `ConcurrentModificationException`/garbage frames under load. Make `getBoundingBox` read-only (compute min/max from a defensive copy; don't call `updatePos` there).

### 4.3 🟡 Per-frame allocations in the rope renderer

`RenderGrapplehookEntity.getStackToRender` builds a new `ItemStack` + NBT every frame per hook; the model lookup is also re-done each frame. Cache the display stack per renderer instance.

---

## 5. Security / multiplayer robustness

These are all inherited from upstream's client-authoritative design, but a fork that targets servers should know about them.

### 5.1 🔴 `PlayerMovementMessage` is an unauthenticated teleport

`PlayerMovementMessage.java:75-93`: the server sets the sender's position/velocity to arbitrary client-supplied values and calls `connection.resetPosition()` (which suppresses vanilla "moved too quickly/wrongly" checks), plus rewrites `fallDistance`. The only validation is `sender.getId() == entityId`. Any player with the mod installed can teleport at will and negate all fall damage *without holding any mod item*. At minimum: require the sender to actually have an active hook/controller state server-side (`ServerControllerManager.attached.contains(id)` or a live hook in `allGrapplehookEntities`), clamp per-tick displacement to something plausible (`maxlen` + margin), and ignore the message otherwise.

### 5.2 🟠 `GrappleModifierMessage` applies unvalidated customization

`GrappleModifierMessage.processMessage` writes the client-sent `GrappleCustomization` straight into the tile entity: no distance-to-block check, no check that the block's upgrade categories are unlocked, no clamping to config `min/max`(`_upgraded`) — the GUI enforces limits client-side only. A modified client gets max-length/max-speed everything on any modifier block it can name (the `crc32` in the stream is an integrity check, not validation — the client computes it over its own forged values). Server-side: verify `player.distanceToSqr(pos) < 64`, and clamp every option through `getMin`/`getMax`/`optionEnabled` against the tile's `unlockedCategories`.

### 5.3 🟠 `KeypressMessage.decode` — unvalidated enum ordinal

`KeypressMessage.java:43`: `Keys.values()[buf.readInt()]` throws `ArrayIndexOutOfBoundsException` on a malformed packet. Forge catches handler exceptions per-packet, but decode exceptions from hostile clients are noisy at best. Use `buf.readEnum(KeypressItem.Keys.class)`.

### 5.4 🟡 Network channel accepts any remote version

`CommonSetup.init`: `newSimpleChannel(..., () -> "1.0", v -> true, v -> true)` accepts mismatched *and absent* channel versions. A client without the mod can join a modded server (and vice versa) and simply desync — hooks invisible, players rubber-banding for others. If that's intentional (server-optional usage), keep it; otherwise use `NetworkRegistry.newSimpleChannel` with exact-match predicates so the handshake rejects cleanly.

---

## 6. Build, packaging & project hygiene

| # | Severity | Item |
|---|----------|------|
| 6.1 | 🟠 | **Unused shaded dependency:** `capabilitysyncer` is declared `shadow` + relocated in `build.gradle`, but `grep` finds zero usages in `src/`. It is bundled into every shipped jar for nothing. Remove the dependency, the `shadow` configuration wiring, and (if then unused) the shadow plugin itself. |
| 6.2 | 🟡 | **Empty mixin config shipped:** `grapplemod.mixins.json` has `"mixins": []`, yet MixinGradle + the annotation processor run on every build and the config is registered. Either delete the mixin scaffolding or keep it only when you actually add a mixin (the VS rope work may want one, so deleting is optional — but then remove `debug.export = true`, which dumps mixin classes in production). |
| 6.3 | 🟡 | **Stale version constants:** `GrappleMod.VERSION = "1.16.5-v12.2"` (GrappleMod.java:38) vs `mod_version=1.0.1` in gradle.properties. Nothing reads `VERSION`; delete it or wire it to the real version. |
| 6.4 | 🟡 | **README is upstream's:** describes 1.12/1.16/1.18 branches and the old MDK setup, and never mentions Valkyrien Skies, the fork's raison d'être. The `Testing` checklist likewise predates VS — add VS cases (attach to moving ship, swing from ship, rope wrap once fixed, ship unload, dedicated server with/without VS installed). |
| 6.5 | 🟡 | **Debug prints:** 35 `System.out.println`/`print` calls across 10 files (`SegmentHandler` alone has 11). Upstream once shipped a release with forgotten debug prints (credited in README). Route through `GrappleMod.LOGGER.debug(...)`. |
| 6.6 | 🟡 | **Mangled license header:** `GrappleModifierMessage.java` header reads "under the teHOUT ANY WARRANTY" — a bad merge artifact. Cosmetic, but it's a GPL notice; restore it. |
| 6.7 | 🟡 | **`ClientEventHandlers` double-registration pattern:** annotated `@Mod.EventBusSubscriber` (which only registers *static* handlers — all its handlers are instance methods, so the annotation is a no-op) *and* registered via constructor. Works, but remove the misleading annotation. |
| 6.8 | 🟡 | **Item property predicates unnamespaced:** `new ResourceLocation("rocket")` etc. in `ClientSetup.registerPropertyOverride` resolve to `minecraft:rocket`. Works because the models use the same bare names, but namespace them `grapplemod:` on both sides to avoid collisions with other mods. |
| 6.9 | 🟡 | **`mods.toml`:** missing `issueTrackerURL`/`displayURL`/`logoFile`; `cloth_config` is `mandatory=true` (correct — `AutoConfig.register` runs unconditionally) but has no `ordering`, and the file still carries template comments. |
| 6.10 | 🟡 | **`org.gradle.daemon=false`** in gradle.properties makes every local build cold-start; only worth keeping for CI. |

---

## 7. Smaller behavioral notes (worth knowing, not necessarily changing)

- **Holding the hook blocks all block-breaking** (`CommonEventHandlers.onBlockBreak` cancels `BreakEvent` whenever the main hand holds a `GrapplehookItem`). Intentional (left-click = throw), but it also cancels breaking initiated in the same tick as swapping items; harmless but occasionally surprising.
- **`LongFallBoots` cancel *all* fall damage** in any amount (`LivingFallEvent` unconditionally canceled) — including from non-grapple sources; also `flyIntoWall` damage. Matches the item's name, just documenting.
- **`GrappleController` constructor "undo friction" heuristic** (`GrappleController.java:75-78`) divides by potentially-zero motion components; NaN comparisons make the guard fail safe, but the expression is fragile — worth a clean rewrite with epsilon checks when touched next.
- **Rope taut/sag rendering is disabled** — `RenderGrapplehookEntity.java:249-251` has the `taut = hookEntity.taut` line commented out, so `taut` is always 1 and the catenary-sag code path (16 segments) never runs. Either re-enable or delete.
- **`GrappleAttachMessage.decode` trailing-bytes sniffing** (`buf.readableBytes() > 0` to detect the optional VS payload) works only because the VS block is last; any future field appended after it will silently mis-parse old-format packets. Prefer an explicit boolean flag.
- **`getPersistentData()` for VS state** works (Forge saves it under `ForgeData`), but hooks never survive a save/reload anyway (`shootingEntity` resolves null → discarded on first tick), so the NBT persistence is vestigial.

---

## 8. Suggested priority order

1. **§3.1** one-line `setHookEntityRight` fix, **§3.2** `return` after ghost removal, **§3.3** assign the `prev*` cache fields — three tiny diffs, immediate wins.
2. **§2.4** rope-length reconciliation for moving ships (biggest *feel* improvement for the VS use case, small code change).
3. **§2.1 + §2.3** ship-aware rope bends (`shouldTransformHitPos=true` + per-bend ship id/local pos) — the headline feature fix.
4. **§5.1/§5.2** server-side validation, if public servers are a target.
5. **§2.2** delete or fix the world-space ship-lookup fallback; **§2.7** cache the VS-loaded flag.
6. Hygiene batch: §6.1 unused shadow dep, §6.5 logger migration, §3.6/§3.7 client fixes, README/Testing refresh.

---

*Audit performed file-by-file over all 60+ Java sources; VS API claims (method signatures, coordinate-space semantics of `getShipObjectManagingPos`, `clipIncludeShips` overloads, presence of VS's `clip_replace/MixinLevel`) were verified by inspecting `valkyrienskies-120-forge-2.4.0.jar` with `javap` rather than from memory.*
