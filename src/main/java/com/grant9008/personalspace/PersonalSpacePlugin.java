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
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.AnimationID;
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
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Personal Space",
	description = "See the whole community: the one player you see on a tile is often a whole group, and this shows everyone standing there. Visual only, and off wherever players can fight.",
	tags = {"stacked", "stacking", "unstack", "overlapping", "clipping", "crowded", "fashionscape", "fashion show", "outfits",
		"drip", "cosmetics", "gear", "other players", "show all players", "reveal", "grand exchange", "social", "hangout",
		"lively", "roleplay", "house party", "drop party", "clan events", "group photo", "screenshots", "content creator",
		"streamers", "shooting stars"}
)
public class PersonalSpacePlugin extends Plugin
{
	static final String VERSION = "1.9.27";

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

	@Inject
	private TooltipManager tooltipManager;

	@Inject
	private PluginManager pluginManager;

	private HoverOverlay hoverOverlay;

	private LevelFilter levelFilter;

	private ChatHider chatHider;
	private AlchStyler alchStyler;
	private IdleSitter idleSitter;
	private ChatRangeOverlay chatRangeOverlay;

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
	/**
	 * Someone given a spot while in the middle of an action waits in the middle of their tile for
	 * a break in it, then walks out: moved mid-action, the action is cut off for the step (they
	 * stand, walk, and drop straight back into it). An alcher pauses between casts within a second
	 * or two; anyone who never pauses moves after this many ticks anyway: 3 seconds. (Six, from
	 * before busy people could be shown walking, kept them waiting in the middle far too long.)
	 */
	private static final int BUSY_WAIT_TICKS = 5;
	/** ...and someone already in a spot, moved because the crowd round them changed, this many. */
	private static final int BUSY_MOVE_WAIT_TICKS = 3;
	private final int[] busyWaitSince = new int[OffsetTable.CAPACITY];
	/** The action each player was last seen busy with. */
	private final int[] busyAnimation = new int[OffsetTable.CAPACITY];
	/**
	 * For each action waited on from the middle of a tile: how many waits ended in a break in it,
	 * how many ran out, and how many times since it wasn't waited on. One that has run out twice
	 * and more often than it paused isn't waited on any more (they'd only wait in the middle, out
	 * of sight, then glide out all the same), but every eighth time it is again, in case it pauses
	 * now: only where nobody else holds the middle, so they're seen waiting there rather than
	 * hidden. An alcher pauses between casts and is still waited on.
	 */
	private final Map<Integer, int[]> waitOutcomes = new HashMap<>();
	/** Who went undrawn longest lately, for the report. Here rather than in the shim, which a map load replaces. */
	private final HideLog hideLog = new HideLog();
	private static final int RETRY_WAIT_EVERY = 8;
	/** Diagnostics: busy waits that ended in a break, ran out, or weren't started because the action never pauses. */
	private long waitBreaks;
	private long waitTimeouts;
	private long waitSkipped;
	/** Tick on which each player was last found to be shown by the game. */
	private final int[] shownTick = new int[OffsetTable.CAPACITY];

	/**
	 * Tending a Forester's Campfire, with any kind of log: it never pauses, so it's never waited on.
	 * A tender waited on was hidden in the middle of their tile for six seconds whenever someone
	 * joined it.
	 */
	static boolean neverPauses(int animation)
	{
		return animation >= AnimationID.FORESTRY_CAMPFIRE_BURNING_GENERIC && animation <= AnimationID.FORESTRY_CAMPFIRE_BURNING_YEW_LOGS
			|| animation >= AnimationID.FORESTRY_CAMPFIRE_BURNING_JATOBA_LOGS && animation <= AnimationID.FORESTRY_CAMPFIRE_BURNING_ROSEWOOD_LOGS
			|| animation >= AnimationID.FORESTRY_CAMPFIRE_BURNING_GENERIC_NOLOOP && animation <= AnimationID.FORESTRY_CAMPFIRE_BURNING_ROSEWOOD_LOGS_NOLOOP;
	}

