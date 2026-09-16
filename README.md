# Personal Space

A RuneLite plugin that spreads out players who are standing on the exact same tile, so at a
cooking fire, a shooting star, a bank or the Grand Exchange you can actually see everyone's
outfit instead of one blob of five people wearing each other.

Up to five players on a tile get nudged into a small ring, well inside the tile. It is purely
cosmetic: the real player, their tile, their clickbox, overhead text, chat bubbles and minimap
dot are all left exactly where they are. The plugin switches itself off in the Wilderness, on
PvP / Deadman / Bounty Hunter / tournament worlds, in PvP areas, and while you are in combat.

## Status

**v0.2.0, prototype, not yet confirmed in-game.**

- v0.1.0 was tested at the Grand Exchange and **nothing moved**. The cause: it nudged players in
  the wrong renderer call. The game draws players, NPCs and projectiles through `drawTemp`;
  `drawDynamic`, which v0.1.0 hooked, only ever carries animated scenery and items on the ground.
  v0.2.0 hooks `drawTemp`.
- Fixing that exposed the real obstacle: when several players stand still in the exact middle of
  one tile, **the game only draws the first of them** and skips the rest. That is why a stack looks
  like one person. So v0.2.0 draws the hidden stackmates itself, at their ring slots, whenever the
  game draws the visible player on that tile.
