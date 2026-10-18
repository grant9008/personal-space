package com.grant9008.personalspace;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;

/**
 * "Idle players sit": other players who've stood doing nothing a while are shown sitting, in the
 * game's own Sit emote. Their standing pose is swapped for it, so the game itself shows them
 * sitting only while idle: the moment they walk or do something it plays that instead.
 *
 * <p>Only someone who hasn't moved or done anything for {@link #SIT_AFTER_CYCLES}: alching, they sat
 * in the moment between casts, and with Alchers behavior on Still (their cast hidden) all the
 * time, though they were busy. Anyone whose cast the plugin restyles counts as busy too
 * ({@link #busy}).
 *
 * <p>Only other players, and only on this screen. Their own standing pose is remembered and put
 * back when they get busy, the switch goes off, the plugin stops, or they leave. Gear changes bring
 * the game's new pose, which is remembered and swapped in turn.
 */
final class IdleSitter
{
	/** The Sit emote, looping. */
	static final int SIT = AnimationID.EMOTE_SIT_LOOP;
	/** Standing doing nothing this long, in game cycles (5 s), they sit down. */
	static final int SIT_AFTER_CYCLES = 250;

	private final Client client;
	/** Each player sat down, and their own standing pose. */
	private final Map<Player, Integer> stance = new IdentityHashMap<>();
	/** When each player last moved or did something, in game cycles. */
	private final Map<Player, Integer> lastBusy = new IdentityHashMap<>();
	/** Where each player stood last tick. */
	private final Map<Player, Long> lastAt = new IdentityHashMap<>();

	IdleSitter(Client client)
	{
		this.client = client;
	}

	/** Once a tick: sit idle players down if {@code on}, else stand everyone back up. Client thread. */
	void update(boolean on)
	{
		WorldView wv = client.getTopLevelWorldView();
		update(on, wv == null ? null : wv.players(), client.getLocalPlayer(), client.getGameCycle());
	}

	/** As above, for these players, you being {@code you}, at game cycle {@code cycle}. */
	void update(boolean on, Iterable<? extends Player> players, Player you, int cycle)
	{
		if (!on || players == null)
		{
			standAll();
			lastBusy.clear();
			lastAt.clear();
			return;
		}
		Set<Player> here = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Player p : players)
		{
			if (p == null || p == you)
			{
				continue;
			}
			here.add(p);
			LocalPoint lp = p.getLocalLocation();
			long at = lp == null ? Long.MIN_VALUE : ((long) lp.getX() << 32) | (lp.getY() & 0xFFFFFFFFL);
			Long was = lastAt.put(p, at);
			if (was == null || was != at || p.getAnimation() != -1)
			{
				lastBusy.put(p, cycle);
			}
			Integer since = lastBusy.get(p);
			if (since == null || cycle - since < SIT_AFTER_CYCLES)
			{
				standUp(p);
				continue;
			}
			int idle = p.getIdlePoseAnimation();
			if (idle != SIT && idle != -1)
			{
				// Idle a while, or their gear changed their stance: remember it and sit them down.
				stance.put(p, idle);
				p.setIdlePoseAnimation(SIT);
			}
		}
		// Gone from sight: nothing to put back.
		stance.keySet().retainAll(here);
		lastBusy.keySet().retainAll(here);
		lastAt.keySet().retainAll(here);
	}

	/**
	 * This player is busy, though the game may not show it: their cast was restyled (Alchers
	 * behavior). They stand up now, and stay up until they've been idle a while. Client thread.
	 */
	void busy(Player p, int cycle)
	{
		lastBusy.put(p, cycle);
		standUp(p);
	}

	private void standUp(Player p)
	{
		Integer own = stance.remove(p);
		if (own != null && p.getIdlePoseAnimation() == SIT)
		{
			p.setIdlePoseAnimation(own);
		}
	}

	/** Stand everyone sat down back up, in their own pose. Client thread. */
	void standAll()
	{
		for (Map.Entry<Player, Integer> e : stance.entrySet())
		{
			Player p = e.getKey();
			if (p.getIdlePoseAnimation() == SIT)
			{
				p.setIdlePoseAnimation(e.getValue());
			}
		}
		stance.clear();
	}

	/** How many are sat down now. */
	int sitting()
	{
		return stance.size();
	}
}
