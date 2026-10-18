package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Player;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;

/**
 * Other players casting skilling spells (High or Low Alchemy, Superheat Item, Charge Orb, and
 * enchanting jewellery or bolts), as "Alchers behavior" asks: drawn just standing, with no spell
 * effect (Still), or doing a random emote each cast (Party). A Grand Exchange full of alchers
 * flickered gold all day.
 *
 * <p>Only other players, and only what this client draws: each cast the server sends brings their
 * real animation back, and it is changed again before the frame is drawn. Changed between frames,
 * not inside the game's own animation event, as the client is mid-way through setting it then.
 * {@code Actor.setAnimation} tells nobody, so no other plugin hears of an animation that never
 * happened.
 */
final class AlchStyler
{
	/**
	 * The casts: High and Low Alchemy (and the same with a fire staff's own animation), Superheat
	 * Item, Charge Orb, and enchanting rings, amulets and crossbow bolts.
	 */
	static final Set<Integer> CASTS = Set.of(
		AnimationID.HUMAN_CASTLOWLVLALCHEMY, AnimationID.HUMAN_CASTHIGHLVLALCHEMY,
		AnimationID.HUMAN_CASTLOWLVLALCHEMY_FIRE, AnimationID.HUMAN_CASTHIGHLVLALCHEMY_FIRE,
		AnimationID.HUMAN_CASTSUPERHEATITEM, AnimationID.HUMAN_CASTCHARGEORB,
		AnimationID.HUMAN_ENCHANTAMULETLVL1, AnimationID.HUMAN_ENCHANTAMULETLVL2, AnimationID.HUMAN_ENCHANTAMULETLVL3,
		AnimationID.HUMAN_CAST_ENCHANTRING, AnimationID.HUMAN_XBOW_ENCHANT_ARROWTIP,
		AnimationID.HUMAN_XBOW_ENCHANT_ARROWTIP_WALKMERGE);

	/** Their spell effects, drawn round the caster. */
	static final Set<Integer> EFFECTS = Set.of(
		SpotanimID.LOWLVLALCHEMY_CASTING, SpotanimID.HIGHLVLALCHEMY_CASTING,
		SpotanimID.POH_LOWLVLALCHEMY_CASTING, SpotanimID.POH_HIGHLVLALCHEMY_CASTING,
		SpotanimID.SPOTANIM_CASTLOWLVLALCHEMY_FIRE, SpotanimID.SPOTANIM_CASTHIGHLVLALCHEMY_FIRE,
		SpotanimID.SUPERHEATITEM_CASTING,
		SpotanimID.CHARGEWATERORB_CASTING, SpotanimID.CHARGEWINDORB_CASTING,
		SpotanimID.CHARGEEARTHORB_CASTING, SpotanimID.CHARGEFIREORB_CASTING,
		SpotanimID.ENCHANT_AMULET_LVL1, SpotanimID.ENCHANT_AMULET_LVL2, SpotanimID.ENCHANT_AMULET_LVL3,
		SpotanimID.ENCHANT_AMULET2_LVL4, SpotanimID.ENCHANT_AMULET2_LVL5, SpotanimID.ENCHANT_AMULET2_LVL6,
		SpotanimID.ENCHANT_RING, SpotanimID.XBOWS_ENCHANTING_TIPS_SPOT);

	/** Party: dance, jig, spin, cheer, jump for joy and the Party emote, with its glowing floor. */
	static final int[] PARTY_EMOTES = {
		AnimationID.EMOTE_DANCE, AnimationID.EMOTE_DANCE_SCOTTISH, AnimationID.EMOTE_DANCE_SPIN,
		AnimationID.EMOTE_CHEER, AnimationID.EMOTE_JUMP_WITH_JOY, AnimationID.EMOTE_PARTY,
	};
	/** The Party emote's floor glow, put under whoever does it (it changes colour as they dance). */
	static final int PARTY_GLOW = SpotanimID.FX_EMOTE_PARTY01_ACTIVE;
	/**
	 * The slot the glow goes in, among a player's spell effects: well clear of the game's own, and
	 * so taken off again when their next cast is another emote.
	 */
	static final int PARTY_GLOW_SLOT = 0x5053;