	/**
	 * Whether to wait for a break in this action now. {@code mayRetry}: they'd wait in a middle
	 * nobody else holds, so could be waited on again to see if it pauses now. Not side-effect free:
	 * counts the retries due.
	 */
	private boolean worthWaiting(int animation, boolean mayRetry)
	{
		if (neverPauses(animation))
		{
			return false;
		}
		int[] outcomes = waitOutcomes.get(animation);
		if (outcomes == null || outcomes[1] < 2 || outcomes[0] > outcomes[1])
		{
			return true;
		}
		return mayRetry && ++outcomes[2] % RETRY_WAIT_EVERY == 0;
	}

	private void noteWaitOutcome(int animation, boolean paused)
	{
		if (paused)
		{
			waitBreaks++;
		}
		else
		{
			waitTimeouts++;
		}
		if (waitOutcomes.size() > 256 && !waitOutcomes.containsKey(animation))
		{
			return;
		}
		int[] outcomes = waitOutcomes.computeIfAbsent(animation, a -> new int[3]);
		outcomes[paused ? 0 : 1]++;
		if (outcomes[0] + outcomes[1] >= 40)
		{
			// Recent waits count most: halve the old ones.
			outcomes[0] /= 2;
			outcomes[1] /= 2;
		}
	}

	/** Our shim, while it is the client's installed draw callback. Null when nothing is hooked. Client thread. */
	private SpreadingDrawCallbacks wrapper;
	/**
	 * Set if the game ever reported an animation change while a busy player was being shown walking:
	 * busy players then glide again, in every shim, until the plugin restarts. Client thread.
	 */
	private boolean busyWalksOff;

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
	/** Diagnostics: times spreading was switched off mid-way, ticks where most of the crowd lost its spot, and shims installed. */
	private long resets;
	private long planDips;
	private long shimInstalls;
	/**
	 * Diagnostics: people who had a spot last tick and, still on the same crowded tile, have none
	 * now, so they went back to being hidden in its middle.
	 */
	private long spotLosses;
	private final long[] placedTile = new long[OffsetTable.CAPACITY];
	private final int[] placedTick = new int[OffsetTable.CAPACITY];
	private int nothingMovedStreak;

	@Provides
	PersonalSpaceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PersonalSpaceConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (config.mode() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME)
		{
			// Test mode has no switch any more: nobody is left stuck in it.
			configManager.setConfiguration(PersonalSpaceConfig.GROUP, PersonalSpaceConfig.KEY_MODE, PersonalSpaceConfig.Mode.SPREAD);
		}
		probe = new StackProbe(client, offsets, stacks);
		renderCallbackManager.register(probe);
		levelFilter = new LevelFilter(client);
		renderCallbackManager.register(levelFilter);
		chatHider = new ChatHider(client, levelFilter);
		alchStyler = new AlchStyler(client);
		idleSitter = new IdleSitter(client);
		alchStyler.sitter = idleSitter;
		namesOverlay = new NamesOverlay(client, config, configManager, offsets, partyService, chatIconManager, levelFilter);
		overlayManager.add(namesOverlay);
		hoverOverlay = new HoverOverlay(client, config, offsets, stacks, tooltipManager, levelFilter);
		overlayManager.add(hoverOverlay);
		chatRangeOverlay = new ChatRangeOverlay(client);
		overlayManager.add(chatRangeOverlay);

