# Changelog

## Unreleased

### Zero and low gravity (VS Genesis, Ad Astra, Gravity Unbound)

Camafy 0.1.7's `Gravity.strength` is the real local gravity, so the rope's gravity step is already 0 in VS Genesis's space and Ad Astra's orbits (a swing there is a tether, not a pendulum) and a sixth on the Moon. On top of that:
- **On an Ad Astra planet** the rope no longer creeps upward: Ad Astra lifts the player at the start of each move instead of weakening the pull, and the controllers already apply the planet's gravity, so the lift (`Gravity.preMoveLift`) is taken back out of the velocity they write.
- **In zero gravity:** any rope can be climbed (there is no "anchor above"), climbing down pushes out along the rope, the slow key brakes a swing in every direction, and the jump key just lets go (no endless vertical kick). Wall runs, the double-jump enchantment and the air controller stand down (the air controller hands movement straight back to the player, since nothing ever lands to end it).
- **The forcefield** pushes evenly in every direction in zero gravity (its extra upward push only held you up against gravity).
- **The hook** drops by the local gravity in flight: Ad Astra's and Gravity Unbound's hooks on `ThrowableProjectile.getGravity` never reached our override, so it arced "down" in an orbit or a zero-strength field. The slack rope's drawn sag follows the local gravity too (straight in space).
- **A rope on a light ship** pulls with the player's weight at the local gravity (none in space) plus its stretch, instead of a hanging player's full weight.
- Camafy 0.1.7 required.

### Renamed to Grapple Unbound

- **GrappleVS is now Grapple Unbound**, like GravityVS became Gravity Unbound: mod id
  `grapplemod` → `grappleunbound`, packages `com.yyon.grapplinghook` → `net.camacraft.grappleunbound`,
  resources under `assets/grappleunbound` and `data/grappleunbound`, jar `grappleunbound-1.20.1-<version>.jar`.
  Network channel `grappleunbound:channel`, Camafy physics owner `grappleunbound:grapple`, config
  `config/grappleunbound.toml`, creative tab `grappleunbound:main`, key binds `key.grappleunbound.*`
  (category `key.grappleunbound.category`), config screen keys `text.autoconfig.grappleunbound.*`.
  Class names are unchanged.
- **Old worlds keep their things.** A `MissingMappingsEvent` handler (`LegacyIds`) remaps every
  `grapplemod:*` registry entry a world saved (items, the modifier block and its block entity, the
  hook entity, the enchantments, the upgrade recipe serializer, the creative tab) to
  `grappleunbound:*`. Forge aliases the old ids for the session, so items, enchantments and blocks
  saved under the old ids load as the new ones and are re-saved with the new ids.
- **The old config is carried over** once: `config/grapplemod.toml` is moved to
  `config/grappleunbound.toml` if there is no new file yet.
- **Key binds reset once**: the key names were un-namespaced (`key.leftthrow.desc`, …) and are now
  `key.grappleunbound.leftthrow` etc.
- Credit to yyon's Grappling Hook Mod and Grappling Hook Mod: Reforged stays (README, `mods.toml`
  credits, the GPL headers); still GPL-3.0.

### Lead overhaul removed

- The lead overhaul is gone: leads belong to the new **Unleashed Leads** mod, and a grappling hook
  mod has no business changing them. Removed the lead rope rendering (`LeadOverhaul`), its mixin
  (`MobRendererMixin`, whose HEAD-cancel of `MobRenderer.renderLeash` also crashed with Pehkui,
  which pushes the pose stack at `renderLeash` HEAD and pops it at RETURN), the lead swing physics,
  the `leads.overhaul_enabled` config and its lang entries, and the mixin config with its refmap and
  annotation processor (the mod ships no mixins again). The "Lead overhaul" entries further down
  describe a feature that no longer exists. The grappling hook's own rope rendering and bend logic
  are untouched.

### Ropes hooked to a ship wrap like ropes hooked to a block

