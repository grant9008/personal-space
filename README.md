# Personal Space

**See the whole crowd.** When several players stand on the same tile, Old School RuneScape only
draws one of them, so a packed bank, the Grand Exchange, an anvil or a campfire looks half empty.
Personal Space brings everyone back into view and gives them a little room, so every player and
every outfit can be seen. Often it isn't a big crowd at all: the one player you saw by the fire
turns out to be four friends hanging out.

![Before and after in a Varrock doorway by a fire](docs/photos/doorway-fire.jpg)

![Before and after: one player turns out to be two](docs/photos/pair.jpg)

![Before and after: one player turns out to be three](docs/photos/trio.jpg)

![A group of three in each pose: off, natural, angled, facing, then facing close and wide](docs/photos/poses.jpg)

[![Tip the developer](https://img.shields.io/badge/%E2%99%A5%20Tip%20the%20developer-ff981f?style=for-the-badge)](https://buy.stripe.com/aFafZg9ehaaxaVaf3e00000)

<img src="docs/photos/sidebar-settings.png" width="242" align="right" alt="The Personal Space sidebar">

## What it does

- **Shows the players the game hides.** Stacked players are drawn again, spread out around their tile.
- **Smart arrangement.** Crowds spread out around their tile, never into a bank booth, stall, anvil
  or wall, and line up side by side at things people face. Small groups stay close together; big
  crowds get more room.
- **Players walk into place** with their own walk animation.
- **Pose small groups.** Two or three players in the open can be angled towards each other like a
  photo, or turned to face each other, for the perfect fashionscape screenshot.
- **At an anvil, range or fire**, players form a curve around it and all face it, so nobody ends
  up hitting thin air. A crowd gathered round a fire stays close and faces the fire, whichever side of it they
  end up on. Two players share the space in front of it evenly; extra players fill the
  curve outwards, then stand in the gaps of a second row behind.
- **At a bank counter**, players line up side by side along the counter, close together, like a
  busy bank rather than a queue. Around a fire they stay close too, whatever the spacing slider says.
- **Calm crowds.** Everyone keeps their own spot when people come and go. A spot is held for a few
  seconds for someone who steps away, and only players at the back move forward to fill a gap.
  When a crowd shrinks to one player, they step back to the middle of their tile.
- **Purely cosmetic.** Nobody's real position, clickbox, name, chat or minimap dot changes.
- **Safe by design.** Switches itself off in the Wilderness, in PvP areas, on PvP-type worlds, in
  PvP minigames such as Castle Wars, Soul Wars and Last Man Standing, and while you're in combat.
  It never shows players the game itself (or another plugin such as Entity Hider) keeps hidden,
  and they never push anyone else aside.

Needs the **GPU** plugin (or 117 HD) turned on.

<br clear="right">

## In game

![Before and after at Varrock West Bank](docs/photos/varrock-west-bank.jpg)

![Before and after along the Varrock West Bank counter](docs/photos/bank-counter-row.jpg)

![Before and after at a busy Grand Exchange](docs/photos/grand-exchange-busy.jpg)

![Before and after beside the Grand Exchange booths](docs/photos/grand-exchange-crowd.jpg)

![Players in a curve around an anvil in Varrock](docs/photos/anvil.jpg)
*Smithing together: a group that would all share one tile forms a curve around the anvil.*

![Personal Space and its sidebar at Varrock West Bank](docs/photos/in-game-sidebar.jpg)
*Choose how many players can share a tile and how far apart they stand; changes show straight away.*

## Using it

Click the **Personal Space** button on RuneLite's right-hand toolbar.

| Setting | What it does |
| --- | --- |
| On/off switch | Spread out crowds, or show the game as normal. |
| Players per tile | How many players on one tile get their own spot: 2 to 10, with 5 as the sweet spot. |
| Spacing | Close, Normal (a tile apart) or Wide (two tiles apart, the default), or drag the slider. Bank counters, fires and anvils stay closer on their own. Changes show live. |
| Arrangement | **Smart** lines people up at things they're facing and rings everyone else. **Circle** always uses rings. |
| Pose for 2 or 3 players | **Natural**: the way they really face. **Angled**: turned halfway towards each other. **Facing**: towards each other. Players at an anvil, booth or fire keep facing it. |
| Auto-space small groups | On: groups of 2 or 3 automatically stand close together, whatever the Spacing slider says. Turn it off to unlock them: the Spacing slider then sets exactly how far apart small groups stand, handy for photos. |
| Move my character too | Off: you stay put and others step around you. |
| Troubleshooting | What Personal Space is doing right now, test mode, live checks and a **Copy report** button for bug reports. |

If crowds aren't spreading, open **Troubleshooting**: the status at the top says why (for example
the GPU plugin is off, or you're in a PvP area) and what to do.

## Support the developer

Personal Space is free, and it will stay free. If it made your world feel busier, here's how to
help it keep improving:

- **[Tip the developer](https://buy.stripe.com/aFafZg9ehaaxaVaf3e00000)** through Stripe. Any amount helps.
- **Star this repository** on GitHub so more players find it.
- **Share screenshots** of a busy world with Personal Space on.
- **Report a problem** or suggest an idea in [Issues](https://github.com/grant9008/personal-space/issues).
  Pressing **Copy report** under Troubleshooting and pasting it in helps a lot.

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
- Players busy with an emote or action (sitting, smithing) slide into place instead of walking, so their action isn't interrupted.
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
  CrowdPlanner.java             per tick: which tiles are spread and who stands where
  StackSpreader.java            the spots on a tile: rings, curved rows, straight counter rows
  ShapeMemory.java              keeps a tile's row or crowd shape while the same people are there
  SlotBook.java                 keeps everyone's spot as people come and go
  CollisionTerrain.java         where a player can stand, from the game's walkability map
  StackRegistry.java            which players share each crowded tile
  StillnessTracker.java         "is this player standing still"
  OffsetTable.java              current offsets and walking into place
  PersonalSpacePanel.java       the sidebar
  StatusSummary.java, Snapshot.java   status line, live checks and report
  PersonalSpaceConfig.java      settings
```
