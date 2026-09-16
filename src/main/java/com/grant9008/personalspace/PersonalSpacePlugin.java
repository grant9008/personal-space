package com.grant9008.personalspace;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Personal Space",
	description = "See the whole crowd: players on the same tile are spread out so everyone is visible. Cosmetic only; off in PvP.",
	tags = {"stack", "stacked", "crowd", "outfit", "fashionscape", "cosmetic", "players", "social", "fire", "bank", "star"}
)
public class PersonalSpacePlugin extends Plugin
{
	static final String VERSION = "1.3.1";

	private static final Logger log = LoggerFactory.getLogger(PersonalSpacePlugin.class);

	/** How often the sidebar is refreshed. */
	private static final long PANEL_REFRESH_NANOS = 500_000_000L;
	/** A problem has to last this many sidebar refreshes in a row before the status goes red. */
	private static final int SUSTAINED_REFRESHES = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PersonalSpaceConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private final OffsetTable offsets = new OffsetTable();
	private final StackRegistry stacks = new StackRegistry();
	/** Tiles laid out as a row last tick, so a tile doesn't flip between row and circle. */
	private java.util.Set<Long> rowTiles = new java.util.HashSet<>();
	/** Created in startUp, once the client is injected. */
	private StackProbe probe;
	private final StillnessTracker stillness = new StillnessTracker();
	/** Tick number each player id was last counted on, to catch two players sharing an id. */
	private final int[] idSeenTick = new int[OffsetTable.CAPACITY];

	/** Our shim, while it is the client's installed draw callback. Null when nothing is hooked. Client thread. */
	private SpreadingDrawCallbacks wrapper;

	private volatile PersonalSpacePanel panel;
	private NavigationButton navButton;

	// ---- client-thread state -----------------------------------------------------------

	private long lastFrameNanos;
	private boolean warnedNoRenderer;
	private boolean warnedBadIds;
	private int tick;

	/** What the last game tick found, for the sidebar. */
	private Snapshot.Gate gate = Snapshot.Gate.NOT_LOGGED_IN;
	private int nearby;
	private int still;
	private int stackedTiles;
	private int moving;
	private int skippedIds;

	/** Sidebar rate bookkeeping. */
	private long lastPanelNanos;
	private SpreadingDrawCallbacks countedWrapper;
	private long lastPlayerDraws;
	private long lastNudgedDraws;
	private long lastRevealedDraws;
	private long lastSceneMismatches;
	private int noDrawsStreak;
	private int nothingMovedStreak;

