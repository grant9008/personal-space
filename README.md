# Personal Space

A RuneLite plugin that spreads out players who are standing on the exact same tile, so at a
cooking fire, a shooting star, a bank or the Grand Exchange you can actually see everyone's
outfit instead of one blob of five people wearing each other.

Up to five players on a tile get nudged into a small ring, well inside the tile. It is purely
cosmetic: the real player, their tile, their clickbox, overhead text, chat bubbles and minimap
dot are all left exactly where they are. The plugin switches itself off in the Wilderness, on
PvP / Deadman / Bounty Hunter / tournament worlds, in PvP areas, and while you are in combat.

## Status

**v0.1.0, prototype.** It compiles cleanly against the current RuneLite release and its
layout logic has unit tests, but the visual result has **not been checked in-game yet**.
That is the next step, and the plugin has a built-in test mode for it.

### What to check in-game (first milestone)

1. Turn on the **GPU** plugin (or 117 HD). Personal Space does nothing without one and will say so in the chat box once.
2. Open the Personal Space settings and set **Mode** to *Test: shift my character*.
3. Your own character should now be drawn a quarter tile to the east of where it really stands. Check that:
   - the feet sit on the ground (no floating or sinking), including on slopes and stairs;
   - the idle animation, emotes and skilling animations all play normally;
   - it looks right from every camera angle and zoom level;
   - while you walk the character is drawn offset the whole way (test mode ignores walking on purpose);
   - your name / chat bubble / skull stay over the real spot, and clicking still works.
4. Turn the GPU plugin off and on again while the game is running, then repeat step 3. The plugin should re-attach itself.
5. Set **Mode** back to *Spread stacked players* and stand on a tile with someone else at a fire or a bank.

If step 3 already looks wrong, the whole approach needs the fallback described under *How it works*, and nothing else is worth testing until then.

## Running it

Everything is a double-click:

- **Run Dev Client.bat** starts a normal RuneLite client with this plugin already loaded. Log in, then find *Personal Space* in the plugin list. The first run downloads RuneLite and takes a few minutes.
- **Check Build.bat** compiles the plugin and runs its unit tests without starting the game.

Both need Java 11 installed (the machine this was written on has it at `C:\Program Files\Java\jdk-11`).

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Mode | Spread stacked players | *Test: shift my character* draws only your own character a fixed distance east, for checking the basic effect on your own. |
| Separation | Medium | Ring radius: Small 20, Medium 32, Large 44 local units. A tile is 128 units, so even Large stays inside the tile. |
| Max players per tile | 5 | Spread at most this many on one tile (2 to 5). Extra players stay in the middle as normal. |
| Move my character too | Off | Off: you always stay exactly where you really are and others step around you. On: you take a ring slot like everyone else. |
| Smooth movement | On | Ease players into their slot over a fraction of a second instead of snapping. |
| Test offset (units) | 32 | Only used by the test mode. |

Only players who are standing still are nudged. Anyone walking, dead, or showing a health bar
is left alone, and the moment the local player is in the Wilderness, on a PvP-type world, in a
PvP area or has a health bar showing, every player snaps back to their real spot immediately.

## How it works

RuneLite gives renderer plugins (GPU, 117 HD) a hook called `DrawCallbacks`. When a renderer is
active, the game asks it to draw every player through `drawDynamic(...)`, passing the final
local x/y/z the model is about to be drawn at. Personal Space installs a thin shim in front of
the active renderer: every call is forwarded untouched, except that for a player who has an
offset, the x/z are nudged and the height is re-sampled from the ground at the new spot so the
feet stay planted on slopes. The `Player` object, its `WorldPoint` and `LocalPoint` are never
modified; there is no public API that could, and the plugin does not want to.

The shim is re-checked every frame, so it survives the GPU plugin being turned off and on, and
it hands the callback back to the renderer when Personal Space is turned off. Installing or
removing it causes the same brief scene reload as toggling the GPU plugin, so it is only done
when actually needed.

Offsets are worked out once per game tick: idle players are grouped by tile, each group is
sorted by player id (so every client agrees on who gets which slot and nothing jitters), and
the members are placed evenly around a ring. Per frame the current offsets ease toward the
targets.

Everything is public RuneLite API. No mixins, no reflection, nothing persisted, nothing sent
anywhere.

### Known limits of this approach

- **Needs a GPU renderer.** In software rendering mode the game never calls draw callbacks, so nothing happens. The plugin tells you once in the chat box.
- **Overheads stay put.** Names, chat bubbles, skull and prayer icons are drawn by the game from the real position, so they float over the real tile, not the nudged model. Same for hitsplats and the minimap.
- **Clickbox.** Since the 2025 renderer rework the game does its own click detection rather than the GPU plugin, so the clickbox most likely stays at the real tile. This is on the in-game checklist.
- **Plugin Hub review risk.** `Client.setDrawCallbacks` is public API, but wrapping another plugin's callback is unusual and a reviewer may push back. If they do, the fallback is to hide the original player through RuneLite's `RenderCallback` (`RenderCallbackManager.register`, `addEntity` returning false for that player) and draw a copy of the player's current model at the offset spot with a `RuneLiteObject`. That works in software mode too, at the cost of more code and re-uploading the model each frame.

## Project layout

```
src/main/java/com/grant9008/personalspace/
  PersonalSpacePlugin.java      plugin entry: installs the shim, gates on safety, computes targets per tick
  SpreadingDrawCallbacks.java   the shim in front of the renderer; the only place x/z are changed
  OffsetTable.java              per-player target / current offsets and the per-frame easing
  StackSpreader.java            pure layout logic (grouping, deterministic slots, ring maths)
  PersonalSpaceConfig.java      settings
src/test/java/com/grant9008/personalspace/
  StackSpreaderTest.java        unit tests for the layout logic
  PersonalSpacePluginTest.java  dev entrypoint used by "Run Dev Client.bat"
```

`.github/workflows/build.yml` compiles and tests on every push, which is what the Plugin Hub
needs to see green before a submission.
