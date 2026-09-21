package com.grant9008.personalspace;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
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
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Personal Space",
	description = "See the whole community: the one player you see on a tile is often a whole group, and this shows everyone standing there. Cosmetic only, and off wherever players can fight.",
	tags = {"stacked", "stacking", "unstack", "overlapping", "clipping", "crowded", "fashionscape", "fashion show", "outfits",
		"drip", "cosmetics", "gear", "other players", "show all players", "reveal", "grand exchange", "social", "hangout",
		"lively", "roleplay", "house party", "drop party", "clan events", "group photo", "screenshots", "content creator",
		"streamers", "shooting stars"}
)
public class PersonalSpacePlugin extends Plugin
{
	static final String VERSION = "1.8.34";

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
	private OverlayManager overlayManager;

	@Inject
	private PartyService partyService;

	@Inject
	private ChatIconManager chatIconManager;

	private NamesOverlay namesOverlay;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private final OffsetTable offsets = new OffsetTable();
	private final StackRegistry stacks = new StackRegistry();
	/** Who has which spot on each crowded tile, so crowds don't reshuffle. */
	/** Decides each tick which crowded tiles are spread and where everyone stands. */
	private final CrowdPlanner planner = new CrowdPlanner();
	private StackProbe probe;
	private final StillnessTracker stillness = new StillnessTracker();
	/** Tick number each player id was last counted on, to catch two players sharing an id. */
	private final int[] idSeenTick = new int[OffsetTable.CAPACITY];
	/**
	 * The tick each player last had an animation playing (alching, smithing, an emote). Anyone busy
	 * in the last {@link #BUSY_TICKS} gives up their spot first when a tile has too few.
	 */
	private final int[] busyTick = new int[OffsetTable.CAPACITY];
	private static final int BUSY_TICKS = 10;
	/** Tick on which each player was last found to be shown by the game. */
	private final int[] shownTick = new int[OffsetTable.CAPACITY];

	/** Our shim, while it is the client's installed draw callback. Null when nothing is hooked. Client thread. */
	private SpreadingDrawCallbacks wrapper;

	private volatile PersonalSpacePanel panel;
	private NavigationButton navButton;

	// ---- client-thread state -----------------------------------------------------------

	private long lastFrameNanos;
	private boolean warnedNoRenderer;
	private boolean warnedBadIds;
	private int tick;
	private final CombatWatch combat = new CombatWatch();

	/** What the last game tick found, for the sidebar. */
	private Snapshot.Gate gate = Snapshot.Gate.NOT_LOGGED_IN;
	private int nearby;
	/** Of those, how many are on a boat. */
	private int aboard;
	private int still;
	private int stackedTiles;
	private int moving;
	private int skippedIds;
	private int unseen;
	private String nearestTile;
	private String yourShape;

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
		namesOverlay = new NamesOverlay(client, config, configManager, offsets, partyService, chatIconManager);
		overlayManager.add(namesOverlay);