	@Provides
	PersonalSpaceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PersonalSpaceConfig.class);
	}

	@Override
	protected void startUp()
	{
		probe = new StackProbe(client, offsets, stacks);
		renderCallbackManager.register(probe);

		PersonalSpacePanel newPanel = new PersonalSpacePanel(configManager, config);
		BufferedImage icon = ImageUtil.loadImageResource(PersonalSpacePlugin.class, "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Personal Space")
			.icon(icon)
			.priority(7)
			.panel(newPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel = newPanel;

		clientThread.invoke(() ->
		{
			lastFrameNanos = 0;
			lastPanelNanos = 0;
			warnedNoRenderer = false;
			warnedBadIds = false;
			countedWrapper = null;
			noDrawsStreak = 0;
			nothingMovedStreak = 0;
			resetTickState();
			probe.reset();
			// The shim itself is installed on the next frame, see ensureInstalled().
		});
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(probe);
		panel = null;
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		clientThread.invoke(() ->
		{
			uninstall();
			resetTickState();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PersonalSpaceConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		PersonalSpacePanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::refreshControls);
		}
		if (PersonalSpaceConfig.KEY_ACTIVE.equals(event.getKey()) && !config.active())
		{
			// Pausing takes effect immediately, not on the next game tick.
			clientThread.invoke(this::resetTickState);
		}
	}

	// ---- per frame ---------------------------------------------------------------------

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		ensureInstalled();

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		offsets.advance(Math.min(dt, 0.25f), config.movement(), config.walkSpeed() / 100f);

		if (lastPanelNanos == 0 || now - lastPanelNanos >= PANEL_REFRESH_NANOS)
		{
			pushSnapshot(now);
		}
	}

	/**
	 * Keep our shim in front of whichever renderer is active. Renderers install themselves with
	 * setDrawCallbacks when they start and set it to null when they stop, so this is re-checked
	 * every frame rather than once at start-up. Cheap: one getter and a reference compare.
	 * Swapping is instant, except that if it lands while the game is part-way through loading a
	 * new area, the game restarts that load once; so it is only ever done when actually needed.
	 */
	private void ensureInstalled()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null)
		{
			// No GPU renderer running; the software renderer never calls draw callbacks.
			wrapper = null;
			return;
		}
		if (current == wrapper)
		{
			return;
		}

		DrawCallbacks target = current;
		if (current instanceof SpreadingDrawCallbacks)
		{
			// Never stack shims (e.g. one left behind by an earlier instance of this plugin).
			target = ((SpreadingDrawCallbacks) current).getDelegate();
		}
		wrapper = new SpreadingDrawCallbacks(client, offsets, stacks, probe, renderCallbackManager, target);
		client.setDrawCallbacks(wrapper);
		log.debug("Hooked draw callbacks in front of {}", target.getClass().getName());
	}

	private void uninstall()
	{
		if (wrapper != null && client.getDrawCallbacks() == wrapper)
		{
			client.setDrawCallbacks(wrapper.getDelegate());
			log.debug("Restored draw callbacks to {}", wrapper.getDelegate().getClass().getName());
		}
		wrapper = null;
	}

	// ---- per game tick -----------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			resetTickState();
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			probe.reset();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tick++;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			resetTickState();
			return;
		}
		Player local = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (local == null || wv == null)
		{
			resetTickState();
			return;
		}

		Snapshot.Gate newGate = safetyGate(local);
		if (newGate != Snapshot.Gate.SAFE || !config.active())
		{
			// Hard off: straight back to real positions, no easing, no exceptions.
			resetTickState();
			gate = newGate;
			return;
		}
		gate = newGate;

		if (wrapper == null && !warnedNoRenderer && client.getDrawCallbacks() == null)
		{
			warnedNoRenderer = true;
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"<col=ff8c00>Personal Space</col>: turn on the GPU plugin (or 117 HD) for this plugin to have any effect.", null);
		}

		probe.enabled = config.mode() == PersonalSpaceConfig.Mode.SPREAD;
		if (config.mode() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME)
		{
			offsets.clearTargets();
			stacks.clear();
			offsets.setTarget(local.getId(), config.testOffset(), 0);
			nearby = countPlayers(wv);
			still = 0;
			stackedTiles = 0;
			moving = config.testOffset() > 0 ? 1 : 0;
			skippedIds = 0;
			return;
		}

		List<StackSpreader.Entry> entries = new ArrayList<>();
		List<StackSpreader.Placement> easingBack = new ArrayList<>();
		int nearbyCount = 0;
		int skipped = 0;
		for (Player p : wv.players())
		{
			if (p == null)
			{
				continue;
			}
			nearbyCount++;
			int id = p.getId();
			if (id < 0 || id >= OffsetTable.CAPACITY || idSeenTick[id] == tick)
			{
				// Out of range, or a second player with the same id this tick: offsets are keyed
				// by id, so such a player can't be moved safely. Should never happen.
				skipped++;
				if (!warnedBadIds)
				{
					warnedBadIds = true;
					log.warn("Skipping player with unusable id {}", id);
				}
				continue;
			}
			idSeenTick[id] = tick;

			LocalPoint lp = p.getLocalLocation();
			WorldPoint wp = p.getWorldLocation();
			if (lp == null || wp == null)
			{
				continue;
			}
			long tileKey = StackRegistry.key(wp.getPlane(), lp.getSceneX(), lp.getSceneY());
			boolean centred = StillnessTracker.isCentred(lp.getX(), lp.getY());
			boolean standingStill = stillness.observe(id, tileKey, centred, tick);
			if (!standingStill || p.isDead())
			{
				if (centred && offsets.isOffset(id))
				{
					// Dropped out of a ring but still on the tile: keep drawing them while they
					// ease back to the middle, instead of vanishing on the spot.
					easingBack.add(new StackSpreader.Placement(id, tileKey, 0, 0));
				}
				continue;
			}
			entries.add(new StackSpreader.Entry(id, tileKey, p == local, p.getCurrentOrientation()));
		}

		int spacing = config.spacing();
		java.util.Set<Long> newRowTiles = new java.util.HashSet<>();
		List<StackSpreader.Placement> placements = StackSpreader.place(
			entries, config.includeLocalPlayer(), config.maxStack(), spacing, layoutFor(config.arrangement()),
			rowTiles, newRowTiles);
		rowTiles = newRowTiles;
		if (config.arrangement() == PersonalSpaceConfig.Arrangement.AUTO && !placements.isEmpty())
		{
			placements = CrowdLayout.settle(placements, obstacles(entries, placements), terrain(wv), spacing);
		}
		placements = StackSpreader.keepCurrentSpots(placements, new StackSpreader.Targets()
		{
			@Override
			public boolean has(int id)
			{
				return offsets.hasTarget(id);
			}

			@Override
			public int dx(int id)
			{
				return offsets.targetX(id);
			}

			@Override
			public int dz(int id)
			{
				return offsets.targetZ(id);
			}
		}, Math.max(14, spacing / 4));
		offsets.clearTargets();
		for (StackSpreader.Placement pl : placements)
		{
			offsets.setTarget(pl.id, pl.dx, pl.dz);
		}
		List<StackSpreader.Placement> revealable = new ArrayList<>(placements);
		for (StackSpreader.Placement pl : easingBack)
		{
			revealable.add(pl);
		}
		stacks.rebuild(revealable);
		probe.forgetTilesNotIn(stacks);

		nearby = nearbyCount;
		still = entries.size();
		stackedTiles = StackSpreader.stackedTiles(entries);
		moving = placements.size();
		skippedIds = skipped;
	}

	/** Why the effect must be off right now, or SAFE. */
	private Snapshot.Gate safetyGate(Player local)
	{
		if (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
		{
			return Snapshot.Gate.WILDERNESS;
		}
		if (client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1)
		{
			return Snapshot.Gate.PVP_AREA;
		}
		EnumSet<WorldType> types = client.getWorldType();
		if (WorldType.isPvpWorld(types)
			|| types.contains(WorldType.PVP_ARENA)
			|| types.contains(WorldType.DEADMAN)
			|| types.contains(WorldType.TOURNAMENT_WORLD)
			|| types.contains(WorldType.BOUNTY))
		{
			return Snapshot.Gate.PVP_WORLD;
		}
		if (local.getHealthRatio() != -1)
		{
			return Snapshot.Gate.IN_COMBAT; // our own health bar is showing
		}
		return Snapshot.Gate.SAFE;
	}

	/** Everyone back to their real spot immediately and forget what the last tick found. Client thread. */
	private void resetTickState()
	{
		if (probe != null)
		{
			probe.enabled = false;
		}
		offsets.snapAllToZero();
		stacks.clear();
		rowTiles.clear();
		stillness.clear();
		gate = client.getGameState() == GameState.LOGGED_IN ? gate : Snapshot.Gate.NOT_LOGGED_IN;
		nearby = 0;
		still = 0;
		stackedTiles = 0;
		moving = 0;
		skippedIds = 0;
	}

	private static StackSpreader.Layout layoutFor(PersonalSpaceConfig.Arrangement arrangement)
	{
		return arrangement == PersonalSpaceConfig.Arrangement.CIRCLE ? StackSpreader.Layout.RING : StackSpreader.Layout.AUTO;
	}

	/** Still players who aren't being moved (you, when you stay put, and anyone on a tile by themselves). */
	private static List<CrowdLayout.Obstacle> obstacles(List<StackSpreader.Entry> entries, List<StackSpreader.Placement> placements)
	{
		java.util.Set<Integer> moving = new java.util.HashSet<>();
		for (StackSpreader.Placement p : placements)
		{
			moving.add(p.id);
		}
		List<CrowdLayout.Obstacle> out = new ArrayList<>();
		for (StackSpreader.Entry e : entries)
		{
			if (!moving.contains(e.id))
			{
				out.add(new CrowdLayout.Obstacle(StackRegistry.plane(e.tile),
					StackRegistry.sceneX(e.tile) * 128 + 64, StackRegistry.sceneY(e.tile) * 128 + 64));
			}
		}
		return out;
	}

	/** The game's walkability map for the current area, so nobody is drawn inside a booth or wall. */
	private static CrowdLayout.Terrain terrain(WorldView wv)
	{
		CollisionData[] maps = wv.getCollisionMaps();
		int[][][] flags = new int[4][][];
		if (maps != null)
		{
			for (int plane = 0; plane < Math.min(4, maps.length); plane++)
			{
				flags[plane] = maps[plane] == null ? null : maps[plane].getFlags();
			}
		}
		return new CollisionTerrain(flags);
	}

	private static int countPlayers(WorldView wv)
	{
		int n = 0;
		for (Player p : wv.players())
		{
			if (p != null)
			{
				n++;
			}
		}
		return n;
	}

	// ---- sidebar -----------------------------------------------------------------------

	/** Client thread. Capture the current state and hand it to the sidebar. */
	private void pushSnapshot(long now)
	{
		PersonalSpacePanel p = panel;
		if (p == null)
		{
			return;
		}

		Snapshot s = new Snapshot();
		s.active = config.active();
		s.arrangement = config.arrangement();
		s.mode = config.mode();
		s.spacing = config.spacing();
		s.movement = config.movement();
		s.walkSpeed = config.walkSpeed();
		s.maxStack = config.maxStack();
		s.includeLocal = config.includeLocalPlayer();
		s.testOffset = config.testOffset();

		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		s.gate = loggedIn ? gate : Snapshot.Gate.NOT_LOGGED_IN;

		DrawCallbacks current = client.getDrawCallbacks();
		SpreadingDrawCallbacks w = wrapper;
		s.hooked = w != null && current == w;
		s.renderer = current == null ? null : rendererName(s.hooked ? w.getDelegate() : current);

		double seconds = lastPanelNanos == 0 ? 0 : (now - lastPanelNanos) / 1_000_000_000.0;
		boolean ratesKnown = w != null && w == countedWrapper && seconds > 0;
		if (ratesKnown)
		{
			s.playerDrawsPerSec = perSecond(w.playerDraws - lastPlayerDraws, seconds);
			s.nudgedDrawsPerSec = perSecond(w.nudgedDraws - lastNudgedDraws, seconds);
			s.revealedDrawsPerSec = perSecond(w.revealedDraws - lastRevealedDraws, seconds);
			s.sceneMismatchesPerSec = perSecond(w.sceneMismatches - lastSceneMismatches, seconds);
		}
		if (w != null)
		{
			s.playersInOtherCalls = w.playersInOtherCalls;
			s.offThreadDraws = w.offThreadDraws;
			s.revealErrors = w.revealErrors;
			s.walkDraws = w.walkDraws;
		}
		countedWrapper = w;
		if (w != null)
		{
			lastPlayerDraws = w.playerDraws;
			lastNudgedDraws = w.nudgedDraws;
			lastRevealedDraws = w.revealedDraws;
			lastSceneMismatches = w.sceneMismatches;
		}
		lastPanelNanos = now;

		StackProbe pr = probe;
		if (pr != null)
		{
			s.probeHeld = pr.heldTotal;
			s.probeGaveUp = pr.gaveUpTotal;
		}
		s.nearby = nearby;
		s.still = still;
		s.stackedTiles = stackedTiles;
		s.moving = moving;
		s.skippedIds = skippedIds;

		boolean effectShouldRun = s.active && loggedIn && s.gate == Snapshot.Gate.SAFE && s.hooked && ratesKnown;
		noDrawsStreak = effectShouldRun && s.playerDrawsPerSec == 0 ? noDrawsStreak + 1 : 0;
		nothingMovedStreak = effectShouldRun && s.moving > 0 && s.nudgedDrawsPerSec + s.revealedDrawsPerSec == 0 ? nothingMovedStreak + 1 : 0;
		s.noPlayerDrawsSustained = noDrawsStreak >= SUSTAINED_REFRESHES;
		s.nothingMovedSustained = nothingMovedStreak >= SUSTAINED_REFRESHES;

		SwingUtilities.invokeLater(() -> p.update(s));
	}

	private static int perSecond(long count, double seconds)
	{
		return count <= 0 ? 0 : (int) Math.round(count / seconds);
	}

	private static String rendererName(DrawCallbacks callbacks)
	{
		String name = callbacks.getClass().getSimpleName();
		if (name.equals("GpuPlugin"))
		{
			return "GPU plugin";
		}
		if (name.toLowerCase().contains("hd"))
		{
			return "117 HD";
		}
		return name;
	}
}
