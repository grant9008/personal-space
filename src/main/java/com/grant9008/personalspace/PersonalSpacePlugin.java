package com.grant9008.personalspace;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Personal Space",
	description = "Spreads out players standing on the same tile so you can see everyone's outfit. Cosmetic only; off in the Wilderness and PvP.",
	tags = {"stack", "stacked", "crowd", "outfit", "fashionscape", "cosmetic", "players", "social", "fire", "bank", "star"}
)
public class PersonalSpacePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PersonalSpacePlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PersonalSpaceConfig config;

	private final OffsetTable offsets = new OffsetTable();

	/** Our shim, while it is the client's installed draw callback. Null when nothing is hooked. */
	private SpreadingDrawCallbacks wrapper;

	private long lastFrameNanos;
	private boolean warnedNoRenderer;
	private boolean warnedBadIds;

	@Provides
	PersonalSpaceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PersonalSpaceConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastFrameNanos = 0;
		warnedNoRenderer = false;
		warnedBadIds = false;
		clientThread.invoke(offsets::snapAllToZero);
		// The shim itself is installed lazily on the first frame, see ensureInstalled().
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			uninstall();
			offsets.snapAllToZero();
		});
	}

	// ---- per frame ---------------------------------------------------------------------

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		ensureInstalled();

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		offsets.advance(Math.min(dt, 0.25f), config.smoothing());
	}

	/**
	 * Keep our shim in front of whichever renderer is active. Renderers install themselves with
	 * setDrawCallbacks when they start and set it to null when they stop, so this is re-checked
	 * every frame rather than once at start-up. Cheap: one getter and a reference compare.
	 * Actually swapping the callback makes the client hand the renderer the scene again (the
	 * same brief hitch as toggling the GPU plugin), so it is only ever done when needed.
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
		wrapper = new SpreadingDrawCallbacks(client, offsets, target);
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
			offsets.snapAllToZero();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			offsets.snapAllToZero();
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || isUnsafe(local))
		{
			// Hard off: straight back to real positions, no easing, no exceptions.
			offsets.snapAllToZero();
			return;
		}

		if (wrapper == null && !warnedNoRenderer && client.getDrawCallbacks() == null)
		{
			warnedNoRenderer = true;
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"<col=ff8c00>Personal Space</col>: turn on the GPU plugin (or 117 HD) for this plugin to have any effect.", null);
		}

		WorldView wv = client.getTopLevelWorldView();
		offsets.setScene(wv.getScene());
		offsets.clearTargets();

		if (config.mode() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME)
		{
			offsets.setTarget(local.getId(), config.testOffset(), 0);
			return;
		}

		List<StackSpreader.Entry> entries = new ArrayList<>();
		for (Player p : wv.players())
		{
			if (p == null || !isStandingStill(p))
			{
				continue;
			}
			int id = p.getId();
			if (id < 0 || id >= OffsetTable.CAPACITY || wv.players().byIndex(id) != p)
			{
				if (!warnedBadIds)
				{
					warnedBadIds = true;
					log.warn("Player id {} does not index the player list; skipping such players", id);
				}
				continue;
			}
			WorldPoint wp = p.getWorldLocation();
			if (wp == null)
			{
				continue;
			}
			entries.add(new StackSpreader.Entry(id, tileKey(wp), p == local));
		}

		List<StackSpreader.Placement> placements = StackSpreader.place(
			entries, config.includeLocalPlayer(), config.maxStack(), config.separation().getUnits());
		for (StackSpreader.Placement pl : placements)
		{
			offsets.setTarget(pl.id, pl.dx, pl.dz);
		}
	}

	/** Only players who are standing still get nudged; walking, dead or fighting players are left alone. */
	private static boolean isStandingStill(Player p)
	{
		return !p.isDead()
			&& p.getPoseAnimation() == p.getIdlePoseAnimation()
			&& p.getHealthRatio() == -1; // -1 means no health bar is showing
	}

	/** Wilderness, any PvP world or area, or the local player being in combat. */
	private boolean isUnsafe(Player local)
	{
		if (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
		{
			return true;
		}
		if (client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1)
		{
			return true;
		}
		EnumSet<WorldType> types = client.getWorldType();
		if (WorldType.isPvpWorld(types)
			|| types.contains(WorldType.PVP_ARENA)
			|| types.contains(WorldType.DEADMAN)
			|| types.contains(WorldType.TOURNAMENT_WORLD)
			|| types.contains(WorldType.BOUNTY))
		{
			return true;
		}
		return local.getHealthRatio() != -1; // our own health bar is showing: we are in combat
	}

	private static long tileKey(WorldPoint wp)
	{
		return ((long) wp.getPlane() << 32) | ((long) (wp.getX() & 0xFFFF) << 16) | (wp.getY() & 0xFFFF);
	}
}