	private final Client client;
	/** Players whose animation or spell effect changed since the last frame. */
	private final Set<Player> changed = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Players restyled lately, and the game cycle until which their spell effect is still looked
	 * for: it can arrive a moment after the cast, without the game saying so, and the alch glow
	 * stayed with Still on.
	 */
	private final java.util.Map<Player, Integer> casting = new IdentityHashMap<>();
	/** How long after a cast its effect is still looked for, in game cycles (4 s). */
	static final int EFFECT_WATCH_CYCLES = 200;
	private final Random random = new Random();
	/** Players given the Party emote's glow: it's taken off as soon as they're doing anything else. */
	private final Set<Player> glowing = Collections.newSetFromMap(new IdentityHashMap<>());
	/** The emote each player last did for a cast (Party), so the next one is always another. */
	private final int[] lastEmote = new int[OffsetTable.CAPACITY];

	/** Diagnostics: casts drawn still or as an emote. */
	long restyled;

	AlchStyler(Client client)
	{
		this.client = client;
	}

	/** From AnimationChanged and GraphicChanged: look at this actor before the next frame. Client thread. */
	void noticed(Actor actor)
	{
		if (actor instanceof Player && actor != client.getLocalPlayer())
		{
			changed.add((Player) actor);
		}
	}

	/** Before each frame is drawn: restyle whoever started alching, if the setting asks and may. Client thread. */
	void apply(PersonalSpaceConfig.Alchers style, boolean allowed)
	{
		if (changed.isEmpty() && casting.isEmpty() && glowing.isEmpty())
		{
			return;
		}
		if (style == PersonalSpaceConfig.Alchers.NORMAL || !allowed)
		{
			changed.clear();
			casting.clear();
			endGlows(true);
			return;
		}
		Player you = client.getLocalPlayer();
		int cycle = client.getGameCycle();
		for (Player p : changed)
		{
			if (p != you)
			{
				restyle(p, style, cycle);
			}
		}
		changed.clear();
		endGlows(false);
		for (java.util.Iterator<java.util.Map.Entry<Player, Integer>> it = casting.entrySet().iterator(); it.hasNext(); )
		{
			java.util.Map.Entry<Player, Integer> e = it.next();
			if (cycle - e.getValue() > 0)
			{
				it.remove();
			}
			else
			{
				removeEffects(e.getKey());
			}
		}
	}

	/** Take the Party emote's glow off everyone given it ({@code all}), or off those no longer doing it. */
	private void endGlows(boolean all)
	{
		for (java.util.Iterator<Player> it = glowing.iterator(); it.hasNext(); )
		{
			Player p = it.next();
			if (all || p.getAnimation() != AnimationID.EMOTE_PARTY)
			{
				p.removeSpotAnim(PARTY_GLOW_SLOT);
				it.remove();
			}
		}
	}

	/** A random party emote, never the one this player did last cast: the same twice looked like nothing changed. */
	int nextEmote(int id)
	{
		int n = PARTY_EMOTES.length;
		int pick = random.nextInt(n);
		boolean known = id >= 0 && id < lastEmote.length;
		if (known && PARTY_EMOTES[pick] == lastEmote[id])
		{
			pick = (pick + 1 + random.nextInt(n - 1)) % n;
		}
		if (known)
		{
			lastEmote[id] = PARTY_EMOTES[pick];
		}
		return PARTY_EMOTES[pick];
	}

	private void restyle(Player p, PersonalSpaceConfig.Alchers style, int cycle)
	{
		if (CASTS.contains(p.getAnimation()))
		{
			casting.put(p, cycle + EFFECT_WATCH_CYCLES);
			if (style == PersonalSpaceConfig.Alchers.PARTY)
			{
				int emote = nextEmote(p.getId());
				p.setAnimation(emote);
				p.setAnimationFrame(0);
				if (emote == AnimationID.EMOTE_PARTY)
				{
					p.createSpotAnim(PARTY_GLOW_SLOT, PARTY_GLOW, 0, 0);
					glowing.add(p);
				}
				else
				{
					p.removeSpotAnim(PARTY_GLOW_SLOT);
					glowing.remove(p);
				}
			}
			else
			{
				p.setAnimation(-1);
			}
			restyled++;
		}
		removeEffects(p);
	}

	/** Take this player's skilling-spell effects off them. */
	private static void removeEffects(Player p)
	{
		IterableHashTable<ActorSpotAnim> effects = p.getSpotAnims();
		if (effects == null)
		{
			return;
		}
		List<Integer> slots = new ArrayList<>(1);
		for (ActorSpotAnim effect : effects)
		{
			if (effect != null && EFFECTS.contains(effect.getId()))
			{
				slots.add((int) effect.getHash());
			}
		}
		for (int slot : slots)
		{
			p.removeSpotAnim(slot);
		}
	}
}
