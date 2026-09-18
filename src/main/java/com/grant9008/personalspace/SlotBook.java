package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
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

	/** Diagnostics: how many times a player who already had a spot was given a different one. */
	long moves;

	/**
	 * Your own player's id when your character may be moved, else -1. You get the best spot on your
	 * tile (the front, where what you're doing looks right); whoever had it swaps with you, once.
	 */
	int localId = -1;



	/**
	 * For the tile you are on, the spots standing between you and the camera. Nobody is put in one
	 * while there is anywhere else, and anyone found in one steps to a free spot if there is one.
	 */
	Map<Long, Set<Integer>> keepClear = new HashMap<>();

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
	 * How long you must have stood on a tile before you take the front spot: 4 ticks, about 2.5
	 * seconds. Stopping for a moment on your way past doesn't push anyone around.
	 */
	static final int LOCAL_SWAP_DELAY = 4;

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
			int capacity = capacityOf.applyAsInt(e.getKey());
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
				if (t.slotOf.get(id) >= capacity)
				{
					t.vacate(id, tick, false);
				}
			}
			// Forget holds that have run out or point past the end.
			t.heldUntil.entrySet().removeIf(h -> h.getValue() < tick || h.getKey() >= capacity);
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
			if (arriving.remove((Integer) localId))
			{
				arriving.add(0, localId);
			}
			int yours = 0;
			Set<Integer> inFront = keepClear.getOrDefault(e.getKey(), Collections.emptySet());
			for (int id : arriving)
			{
				if (t.slotOf.containsKey(id))
				{
					continue;
				}
				if (id == localId && yours < capacity && !t.occupant.containsKey(yours) && !t.held(yours, tick))
				{
					t.assign(id, yours);
					movedYou = true;
					continue;
				}
				// The best free spot out of your way, or, when there is none, any free spot.
				int spot = -1;
				for (int s : order(e.getKey(), capacity))
				{
					if (spot < 0 && s < capacity && !t.occupant.containsKey(s) && !t.held(s, tick) && !inFront.contains(s))
					{
						spot = s;
					}
				}
				for (int s = 0; s < capacity && spot < 0; s++)
				{
					spot = !t.occupant.containsKey(s) && !t.held(s, tick) ? s : -1;
				}
				if (spot >= 0)
				{
					t.assign(id, spot);
					movedYou |= id == localId;
				}
			}
			// Once you've stood here a moment you get the best spot going: a free one if there is
			// one, else whoever has the front spot takes yours. Not while a better spot is being held
			// for someone: they may come back, and a swap now would only be undone later.
			Integer mine = t.slotOf.get(localId);
			if (mine != null)
			{
				sawLocal = true;
				if (localTile != e.getKey())
				{
					localTile = e.getKey();
					localSince = tick;
				}
				boolean holdAhead = false;
				int free = -1;
				for (int s = 0; s < mine; s++)
				{
					holdAhead |= t.held(s, tick);
					if (free < 0 && !t.occupant.containsKey(s))
					{
						free = s;
					}
				}
				boolean clear = yours == 0 ? !holdAhead : !t.held(yours, tick);
				if (mine != yours && clear && tick - localSince >= LOCAL_SWAP_DELAY)
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
			int capacity = capacityOf.applyAsInt(c.getKey());
			Set<Integer> inFront = c.getValue();
			boolean changed = false;
			for (int s : inFront)
			{
				Integer who = t.occupant.get(s);
				if (who == null || who == localId)
				{
					continue;
				}
				for (int to : order(c.getKey(), capacity))
				{
					if (to < capacity && !t.occupant.containsKey(to) && !t.held(to, tick) && !inFront.contains(to))
					{
						t.vacate(who, tick, false);
						t.assign(who, to);
						asideForYou.add(who);
						moves++;
						changed = true;
						break;
					}
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