- **Hooked to a moving Valkyrien Skies ship, the rope wrapped around pillars only some of the time
  and otherwise phased through.** Three causes, all fixed:
  - *The "was the rope already through this last tick?" check asked every block and ship at once.*
    Last tick's hook point is where the hull was; a ship that moved toward the rope (the rope's own
    pull does that to a light ship) has since moved over it, so last tick's rope "hit" the hooked
    ship and the sweep gave up. One missed tick and the rope was inside the pillar, where the same
    check blocks it forever. The sweep now works in the frame of whatever the rope hit: for world
    blocks, last tick's rope against world blocks only; for a ship, last tick's rope carried along
    with the ship against that ship only. The corner search stays in the same frame.
  - *The stretch from the hook to the first bend was never swept on the owner's client*, which
    only swept the player's end. A hook on a ship moves every tick, so the ship dragged that stretch
    through anything in its way. It is swept now whenever there are bends, and so is every stretch
    between two bends (a ship bend moves with its ship, and a ship can move into a still stretch).
  - *The line-of-sight prune dropped wraps that still held.* Once the rope has swung far enough
    round a thin pillar, the straight line between a bend's neighbours passes the pillar's far side
    while the pillar still sits inside the rope's turn; a moving ship does that to a hooked rope all
    the time. A bend is now only pruned when its neighbours see each other *and* there is nothing
    inside its turn (checked with lines between the rope and that straight line). Stale seam and
    ground-contact bends, with only air inside their turn, are still pruned.
