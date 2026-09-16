# Personal Space

**See the whole crowd.** When several players stand on the same tile, Old School RuneScape only
draws one of them, so a busy bank, the Grand Exchange, an anvil or a cooking fire looks half
empty. Personal Space spreads everyone on a crowded tile out a little so every player and every
outfit is visible.

- Players facing the same way (at an anvil, bank booth, range, furnace or fire) stand **side by
  side**. A crowd facing every which way forms a small **circle**.
- Purely cosmetic. Nobody's real position, clickbox, name, chat or minimap dot changes.
- Switches itself off in the Wilderness, in PvP areas, on PvP-type worlds and while you're in combat.
- Never shows players the game itself keeps hidden.
- Needs the **GPU** plugin (or 117 HD) turned on.

## Using it

Click the **Personal Space** button on RuneLite's right-hand toolbar (three coloured dots).

| Control | What it does |
| --- | --- |
| On/off switch | Spread out crowds, or show the game as normal. |
| Players per tile | How many players on one tile get their own spot (2 to 5). |
| Spacing | Close, Normal or Wide. Wide still stays inside the tile. |
| Arrangement | **Auto** picks side by side or circle for each tile. Or force one. |
| Move my character too | Off: you stay put and others step around you. |
| Smooth movement | Players glide into place instead of jumping. |
| Troubleshooting | Test mode, live checks and a **Copy report** button for bug reports. |

The status line at the top says what's happening: green is working, grey is waiting (for example
no crowds nearby), orange is off or paused for safety, red is a problem worth reporting.

## How it works

The game adds players to the scene every frame, and when several stand still in the exact middle
of one tile it only adds the first. Personal Space sits in front of the GPU renderer
(`DrawCallbacks`): when the game draws that first player, it also draws the others on the tile at
their spots, using each player's own current model, animation and facing.

To make sure it only ever draws players the game is willing to show (and never, say, an invisible
moderator), it watches RuneLite's render callback, which only sees players the game has already
decided to draw. Players stacked behind someone are briefly let through one at a time to be
confirmed; the frame looks identical while this happens.

Everything is public RuneLite API. No reflection, nothing saved about other players, nothing sent
anywhere.

### Known limits

- **Spell and emote graphics** (for example High Alchemy) still appear at the middle of the tile.
- If **Entity Hider** hides every relevant player, the status may wrongly turn red.
- Players past the "players per tile" limit stay hidden in the middle, as in the normal game.

## Development

- **Run Dev Client.bat**, or in IntelliJ the **Run Dev Client** configuration, starts RuneLite with the plugin loaded.
- **Check Build.bat** compiles and runs the unit tests.
- Java 11. `.github/workflows/build.yml` builds on every push.

```
src/main/java/com/grant9008/personalspace/
  PersonalSpacePlugin.java      plugin entry: safety rules, per-tick layout, sidebar updates
  SpreadingDrawCallbacks.java   sits in front of the renderer; draws players at their spots
  StackProbe.java               confirms players are ones the game is willing to draw
  StackSpreader.java            layout: who goes where (circle or side by side)
  StackRegistry.java            which players share each crowded tile
  StillnessTracker.java         "is this player standing still"
  OffsetTable.java              current offsets and smooth movement
  PersonalSpacePanel.java       the sidebar
  StatusSummary.java, Snapshot.java   status line, live checks and report
  PersonalSpaceConfig.java      settings
```