		PersonalSpacePanel newPanel = new PersonalSpacePanel(configManager, config);
		newPanel.onTurnOnGpu(this::turnOnGpu);
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
		if (levelFilter != null)
		{
			renderCallbackManager.unregister(levelFilter);
			levelFilter = null;
		}
		chatHider = null;
		alchStyler = null;
		IdleSitter sitter = idleSitter;
		idleSitter = null;
		clientThread.invoke(() ->
		{
			if (sitter != null)
			{
				sitter.standAll();
			}
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.refreshChat();
			}
		});
		if (namesOverlay != null)
		{
			overlayManager.remove(namesOverlay);
			namesOverlay = null;
		}
		if (hoverOverlay != null)
		{
			overlayManager.remove(hoverOverlay);
			hoverOverlay = null;
		}
		if (chatRangeOverlay != null)
		{
			overlayManager.remove(chatRangeOverlay);
			chatRangeOverlay = null;
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

	/** As each chat line is built: drop it if it's from someone the level filter hides, or said too far away. */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		ChatHider hider = chatHider;
		int range = config.hideChatFurtherThan();
		if (hider != null && "chatFilterCheck".equals(event.getEventName()) && (config.hideTheirChat() || range > 0))
		{
			hider.check(config.hideTheirChat(), range);
		}
	}

	/** As each chat line arrives: note how far away its speaker is (Hide chat further than). */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatHider hider = chatHider;
		if (hider != null && event.getMessageNode() != null)
		{
			hider.heard(event.getType(), event.getMessageNode().getId(), event.getName());
		}
	}

	/** Someone further away than "Hide chat further than" says nothing over their head either. */
	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		// Someone talking isn't away from the keyboard: they don't sit (Idle players sit).
		IdleSitter sitter = idleSitter;
		if (sitter != null && event.getActor() instanceof Player && event.getActor() != client.getLocalPlayer())
		{
			sitter.busy((Player) event.getActor(), client.getGameCycle());
		}
		ChatHider hider = chatHider;
		int range = config.hideChatFurtherThan();
		if (hider != null && range > 0 && event.getActor() instanceof Player
			&& hider.overheadTooFar((Player) event.getActor(), range))
		{
			// As RuneLite's Chat Filter does: a blank, not nothing.
			event.getActor().setOverheadText(" ");
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PersonalSpaceConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		ChatRangeOverlay range = chatRangeOverlay;
		if (range != null && PersonalSpaceConfig.KEY_CHAT_RANGE.equals(event.getKey()))
		{
			// Show how far that is, on the ground round you, while the slider moves.
			range.show(config.hideChatFurtherThan());
		}
		if (PersonalSpaceConfig.KEY_HIDE_CHAT.equals(event.getKey()) || PersonalSpaceConfig.KEY_HIDE_BELOW.equals(event.getKey())
			|| PersonalSpaceConfig.KEY_CHAT_RANGE.equals(event.getKey()))
		{
			// Rebuild the chat box, so lines appear or go as the setting changes. The new level
			// goes in first (the tick would only set it later), and who was remembered as hidden
			// is forgotten, or lowering the level would keep hiding them.
			boolean newLevel = PersonalSpaceConfig.KEY_HIDE_BELOW.equals(event.getKey());
			clientThread.invoke(() ->
			{
				LevelFilter filter = levelFilter;
				ChatHider hider = chatHider;
				if (newLevel && filter != null && hider != null)
				{
					filter.below = config.hideBelowLevel();
					hider.clear();
				}
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					client.refreshChat();
				}
			});
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
		AlchStyler styler = alchStyler;
		LevelFilter filter = levelFilter;
		if (styler != null)
		{
			// Before the scene is drawn: an alch cast never shows for a frame. Only while the
			// effect is on, and not in PvP areas, like the level filter.
			styler.apply(config.otherAlchers(), filter != null && filter.enabled);
		}
		ensureInstalled();
		if (wrapper != null)
		{
			wrapper.drawMeInFront = config.drawMeInFront();
			wrapper.depthBetweenPlayers = config.depthBetweenPlayers();
			wrapper.walkWhileBusy = config.walkWhileBusy();
		}

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		offsets.advance(Math.min(dt, 0.25f));
		keepOffWalls(Math.min(dt, 0.25f));

		if (lastPanelNanos == 0 || now - lastPanelNanos >= PANEL_REFRESH_NANOS)
		{
			pushSnapshot(now);
		}
	}

	/**
	 * Client thread, once a frame, after the offsets have moved: nobody whose offset is running out
	 * is drawn through a wall (see {@link OffsetTable#keepClear}). Only players drawn away from
	 * where they are get looked at, and the walls are only read when someone is.
	 */
	private void keepOffWalls(float dt)
	{
		WorldView top = offsets.anyActive() ? client.getTopLevelWorldView() : null;
		if (top == null)
		{
			return;
		}
		keepOffWalls(top, dt);
		for (WorldView boat : top.worldViews())
		{
			if (boat != null)
			{
				keepOffWalls(boat, dt);
			}
		}
	}

	private void keepOffWalls(WorldView wv, float dt)
	{
		CollisionTerrain terrain = null;
		for (Player p : wv.players())
		{
			int id = p == null ? -1 : p.getId();
			if (!offsets.isOffset(id))
			{
				continue;
			}
			LocalPoint lp = p.getLocalLocation();
			WorldPoint wp = p.getWorldLocation();
			if (lp == null || wp == null)
			{
				continue;
			}
			if (terrain == null)
			{
				terrain = terrain(wv);
			}
			boolean runningOut = offsets.targetIsMiddle(id) || !StillnessTracker.isCentred(lp.getX(), lp.getY());
			offsets.keepClear(id, lp.getX(), lp.getY(), SpreadingDrawCallbacks.reallyMoving(p), dt, runningOut, terrain.walls(wp.getPlane()));
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
		wrapper.hides = hideLog;
		wrapper.busyWalks = !busyWalksOff;
		client.setDrawCallbacks(wrapper);
		shimInstalls++;
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

	/**
	 * Players walking to a spot are drawn with their action swapped for the walk for a moment, which
	 * the client doesn't report to anyone. Should it ever, other plugins (an idle notifier, say) would
	 * hear of animation changes that never happened: action swaps are switched off at once.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		AlchStyler styler = alchStyler;
		if (styler != null)
		{
			styler.noticed(event.getActor());
		}
		SpreadingDrawCallbacks w = wrapper;
		if (w != null && w.fromBusySwap(event.getActor()))
		{
			busyWalksOff = true;
			w.busyWalks = false;
			log.warn("Animation change reported while showing a player walking; busy players glide again, and crowds may, until restart");
		}
	}

	/** An alch's spell effect is taken off before the next frame too (Alchers behavior). */
	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		AlchStyler styler = alchStyler;
		if (styler != null)
		{
			styler.noticed(event.getActor());
		}
	}

	/**
	 * Using an item or spell, someone drawn away from where they really stand takes the left-click
	 * from what you see there instead: you used raw fish on the campfire, which looked empty, and
	 * traded the people standing in it. Their option is put below the rest; right-click still has it.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.clickPastMoved())
		{
			return;
		}
		net.runelite.api.MenuEntry entry = event.getMenuEntry();
		net.runelite.api.MenuAction type = entry.getType();
		if (type != net.runelite.api.MenuAction.WIDGET_TARGET_ON_PLAYER)
		{
			return;
		}
		// Nor does anyone left undrawn, hidden in the middle of a crowd: the fire looked empty.
		Player player = entry.getPlayer();
		if (player != null && (movedAway(player.getId(), offsets.dx(player.getId()), offsets.dz(player.getId()))
			|| hideLog.notedLately(player.getId(), offsets.frame())))
		{
			entry.setDeprioritized(true);
		}
	}

	/** The walk animations walks were drawn with legs still, most first, each marked if skeletal. */
	private static String topAnimations(Map<Integer, Long> counts, Client client)
	{
		StringBuilder top = new StringBuilder();
		counts.entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.limit(4)
			.forEach(e ->
			{
				boolean skeletal = false;
				try
				{
					net.runelite.api.Animation a = client.loadAnimation(e.getKey());
					skeletal = a != null && a.isMayaAnim();
				}
				catch (RuntimeException ignored)
				{
					// left unmarked
				}
				top.append(top.length() == 0 ? "" : ", ").append(e.getKey()).append(skeletal ? " (skeletal)" : "").append(" x").append(e.getValue());
			});
		return top.toString();
	}

	/** Whether a player is drawn away from where they really stand. */
	static boolean movedAway(int id, int dx, int dz)
	{
		return id >= 0 && id < OffsetTable.CAPACITY && (dx != 0 || dz != 0);
	}

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
		levelFilter.below = config.hideBelowLevel();
		levelFilter.enabled = true;
		if (idleSitter != null)
		{
			idleSitter.update(config.idlePlayersSit());
		}

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
			offsets.setWalkingOff(id, !centred);
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
				busyAnimation[id] = p.getAnimation();
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
		for (StackSpreader.Placement pl : plan.unplaced)
		{
			if (pl.id >= 0 && pl.id < OffsetTable.CAPACITY && placedTick[pl.id] == tick - 1 && placedTile[pl.id] == pl.tile)
			{
				spotLosses++;
			}
		}
		// Busy people waiting in the middle of their tile for a break: drawn there like anyone else
		// waiting in the middle, so only one of them at a time, and nobody inside you. Drawn as well,
		// two people arriving together at a campfire stood inside each other until they moved.
		Set<Integer> waitingInTheMiddle = new HashSet<>();
		int localId = local.getId();
		// Middles someone else holds, or that aren't drawn: anyone waiting there is hidden.
		Set<Long> middleTaken = new HashSet<>(plan.middleOutOfSight);
		for (StackSpreader.Placement pl : plan.unplaced)
		{
			middleTaken.add(pl.tile);
		}
		for (StackSpreader.Entry e : entries)
		{
			if (e.local)
			{
				middleTaken.add(e.tile);
			}
		}
		for (StackSpreader.Placement pl : plan.placements)
		{
			if (pl.id >= 0 && pl.id < OffsetTable.CAPACITY && placedTick[pl.id] != tick - 1)
			{
				// Not placed last tick: whatever they were waiting for then is over. Left set, it ended
				// their next wait at once, or counted a break that never happened.
				busyWaitSince[pl.id] = 0;
			}
		}
		for (StackSpreader.Placement pl : plan.placements)
		{
			boolean valid = pl.id >= 0 && pl.id < OffsetTable.CAPACITY;
			int nowX = valid ? offsets.dx(pl.id) : 0;
			int nowZ = valid ? offsets.dz(pl.id) : 0;
			boolean fromTheMiddle = valid && nowX == 0 && nowZ == 0 && (pl.dx != 0 || pl.dz != 0);
			boolean aStep = valid && Math.hypot(pl.dx - nowX, pl.dz - nowZ) >= OffsetTable.MIN_WALK_DISTANCE;
			boolean busyStep = valid && aStep && !offsets.isWalking(pl.id) && busyTick[pl.id] == tick;
			boolean waitHere = busyStep && (busyWaitSince[pl.id] != 0
				|| worthWaiting(busyAnimation[pl.id], fromTheMiddle && !middleTaken.contains(pl.tile)));
			if (busyStep && !waitHere)
			{
				waitSkipped++;
			}
			if (waitHere)
			{
				// Mid-action: stay put, in the middle or in their spot, for a break in it, then walk.
				if (busyWaitSince[pl.id] == 0)
				{
					busyWaitSince[pl.id] = tick;
				}
				if (tick - busyWaitSince[pl.id] < (fromTheMiddle ? BUSY_WAIT_TICKS : BUSY_MOVE_WAIT_TICKS))
				{
					if (!fromTheMiddle)
					{
						offsets.setTarget(pl.id, nowX, nowZ);
					}
					else
					{
						middleTaken.add(pl.tile);
						if (pl.id != localId)
						{
							waitingInTheMiddle.add(pl.id);
						}
					}
					placedTile[pl.id] = pl.tile;
					placedTick[pl.id] = tick;
					continue;
				}
				if (fromTheMiddle)
				{
					// Only waits in the middle teach what's worth waiting for: one in a spot runs out
					// sooner, so it said too little about whether the action pauses.
					noteWaitOutcome(busyAnimation[pl.id], false);
				}
			}
			else if (valid && busyWaitSince[pl.id] != 0 && busyTick[pl.id] != tick && fromTheMiddle)
			{
				noteWaitOutcome(busyAnimation[pl.id], true);
			}
			if (pl.id >= 0 && pl.id < OffsetTable.CAPACITY)
			{
				busyWaitSince[pl.id] = 0;
			}
			offsets.setTarget(pl.id, pl.dx, pl.dz);
			if (pl.id >= 0 && pl.id < OffsetTable.CAPACITY)
			{
				placedTile[pl.id] = pl.tile;
				placedTick[pl.id] = tick;
			}
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
		unplacedIds.addAll(waitingInTheMiddle);
		// Curved rows and counter rows alike: everyone turns towards what they are facing.
		Set<Long> turnIn = new HashSet<>(plan.curvedRows);
		turnIn.addAll(plan.facingIn);
		stacks.rebuild(members, turnIn, unplacedIds, plan.fires, plan.poses, plan.middleOutOfSight);
		stacks.setWaiting(waitingInTheMiddle);
		stacks.setArrivals(stillness::arrivedTick);
		probe.forgetTilesNotIn(stacks);

		nearby = nearbyCount;
		aboard = onBoats;
		still = entries.size();
		stackedTiles = StackSpreader.stackedTiles(entries);
		if (moving >= 6 && plan.placements.size() * 2 < moving)
		{
			planDips++;
		}
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
		if (moving > 0)
		{
			resets++;
		}
		if (probe != null)
		{
			probe.enabled = false;
		}
		if (levelFilter != null)
		{
			levelFilter.enabled = false;
		}
		if (idleSitter != null)
		{
			// Off, unsafe or logged out: everyone stands in their own pose again.
			idleSitter.standAll();
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
	/** How far clear of a fire's tile anyone is drawn, in local units. */
	static final int FIRE_CLEARANCE = 20;

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

			/** Which tiles have a fire on them, looked up once a tick. */
			private final Map<Long, Boolean> fireAt = new HashMap<>();

			private boolean fireOn(WorldView world, int plane, int sceneX, int sceneY)
			{
				long key = ((long) world.getId() << 44) | ((long) (plane & 0xF) << 40) | ((long) (sceneX & 0xFFFFF) << 20) | (sceneY & 0xFFFFF);
				return fireAt.computeIfAbsent(key, k -> hasFire(world, plane, sceneX, sceneY));
			}

			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + 64;
				int z = StackRegistry.sceneY(tile) * 128 + 64;
				int plane = StackRegistry.planeOf(StackRegistry.plane(tile));
				if (!collision(tile).canStand(plane, x, z, x + dx, z + dz))
				{
					return false;
				}
				// Never drawn standing in a fire. Some campfires, like the Forester's Campfire at the
				// Grand Exchange, can be walked over, and a crowd round one was spread right on top
				// of it, hiding the very fire they were gathered at. A little to spare all round, as
				// a body is wider than its feet.
				WorldView world = world(tile);
				if (world == null)
				{
					return true;
				}
				int px = x + dx;
				int pz = z + dz;
				for (int cx : new int[]{px - FIRE_CLEARANCE, px + FIRE_CLEARANCE})
				{
					for (int cz : new int[]{pz - FIRE_CLEARANCE, pz + FIRE_CLEARANCE})
					{
						if (cx >= 0 && cz >= 0 && fireOn(world, plane, cx >> 7, cz >> 7))
						{
							return false;
						}
					}
				}
				return true;
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
		return yourTileLine(yours.shape, autoSpace && yours.spacing < slider);
	}

	/** The report's line about your tile, for a tile of this shape; {@code keptClose} if Auto spacing closed it up. */
	static String yourTileLine(String shape, boolean keptClose)
	{
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
		else if (shape.startsWith(CrowdPlanner.ROUND_OBJECT))
		{
			what = "a crowd round what you're facing";
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
		else if (keptClose)
		{
			why = ", kept close by Auto spacing";
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
		s.depthTest = config.depthBetweenPlayers();
		s.walkWhileBusy = config.walkWhileBusy();
		s.idleSit = config.idlePlayersSit();
		s.sitting = idleSitter == null ? 0 : idleSitter.sitting();
		s.clickPast = config.clickPastMoved();
		s.names = config.namesFollowPlayers();
		s.hover = config.hoverShowsWho();
		s.hoverArrow = config.hoverArrow();
		s.hideBelow = config.hideBelowLevel();
		s.hideChat = config.hideTheirChat();
		s.chatRange = config.hideChatFurtherThan();
		s.alchers = config.otherAlchers();
		s.alchersRestyled = alchStyler == null ? 0 : alchStyler.restyled;
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
		Plugin hd = findPlugin("HdPlugin");
		s.hdEnabled = hd != null && pluginManager.isPluginEnabled(hd);

		double seconds = lastPanelNanos == 0 ? 0 : (now - lastPanelNanos) / 1_000_000_000.0;
		boolean ratesKnown = w != null && w == countedWrapper && seconds > 0;
		if (ratesKnown)
		{
			s.playerDrawsPerSec = perSecond(w.playerDraws - lastPlayerDraws, seconds);
			s.nudgedDrawsPerSec = perSecond(w.nudgedDraws - lastNudgedDraws, seconds);
			s.revealedDrawsPerSec = perSecond(w.revealedDraws - lastRevealedDraws, seconds);
			s.sceneMismatchesPerSec = perSecond(w.sceneMismatches - lastSceneMismatches, seconds);
		}
		s.resets = resets;
		s.driftFrames = offsets.driftFrames;
		s.wallHurries = offsets.wallHurries;
		s.wallHolds = offsets.wallHolds;
		s.wallFollows = offsets.wallFollows;
		s.wallShrinks = offsets.wallShrinks;
		s.insideYouHidden = planner.insideYouHidden;
		s.spotLosses = spotLosses;
		s.longestHidden = hideLog.longest(offsets.frame(), client.getGameCycle());
		s.flickering = hideLog.flickering(offsets.frame(), client.getGameCycle());
		s.sliding = hideLog.sliding(client.getGameCycle());
		s.waitBreaks = waitBreaks;
		s.waitTimeouts = waitTimeouts;
		s.waitSkipped = waitSkipped;
		s.planDips = planDips;
		s.shimInstalls = shimInstalls;
		if (w != null)
		{
			s.playersInOtherCalls = w.playersInOtherCalls;
			s.offThreadDraws = w.offThreadDraws;
			s.revealErrors = w.revealErrors;
			s.walkDraws = w.walkDraws;
			s.orderedDraws = w.orderedDraws;
			s.depthDraws = w.depthDraws;
			s.depthNoSpacer = w.depthNoSpacer;
			s.keptDips = w.keptDips;
			s.probeBlackouts = w.probeBlackouts;
			s.droppedUndrawn = w.droppedUndrawn;
			s.blinkNoMiddle = w.blinkNoMiddle;
			s.blinkUnconfirmed = w.blinkUnconfirmed;
			s.blinkOthersHid = w.blinkOthersHid;
			s.blinkMoved = w.blinkMoved;
			s.blinkOther = w.blinkOther;
			s.keptWithoutModel = w.keptWithoutModel;
			s.skippedUnconfirmed = w.skippedUnconfirmed;
			s.skippedOthersHid = w.skippedOthersHid;
			s.skippedMoved = w.skippedMoved;
			s.fireBlackouts = w.fireBlackouts;
			s.fireUnconfirmed = w.fireUnconfirmed;
			s.middleKeptSteady = w.middleKeptSteady;
			s.refusedDrawn = w.refusedDrawn;
			s.aloneRefusedDrawn = w.aloneRefusedDrawn;
			s.movingRefusedDrawn = w.movingRefusedDrawn;
			s.walksLegsStill = w.walksLegsStill;
			s.walksLegsStillBusy = w.walksLegsStillBusy;
			s.legsStillBy = w.legsStillBy.clone();
			s.legsStillAnims = topAnimations(w.legsStillAnims, client);
			s.legsMatched = w.legsMatched;
			s.movingBusyWalks = w.movingBusyWalks;
			s.swapsCameraMoving = w.swapsCameraMoving;
			s.swapsCameraStill = w.swapsCameraStill;
			s.stillSwapsMoved = w.stillSwapsMoved;
			s.stillSwapsOther = w.stillSwapsOther;
			s.stillSwapsWalking = w.stillSwapsWalking;
			s.stillSwapsStanding = w.stillSwapsStanding;
			s.swapsBack = w.swapsBack;
			s.passMissedFrames = w.passMissedFrames;
			s.hiddenInYourWay = w.hiddenInYourWay;
			s.walkSkippedBusy = w.walkSkippedBusy;
			s.walkBusy = w.walkBusy;
			s.busyWalksOff = busyWalksOff;
			s.slideBusy = w.slideBusy;
			s.slideWalkingOff = w.slideWalkingOff;
			s.slideOther = w.slideOther;
			s.slideBusyArriving = w.slideBusyArriving;
			s.slideBusyBetween = w.slideBusyBetween;
			s.slideBusyToMiddle = w.slideBusyToMiddle;
			StringBuilder top = new StringBuilder();
			w.busyAnimations.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.limit(4)
				.forEach(e -> top.append(top.length() == 0 ? "" : ", ").append(e.getKey()).append(" x").append(e.getValue()));
			s.busyAnimations = top.toString();
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
			s.probeBackOffs = pr.backOffs;
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

	/** RuneLite's GPU plugin, or 117 HD: found by class name, as neither can be referred to directly. */
	private Plugin findPlugin(String simpleName)
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (simpleName.equals(p.getClass().getSimpleName()))
			{
				return p;
			}
		}
		return null;
	}

	/**
	 * The sidebar's Turn on GPU button: switches on RuneLite's GPU plugin, just as ticking it in the
	 * plugin list does. Swing thread. Never over 117 HD: the button isn't offered while it's on.
	 */
	private void turnOnGpu()
	{
		Plugin hd = findPlugin("HdPlugin");
		Plugin gpu = findPlugin("GpuPlugin");
		if (gpu == null || (hd != null && pluginManager.isPluginEnabled(hd)) || pluginManager.isPluginActive(gpu))
		{
			return;
		}
		pluginManager.setPluginEnabled(gpu, true);
		try
		{
			pluginManager.startPlugin(gpu);
		}
		catch (PluginInstantiationException e)
		{
			log.warn("Couldn't turn on the GPU plugin", e);
		}
	}

	private static String rendererName(DrawCallbacks callbacks)
	{
		String name = callbacks.getClass().getSimpleName();
		if (name.equals("GpuPlugin"))
		{
			return "GPU plugin";
		}
		if (name.toLowerCase().contains("hd") || callbacks.getClass().getName().startsWith("rs117."))
		{
			return "117 HD";
		}
		return name;
	}
}
