package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Remembers which spot each player has on each crowded tile, so a crowd doesn't reshuffle every
 * time someone arrives or leaves.
 *
 * <p>Spots are numbered from best to worst: 0 is the best (front and centre), higher numbers are
 * further out or further back. The rules:
 * <ul>
 * <li>Anyone who already has a spot keeps it.</li>
 * <li>A newcomer takes the best free spot.</li>
 * <li>When someone leaves, their spot is held for {@link #HOLD_TICKS} ticks. If they come back in
 * that time they get it back; nobody else takes it.</li>
 * <li>Once a hold runs out, the player in the worst spot moves into the gap. Only that one player
 * moves, so the front row stays put and the back fills forward.</li>
 * </ul>
 *
 * <p>Client thread only. Knows nothing about RuneLite; unit tested.
 */
final class SlotBook
{
	/** How long a spot is kept for someone who just left: 8 ticks, about 5 seconds. */
	static final int HOLD_TICKS = 8;

	private static final class Tile
	{
		final Map<Integer, Integer> slotOf = new HashMap<>();
		final Map<Integer, Integer> occupant = new HashMap<>();
		final Map<Integer, Integer> heldFor = new HashMap<>();
		final Map<Integer, Integer> heldUntil = new HashMap<>();

		boolean held(int slot, int tick)
		{
			Integer until = heldUntil.get(slot);
			return until != null && until >= tick;
		}

		boolean anyHeld(int tick)
		{
			for (int until : heldUntil.values())
			{
				if (until >= tick)
				{
					return true;
				}
			}
			return false;
		}

		void vacate(int id, int tick, boolean hold)
		{
			Integer slot = slotOf.remove(id);
			if (slot == null)
			{
				return;
			}
			occupant.remove(slot);
			if (hold)
			{
				heldFor.put(slot, id);
				heldUntil.put(slot, tick + HOLD_TICKS);
			}
		}

		void assign(int id, int slot)
		{
			slotOf.put(id, slot);
			occupant.put(slot, id);
			heldFor.remove(slot);
			heldUntil.remove(slot);
		}
	}

	private final Map<Long, Tile> tiles = new HashMap<>();

	/** Whether this spot is being held for you: the only hold a newcomer doesn't walk into. */
	private boolean heldForYou(Tile t, int slot, int tick)
	{
		return t.held(slot, tick) && yourId >= 0 && Integer.valueOf(yourId).equals(t.heldFor.get(slot));
	}

	/** Diagnostics: how many times a player who already had a spot was given a different one. */
	long moves;

	/**
	 * Your own player's id when your character may be moved, else -1. You get the best spot on your
	 * tile (the front, where what you're doing looks right); whoever had it swaps with you, once.
	 */
	int localId = -1;

	/** Your player id whether or not your character is moved or here now: a spot held for you is never given away. */
	int yourId = -1;



	/**
	 * For the tile you are on, the spots standing between you and the camera. Nobody is put in one
	 * while there is anywhere else, and anyone found in one steps to a free spot if there is one.
	 */
	Map<Long, Set<Integer>> keepClear = new HashMap<>();

	/**
	 * For tiles near you, how many spots at the end of their list are spare: past the players-per-
	 * tile limit, and only for someone stepping out from between you and the camera when every
	 * other spot is taken. In a pile, nobody had anywhere to step to.
	 */
	Map<Long, Integer> spare = new HashMap<>();

	/**
	 * People busy with something (alching, an emote, smithing). When a tile has more people than
	 * spots, they are the ones left without one, rather than whoever came last.
	 */
	Set<Integer> busy = new HashSet<>();

	/**
	 * For the tile you're on, when just you and one other are about to be given spots there: the
	 * spot to take yourself, the one on the camera's side. Take the other and the one left, with
	 * the camera off to their side of you, stood between you and it, and was sent round to your
	 * far side instead of beside you. Chosen once, when the pair forms, and kept while you stay:
	 * turning the camera afterwards doesn't swap you.
	 */
	Map<Long, Integer> pairSideForYou = new HashMap<>();
	private long yourPickTile = Long.MIN_VALUE;
	private int yourPick;

	/**
	 * This tick, people who would have stood between you and the camera with nowhere else on
	 * their tile to go: they wait without a spot, like anyone past the players-per-tile limit.
	 */
	final Set<Integer> hiddenForYou = new HashSet<>();

	/**
	 * Whether you've stood still long enough for people in front of you to step aside. Until then
	 * nobody is moved for you, but newcomers and gap-filling still keep out of those spots.
	 */
	boolean stepAside = true;

	/**
	 * For those tiles, the order to try free spots in when someone must go elsewhere: nearest the
	 * tile's middle first, so they step a little back near their own booth rather than along the
	 * counter to the far end of it.
	 */
	Map<Long, List<Integer>> nearestFirst = new HashMap<>();

	/**
	 * People who stepped aside for you, and the spots they stepped out of. They stay put while that
	 * doesn't change: filling a gap next tick would move them a second time, since stepping aside
	 * picks the free spot nearest their booth and gap-filling the best spot on the tile.
	 */
	private final Set<Integer> asideForYou = new HashSet<>();

	/**
	 * Given a tile and a spot on it, the spots on that tile that would stand between someone there
	 * and the camera; empty when the camera isn't known. Whoever you swap places with is sent
	 * straight to a spot out of your way, rather than to yours and then aside again.
	 */
	java.util.function.BiFunction<Long, Integer, Set<Integer>> inFrontOf = (tile, spot) -> Collections.emptySet();
	/** Which way the camera looks, as the planner rounds it. Whoever stepped aside may move again when it changes. */
	int viewKey = -1;
	private int asideView = -2;
	private long asideTile = Long.MIN_VALUE;

	/**
	 * How long you must have been given a spot on a tile before you take the front one: none. You
	 * go straight to it rather than start off to the side and shuffle in a few seconds later.
	 * Walking past never counts: nobody is given a spot until they have stood still for two ticks.
	 */
	static final int LOCAL_SWAP_DELAY = 0;

	private long localTile = Long.MIN_VALUE;
	private int localSince;

	/**
	 * Work out this tick's spots.
	 *
	 * @param present  for each tile, the players standing there who may be moved
	 * @param capacityOf how many spots each tile has
	 * @param tick     this tick's number
	 * @return for each tile, each player's spot; players with no spot (the tile is full) are left out
	 */
	Map<Long, Map<Integer, Integer>> update(Map<Long, List<Integer>> present, ToIntFunction<Long> capacityOf, int tick)
	{
		boolean movedYou = false;
		hiddenForYou.clear();
		if (viewKey != asideView || localTile != asideTile)
		{
			asideForYou.clear();
			asideView = viewKey;
			asideTile = localTile;
		}
		// Tiles nobody is on any more: everyone there has left.
		for (Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Long, Tile> e = it.next();
			if (present.containsKey(e.getKey()))
			{
				continue;
			}
			Tile t = e.getValue();
			for (int id : new ArrayList<>(t.slotOf.keySet()))
			{
				t.vacate(id, tick, true);
			}
			if (!t.anyHeld(tick))
			{
				it.remove();
			}
		}

		boolean sawLocal = false;
		Map<Long, Map<Integer, Integer>> out = new HashMap<>();
		for (Map.Entry<Long, List<Integer>> e : present.entrySet())
		{
			Tile t = tiles.computeIfAbsent(e.getKey(), k -> new Tile());
			int all = capacityOf.applyAsInt(e.getKey());
			int capacity = all - Math.min(all, spare.getOrDefault(e.getKey(), 0));
			List<Integer> ids = new ArrayList<>(e.getValue());
			Collections.sort(ids);
			Set<Integer> here = new HashSet<>(ids);

			// People who left.
			for (int id : new ArrayList<>(t.slotOf.keySet()))
			{
				if (!here.contains(id))
				{
					t.vacate(id, tick, true);
				}
			}
			// Spots that no longer exist because the tile got smaller.
			for (int id : new ArrayList<>(t.slotOf.keySet()))
			{
				if (t.slotOf.get(id) >= all)
				{
					t.vacate(id, tick, false);
				}
			}
			// Forget holds that have run out or point past the end.
			t.heldUntil.entrySet().removeIf(h -> h.getValue() < tick || h.getKey() >= all);
			t.heldFor.keySet().retainAll(t.heldUntil.keySet());

			// People coming back to a spot held for them.
			for (int id : ids)
			{
				if (t.slotOf.containsKey(id))
				{
					continue;
				}
				for (Map.Entry<Integer, Integer> h : new ArrayList<>(t.heldFor.entrySet()))
				{
					if (h.getValue() == id && !t.occupant.containsKey(h.getKey()))
					{
						t.assign(id, h.getKey());
						break;
					}
				}
			}
			// Newcomers take the best free spot that isn't being held. You go first, so walking in
			// beside someone settles both of you in one step: without that, whoever has the lower
			// player id takes the front spot and you swap with them a few ticks later, which looks
			// like the pair shuffling about once for no reason.
			List<Integer> arriving = new ArrayList<>(ids);
			// Those not busy with anything first, so when there aren't spots for everyone arriving
			// the ones left waiting are the busy ones (a stable sort: otherwise in the order given).
			arriving.sort(Comparator.comparingInt(id -> busy.contains(id) ? 1 : 0));
			if (arriving.remove((Integer) localId))
			{
				arriving.add(0, localId);
			}
			int yours = yourPickTile == e.getKey() ? yourPick : 0;
			Set<Integer> inFront = keepClear.getOrDefault(e.getKey(), Collections.emptySet());
			for (int id : arriving)
			{
				if (t.slotOf.containsKey(id))
				{
					continue;
				}
				if (id == localId && yourPickTile != e.getKey())
				{
					Integer side = pairSideForYou.get(e.getKey());
					yourPick = side != null && side < capacity ? side : 0;
					yourPickTile = e.getKey();
					yours = yourPick;
				}
				if (id == localId && yours < capacity && !t.occupant.containsKey(yours) && !t.held(yours, tick))
				{
					t.assign(id, yours);
					movedYou = true;
					continue;
				}
				// The best free spot out of your way, or, when there is none, any free spot. A spot
				// held for someone who stepped away counts as free to a newcomer: kept for them, it
				// had a newcomer stand behind (at a bank) for five seconds and then step forward
				// into it, when walking straight into the gap is what anyone would do. A spot held
				// for you is never taken.
				int spot = -1;
				for (int s : order(e.getKey(), capacity))
				{
					if (spot < 0 && s < capacity && !t.occupant.containsKey(s) && !heldForYou(t, s, tick) && !inFront.contains(s))
					{
						spot = s;
					}
				}
				// Once you've settled, someone arriving at a full tile waits rather than stand
				// between you and the camera.
				for (int s = 0; s < capacity && spot < 0; s++)
				{
					spot = !t.occupant.containsKey(s) && !heldForYou(t, s, tick) && !(stepAside && inFront.contains(s)) ? s : -1;
				}
				if (spot >= 0)
				{
					t.assign(id, spot);
					movedYou |= id == localId;
				}
			}
			// You always get a spot. On a full tile you used to be left standing in the middle, with
			// everyone in a ring round you, until someone left. Whoever is busy with something gives
			// theirs up first, else whoever has the spot furthest back.
			if (localId >= 0 && here.contains(localId) && !t.slotOf.containsKey(localId) && !t.occupant.isEmpty())
			{
				int from = -1;
				for (Map.Entry<Integer, Integer> o : t.occupant.entrySet())
				{
					if (o.getKey() >= capacity)
					{
						continue;
					}
					boolean better = from < 0
						|| (busy.contains(o.getValue()) && !busy.contains(t.occupant.get(from)))
						|| (busy.contains(o.getValue()) == busy.contains(t.occupant.get(from)) && o.getKey() > from);
					if (better)
					{
						from = o.getKey();
					}
				}
				if (from >= 0)
				{
					t.vacate(t.occupant.get(from), tick, false);
					t.assign(localId, from);
					movedYou = true;
					moves++;
				}
			}
			// More people than spots: those who aren't busy with anything (alching, an emote) come
			// first in the queue for a free spot, so the ones left waiting are the busy ones. But
			// nobody is turned out of a spot they already have for a newcomer who isn't busy:
			// someone busy can't walk without their spell being cut short, so they slid off to the
			// middle of the tile mid-cast, which at a counter is behind the row.
			// You get the best spot going the moment you have one: a free one if there is one, else
			// whoever has the front spot takes yours. A front spot being held for someone who stepped
			// away is yours too: you'd otherwise stand waiting for their hold to run out, and if they
			// come back they take the next free spot like anyone arriving.
			Integer mine = t.slotOf.get(localId);
			if (mine != null)
			{
				sawLocal = true;
				if (localTile != e.getKey())
				{
					localTile = e.getKey();
					localSince = tick;
				}
				int free = -1;
				for (int s = 0; s < mine; s++)
				{
					if (free < 0 && !t.occupant.containsKey(s) && !t.held(s, tick))
					{
						free = s;
					}
				}
				if (mine != yours && tick - localSince >= LOCAL_SWAP_DELAY)
				{
					// Straight to your spot in one step. Whoever was there takes the best free spot if
					// there is one, else yours, so neither of you is moved again next tick.
					Integer other = t.occupant.get(yours);
					t.vacate(localId, tick, false);
					if (other != null)
					{
						t.vacate(other, tick, false);
						int to = free >= 0 && free != yours ? free : mine;
						Set<Integer> wouldHide = inFrontOf.apply(e.getKey(), yours);
						if (wouldHide.contains(to))
						{
							for (int s : order(e.getKey(), capacity))
							{
								if (s < capacity && s != yours && !t.occupant.containsKey(s) && !t.held(s, tick)
									&& !wouldHide.contains(s))
								{
									to = s;
									asideForYou.add(other);
									break;
								}
							}
						}
						t.assign(other, to);
					}
					t.assign(localId, yours);
					movedYou = true;
					moves++;
				}
			}
			// Fill gaps from the back: the player in the worst spot moves into the best free one -
			// never one standing between you and the camera.
			for (int s = 0; s < capacity; s++)
			{
				if (t.occupant.containsKey(s) || t.held(s, tick) || inFront.contains(s))
				{
					continue;
				}
				int worst = -1;
				for (int slot : t.occupant.keySet())
				{
					// Never you. Other people filling a gap is what keeps a crowd tidy, but you notice
					// your own character moving far more than anyone else's, so only arriving and
					// settling the camera somewhere new ever move you.
					if (t.occupant.get(slot) == localId || asideForYou.contains(t.occupant.get(slot)))
					{
						continue;
					}
					if (slot > s && slot > worst)
					{
						worst = slot;
					}
				}
				if (worst < 0)
				{
					break;
				}
				int mover = t.occupant.get(worst);
				t.vacate(mover, tick, false);
				t.assign(mover, s);
				moves++;
			}

			out.put(e.getKey(), new HashMap<>(t.slotOf));
		}

		// Anyone standing between you and the camera steps to a free spot out of the way. Only once
		// you're in your place: on a tick you moved, the spots in front of you are about to change,
		// and people stepped aside for where you were only to step again for where you are. Only a
		// spot nobody has is ever used, so nobody is left without one for your sake; someone with
		// nowhere else to go simply stays.
		for (Map.Entry<Long, Set<Integer>> c : movedYou || !stepAside ? Collections.<Long, Set<Integer>>emptyMap().entrySet() : keepClear.entrySet())
		{
			Tile t = tiles.get(c.getKey());
			if (t == null || !present.containsKey(c.getKey()))
			{
				continue;
			}
			int all = capacityOf.applyAsInt(c.getKey());
			int capacity = all - Math.min(all, spare.getOrDefault(c.getKey(), 0));
			Set<Integer> inFront = c.getValue();
			boolean changed = false;
			// An ordinary free spot if there is one, else a spare one past the players-per-tile limit.
			List<Integer> free = new ArrayList<>();
			for (int to : order(c.getKey(), all))
			{
				if (to < capacity)
				{
					free.add(to);
				}
			}
			for (int to : order(c.getKey(), all))
			{
				if (to >= capacity && to < all)
				{
					free.add(to);
				}
			}
			// Those who aren't busy with anything get the free spots first; whoever is left over
			// waits in the middle rather than stand in front of you. In a pile at an anvil or a
			// packed bank there is nowhere else, and you should be the one people can see.
			List<Integer> blocking = new ArrayList<>(inFront);
			blocking.sort(Comparator.comparingInt(s -> busy.contains(t.occupant.getOrDefault(s, -1)) ? 1 : 0));
			for (int s : blocking)
			{
				Integer who = t.occupant.get(s);
				if (who == null || who == localId)
				{
					continue;
				}
				boolean stepped = false;
				for (int to : free)
				{
					if (!stepped && !t.occupant.containsKey(to) && !t.held(to, tick) && !inFront.contains(to))
					{
						stepped = true;
						t.vacate(who, tick, false);
						t.assign(who, to);
						asideForYou.add(who);
						moves++;
						changed = true;
						break;
					}
				}
				if (!stepped)
				{
					t.vacate(who, tick, false);
					hiddenForYou.add(who);
					moves++;
					changed = true;
				}
			}
			if (changed)
			{
				out.put(c.getKey(), new HashMap<>(t.slotOf));
			}
		}
		if (!sawLocal)
		{
			localTile = Long.MIN_VALUE;
			yourPickTile = Long.MIN_VALUE;
		}
		return out;
	}

	/** The spots of a tile in the order to try them: nearest its middle first where that's known. */
	private List<Integer> order(long tile, int capacity)
	{
		List<Integer> known = nearestFirst.get(tile);
		if (known != null)
		{
			return known;
		}
		List<Integer> plain = new ArrayList<>(capacity);
		for (int s = 0; s < capacity; s++)
		{
			plain.add(s);
		}
		return plain;
	}

	void clear()
	{
		tiles.clear();
	}
}