		PersonalSpacePanel newPanel = new PersonalSpacePanel(configManager, config);
		BufferedImage icon = ImageUtil.loadImageResource(PersonalSpacePlugin.class, "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Personal Space")
			.icon(icon)
			.priority(7)
			.panel(newPanel)
			.build();
		if (config.showSidebarButton())
		{
			clientToolbar.addNavigation(navButton);
		}
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
		if (namesOverlay != null)
		{
			overlayManager.remove(namesOverlay);
			namesOverlay = null;
		}
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
		NavigationButton button = navButton;
		if (PersonalSpaceConfig.KEY_SHOW_SIDEBAR.equals(event.getKey()) && button != null)
		{
			// For anyone who likes a tidy sidebar; the settings stay in the plugin's config.
			boolean show = config.showSidebarButton();
			SwingUtilities.invokeLater(() ->
			{
				if (navButton != button)
				{
					return; // switched off meanwhile
				}
				if (show)
				{
					clientToolbar.addNavigation(button);
				}
				else
				{
					clientToolbar.removeNavigation(button);
				}
			});
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
		if (wrapper != null)
		{
			wrapper.drawMeInFront = config.drawMeInFront();
		}

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		offsets.advance(Math.min(dt, 0.25f));

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
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			// There is nothing to spread until you are in the world, and a renderer is at its most
			// delicate while it is starting up or shutting down: it creates and destroys its native
			// drawing context around then, and anything standing in front of it at that moment can be
			// handed calls it isn't ready for. So we stay out of the way until the world is up.
			uninstall();
			return;
		}

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
			aboard = 0;
			stackedTiles = 0;
			moving = config.testOffset() > 0 ? 1 : 0;
			skippedIds = 0;
			unseen = 0;
			nearestTile = null;
			yourShape = null;
			return;
		}

		List<StackSpreader.Entry> entries = new ArrayList<>();
		List<StackSpreader.Placement> easingBack = new ArrayList<>();
		int nearbyCount = 0;
		int skipped = 0;
		int cycle = client.getGameCycle();
		int onBoats = 0;
		// The main world, then every boat in view: each is a world of its own, with its own deck.
		List<WorldView> worlds = new ArrayList<>();
		worlds.add(wv);
		for (WorldView boat : wv.worldViews())
		{
			if (boat != null)
			{
				worlds.add(boat);
			}
		}
		for (WorldView world : worlds)
		{
		for (Player p : world.players())
		{
			if (p == null)
			{
				continue;
			}
			nearbyCount++;
			if (world != wv)
			{
				onBoats++;
			}
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
			long tileKey = StackRegistry.key(StackRegistry.layer(world.getId(), wp.getPlane()), lp.getSceneX(), lp.getSceneY());
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
			if (probe.isShown(id, p, cycle) && probe.othersAllow(renderCallbackManager, p))
			{
				shownTick[id] = tick;
			}
			if (p.getAnimation() != -1)
			{
				busyTick[id] = tick;
			}
			boolean busy = busyTick[id] != 0 && tick - busyTick[id] <= BUSY_TICKS;
			entries.add(new StackSpreader.Entry(id, tileKey, p == local, p.getCurrentOrientation(), busy));
		}
		}

		planner.smallGroupsClose = config.smallGroupsClose();
		planner.pose = config.pose();
		planner.cameraX = client.getCameraX();
		planner.cameraY = client.getCameraY();
		if (local.getWorldView() != null && local.getWorldView().getId() != WorldView.TOPLEVEL)
		{
			// Aboard a boat: the deck has coordinates of its own and the camera's are the world's,
			// so which way the camera is from you can't be told. Nobody steps out of your view.
			planner.cameraX = Integer.MIN_VALUE;
			planner.cameraY = Integer.MIN_VALUE;
		}
		planner.arrangement = config.arrangement();
		CrowdPlanner.Plan plan = planner.plan(entries, id -> shownTick[id] == tick, config.spacing(), config.maxStack(),
			config.arrangement() != PersonalSpaceConfig.Arrangement.CIRCLE, config.includeLocalPlayer(), tick, surroundings(wv));
		offsets.clearTargets();
		for (StackSpreader.Placement pl : plan.placements)
		{
			offsets.setTarget(pl.id, pl.dx, pl.dz);
		}
		// Everyone on a crowded tile goes in the table, not just those given a spot: the probe needs
		// them there to find out whether the game is willing to show them.
		List<StackSpreader.Placement> members = new ArrayList<>(plan.placements);
		members.addAll(plan.unplaced);
		members.addAll(easingBack);
		java.util.Set<Integer> unplacedIds = new java.util.HashSet<>();
		for (StackSpreader.Placement pl : plan.unplaced)
		{
			unplacedIds.add(pl.id);
		}
		// Curved rows and counter rows alike: everyone turns towards what they are facing.
		Set<Long> turnIn = new HashSet<>(plan.curvedRows);
		turnIn.addAll(plan.facingIn);
		stacks.rebuild(members, turnIn, unplacedIds, plan.fires, plan.poses, plan.middleOutOfSight);
		probe.forgetTilesNotIn(stacks);

		nearby = nearbyCount;
		aboard = onBoats;
		still = entries.size();
		stackedTiles = StackSpreader.stackedTiles(entries);
		moving = plan.placements.size();
		skippedIds = skipped;
		unseen = plan.unseen;
		nearestTile = nearestTileReport(plan, local);
		yourShape = yourShapeReport(plan, local, config.spacing(), config.smallGroupsClose());
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
		if (playersCanFight())
		{
			return Snapshot.Gate.PVP_ACTIVITY;
		}
		Actor target = local.getInteracting();
		boolean attacking = target instanceof NPC || target instanceof Player;
		boolean fighting = combat.update(local.getHealthRatio() != -1, attacking, attacking && target.getHealthRatio() != -1, tick);
		if (fighting && config.pauseInCombat())
		{
			return Snapshot.Gate.IN_COMBAT;
		}
		return Snapshot.Gate.SAFE;
	}

	/**
	 * True when the game offers to attack other players, as in Castle Wars, Soul Wars, Last Man
	 * Standing, Clan Wars or the Fight Pits, even on a normal world outside the Wilderness.
	 */
	private boolean playersCanFight()
	{
		String[] options = client.getPlayerOptions();
		if (options == null)
		{
			return false;
		}
		for (String option : options)
		{
			if (option == null)
			{
				continue;
			}
			String plain = Text.removeTags(option).trim();
			if (plain.equalsIgnoreCase("Attack") || plain.equalsIgnoreCase("Fight"))
			{
				return true;
			}
		}
		return false;
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
		planner.clear();
		stillness.clear();
		gate = client.getGameState() == GameState.LOGGED_IN ? gate : Snapshot.Gate.NOT_LOGGED_IN;
		nearby = 0;
		still = 0;
		aboard = 0;
		stackedTiles = 0;
		moving = 0;
		skippedIds = 0;
		unseen = 0;
		nearestTile = null;
		yourShape = null;
	}

	/**
	 * Client thread. The world around crowded tiles: the main world's, or a boat's for tiles on
	 * its deck. A collision map is only read if a tile on it needs it.
	 */
	private CrowdPlanner.Surroundings surroundings(WorldView wv)
	{
		return new CrowdPlanner.Surroundings()
		{
			private final Map<Integer, CollisionTerrain> collision = new HashMap<>();

			private WorldView world(long tile)
			{
				int id = StackRegistry.worldViewOf(StackRegistry.plane(tile));
				return id == WorldView.TOPLEVEL ? wv : client.getWorldView(id);
			}

			private CollisionTerrain collision(long tile)
			{
				return collision.computeIfAbsent(StackRegistry.worldViewOf(StackRegistry.plane(tile)), id ->
				{
					WorldView world = world(tile);
					return world == null ? new CollisionTerrain(new int[4][][]) : terrain(world);
				});
			}

			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + 64;
				int z = StackRegistry.sceneY(tile) * 128 + 64;
				return collision(tile).canStand(StackRegistry.planeOf(StackRegistry.plane(tile)), x, z, x + dx, z + dz);
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return collision(tile).facesObstacle(StackRegistry.planeOf(StackRegistry.plane(tile)), StackRegistry.sceneX(tile), StackRegistry.sceneY(tile), angle);
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return collision(tile).isCounter(StackRegistry.planeOf(StackRegistry.plane(tile)), StackRegistry.sceneX(tile), StackRegistry.sceneY(tile), angle);
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				List<int[]> out = new ArrayList<>(1);
				WorldView world = world(tile);
				if (world == null)
				{
					return out;
				}
				for (int east = -1; east <= 1; east++)
				{
					for (int north = -1; north <= 1; north++)
					{
						if (hasFire(world, StackRegistry.planeOf(StackRegistry.plane(tile)), StackRegistry.sceneX(tile) + east, StackRegistry.sceneY(tile) + north))
						{
							out.add(new int[]{east, north});
						}
					}
				}
				return out;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				WorldView world = world(tile);
				return world != null && PersonalSpacePlugin.this.facesFire(world, StackRegistry.planeOf(StackRegistry.plane(tile)), StackRegistry.sceneX(tile), StackRegistry.sceneY(tile), angle);
			}
		};
	}

	/**
	 * Plain words for the shape you are standing in, for the line under the sidebar's title, or null
	 * when your own tile isn't being spread. It also says why people are close when something other
	 * than the slider decided that, which is the question the sidebar can't otherwise answer.
	 */
	private static String yourShapeReport(CrowdPlanner.Plan plan, Player local, int slider, boolean autoSpace)
	{
		LocalPoint lp = local.getLocalLocation();
		WorldPoint wp = local.getWorldLocation();
		if (lp == null || wp == null)
		{
			return null;
		}
		int layer = StackRegistry.layer(local.getWorldView() == null ? WorldView.TOPLEVEL : local.getWorldView().getId(), wp.getPlane());
		CrowdPlanner.TileReport yours = plan.tiles.get(StackRegistry.key(layer, lp.getSceneX(), lp.getSceneY()));
		if (yours == null)
		{
			return null;
		}
		String shape = yours.shape;
		String what;
		if (shape.startsWith("counter row"))
		{
			what = "a row along the counter or wall";
		}
		else if (shape.startsWith("line"))
		{
			what = "a line";
		}
		else if (shape.contains("fire"))
		{
			what = "a ring round the fire";
		}
		else if (shape.startsWith("curved row"))
		{
			what = "a curve round what you're facing";
		}
		else
		{
			what = "a ring";
		}
		String why = "";
		if (shape.contains("squeezed"))
		{
			why = ", squeezed for room";
		}
		else if (autoSpace && yours.spacing < slider)
		{
			why = ", kept close by Auto-space";
		}
		return "You're in " + what + why;
	}

	/** What was decided for the spread tile nearest to you, for the report; null if none is near. */
	private static String nearestTileReport(CrowdPlanner.Plan plan, Player local)
	{
		LocalPoint lp = local.getLocalLocation();
		WorldPoint wp = local.getWorldLocation();
		if (lp == null || wp == null)
		{
			return null;
		}
		int layer = StackRegistry.layer(local.getWorldView() == null ? WorldView.TOPLEVEL : local.getWorldView().getId(), wp.getPlane());
		Long nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (long tile : plan.tiles.keySet())
		{
			if (StackRegistry.plane(tile) != layer)
			{
				continue;
			}
			int distance = Math.max(Math.abs(StackRegistry.sceneX(tile) - lp.getSceneX()),
				Math.abs(StackRegistry.sceneY(tile) - lp.getSceneY()));
			if (distance < nearestDistance || (distance == nearestDistance && tile < nearest))
			{
				nearest = tile;
				nearestDistance = distance;
			}
		}
		return nearest == null ? null : plan.tiles.get(nearest) + ", " + nearestDistance + " tiles from you";
	}

	/** Client thread. True if the tile a row is facing has a fire on it. */
	private boolean facesFire(WorldView wv, int plane, int sceneX, int sceneY, double angle)
	{
		return hasFire(wv, plane, sceneX + (int) Math.round(-Math.sin(angle)), sceneY + (int) Math.round(-Math.cos(angle)));
	}

	/** Client thread. True if this scene tile has a fire on it. */
	private boolean hasFire(WorldView wv, int plane, int x, int y)
	{
		Scene scene = wv.getScene();
		if (scene == null)
		{
			return false;
		}
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length || x < 0 || x >= tiles[plane].length
			|| y < 0 || y >= tiles[plane][x].length || tiles[plane][x][y] == null)
		{
			return false;
		}
		for (GameObject object : tiles[plane][x][y].getGameObjects())
		{
			if (object == null)
			{
				continue;
			}
			ObjectComposition def = client.getObjectDefinition(object.getId());
			if (def != null && def.getImpostorIds() != null)
			{
				def = def.getImpostor();
			}
			if (def != null && StackSpreader.isFireName(def.getName()))
			{
				return true;
			}
		}
		return false;
	}

	/** The game's walkability map for the current area, so nobody is drawn inside a booth or wall. */
	private static CollisionTerrain terrain(WorldView wv)
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
		s.maxStack = config.maxStack();
		s.includeLocal = config.includeLocalPlayer();
		s.drawMeInFront = config.drawMeInFront();
		s.names = config.namesFollowPlayers();
		s.smallGroupsClose = config.smallGroupsClose();
		s.pauseInCombat = config.pauseInCombat();
		s.pose = config.pose();
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
			s.orderedDraws = w.orderedDraws;
			s.passMissedFrames = w.passMissedFrames;
			s.hiddenInYourWay = w.hiddenInYourWay;
			s.walkSkippedBusy = w.walkSkippedBusy;
			s.walkSkippedNoAnimation = w.walkSkippedNoAnimation;
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
		s.shapeChanges = planner.shapeChanges();
		s.spotMoves = planner.spotMoves();
		s.unseenStacked = unseen;
		s.nearestTile = nearestTile;
		s.yourShape = yourShape;
		s.nearby = nearby;
		s.aboard = aboard;
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
