package com.grant9008.personalspace;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;

/**
 * Makes sure Personal Space only ever draws players the game itself is willing to show.
 *
 * <p>The game skips two kinds of player: ones hidden only because they share a tile with someone
 * (the stacks Personal Space spreads out), and ones the server hides on purpose, such as an
 * invisible Jagex moderator. The API can't tell these apart, so this callback watches the moment
 * the game adds each player to the scene. That step runs after the game has dropped server-hidden
 * players, so any player that reaches it is fine to draw. Such a player counts as "confirmed" for
 * a few seconds.
 *
 * <p>A player stacked behind someone else never reaches that step on their own. So while a
 * stacked tile has an unconfirmed player, this callback briefly tells the game not to draw the
 * confirmed players on that tile. RuneLite then lets the next player on the tile through, and it
 * gets confirmed and drawn. The players it held back are drawn by {@link SpreadingDrawCallbacks}
 * at their spots in the same frame, so nothing visibly changes. A player who never shows up within
 * a few frames is one the game won't draw, and is left hidden.
 *
 * <p>Client thread only: the game adds players to the scene and draws them on the client thread.
 */
final class StackProbe implements RenderCallback
{
	/** How long a confirmation lasts, in game cycles (20 ms each): 10 seconds. */
	static final int FRESH_CYCLES = 500;
	/** Frames to keep holding players back before deciding the missing one won't be drawn. */
	static final int MAX_PROBE_FRAMES = 3;
	/**
	 * Extra time a player still counts as shown when laying out a crowd. A stackmate's confirmation
	 * is only renewed on the frames the probe runs, so a short gap must not drop them from their
	 * crowd, which would make everyone walk in and back out. 10 seconds.
	 */
	static final int SHOWN_GRACE_CYCLES = 500;
	/**
	 * How long past its {@link #FRESH_CYCLES} a confirmation still lets a stackmate be drawn: 5
	 * seconds. Everyone on a tile is confirmed again in the same frame, so their confirmations all
	 * run out together, and the game lets only one of them through a frame to be confirmed again:
	 * with 16 on a tile, most of the crowd vanished for a moment and came back one by one, every 10
	 * seconds. Now they stay drawn while that happens.
	 */
	static final int DRAW_GRACE_CYCLES = 250;
	/** How long to leave a player alone after they failed to show up: 60 seconds. */
	static final int GIVE_UP_CYCLES = 3000;

	private static final int MAX_HIDDEN_PER_FRAME = 256;

	private static final class TileState
	{
		int frame = -1;
		boolean probing;
		int signature;
		int framesUsed;
	}

	private final Client client;
	private final OffsetTable offsets;
	private final StackRegistry stacks;

	private final Player[] confirmed = new Player[OffsetTable.CAPACITY];
	private final int[] confirmedCycle = new int[OffsetTable.CAPACITY];
	private final int[] leaveAloneUntil = new int[OffsetTable.CAPACITY];
	private final Map<Long, TileState> tiles = new HashMap<>();

	/** Players held back this frame, and their tiles, so the draw shim can draw them. */
	private int heldFrame = -1;
	private int heldCount;
	private final int[] heldIds = new int[MAX_HIDDEN_PER_FRAME];
	private final long[] heldTiles = new long[MAX_HIDDEN_PER_FRAME];

	/** Whether spreading is running right now. Set by the plugin each tick. */
	boolean enabled;
	/** Set by the draw shim while it asks other plugins about a player, so this callback stays out of it. */
	boolean asking;

	/** Diagnostics: total players held back. */
	long heldTotal;
	/** Diagnostics: players given up on because they never showed up. */
	long gaveUpTotal;

	StackProbe(Client client, OffsetTable offsets, StackRegistry stacks)
	{
		this.client = client;
		this.offsets = offsets;
		this.stacks = stacks;
	}

	@Override
	public boolean addEntity(Renderable renderable, boolean drawingUI)
	{
		if (drawingUI || asking || !(renderable instanceof Player) || !client.isClientThread())
		{
			return true;
		}
		Player player = (Player) renderable;
		int id = player.getId();
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return true;
		}

		int cycle = client.getGameCycle();
		boolean alreadyConfirmed = isConfirmed(id, player, cycle);
		confirmed[id] = player;
		confirmedCycle[id] = cycle;
		leaveAloneUntil[id] = 0;

		// A player who wasn't confirmed yet is drawn by the game as normal, which is exactly what
		// lets the rest of the tile be revealed around them.
		if (!enabled || !alreadyConfirmed || stacks.isEmpty())
		{
			return true;
		}
		LocalPoint lp = player.getLocalLocation();
		if (lp == null || !StillnessTracker.isCentred(lp.getX(), lp.getY()))
		{
			return true;
		}
		WorldView wv = player.getWorldView();
		if (wv == null)
		{
			return true;
		}
		long key = StackRegistry.key(StackRegistry.layer(wv.getId(), player.getWorldLocation().getPlane()), lp.getSceneX(), lp.getSceneY());
		int[] members = stacks.membersAt(key);
		if (members.length == 0 || !shouldProbe(key, members, wv, offsets.frame(), cycle))
		{
			return true;
		}

