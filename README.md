# WoodBot (Fabric, Minecraft 26.1.2, client-side)

`/woodbot start` and `/woodbot stop` control an autonomous tree-chopping bot.

Priorities each tick: self-defense (hostile within 5 blocks) > eating (food < 16) >
axe management (>= 15 durability left, else swap, else stop + warn) > chop nearest tree
within 25 blocks (A* pathing, 1-block jumps), mine all connected logs, collect drops, repeat.

## Build
Requires JDK 25. Gradle wrapper files are not included: copy `gradlew`, `gradlew.bat` and
`gradle/` from https://github.com/FabricMC/fabric-example-mod (branch 26.1), or run
`gradle wrapper --gradle-version 9.4.1` once. Then:

    ./gradlew build        # jar in build/libs/
    ./gradlew runClient    # dev client

Check versions in gradle.properties against https://fabricmc.net/develop/.

## Notes / limits
- Needs an axe in the hotbar (start is refused otherwise). Food and weapons must be in the hotbar too.
- Trees: a log cluster touching natural (non-persistent) leaves; player-built log structures are ignored.
- Very tall trees: logs beyond reach (~5-6 blocks above the ground) are skipped, there is no pillaring.
- Neutral mobs (endermen, zombified piglins, ...) are not attacked.
- Rotation/mining is done client-side with instant aiming; many multiplayer servers' anti-cheat will flag this.