- v0.2.0 also adds a **Personal Space sidebar panel** (the button with three coloured dots on
  RuneLite's right-hand toolbar) so all testing happens in one place: a live status line, every
  setting, a list of live checks, and a **Copy report** button.
- Standing still is now detected by position (in the middle of the same tile two game ticks in a
  row) instead of by animation ids.

## Testing it

1. Start the dev client, either by double-clicking **Run Dev Client.bat** or from IntelliJ with the
   *Run Dev Client* configuration, and log in.
2. Make sure the **GPU** plugin is on.
3. Click the **Personal Space** button on the right-hand toolbar (three coloured dots) to open the sidebar.
4. The status line at the top says whether it is working and, if not, what to do. Green is working,
   grey is waiting for something (e.g. no stacked players nearby), orange is paused or switched off
   for safety, red is a problem.
5. Set **Mode** to *Test: shift my character*. Your character should be drawn a quarter tile to the
   east. Check that feet stay on the ground, animations play normally, it holds up while walking and
   turning the camera, and that your name and clicks stay at your real spot.
6. Set **Mode** back to *Spread stacked players* and stand on someone's tile at a bank or fire.
7. If the status turns red, press **Copy report** and paste the text into a message. It contains
   every number the panel shows.

## Running it

Everything is a double-click:

- **Run Dev Client.bat** starts a normal RuneLite client with this plugin already loaded. The first run downloads RuneLite and takes a few minutes.
- **Check Build.bat** compiles the plugin and runs its unit tests without starting the game.
- In IntelliJ, open this folder, then pick **Run Dev Client** from the run menu at the top (the one that says *Current File* until you pick something).

All need Java 11 (the machine this was written on has it at `C:\Program Files\Java\jdk-11`).

## Settings

All of these are in the sidebar panel and in RuneLite's normal settings screen; the two stay in sync.

| Setting | Default | What it does |
| --- | --- | --- |
| Effect on | On | Untick to pause without turning the plugin off. Everyone snaps back to their real spot. |
| Mode | Spread stacked players | *Test: shift my character* draws only your own character a fixed distance east, for checking the basic effect on your own. |
| Separation | Medium | Ring radius: Small 20, Medium 32, Large 44 local units. A tile is 128 units, so even Large stays inside the tile. |
| Max players per tile | 5 | Spread at most this many on one tile (2 to 5). Extra players stay in the middle as normal. |
| Move my character too | Off | Off: you always stay exactly where you really are and others step around you. On: you take a ring slot like everyone else. |
| Smooth movement | On | Ease players into their slot over a fraction of a second instead of snapping. |
| Test offset (units) | 32 | Only used by the test mode. |

Only players who are standing still are spread out. Anyone walking or dead
is left alone, and the moment the local player is in the Wilderness, on a PvP-type world, in a
PvP area or has a health bar showing, every player snaps back to their real spot immediately.

## The sidebar's live checks

| Check | Meaning |
| --- | --- |
| Effect | On, or paused by the *Effect on* switch. |
| Logged in / Safety | Whether a safety rule is holding the effect off, and which. |
| Renderer | The GPU renderer Personal Space sits in front of. *None* means turn on the GPU plugin. |
| Connected | Whether Personal Space is currently hooked in front of that renderer. |
| Players drawn | How many player draws per second pass through the hook. Should never be 0 while logged in. |
| Players nearby / Standing still | Players in the scene, and how many of them count as standing still. |
| Stacked tiles / Being spread | Tiles with two or more still players, and how many players have a spot in a ring. |
| Drawn moved | Player draws per second actually drawn at a nudged spot. |
| Oddities | Only shown if something that should never happen did; the report has the detail. |

## How it works

RuneLite gives renderer plugins (GPU, 117 HD) a hook called `DrawCallbacks`. When a renderer is
active, the game asks it to draw every player through `drawTemp(...)`, passing the final local
x/y/z the model is about to be drawn at. Personal Space installs a thin shim in front of the
active renderer: every call is forwarded untouched, except that for a player who has an offset,
the x/z are nudged and the height is re-sampled from the ground at the new spot so the feet stay
planted on slopes. The `Player` object, its `WorldPoint` and `LocalPoint` are never modified.

The shim is re-checked every frame, so it survives the GPU plugin being turned off and on, and
it hands the callback back to the renderer when Personal Space is turned off. Installing or
removing it causes the same brief scene reload as toggling the GPU plugin, so it is only done
when actually needed.

Offsets are worked out once per game tick: standing-still players are grouped by tile, each
group is sorted by player id (so every client agrees on who gets which slot and nothing
jitters), and the members are placed evenly around a ring. Per frame the current offsets ease
toward the targets.

Everything is public RuneLite API. No mixins, no reflection, nothing persisted, nothing sent
anywhere.

### Known limits of this approach

- **Must fix before publishing: server-hidden players.** The game also skips players the server marks as hidden (for example an invisible Jagex moderator). Personal Space cannot tell them apart from players hidden only by stacking, so one standing still in a stack would be drawn. This is fine for local testing but must be solved before a Plugin Hub submission.
- **Graphics stay put.** Spell and emote graphics (e.g. High Alchemy) are drawn at the real tile centre, and hidden stackmates are drawn without theirs.
- **Entity Hider.** Players hidden by Entity Hider stay hidden, but the sidebar may wrongly turn red if every relevant player is hidden.
- **Needs a GPU renderer.** In software rendering mode the game never calls draw callbacks, so nothing happens. The sidebar and a one-time chat message say so.
- **Overheads stay put.** Names, chat bubbles, skull and prayer icons are drawn by the game from the real position, so they float over the real tile, not the nudged model. Same for hitsplats and the minimap.
- **Clickbox.** The game works out clicks from the player's real position, not from the draw call, so the clickbox should stay at the real tile. This is on the in-game checklist.
- **Plugin Hub review risk.** `Client.setDrawCallbacks` is public API, but wrapping another plugin's callback is unusual and a reviewer may push back. If they do, the fallback is to hide the original player through RuneLite's `RenderCallback` (`RenderCallbackManager.register`, `addEntity` returning false for that player) and draw a copy of the player's current model at the offset spot with a `RuneLiteObject`. That works in software mode too, at the cost of more code and re-uploading the model each frame.

## Project layout

```
src/main/java/com/grant9008/personalspace/
  PersonalSpacePlugin.java      plugin entry: installs the shim, safety rules, per-tick layout, sidebar updates
  SpreadingDrawCallbacks.java   the shim in front of the renderer; the only place x/z are changed
  OffsetTable.java              per-player target / current offsets and the per-frame easing
  StackSpreader.java            pure layout logic (grouping, deterministic slots, ring maths)
  StillnessTracker.java         pure "is this player standing still" logic
  PersonalSpacePanel.java       the sidebar panel (Swing)
  Snapshot.java                 what the sidebar shows, captured once per refresh
  StatusSummary.java            pure logic turning a snapshot into the status line, checks and report
  PersonalSpaceConfig.java      settings
src/main/resources/com/grant9008/personalspace/
  panel_icon.png                the sidebar button icon
src/test/java/com/grant9008/personalspace/
  StackSpreaderTest.java, StillnessTrackerTest.java, StatusSummaryTest.java   unit tests
  PersonalSpacePluginTest.java  dev entrypoint used by "Run Dev Client.bat" and IntelliJ
```

`.github/workflows/build.yml` compiles and tests on every push, which is what the Plugin Hub
needs to see green before a submission.