		hold(id, key, offsets.frame());
		return false;
	}

	/** True if this exact player object reached the scene recently, so the game is willing to draw them. */
	boolean isConfirmed(int id, Player player, int cycle)
	{
		return id >= 0 && id < OffsetTable.CAPACITY
			&& confirmed[id] == player
			&& cycle - confirmedCycle[id] <= FRESH_CYCLES;
	}

	/** True if this stackmate may be drawn: confirmed lately, or being confirmed again right now. */
	boolean mayDraw(int id, Player player, int cycle)
	{
		return id >= 0 && id < OffsetTable.CAPACITY
			&& confirmed[id] == player
			&& cycle - confirmedCycle[id] <= FRESH_CYCLES + DRAW_GRACE_CYCLES
			&& leaveAloneUntil[id] <= cycle;
	}

	/**
	 * True if the game has shown this player recently enough for them to count in a crowd. Players
	 * the game never shows (hidden by the server or by another plugin) never count, so they can't
	 * push a visible player aside next to nobody.
	 */
	boolean isShown(int id, Player player, int cycle)
	{
		return id >= 0 && id < OffsetTable.CAPACITY
			&& confirmed[id] == player
			&& cycle - confirmedCycle[id] <= FRESH_CYCLES + SHOWN_GRACE_CYCLES
			&& leaveAloneUntil[id] <= cycle;
	}

	/** Fill {@code out} with the ids held back this frame on the given tile; returns how many. */
	int heldOn(long tileKey, int frame, int[] out)
	{
		if (heldFrame != frame)
		{
			return 0;
		}
		int n = 0;
		for (int i = 0; i < heldCount && n < out.length; i++)
		{
			if (heldTiles[i] == tileKey)
			{
				out[n++] = heldIds[i];
			}
		}
		return n;
	}

	/** The tiles on which players were held back this frame, each once. */
	long[] heldTiles(int frame)
	{
		if (heldFrame != frame || heldCount == 0)
		{
			return new long[0];
		}
		long[] out = new long[heldCount];
		int n = 0;
		for (int i = 0; i < heldCount; i++)
		{
			boolean seen = false;
			for (int j = 0; j < n && !seen; j++)
			{
				seen = out[j] == heldTiles[i];
			}
			if (!seen)
			{
				out[n++] = heldTiles[i];
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Forget everything, e.g. on logout or world hop. */
	void reset()
	{
		for (int i = 0; i < confirmed.length; i++)
		{
			confirmed[i] = null;
			confirmedCycle[i] = 0;
			leaveAloneUntil[i] = 0;
		}
		tiles.clear();
		heldFrame = -1;
		heldCount = 0;
	}

	/** Drop per-tile state for tiles that are no longer stacked. */
	void forgetTilesNotIn(StackRegistry registry)
	{
		tiles.keySet().removeIf(key -> registry.membersAt(key).length == 0);
	}

	/** Decided once per tile per frame, on the first confirmed player to arrive there. */
	private boolean shouldProbe(long key, int[] members, WorldView wv, int frame, int cycle)
	{
		TileState t = tiles.computeIfAbsent(key, k -> new TileState());
		if (t.frame == frame)
		{
			return t.probing;
		}
		t.frame = frame;

		int signature = 17;
		boolean waiting = false;
		for (int id : members)
		{
			if (id < 0 || id >= OffsetTable.CAPACITY || leaveAloneUntil[id] > cycle)
			{
				continue;
			}
			Player mate = wv.players().byIndex(id);
			if (mate == null || isConfirmed(id, mate, cycle))
			{
				continue;
			}
			waiting = true;
			signature = 31 * signature + id;
		}

		if (!waiting)
		{
			t.probing = false;
			t.signature = 0;
			t.framesUsed = 0;
			return false;
		}
		if (signature != t.signature)
		{
			t.signature = signature;
			t.framesUsed = 0;
		}
		if (t.framesUsed >= MAX_PROBE_FRAMES)
		{
			// They had their chance and never reached the scene: the game isn't drawing them.
			for (int id : members)
			{
				if (id >= 0 && id < OffsetTable.CAPACITY && leaveAloneUntil[id] <= cycle)
				{
					Player mate = wv.players().byIndex(id);
					if (mate != null && !isConfirmed(id, mate, cycle))
					{
						leaveAloneUntil[id] = cycle + GIVE_UP_CYCLES;
						gaveUpTotal++;
					}
				}
			}
			t.probing = false;
			t.signature = 0;
			t.framesUsed = 0;
			return false;
		}
		t.framesUsed++;
		t.probing = true;
		return true;
	}

	private void hold(int id, long key, int frame)
	{
		if (heldFrame != frame)
		{
			heldFrame = frame;
			heldCount = 0;
		}
		if (heldCount < MAX_HIDDEN_PER_FRAME)
		{
			heldIds[heldCount] = id;
			heldTiles[heldCount] = key;
			heldCount++;
		}
		heldTotal++;
	}

	/** Ask the other render callbacks (e.g. Entity Hider) whether a player may be drawn, without this one taking part. */
	boolean othersAllow(RenderCallbackManager manager, Player player)
	{
		if (manager == null)
		{
			return true;
		}
		asking = true;
		try
		{
			return manager.addEntity(player, false);
		}
		finally
		{
			asking = false;
		}
	}
}
