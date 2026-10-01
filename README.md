# BetterMobCrucible

Paper plugin by Leon: item skills for [BetterMob](https://github.com/HyperGaming99/bettermob).
Define items in YAML and let them trigger BetterMob skills.

## Requirements

- Paper 1.21+, Java 21
- BetterMob

## Items

`plugins/BetterMobCrucible/items/*.yml`:

```yaml
example_wand:
  Material: STICK
  Display: '&bExample Wand'
  Lore: ['&7Right click']
  Model: 1001            # optional custom model data
  Skills:
    - sound{s=entity.experience_orb.pickup;p=1;v=1} @self ~onUse
```

Triggers: `~onUse`, `~onSwing`, `~onPressF`, `~onEquip`, `~onPickup`, `~onDrop`, `~onTimer:<ticks>`.
The skill lines are written as a pack into `plugins/BetterMob/packs/BetterMobCrucible/skills/items.yml`
and BetterMob is reloaded when they change.

## Mechanics (registered in BetterMob)

- `setitemnbt{key=..;value=..}`
- `setlore{lore=line 1|&aline 2}`

## Commands

`/bettermobcrucible <give|list|reload|debug>` (alias `/bmc`, permission `bettermobcrucible.admin`)

## Build

JDK 21:

    mvn package

The BetterMob API classes under `src/main/java/eu/northsoft/bettermob/api` are a compile-only copy
(excluded from the jar, BetterMob provides them at runtime). Update them when the BetterMob API changes.

CI (`.github/workflows/build.yml`) builds on every push to `main` and creates or updates the
release `v<version>`.