- **One pose for the ship everywhere.** On the client, Valkyrien Skies traces client ships at their
  render pose (up to a tick behind), while the hook, the ship bends and the previous-tick mapping
  use the tick pose, which put the hook (0.05 blocks off the hull) inside the hull the traces saw.
  The rope now traces ships itself, in shipyard space at the tick pose, through Camafy (and world
  blocks with vanilla's block traversal, since `Level.clip` includes ships under VS). The owner's
  client also re-reads a ship hook's position from the ship right before the rope update, and a
  ship bend's previous position comes from the ship's previous pose.
- **Headless tests** (`src/test`, run by `./gradlew build`): the rope logic against a stand-in world
  with a moving, turning ship. They cover pillar wraps with the ship still, moving toward/away/
  sideways/up and turning (client and server call), the hook end dragged round a second corner,
  wrapping the hooked ship's own hull, a ship pushing into a still rope, unwrapping, stale-bend
  pruning, and a line-for-line port of the old sweep that reproduces each of the three bugs.

### Entity grappling rework (smooth + real physics)

- **No more stutter.** The hook now syncs its grappled target (entity id + rope length) through
  entity data; every client pins the hook onto the target each tick, so the rope endpoints ride
  the target's own interpolated movement instead of chasing discrete server position packets.
- **Real rope feel.** Entity-attached ropes now get sag (taut computed client-side from the
  synced rope length) and full segment physics — the server runs bend detection for entity
  hooks and now includes the owner in segment sync (the owner doesn't simulate these locally).
- **Mobs swing.** The server-side constraint is the same sphere/swing math the player controller
  uses on wall hooks: tangential velocity is kept, outward velocity is dropped, and positional
  corrections are softened into velocity. Reeling (crouch) and rope-snap behavior unchanged.
- **Players swing too (PvP grapples).** A grappled player's own client now runs a
  `GrappledController` with the same swing physics as hanging from a wall — WASD swing control,
  air friction, creative-flight passthrough, momentum-preserving release. It starts and stops
  purely from the hook's synced data (no new packets). Independent hooks: if two players hook
  each other, both constraints coexist (emergent tug-of-war). A player using their own grapple
  keeps their own controller (being grappled doesn't stack a second constraint).

### Smithing-style hook upgrades

- **Upgrades now live on the hook item.** Combine a hook + an upgrade item (template) + an iron
  ingot in a vanilla **smithing table** to bake that category permanently into the hook
  (`upgrades` NBT). Each category can be applied once per hook; hook NBT (customization) is
  preserved. Implemented as a custom smithing recipe type (`grapplemod:hook_upgrade`), so it
  works in the vanilla table and shows in JEI/recipe book.
- **In-hand customization.** Press **V** (rebindable: "Customize Held Grappling Hook") with a
  hook in hand to open the customization screen anywhere, gated by the upgrades on that hook.
  Changes are validated server-side against the item's upgrades, mirroring the block flow.
- **Tooltips** list the applied upgrades and the customization key.
- The modifier block still works as the legacy path (block unlocks + stamping hooks).

### Ship pulling (rope tension on Valkyrien Skies ships)

- **Taut ropes now pull hooked ships.** While the rope to a ship-attached hook is taut (hanging
  off it, reeling in, or walking away), a continuous rope-tension force is applied to the ship
  at the attach point, directed at the player: roughly a hanging player's weight plus a spring
  term when stretched. Mass decides the outcome naturally — a one-block ship gets dragged along
  or reeled in like a mob, a heavy hull barely moves and you swing from it instead. Config:
  `hook_ship_pull_force` multiplier (0 disables).
- To make the server-side tension correct, the owning client now mirrors rope-length changes
  (climbing/reeling are client-authoritative) to the server via a new `HookRopeLengthMessage`;
  network protocol bumped to **2.2**.

### Rope wrapping rework + rope snapping

- **Seam catches vs real wraps are now told apart geometrically, not by thresholds.** Up close
  they look identical (tiny angle, tiny penetration) — what differs is what lies BEYOND the
  edge: a flat-ground seam has more surface there, a real corner has air. Bend creation now
  probes just past the corner (ship-aware: probes transform into shipyard space for ship
  corners); surface beyond → seam, skipped; air beyond → wrap, created. This replaces the
  angle-threshold whack-a-mole that was simultaneously too sensitive (ground catches while
  towing) and not sensitive enough (pillar wraps phasing through).
- **Ropes snap when overstretched, and turn red first.** A rope stretched past its allowance
  (3x snap buffer beyond rope length) now breaks server-side and the hook retracts. The rope
  rendering fades white → red as it approaches the snapping point (rope length is now synced to
  all clients for this).
- **Gently lowered mobs take no fall damage.** A hooked mob's fall distance is cleared while it
  descends slower than ~10 m/s; genuine fast drops still hurt.
- **The legacy modifier table is retired.** Its crafting recipe is removed and it no longer
  appears in the creative tab; the block stays registered (and functional) so existing worlds
  keep theirs. Smithing upgrades + the in-hand screen (V) are the upgrade path.

### Rope wrapping fixes

- **Ropes reliably wrap around pillars again.** Two of the recent anti-artifact guards were
  overreaching. The angle-based stale-bend prune deleted fresh wraps (a new wrap is always
  nearly straight) and left the rope embedded in the block, where the prev-tick-clear check
  blocks re-wrapping forever — the rope phased through pillars. Pruning is now line-of-sight
  only (which by construction only removes bends the rope no longer needs), and the
  creation-time straightness filter is tightened to truly degenerate (~2°) catches.
- **Ship-attached ropes wrap world blocks again.** The rolling-ship artifact guard rejected all
  corners not on the hooked ship — including legitimate pillar wraps while towing. It now only
  rejects foreign corners while the ship is actually spinning (>1 rad/s).

### Ship pulling fixes (fourth pass)

- **Pulled ships follow the rope path, not a straight line to the player.** The server now keeps
  the rope segments for ship-attached hooks up to date each tick (with a stretch allowance so a
  taut light-ship rope doesn't get its bends pruned) and pulls the ship towards the bend nearest
  the hook. Wrap the rope around a pillar and the ship is dragged to the pillar first, then
  around it — instead of ignoring the bends until they vanished and the rope snapped straight.
  Observers also see bends on attached ropes now (previously owner-only).
- **No more camera jitter (and VS hull clipping) when climbing right up to the hook.** Climbing
  now always leaves at least 1 block of rope: at near-zero remaining rope the sphere constraint
  centered the player on the hook inside the wall, and rope-pull vs. collision push-out
  oscillated every tick.
- **Hooked mobs stop pathfinding.** The rope owns their movement while attached, and they no
  longer march back to a stale destination the moment they're released.

### Ship pulling fixes (third pass)

- **Light ships no longer wind up into an ever-faster orbit.** The spring pumped energy every
  stretch-contract cycle (elastic pendulum resonance) with nothing bleeding off tangential
  speed. Rope tension now includes a damper: a center-of-mass force opposing the ship's
  velocity relative to the player (500 N per m/s, capped) while the rope is taut. Light ships
  settle into a tow within a couple of seconds; heavy ships barely notice it. The old
  10 m/s approach-speed cutoff is replaced by this damper.
- **The rope stays visible all the way to auto-release.** The hook entity's client tracking
  range was ~80 blocks while the release distance is 96, leaving a dead zone with an invisible
  but still-attached rope; the hook now tracks out to 8 chunks (128 blocks).

### Ship pulling fixes (second pass)

- **No more phantom ground bends under a rolling ship.** When the rope's collision sweep hits a
  ship block, last tick's rope is remapped into the ship's frame; on a *rotating* ship that
  remapped sweep is exaggerated and the corner probe caught ground seams near the contact
  point. Ship-relative sweeps now only accept corners on that same ship.
- **You can't pull a ship you're standing on.** Rope tension is skipped while the shooter is
  grounded on the hooked ship's bounds (no bootstrap paradox).
- **Hooks auto-release at extreme range.** Attached hooks (block, ship, or entity) now detach
  server-side when the owner gets beyond `hook_release_distance` (default 96 blocks, never
  below max rope length + snap buffer; 0 disables) — covering the case where the rope was so
  long it wasn't even rendered any more.

### Ship pulling fixes

- **Hooked ships no longer spin out of control / orbit the player.** The 70/30 center-of-mass
  split is gone: rope tension is applied 100% at the attach point (a real rope), and while the
  rope pulls, the ship sheds ~8% of its angular momentum per physics step. Off-center force
  with no damping was pumping spin until the ship tumbled and orbited; now it settles into a
  hang. This also fixes lifting: a pillar hooked at the top rights itself upright,
  pendulum-style, instead of being dragged flat by its center of mass.
- **Ropes no longer fall through the world after re-entering render distance.** The hook's
  `attached` state is now in its spawn data; previously a client re-tracking an attached hook
  recreated it unattached, and client-side projectile gravity dragged the visual hook (and the
  rope) through the floor.
- **Bends left at a rolling ship's ground contact are pruned.** Grapple ropes now get the same
  line-of-sight stale-bend pruning as leads: any bend whose neighbours can see each other again
  is dropped immediately.

### Creative flight + ship pulling fixes

- **Releasing a grapple while flying no longer drops you out of the sky.** Rope release spawns
  an air-friction controller for momentum smoothing, which applies gravity every tick — while
  flying that read as being kicked out of flight. It is now skipped while flying (and bails out
  if flight starts while it runs).
- **Mass-aware ship pulling.** The hook now syncs the attached ship's mass. Ships lighter than
  `hook_ship_anchor_mass` (default 4000 kg) *yield to the rope*: they never constrain or drag
  the player (a falling wool block won't tug you off a cliff), the rope stretches instead of
  snapping, and the ship takes the tension. Heavier ships anchor the player exactly as before —
  you swing from them and they can drag you.
- **The rope goes taut immediately when flying away from a hooked ship.** The creative-flight
  branch paid out rope for all hooks; ship-attached ropes now keep their length while flying so
  tension builds at once instead of "way further out".
- **Stronger pull.** Base tension 3000 N + 6000 N per block of stretch (capped at 30 kN), and
  tension stops feeding a ship already flying at the player faster than 10 m/s.
- **Ropes no longer catch on flat ground.** Dragging rope across level terrain caught on block
  seams (top face of one block + side face of the next), creating bends the unwrap plane test
  never released — visible when a hooked ship rolled on the ground. Bends the rope passes
  straight through (within ~6°) are no longer created and existing ones are pruned every tick.

### Creative flight fix

- **WASD works while flying with an attached grapple.** The movement-input capture (which
  repurposes WASD for swing control while a controller is attached) now steps aside entirely
  during creative flight, matching the flying branch of the controller that already suspends
  rope physics. Previously only vertical flight (space/crouch) responded while grappled.

### Lead overhaul fixes

- **Lead bends unwrap eagerly.** Any bend whose neighbours regain line of sight is dropped
  immediately instead of waiting for the physical release-plane crossing — on a lead the strict
  test read as the rope being glued to a wall corner until pulled far past it.
- **Fence knots no longer spawn junk bends.** A knot's rope-hold position sits inside the fence
  post, so the wrapping raytrace permanently collided at the endpoint; the collision endpoint is
  now nudged out of the post (rendering still meets the real knot).
- **Re-leashing starts clean.** The client-side rope state resets when the holder changes or the
  lead wasn't rendered recently, so unleashing and re-leashing no longer resurrects stale bends.

### Ship pulling fixes

- **Hooked ships translate instead of spinning in place.** Rope tension applied purely at the
  attach point made small ships spin (tiny moment of inertia — torque dominated); tension is now
  split ~70% at the center of mass (translation) / ~30% at the attach point (swing/torque).
- **Stronger pull.** Base tension raised ~3x (2500 N + 4000 N per block of stretch, capped) so a
  player can genuinely drag light ships; `hook_ship_pull_force` still scales or disables it.
- Hardened the phys-tick delta handling (implausible values fall back to 1/60 s).

### Lead overhaul fixes

- **Sag is actually visible now.** The rope renderer takes an explicit sag depth in blocks
  (`drawSegmentSag`); leads droop up to ~1.4 blocks based on unused slack, distributed across
  wrapped spans. (The old taut formula produced at most ~0.07 blocks at typical leash distances
  — technically present, invisible in practice.)
- **The rope attaches at the mob's neck.** The leash offset is model-space and must be rotated
  by the mob's body yaw (as vanilla does); without the rotation the rope floated beside/above
  wide mobs like horses.

### Lead overhaul (config: `leads.overhaul_enabled`, default on)

- **Bedrock-style leads.** Leashed mobs swing on the lead (outward velocity dropped, spring
  easing back) instead of vanilla's jerky elastic bounce; attach rules and the 10-block break
  distance stay vanilla.
- **Rope visuals.** The vanilla straight leash line is replaced with the grapple rope: sagging
  below ~6 blocks, and visually wrapping around terrain corners (client-side segments; purely
  cosmetic). Implemented via the mod's first real mixin (`MobRenderer.renderLeash`), which
  meant re-adding a mixin config + annotation processor to the build. Known niche interaction:
  with the overhaul on, leads attached to fence knots on VS ships bypass VS's own leash-render
  adjustments; disable the overhaul if that matters to you.
- Network protocol bumped to **2.1** (new in-hand customization message).

### Valkyrien Skies integration

- **Rope bends now work on rotated ships.** Ship raytraces report hit sides as shipyard-space
  `Direction`s, but all bend math (corner planes, bend offsets, the unwrap plane test) ran in world
  space with axis-aligned normals — correct only at rotation 0 0 0. All bend geometry now rotates
  ship-hit normals into world space via the ship's current transform, so rope wraps correctly on
  ships at any angle (this also removes the "unwrap slightly early/late" limitation noted in 1.0.2,
  and stops ship hits from corrupting nearby world-block bends).
- **Ships moving or rotating into a stationary rope now catch it.** The bend sweep compared this
  tick's rope against last tick's rope in world space, so a ship moving into a rope that wasn't
  itself moving looked like a pre-existing overlap and was ignored. When the rope hits a ship
  block, last tick's rope endpoints are now mapped through the ship's previous-tick transform
  (glued to the ship), so relative motion is detected: a cube ship rotating inward now wraps the
  rope instead of phasing through it.
- **Hook impacts knock ships.** Attaching to a ship queues a small world-space impulse at the hit
  point, applied on the next physics tick (via the VS phys-tick event). Scales with hook impact
  speed; light ships get visibly nudged, heavy ships barely notice. Tunable/disableable via the new
  `hook_ship_impulse` config multiplier. (Requires the `org.valkyrienskies.core:internal` artifact
  at compile time — added to `build.gradle`.)

### Gameplay

- **Grappling and creative flight now coexist.** While flying, the controller keeps the hook
  attached but stops applying rope physics: no constraint, no rope-snap, no space-detach; rope
  length pays out as you fly so leaving flight doesn't yank you back. A mid-air space press by a
  player who *can* fly defers the rope-jump detach by 8 ticks (vanilla's double-tap window), so
  double-tapping into flight no longer insta-detaches the grapple.
- **Hitting a mob now clings instead of yanking.** The old behavior launched the mob at the player
  and deleted the hook. Now the hook damages the mob on impact (new `hook_entity_damage` config,
  default 3), then clings to it lead-style: the hook follows the mob, walking out of rope range
  drags the mob with you, and crouching reels it in quickly (new `hook_entity_retract_speed`
  config). The rope snaps if the mob gets beyond max rope length + snap buffer; the hook releases
  if the mob dies. The player is never pulled toward the mob. (Lead-style ropes render straight —
  they don't bend around terrain.)

## 1.0.2 — 2026-07-18

Full audit-driven release. Every item below references its section in `CODEBASE_AUDIT.md`.
The network protocol version was bumped to **2.0** (segment/attach packets changed shape), so
1.0.2 clients/servers will not silently desync against 1.0.1 peers.

### Valkyrien Skies integration

- **Rope now wraps around VS ships** (§2.1). `SegmentHandler` previously skipped creating rope
  bends whenever a raytrace hit a shipyard block, so ropes passed straight through ship hulls.
  Bends created on ship blocks now store the ship id and the bend's ship-local position
  (new `segmentShipIds` / `segmentShipLocals` lists), and every tick each ship bend is
  re-projected to its current world position, so wrapped rope follows the ship as it moves and
  rotates. Bends whose ship unloads are dropped automatically. `getBendBlock()` computes the bend's
  block in shipyard space for ship bends, so hook-past-bend and sticky-rope attachment land on the
  correct ship block.
  *Known limitation:* the unwrap plane test still treats bend edges as world-axis-aligned, so
  ropes on heavily rotated ships may unwrap slightly early/late (documented in the README).
- **Ship raycast hits are now requested in world space** (§2.3).
  `clipIncludeShips(level, ctx, false)` became `clipIncludeShips(level, ctx, true)` — the third
  argument is `shouldTransformHitPos`, and `false` was leaving ship hit *locations* in shipyard
  coordinates (unusable for rope geometry; also wrong for the entity-hit fallback raytrace).
- **Rope pays out instead of instantly snapping when a hooked ship moves away** (§2.4). When the
  hook is ship-attached and the rope would exceed the snap buffer, `GrappleController` now extends
  the rope length up to the hook's max length first; the rope only snaps once genuinely out of
  rope.
- **Hooks detach when their ship disappears** (§2.6). If the attached ship can't be resolved for
  3 seconds (unloaded/deleted), the server now releases the hook and notifies the owner instead of
  leaving it frozen in mid-air with a taut rope.
- **Removed the broken ship-lookup fallback** (§2.2). `attachToShip` passed a *world-space*
  position to `getShipObjectManagingPos`, which expects *shipyard* coordinates — dead code at best,
  a mis-attach at worst. The block-position path (which was always the one that actually worked)
  remains.
- **Attach-side nudge applied in shipyard space** (§2.8). The small hook offset per hit side
  (`serverAttach`) was applied along world axes; on ships the hit `Direction` is shipyard-space, so
  the nudge is now applied to the ship-local position inside `attachToShip`, keeping the hook flush
  on rotated ships.
- **`ModList.isLoaded("valkyrienskies")` is now cached** (§2.7) in `GrapplemodUtils.vsLoaded()`
  instead of being looked up on every raytrace/tick.
- **VS core dependency versions pinned** (§2.9). `org.valkyrienskies.core:api/impl/util` used
  dynamic `+` versions; they're now pinned to `1.1.0+b8f10be9b1`, the exact versions VS 2.4.0
  declares in its POM (new `vs_core_version` gradle property).
- **VS dependency range tightened** (§2.9). `mods.toml` now declares `[2.4.0,2.5.0)` instead of
  `[2.4.0,)`, since the integration touches VS-internal API that can change between releases.
- New/changed network payloads: `SegmentMessage` carries per-bend ship id + ship-local position;
  `GrappleAttachMessage` carries the same for every synced bend, and its optional hook-ship payload
  is now behind an explicit boolean flag instead of trailing-bytes sniffing (§7).

### Bug fixes

- **Right-hand hook cleared on item drop** (§3.1). `onDroppedByPlayer` called
  `setHookEntityLeft(player, null)` in the right-hook branch (copy-paste bug), leaking a stale map
  entry holding dead entity references.
- **`GrapplehookEntity.tick()` returns after discarding a ghost hook** (§3.2). Previously the whole
  tick kept running with a null `shootingEntity`, and the magnet branch dereferenced
  `shootingEntity` *before* its null check — an NPE for magnet hooks whose owner despawned.
- **Grappling-block config cache actually caches** (§3.3). The `prev*` fields in
  `GrappleConfigUtils` were never assigned, so the block lists were re-parsed (string splits +
  registry lookups) on *every* `attachesBlock`/`breaksBlock` call — including inside the magnet's
  per-tick volume scan. Unresolvable block ids in the config are now skipped with a warning instead
  of silently adding a bogus entry.
- **Config sync no longer corrupts on non-ASCII strings** (§3.4). `LoggedInMessage` wrote strings
  as UTF-16 length + platform-default-charset bytes (length mismatch desyncs the entire stream;
  charset differs between Windows clients and Linux servers). Now uses `writeUtf`/`readUtf`
  (length-prefixed UTF-8).
- **Client no longer crashes when the attach packet arrives before the player entity resolves**
  (§3.5). `GrappleAttachMessage` now falls back to the hook position instead of NPE-ing on a null
  player (respawn/dimension-change race).
- **Controller tick iterates a copy** (§3.6). `onClientTick` caught and ignored
  `ConcurrentModificationException` (silently skipping controller ticks); it now iterates a
  snapshot of the controller map.
- **Wallrun enchant detection fixed** (§3.7). The check `break`-ed after inspecting only the first
  enchantment of each armor piece, so boots with e.g. Unbreaking + Wallrun intermittently failed to
  wallrun. Now uses `EnchantmentHelper.getItemEnchantmentLevel` per piece.
- **Server-side player maps cleaned on logout** (§3.8). New `PlayerLoggedOutEvent` handler removes
  the player from `ServerControllerManager.attached` / `allGrapplehookEntities` and the
  left/right hook maps, and kills any live hooks — these previously leaked player and hook entity
  references across sessions.
- **Owner no longer receives duplicate segment sync** (§3.9). Segment add/remove broadcasts moved
  from `TRACKING_CHUNK` (which included the owning player, who also simulates segments locally,
  causing transient rope flicker) to `TRACKING_ENTITY` on the owner (which excludes them). The
  remove handler also bounds-checks the index against races with local unwrapping.
- **Ghost-hook removal returns immediately** — see §3.2 above.

### Security / multiplayer robustness

- **Movement packets are validated** (§5.1). `PlayerMovementMessage` previously teleported the
  sender to arbitrary client-supplied coordinates with no checks (and reset vanilla movement
  checking). The server now rejects non-finite values, displacements over 16 blocks per message,
  and speeds over 16 blocks/tick, logging offenders.
- **Modifier-block edits are validated server-side** (§5.2). `GrappleModifierMessage` applied any
  client-sent customization directly. The server now requires the player to be within 8 blocks of
  the block, and re-applies the GUI's rules: options whose upgrade category isn't unlocked (or that
  the config disables, or that require the limits upgrade the block doesn't have) are reset to
  defaults, and all slider values are clamped to their configured min/max (creative mode bypasses
  unlocks, matching the GUI). New `GrappleCustomization.getCategory()` provides the
  option→category mapping.
- **Malformed keypress packets can't throw** (§5.3). `KeypressMessage` decoded an unvalidated enum
  ordinal (`values()[readInt()]`); now uses `readEnum`/`writeEnum`.
- **Network channel requires matching versions** (§5.4). The channel accepted any remote version
  (`v -> true`), allowing silently-desynced mismatched connections. It now requires an exact
  protocol match (`2.0`) when the mod is present on the other side, while still allowing
  vanilla/modless peers via `NetworkRegistry.acceptMissingOr`.

### Performance

- **Magnet block scan sphere-culled** (§4.1). The O((2r+1)³) cube scan now skips corners outside
  the attraction radius (~48% fewer block checks), on top of the §3.3 config-cache fix that
  removed a config re-parse per checked block.
- **Culling bounding box is read-only** (§4.2). `SegmentHandler.getBoundingBox` no longer mutates
  the segment lists from the render thread (it copied `updatePos` behavior while the tick thread
  could be mid-mutation); it now computes min/max from a defensive snapshot.
- **Rope renderer caches its display ItemStack** (§4.3). Previously a new stack + NBT was allocated
  every frame per hook.

### Rendering

- **Rope sag re-enabled** (§7). The `taut` line in `RenderGrapplehookEntity` was commented out, so
  the catenary-sag rendering never ran; slack rope now visibly sags again.

### Build & packaging

- **Removed the unused `capabilitysyncer` dependency and the entire shadow-jar pipeline** (§6.1).
  The library had zero usages but was being bundled (relocated) into every release jar. The shadow
  plugin, `shadow` configuration, `shadowJar`/`reobfShadowJar` wiring and its gradle.properties
  entry are gone; plain `jar` + `reobfJar` remains.
- **Removed the empty Mixin scaffolding** (§6.2). `grapplemod.mixins.json` declared no mixins, yet
  MixinGradle, the annotation processor, and `debug.export = true` ran on every build. All of it is
  deleted (including the `mixin_version` property).
- **Repository hygiene** (build fix discovered while verifying): the Forge maven is declared before
  the Valkyrien Skies reposilite (which half-claims some Forge modules, e.g. `mergetool`, without
  serving all their classifier jars), the KotlinForForge repo is content-filtered to its own group,
  and the Modrinth maven is filtered to `maven.modrinth`. KotlinForForge is no longer passed
  through `fg.deobf` (it contains no Minecraft classes; remapping it fails under non-official
  mappings). Unused repositories (SpongePowered, GeckoLib, 100Media) removed.
- **Removed `org.gradle.daemon=false`** (§6.10) — cold-starting Gradle on every invocation.
- **Deleted the stale `GrappleMod.VERSION` constant** (§6.3) (`"1.16.5-v12.2"`, read by nothing).
- **`mods.toml` refreshed** (§6.9): added `issueTrackerURL`, `displayURL`, `logoFile`; removed
  template boilerplate comments.
- Version bumped to **1.0.2**.

### Code quality

- **All 35 `System.out` debug prints replaced with `GrappleMod.LOGGER`** (§6.5) at appropriate
  levels (debug/warn/error) across `SegmentHandler`, `GrapplehookEntity`, `GrappleController`,
  `ClientControllerManager`, `GrappleCustomization`, `LoggedInMessage`, `PlayerMovementMessage`,
  `GrapplemodUtils`, `CommonEventHandlers`, and `Vec`.
- **Restored the mangled GPL header** in `GrappleModifierMessage` (§6.6).
- **Removed the misleading `@Mod.EventBusSubscriber` on `ClientEventHandlers`** (§6.7) — it only
  registers static handlers and all handlers there are instance methods registered via the
  constructor; a comment now explains the registration path.
- **Item property predicates namespaced** (§6.8): `rocket`, `double`, `motor`, `smart`,
  `enderstaff`, `magnet`, `attached`, `hook` are now registered as `grapplemod:*` (previously they
  landed in the `minecraft:` namespace), with `grapplinghook.json` and `repeller.json` model
  overrides updated to match.
- **Creative tab uses the sided proxy interface** (§3.10): `CommonSetup`'s tab lambda referenced
  the client-only `ClientProxy` class directly from common code; it now goes through
  `ClientProxyInterface` with a null check (the proxy is null on dedicated servers).
  `CommonEventHandlers` is now constructed explicitly in the `GrappleMod` constructor instead of a
  static-initializer side effect.
- **NaN-safe friction-undo heuristic** (§7): the `GrappleController` constructor compared motion
  ratios with raw division (0/0 → NaN); rewritten as an epsilon-guarded same-sign
  within-factor-two check with unchanged intent.

### Documentation

- **README rewritten for this fork** (§6.4): describes the VS integration (and its known
  limitation), correct requirements and dev setup; upstream credits retained.
- **`Testing` checklist gained a Valkyrien Skies section** (§6.4): attach/swing on moving ships,
  rope pay-out and snap, wrap/unwrap around hulls, ship deletion/unload detach, rotated-ship
  attach, wallrun on hulls, dedicated-server runs with and without VS installed.
- Added `CODEBASE_AUDIT.md` (the audit this release implements) and this changelog.

### Audit items deliberately *not* implemented

- **§2.5 (two-way ship forces)** — flagged in the audit as an enhancement, not a bug: grappling a
  ship still applies no reaction force to the ship. Doing this well needs a VS ship force
  attachment and tension modeling; tracked as future work.
- §7 behavioral notes documented as intentional: holding the hook blocks block-breaking
  (left-click = throw), Long Fall Boots cancel all fall damage, ender-launch movement remains
  client-authoritative (now bounded by the §5.1 clamps).
