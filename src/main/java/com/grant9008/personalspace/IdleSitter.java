package com.grant9008.personalspace;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.AnimationID;

/**
 * "Idle players sit": other players standing doing nothing are shown sitting, in the game's own
 * Sit emote. Their standing pose is swapped for it, so the game itself shows them sitting only
 * while idle: the moment they walk or do something it plays that instead, and they're up.
 *
 * <p>Only other players, and only on this screen. Their own standing pose is remembered and put
 * back when the switch goes off, the plugin stops, or they leave. Gear changes bring the game's
 * new pose, which is remembered and swapped in turn.
 */
final class IdleSitter
{
	/** The Sit emote, looping. */
	static final int SIT = AnimationID.EMOTE_SIT_LOOP;

	private final Client client;
	/** Each player sat down, and their own standing pose. */
	private final Map<Player, Integer> stance = new IdentityHashMap<>();

	IdleSitter(Client client)
	{
		this.client = client;
	}

	/** Once a tick: sit everyone else down if {@code on}, else stand everyone back up. Client thread. */
	void update(boolean on)
	{
		WorldView wv = client.getTopLevelWorldView();
		update(on, wv == null ? null : wv.players(), client.getLocalPlayer());
	}

	/** As above, for these players, you being {@code you}. */
	void update(boolean on, Iterable<? extends Player> players, Player you)
	{
		if (!on || players == null)
		{
			standAll();
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
			int idle = p.getIdlePoseAnimation();
			if (idle != SIT && idle != -1)
			{
				// New, or their gear changed their stance: remember it and sit them down again.
				stance.put(p, idle);
				p.setIdlePoseAnimation(SIT);
			}
		}
		// Gone from sight: nothing to put back.
		stance.keySet().retainAll(here);
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
