# BetterMobCrucible

Paper plugin by Leon: item skills for [BetterMob](https://github.com/HyperGaming99/bettermob).
Define items in YAML and let them trigger BetterMob skills.

## Requirements

- Paper 1.21+, Java 21
- BetterMob 1.1.8 or newer (dev-build for placeholders)

## Items

Items are defined in BetterMob (`plugins/BetterMob/items/*.yml` and each pack's `Items/`), see the BetterMob wiki.
BetterMob handles `~onUse`; BetterMobCrucible adds more triggers to the same `Skills:` lists:

```yaml
example_wand:
  Id: STICK
  Display: '&bExample Wand'
  Skills:
    - sound{s=entity.experience_orb.pickup} @self ~onUse
    - setitemnbt{key=swings;value=1} @self ~onSwing
```

Extra triggers: `~onSwing`, `~onPressF`, `~onEquip`, `~onPickup`, `~onDrop`, `~onTimer:<ticks>`
(timer runs while the item is in the main hand). BetterMob may log these lines as invalid when it
loads items; that is harmless. They are written as skills into
`plugins/BetterMob/packs/BetterMobCrucible/skills/items.yml`, and BetterMob is reloaded when they change.
Run `/bmc reload` after editing item files.

## Mechanics (registered in BetterMob)

- `setitemnbt{key=..;value=..}`
- `setlore{lore=line 1|&aline 2}`

Placeholders (needs a BetterMob build with the placeholder API): `<itemnbt.key>`, `<item.nbt.key>`, `<item.id>`.

## Commands

`/bettermobcrucible <list|reload|debug>` (alias `/bmc`, permission `bettermobcrucible.admin`)

## Build

JDK 21:

    mvn package

The BetterMob API classes under `src/main/java/eu/northsoft/bettermob/api` are a compile-only copy
(excluded from the jar, BetterMob provides them at runtime). Update them when the BetterMob API changes.

CI (`.github/workflows/build.yml`) builds on every push to `main` and creates or updates the
release `v<version>`.
