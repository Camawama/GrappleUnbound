# Grapple Unbound: Grappling Hook Mod with Valkyrien Skies Compatibility

A fork of [yyon's Grappling Hook Mod](https://github.com/yyon/grapplemod), by way of its 1.20.1
port *Grappling Hook Mod: Reforged*, for **Forge 1.20.1**, with compatibility for
**[Valkyrien Skies 2](https://valkyrienskies.org/)** (2.4.x). Formerly **GrappleVS**.

Grappling hooks can attach to VS ships, track them as they move and rotate, wrap their rope around
ship hulls (and around terrain while hooked to a moving ship), and pay out rope when a hooked ship
pulls away.

## Requirements

- Minecraft 1.20.1, Forge 47.4+
- [Cloth Config API (Forge)](https://www.curseforge.com/minecraft/mc-mods/cloth-config-forge), required
- Camafy (the shared library of the CamaCraft mods), required
- [Valkyrien Skies 2](https://modrinth.com/mod/valkyrien-skies) 2.4.11 or newer 2.4.x, **optional**;
  everything works without it, ship integration activates when it's installed (VS itself requires
  Kotlin for Forge)

## Coming from GrappleVS

The mod id changed from `grapplemod` to `grappleunbound`.

- **Worlds carry over.** Everything a world saved under `grapplemod:*` (hooks and upgrade items,
  long fall boots, the modifier block and its block entity, the enchantments) is remapped to
  `grappleunbound:*` when the world loads. Forge still mentions the missing `grapplemod` mod in the
  log once.
- **The config carries over.** `config/grapplemod.toml` is moved to `config/grappleunbound.toml`
  on the first start.
- **Key binds reset once**: the key names are namespaced now (`key.grappleunbound.*`), so rebind
  any you had changed.

## Valkyrien Skies integration notes

- Hook attachment positions and rope bend points on ships are stored in ship-local coordinates and
  re-projected every tick, so ropes follow moving/rotating ships.
- The rope's collision checks see a ship at its current game-tick pose, on the client as well as
  the server, and measure each sweep in the frame of whatever the rope hit (the world, or one ship),
  so ropes hooked to a moving ship wrap around terrain and hulls the same as ropes hooked to a block.
- If a hooked ship moves away, rope is paid out up to the hook's max rope length before the rope snaps.
- If the ship a hook is attached to unloads or is deleted, the hook detaches after a few seconds.
- Rope-bend math uses the ship's current transform, so wrapping and unwrapping work on ships at
  any rotation, and ships moving or rotating into a stationary rope catch it.
- Hook impacts apply a small impulse to ships at the hit point (configurable: `hook_ship_impulse`).

## Setup for Developing

1. Clone this repository
2. `./gradlew build` (standard [Forge development setup](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/));
   this also runs the headless rope tests in `src/test` (`./gradlew test` on its own)
3. `./gradlew runClient` to test in-game

The manual test checklist lives in the `Testing` file, including a Valkyrien Skies section.

## Upstream project

Original mod by yyon: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/grappling-hook-mod) ·
[GitHub](https://github.com/yyon/grapplemod). Licensed GPL-3.0, as is this fork (see `COPYING`).

## Credits

Fork, Valkyrien Skies compatibility and Grapple Unbound by Cama.

Upstream credits:

- Original mod by yyon
- 1.18 update by Nyfaria
- Textures by Mayesnake
- Grappling Hook Mod: Reforged, the 1.20.1 port this fork started from (authors: Yyon, Nyfaria,
  Mayesnake, Chummycho)
- Bug fixes: Random832, LachimHeigrim
- Languages: Blueberryy (Russian), Neerwan (French), Eufranio (Brazilian Portuguese)
- Sound effects: Iwan Gabovitch (double jump, CC0), Outroelison (ender staff, CC0)
- Bug finding: Shivaxi
