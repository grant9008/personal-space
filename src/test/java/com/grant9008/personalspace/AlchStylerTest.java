package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.AnimationID;
import org.junit.Assert;
import org.junit.Test;

public class AlchStylerTest
{
	/** A player that only knows its animation and frame. */
	private static final class FakePlayer
	{
		int animation;
		int frame = 7;
		int id = 5;
		/** Spell effects on them: slot to spotanim id. */
		final java.util.Map<Integer, Integer> effects = new java.util.LinkedHashMap<>();

		Player proxy()
		{
			return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, args) ->
			{
				switch (m.getName())
				{
					case "getAnimation":
						return animation;
					case "getId":
						return id;
					case "getSpotAnims":
						return spotAnims();
					case "removeSpotAnim":
						effects.remove((Integer) args[0]);
						return null;
					case "createSpotAnim":
						effects.put((Integer) args[0], (Integer) args[1]);
						return null;
					case "setAnimation":
						animation = (Integer) args[0];
						return null;
					case "setAnimationFrame":
						frame = (Integer) args[0];
						return null;
					case "hashCode":
						return System.identityHashCode(p);
					case "equals":
						return p == args[0];
					default:
						return null;
				}
			});
		}

		private net.runelite.api.IterableHashTable<net.runelite.api.ActorSpotAnim> spotAnims()
		{
			java.util.List<net.runelite.api.ActorSpotAnim> list = new java.util.ArrayList<>();
			for (java.util.Map.Entry<Integer, Integer> e : effects.entrySet())
			{
				int slot = e.getKey();
				int spotanim = e.getValue();
				list.add((net.runelite.api.ActorSpotAnim) Proxy.newProxyInstance(Player.class.getClassLoader(),
					new Class<?>[]{net.runelite.api.ActorSpotAnim.class}, (q, m, a) ->
						m.getName().equals("getId") ? (Object) spotanim : m.getName().equals("getHash") ? (Object) (long) slot : null));
			}
			@SuppressWarnings("unchecked")
			net.runelite.api.IterableHashTable<net.runelite.api.ActorSpotAnim> table = (net.runelite.api.IterableHashTable<net.runelite.api.ActorSpotAnim>)
				Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{net.runelite.api.IterableHashTable.class},
					(q, m, a) -> m.getName().equals("iterator") ? list.iterator() : null);
			return table;
		}
	}

	/** The game cycle the fake client is on. */
	private static int cycle;

	private static Client client(Player you)
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(p, m, args) -> m.getName().equals("getLocalPlayer") ? you : m.getName().equals("getGameCycle") ? (Object) cycle : null);
	}

	@Test
	public void anAlchGlowArrivingAfterTheCastIsTakenOffToo()
	{
		cycle = 1000;
		FakePlayer alcher = new FakePlayer();
		alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
		alcher.effects.put(3, 86);
		Player p = alcher.proxy();
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		styler.noticed(p);
		styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
		Assert.assertEquals(-1, alcher.animation);
		// The glow comes a moment later, and nobody says so: it's taken off at the next frame.
		cycle += 30;
		alcher.effects.put(0, 113);
		styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
		Assert.assertFalse("the alch glow is still there", alcher.effects.containsValue(113));
		Assert.assertTrue("another effect was taken off", alcher.effects.containsValue(86));
		// Long after the cast, nobody's effects are touched any more.
		cycle += AlchStyler.EFFECT_WATCH_CYCLES + 1;
		styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
		alcher.effects.put(0, 113);
		styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
		Assert.assertTrue(alcher.effects.containsValue(113));
	}

	@Test
	public void stillAlchersJustStand()
	{
		FakePlayer alcher = new FakePlayer();
		alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
		Player p = alcher.proxy();
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		styler.noticed(p);
		styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
		Assert.assertEquals(-1, alcher.animation);
		Assert.assertEquals(1, styler.restyled);
	}

	@Test
	public void partyAlchersDoOneOfTheEmotes()
	{
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		for (int cast = 0; cast < 50; cast++)
		{
			FakePlayer alcher = new FakePlayer();
			alcher.animation = cast % 2 == 0 ? AnimationID.HUMAN_CASTLOWLVLALCHEMY : AnimationID.HUMAN_CASTHIGHLVLALCHEMY_FIRE;
			styler.noticed(alcher.proxy());
			styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
			int emote = alcher.animation;
			Assert.assertTrue("cast " + cast + ": " + emote, Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == emote));
			Assert.assertEquals("from the start", 0, alcher.frame);
		}
	}

	@Test
	public void yourOwnAlchingAndEveryoneElsesOtherAnimationsAreLeftAlone()
	{
		FakePlayer you = new FakePlayer();
		you.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
		FakePlayer smith = new FakePlayer();
		smith.animation = 898;
		Player yours = you.proxy();
		AlchStyler styler = new AlchStyler(client(yours));
		styler.noticed(yours);
		styler.noticed(smith.proxy());
		styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
		Assert.assertEquals("your alch", AnimationID.HUMAN_CASTHIGHLVLALCHEMY, you.animation);
		Assert.assertEquals("smithing", 898, smith.animation);
		Assert.assertEquals(0, styler.restyled);
	}

	@Test
	public void normalOrSwitchedOffLeavesAlchersAsTheyAre()
	{
		for (boolean normal : new boolean[]{true, false})
		{
			FakePlayer alcher = new FakePlayer();
			alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
			styler.noticed(alcher.proxy());
			styler.apply(normal ? PersonalSpaceConfig.Alchers.NORMAL : PersonalSpaceConfig.Alchers.STILL, normal);
			Assert.assertEquals(AnimationID.HUMAN_CASTHIGHLVLALCHEMY, alcher.animation);
		}
	}

	@Test
	public void eachCastIsARandomEmoteNeverTheSameTwiceRunning()
	{
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		int[] seen = new int[AlchStyler.PARTY_EMOTES.length];
		int last = -1;
		for (int cast = 0; cast < 1200; cast++)
		{
			int emote = styler.nextEmote(42);
			Assert.assertNotEquals("cast " + cast + " repeated the last emote", last, emote);
			for (int i = 0; i < seen.length; i++)
			{
				seen[i] += AlchStyler.PARTY_EMOTES[i] == emote ? 1 : 0;
			}
			last = emote;
		}
		for (int i = 0; i < seen.length; i++)
		{
			Assert.assertTrue("emote " + AlchStyler.PARTY_EMOTES[i] + " came up " + seen[i] + " times in 1200", seen[i] > 800 / seen.length);
		}
	}

	@Test
	public void theCrabDanceTakesSpinsPlacePlayedOnceWithNoGlow()
	{
		// The emote tab's Crab dance instead of Spin, and the one that ends by itself, like the rest.
		Assert.assertTrue(Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.HUMAN_EMOTE_CRABDANCE));
		Assert.assertFalse("Spin is still in", Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.EMOTE_DANCE_SPIN));
		Assert.assertFalse("the looping crab dance", Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.HUMAN_EMOTE_CRABDANCE_LOOP));
		// The zombie dance and the goblin salute too, each the one that ends by itself.
		Assert.assertTrue(Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.ZOMBIE_DANCE));
		Assert.assertTrue(Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.HUMAN_CAVE_GOBLIN_DANCE));
		Assert.assertFalse("the looping zombie dance", Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.ZOMBIE_DANCE_LOOP));
		Assert.assertFalse("the looping goblin salute", Arrays.stream(AlchStyler.PARTY_EMOTES).anyMatch(e -> e == AnimationID.HUMAN_CAVE_GOBLIN_DANCE_LOOP));
		Assert.assertEquals("an emote listed twice", AlchStyler.PARTY_EMOTES.length, Arrays.stream(AlchStyler.PARTY_EMOTES).distinct().count());
		// A crab dance straight after the Party emote: from its first frame, and the glow goes.
		cycle = 7000;
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		FakePlayer alcher = new FakePlayer();
		Player p = alcher.proxy();
		boolean afterParty = false;
		boolean seen = false;
		for (int cast = 0; cast < 3000 && !seen; cast++)
		{
			alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			alcher.frame = 7;
			styler.noticed(p);
			styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
			if (alcher.animation == AnimationID.HUMAN_EMOTE_CRABDANCE && afterParty)
			{
				Assert.assertEquals("from the start", 0, alcher.frame);
				Assert.assertFalse("the Party glow under a crab dance", alcher.effects.containsKey(AlchStyler.PARTY_GLOW_SLOT));
				seen = true;
			}
			afterParty = alcher.animation == AnimationID.EMOTE_PARTY;
		}
		Assert.assertTrue("no crab dance after the Party emote in 3000 casts", seen);
	}

	@Test
	public void thePartyEmoteBringsItsGlowAndTheNextEmoteTakesItAway()
	{
		cycle = 5000;
		AlchStyler styler = new AlchStyler(client(new FakePlayer().proxy()));
		FakePlayer alcher = new FakePlayer();
		Player p = alcher.proxy();
		boolean sawParty = false;
		boolean sawGlowGo = false;
		for (int cast = 0; cast < 200; cast++)
		{
			alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			styler.noticed(p);
			styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
			boolean glow = AlchStyler.PARTY_GLOW == (alcher.effects.getOrDefault(AlchStyler.PARTY_GLOW_SLOT, -1));
			if (alcher.animation == AnimationID.EMOTE_PARTY)
			{
				sawParty = true;
				Assert.assertTrue("cast " + cast + ": the Party emote without its glow", glow);
			}
			else
			{
				Assert.assertFalse("cast " + cast + ": the glow stayed under " + alcher.animation, glow);
				sawGlowGo |= sawParty;
			}
		}
		Assert.assertTrue(sawParty && sawGlowGo);
		// Dancing done, or Party switched off: no glow left under them.
		while (alcher.animation != AnimationID.EMOTE_PARTY)
		{
			alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			styler.noticed(p);
			styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
		}
		alcher.animation = -1;
		styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
		Assert.assertFalse("the glow outlasted the dance", alcher.effects.containsKey(AlchStyler.PARTY_GLOW_SLOT));
		while (alcher.animation != AnimationID.EMOTE_PARTY)
		{
			alcher.animation = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			styler.noticed(p);
			styler.apply(PersonalSpaceConfig.Alchers.PARTY, true);
		}
		styler.apply(PersonalSpaceConfig.Alchers.NORMAL, true);
		Assert.assertFalse("the glow outlasted Party", alcher.effects.containsKey(AlchStyler.PARTY_GLOW_SLOT));
	}

	@Test
	public void noEmoteIsAnAlchAndEveryAlchCastIsKnown()
	{
		for (int e : AlchStyler.PARTY_EMOTES)
		{
			Assert.assertFalse(AlchStyler.CASTS.contains(e));
		}
		Assert.assertEquals(8, AlchStyler.PARTY_EMOTES.length);
		Assert.assertTrue("alchs, superheat, charge orb, enchants", AlchStyler.CASTS.containsAll(Arrays.asList(712, 713, 725, 726, 719, 931)));
		Assert.assertTrue(AlchStyler.EFFECTS.containsAll(Arrays.asList(112, 113, 148, 149, 150, 151, 152, 114, 238)));
	}
}
