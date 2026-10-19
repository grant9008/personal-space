package com.grant9008.personalspace;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;

/**
 * "Idle players sit": other players who've stood doing nothing a while (away from the keyboard)
 * are shown sitting, in the
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
 *
 * <p>The Sit emote's loop starts with nine frames of getting down before the seated part repeats,
 * so each frame the scene is drawn, everyone sat down is put in the seated part alone ({@link #seat}).
 */
final class IdleSitter
{
	/** The Sit emote, looping: nine frames of sitting down, then the seated part, repeated. */
	static final int SIT = AnimationID.EMOTE_SIT_LOOP;
	/**
	 * The Sit emote's seated part on its own, frame for frame: the same cross-legged loop with no
	 * sitting down in front of it, so every frame of it is seated.
	 */
	static final int SEATED = AnimationID.AIDE_SITTING_CROSSLEGGED_READY;
	/** Sitters breathe out of step: each player id's seated loop runs this many game cycles on from the one before. */
	private static final int SEATED_PHASE = 7;
	/**
	 * Standing doing nothing this long, in game cycles (30 s), they sit down: away from the keyboard,
	 * not just between one thing and the next. (After 5 s, anyone pausing for a moment sat.)
	 */
	static final int SIT_AFTER_CYCLES = 1500;

	private final Client client;
	/** Each player sat down, and their own standing pose. */
	private final Map<Player, Integer> stance = new IdentityHashMap<>();
	/** When each player last moved or did something, in game cycles. */
	private final Map<Player, Integer> lastBusy = new IdentityHashMap<>();
	/** Where each player stood last tick. */
	private final Map<Player, Long> lastAt = new IdentityHashMap<>();
	/** How long each frame of {@link #SEATED} lasts, in game cycles, as the game has it; null until read. */
	private int[] seatedLengths;
	/** Whether {@link #seatedLengths} was looked for since the last tick: not read, it is tried again a tick later. */
	private boolean seatedLooked;

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
		seatedLooked = false;
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
	 * Once a frame, before the scene is drawn: everyone sat down who is sitting still, or turning
	 * on the spot, is drawn in the seated loop ({@link #SEATED}) for this frame. Client thread.
	 *
	 * <p>Their standing pose swapped for the Sit emote wasn't enough on its own. The game never
	 * starts a player's new pose from its first frame: it carries on at whatever frame the pose
	 * before had got to, which lands in the emote's nine frames of sitting down. Sitting down, and
	 * again whenever they turned on the spot (the game stands people up to turn), they showed
	 * part-way down, leaning forward with their arms out. With more than 200 players about (50 in
	 * low-memory mode) the game stops animating anyone whose pose is their standing pose and draws
	 * them at a frame it held from long before, nearly always one of those nine: everyone sat down
	 * was frozen half-way, and the whole crowd stood up and sat down again each time the count of
	 * players crossed 200. The seated loop isn't their standing pose, so the game draws it as it is,
	 * every frame of it seated; and the next game cycle the game puts their own pose back itself,
	 * so nothing is left to undo when they get up.
	 */
	void seat()
	{
		if (stance.isEmpty())
		{
			return;
		}
		if (seatedLengths == null && !seatedLooked)
		{
			seatedLooked = true;
			seatedLengths = seatedLengths(client);
		}
		seat(client.getGameCycle(), seatedLengths);
	}

	/** As above, at game cycle {@code cycle}, each frame of the seated loop lasting this long. */
	void seat(int cycle, int[] lengths)
	{
		int total = 0;
		if (lengths != null)
		{
			for (int length : lengths)
			{
				total += Math.max(1, length);
			}
		}
		if (total == 0)
		{
			return;
		}
		for (Player p : stance.keySet())
		{
			int pose = p.getPoseAnimation();
			boolean still = pose == SIT || pose == SEATED || pose == p.getIdlePoseAnimation()
				|| pose == p.getIdleRotateLeft() || pose == p.getIdleRotateRight();
			if (p.getAnimation() != -1 || pose == -1 || !still || SpreadingDrawCallbacks.reallyMoving(p))
			{
				// Doing something, or walking off: the game shows that, and they stand up at the next tick.
				continue;
			}
			int t = Math.floorMod(cycle + SEATED_PHASE * p.getId(), total);
			int frame = 0;
			while (t >= Math.max(1, lengths[frame]))
			{
				t -= Math.max(1, lengths[frame]);
				frame++;
			}
			p.setPoseAnimation(SEATED);
			p.setPoseAnimationFrame(frame);
		}
	}

	/** The frame lengths of {@link #SEATED}, or null if the game can't give them yet. */
	private static int[] seatedLengths(Client client)
	{
		try
		{
			Animation seated = client.loadAnimation(SEATED);
			int[] lengths = seated == null || seated.isMayaAnim() ? null : seated.getFrameLengths();
			return lengths == null || lengths.length == 0 ? null : lengths;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * This player is busy, though their body may not show it: they said something, or their cast
	 * was restyled (Alchers behavior). They stand up now, and stay up until they've been idle a
	 * while. Client thread.
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
