# MythicItemSkills

Paper plugin for MythicMobs by Leon. Runs MythicMobs item `Skills` without MythicCrucible.

## Features

- Item triggers from `Items/*.yml`: `~onUse`, `~onSwing`, `~onPressF`, `~onEquip`, `~onPickup`, `~onDrop`, `~onTimer:<ticks>`
- Mechanics: `setitemnbt{key=..;value=..}`, `setlore{lore=[...]}`
- Placeholders: `<caster.itemnbt.key>` / `<caster.item.nbt.key>`
- Command: `/itemskills` (permission `itemskills.debug`)

## Build

Requires JDK 21+.

    mvn package

The jar is written to `target/MythicItemSkills-1.0.0.jar`.
