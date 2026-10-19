package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.AnimationID;
import org.junit.Assert;
import org.junit.Test;

/**
 * Two or three on a tile, one of them alching (or enchanting, charging orbs, superheating): the
 * caster is drawn where they really stand, and the others round them.
 */
public class CasterInTheMiddleTest
{
	private static final long TILE = StackRegistry.key(0, 50, 50);
	private static final int YOU = 999;

	private static CrowdPlanner.Surroundings surroundings(boolean obstacle, boolean counter)
	{
		return new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz) { return true; }
			public boolean facesObstacle(long t, double a) { return obstacle; }
			public boolean isCounter(long t, double a) { return counter; }
			public boolean facesFire(long t, double a) { return false; }
			public List<int[]> firesNear(long t) { return new ArrayList<>(); }
		};
	}

	private static final CrowdPlanner.Surroundings OPEN = surroundings(false, false);

	/** These ids on {@link #TILE}, all facing north, the casters busy. */
	private static List<StackSpreader.Entry> onTheTile(IntPredicate busy, int... ids)
	{
		List<StackSpreader.Entry> out = new ArrayList<>();
		for (int id : ids)
		{
			out.add(new StackSpreader.Entry(id, TILE, id == YOU, 1024, busy.test(id)));
		}
		return out;
	}

	private static CrowdPlanner.Plan tick(CrowdPlanner planner, int t, List<StackSpreader.Entry> still)
	{
		return planner.plan(still, id -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.DEFAULT_STACK, true, true, t, OPEN);
	}

	private static Map<Integer, int[]> spots(CrowdPlanner.Plan plan)
	{
		Map<Integer, int[]> out = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertNull("placed twice", out.put(p.id, new int[]{p.dx, p.dz}));
		}
		return out;
	}

	private static boolean inTheMiddle(int[] spot)
	{
		return spot != null && spot[0] == 0 && spot[1] == 0;
	}

	private static double angleBetween(int[] a, int[] b)
	{
		double d = Math.abs(Math.atan2(a[1], a[0]) - Math.atan2(b[1], b[0]));
		return Math.toDegrees(Math.min(d, 2 * Math.PI - d));
	}

	/** Nobody stands within a body's width of anybody else, a caster in the middle included. */
	private static void assertNobodyInsideAnybody(String when, Map<Integer, int[]> at)
	{
		List<Map.Entry<Integer, int[]>> all = new ArrayList<>(at.entrySet());
		for (int i = 0; i < all.size(); i++)
		{
			for (int j = i + 1; j < all.size(); j++)
			{
				int[] a = all.get(i).getValue();
				int[] b = all.get(j).getValue();
				double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
				Assert.assertTrue(when + ": " + all.get(i).getKey() + " and " + all.get(j).getKey() + " are " + Math.round(d) + " apart",
					d >= CrowdPlanner.NEIGHBOUR_GAP);
			}
		}
	}

	@Test
	public void aCasterKeepsTheMiddleAsOthersComeAndGo()
	{
		// Alching alone; someone stops beside them, then a third, then they leave one by one. The
		// alcher stays exactly where they stand, and the others are moved round them.
		CrowdPlanner planner = new CrowdPlanner();
		planner.casting = id -> id == 1;
		planner.midAction = id -> id == 1;
		IntPredicate busy = id -> id == 1;
		Map<Integer, int[]> first = null;
		for (int t = 1; t <= 40; t++)
		{
			List<StackSpreader.Entry> still = t < 5 ? onTheTile(busy, 1) : t < 12 ? onTheTile(busy, 1, 2)
				: t < 25 ? onTheTile(busy, 1, 2, 3) : onTheTile(busy, 1, 3);
			CrowdPlanner.Plan plan = tick(planner, t, still);
			Map<Integer, int[]> at = spots(plan);
			if (t < 5)
			{
				Assert.assertTrue("alone, nobody is moved", at.isEmpty());
				continue;
			}
			Assert.assertTrue("tick " + t + ": the alcher was moved to " + Arrays.toString(at.get(1)), inTheMiddle(at.get(1)));
			Assert.assertTrue(plan.castersInTheMiddle.contains(1));
			Assert.assertTrue("tick " + t + ": the shape says so", plan.tiles.get(TILE).shape.endsWith("caster in the middle"));
			assertNobodyInsideAnybody("tick " + t, at);
			if (t == 24)
			{
				first = at;
			}
			if (t >= 25)
			{
				// The one who stays doesn't walk round the alcher, or through them, to fill the gap.
				Assert.assertTrue("tick " + t + ": 3 went round the alcher", angleBetween(at.get(3), first.get(3)) < 1);
			}
		}
	}

	/** A player who only knows their id and animation, as AlchStyler sees them. */
	private static Player player(int id, int[] animation)
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getAnimation":
					return animation[0];
				case "setAnimation":
					animation[0] = (Integer) args[0];
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

	private static Client client(Player you, int[] cycle)
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(p, m, args) -> m.getName().equals("getLocalPlayer") ? you : m.getName().equals("getGameCycle") ? (Object) cycle[0] : null);
	}

	@Test
	public void theMiddleIsKeptThroughThePausesBetweenCastsAndGivenUpOnceTheyStop()
	{
		// High Alchemy every five ticks, drawn still (Alchers behavior), for 40 ticks; someone stops
		// beside them. Between casts the alcher looks idle and the game shows no cast, but they keep
		// the middle; once they've stopped a while they get a spot like anyone.
		int[] cycle = {0};
		int[] animation = {-1};
		Player alcher = player(1, animation);
		AlchStyler styler = new AlchStyler(client(player(2, new int[]{-1}), cycle));
		CrowdPlanner planner = new CrowdPlanner();
		planner.casting = id -> styler.castWithin(id, cycle[0], PersonalSpacePlugin.CASTING_CYCLES);
		planner.midAction = id -> id == 1 && animation[0] != -1;
		int lastCast = 0;
		for (int t = 1; t <= 70; t++)
		{
			cycle[0] = t * 30;
			if (t % 5 == 1 && t <= 41)
			{
				animation[0] = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
				styler.noticed(alcher);
				lastCast = t;
			}
			// Planned at the game tick, before the frame where the cast is drawn still.
			CrowdPlanner.Plan plan = tick(planner, t, t < 5 ? onTheTile(id -> true, 1) : onTheTile(id -> id == 1, 1, 2));
			styler.apply(PersonalSpaceConfig.Alchers.STILL, true);
			Assert.assertEquals("drawn still", -1, animation[0]);
			Map<Integer, int[]> at = spots(plan);
			boolean stillCasting = (t - lastCast) * 30 <= PersonalSpacePlugin.CASTING_CYCLES;
			if (t < 5)
			{
				Assert.assertTrue("alone, nobody is moved", at.isEmpty());
			}
			else if (stillCasting)
			{
				Assert.assertTrue("tick " + t + ": " + (t - lastCast) + " ticks after a cast the alcher lost the middle", inTheMiddle(at.get(1)));
			}
			else if (t > lastCast)
			{
				Assert.assertFalse("tick " + t + ": stopped, but still in the middle", inTheMiddle(at.get(1)));
				Assert.assertNotNull("tick " + t + ": stopped, they get a spot like anyone", at.get(1));
			}
		}
	}

	@Test
	public void restyledCastsStillCountAsCasting()
	{
		// Still and Party change how a cast is drawn the moment it starts, before the next frame:
		// alching all the same, so it still counts. So does yours, and nothing that isn't a cast.
		for (PersonalSpaceConfig.Alchers style : PersonalSpaceConfig.Alchers.values())
		{
			int[] cycle = {1000};
			int[] yourAnimation = {-1};
			Player you = player(7, yourAnimation);
			int[] animation = {AnimationID.HUMAN_CAST_ENCHANTRING};
			Player caster = player(3, animation);
			int[] dancing = {AnimationID.EMOTE_DANCE};
			Player dancer = player(4, dancing);
			AlchStyler styler = new AlchStyler(client(you, cycle));
			styler.noticed(caster);
			styler.noticed(dancer);
			styler.apply(style, true);
			Assert.assertEquals(style + ": restyled", style == PersonalSpaceConfig.Alchers.NORMAL, animation[0] == AnimationID.HUMAN_CAST_ENCHANTRING);
			cycle[0] += 20;
			Assert.assertTrue(style + ": casting", styler.castWithin(3, cycle[0], PersonalSpacePlugin.CASTING_CYCLES));
			Assert.assertFalse(style + ": dancing isn't casting", styler.castWithin(4, cycle[0], PersonalSpacePlugin.CASTING_CYCLES));
			yourAnimation[0] = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
			styler.noticed(you);
			styler.apply(style, true);
			Assert.assertEquals(style + ": your own cast is never restyled", AnimationID.HUMAN_CASTHIGHLVLALCHEMY, yourAnimation[0]);
			Assert.assertTrue(style + ": you're casting", styler.castWithin(7, cycle[0], PersonalSpacePlugin.CASTING_CYCLES));
			Assert.assertFalse(style + ": long after the cast, they've stopped",
				styler.castWithin(3, cycle[0] + PersonalSpacePlugin.CASTING_CYCLES, PersonalSpacePlugin.CASTING_CYCLES));
		}
	}

	@Test
	public void twoCastersDontSwapAndTheMiddleIsHandedOverOnlyOnceItsClear()
	{
		// Two alching on one tile: whoever has the middle keeps it, cast after cast. When they stop,
		// they step out to a spot of their own, and the other steps in only after that, between casts.
		CrowdPlanner planner = new CrowdPlanner();
		boolean[] oneCasts = {true};
		boolean[] twoMidCast = {true};
		planner.casting = id -> id == 2 || id == 1 && oneCasts[0];
		planner.midAction = id -> id == 1 && oneCasts[0] || id == 2 && twoMidCast[0];
		IntPredicate busy = id -> true;
		int[] twosSpot = null;
		for (int t = 1; t <= 30; t++)
		{
			Map<Integer, int[]> at = spots(tick(planner, t, t < 4 ? onTheTile(busy, 1) : onTheTile(busy, 1, 2)));
			if (t < 4)
			{
				continue;
			}
			Assert.assertTrue("tick " + t + ": the first alcher lost the middle", inTheMiddle(at.get(1)));
			Assert.assertFalse("tick " + t + ": both in the middle", inTheMiddle(at.get(2)));
			if (twosSpot != null)
			{
				Assert.assertArrayEquals("tick " + t + ": the second alcher was moved", twosSpot, at.get(2));
			}
			twosSpot = at.get(2);
			assertNobodyInsideAnybody("tick " + t, at);
		}
		oneCasts[0] = false;
		Map<Integer, int[]> out = spots(tick(planner, 31, onTheTile(busy, 1, 2)));
		Assert.assertFalse("the first alcher stopped but kept the middle", inTheMiddle(out.get(1)));
		Assert.assertArrayEquals("the second stepped in as the first stepped out", twosSpot, out.get(2));
		Assert.assertTrue("the first stepped out towards the second, " + Math.round(angleBetween(out.get(1), twosSpot)) + " degrees",
			angleBetween(out.get(1), twosSpot) > 90);
		for (int t = 32; t <= 34; t++)
		{
			Map<Integer, int[]> at = spots(tick(planner, t, onTheTile(busy, 1, 2)));
			Assert.assertArrayEquals("tick " + t + ": the second alcher was moved mid-cast", twosSpot, at.get(2));
			Assert.assertArrayEquals("tick " + t + ": the first alcher was moved again", out.get(1), at.get(1));
		}
		twoMidCast[0] = false;
		Map<Integer, int[]> handed = spots(tick(planner, 35, onTheTile(busy, 1, 2)));
		Assert.assertTrue("between casts the second alcher takes the middle", inTheMiddle(handed.get(2)));
		Assert.assertArrayEquals("the first stays where they went", out.get(1), handed.get(1));
	}

	@Test
	public void aCasterJoiningAPairTakesTheMiddleAndThePairStepsBackOnTheirOwnSides()
	{
		// A pair standing together; someone walks up and alches on their tile. The alcher stays where
		// they stopped, and the pair make room, each on their own side of them: in the open and in a curve.
		for (PersonalSpaceConfig.Arrangement arrangement : new PersonalSpaceConfig.Arrangement[]{
			PersonalSpaceConfig.Arrangement.AUTO, PersonalSpaceConfig.Arrangement.ARC})
		{
			CrowdPlanner planner = new CrowdPlanner();
			planner.arrangement = arrangement;
			planner.casting = id -> id == 3;
			planner.midAction = id -> id == 3;
			IntPredicate busy = id -> id == 3;
			Map<Integer, int[]> pair = null;
			for (int t = 1; t <= 30; t++)
			{
				Map<Integer, int[]> at = spots(tick(planner, t, t <= 8 ? onTheTile(busy, 1, 2) : onTheTile(busy, 1, 2, 3)));
				if (t == 8)
				{
					pair = at;
				}
				if (t <= 8)
				{
					continue;
				}
				Assert.assertTrue(arrangement + " tick " + t + ": the alcher was moved", inTheMiddle(at.get(3)));
				for (int id = 1; id <= 2; id++)
				{
					Assert.assertTrue(arrangement + " tick " + t + ": " + id + " went round the alcher",
						angleBetween(at.get(id), pair.get(id)) < 30);
				}
				assertNobodyInsideAnybody(arrangement + " tick " + t, at);
			}
		}
	}

	@Test
	public void someoneWithASpotWhoStartsCastingStepsIntoTheMiddleBetweenCasts()
	{
		// A pair standing together, and one of them starts alching. Mid-cast they stay put: walked
		// then, the cast was cut short for the step. In the pause after it they step into the middle,
		// and the other keeps their place.
		CrowdPlanner planner = new CrowdPlanner();
		boolean[] casting = {false};
		boolean[] midCast = {false};
		planner.casting = id -> id == 1 && casting[0];
		planner.midAction = id -> id == 1 && midCast[0];
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 5; t++)
		{
			before = spots(tick(planner, t, onTheTile(id -> false, 1, 2)));
		}
		casting[0] = true;
		midCast[0] = true;
		for (int t = 6; t <= 9; t++)
		{
			Map<Integer, int[]> at = spots(tick(planner, t, onTheTile(id -> id == 1, 1, 2)));
			Assert.assertArrayEquals("tick " + t + ": moved mid-cast", before.get(1), at.get(1));
			Assert.assertArrayEquals(before.get(2), at.get(2));
		}
		midCast[0] = false;
		Map<Integer, int[]> pause = spots(tick(planner, 10, onTheTile(id -> id == 1, 1, 2)));
		Assert.assertTrue("between casts they step into the middle", inTheMiddle(pause.get(1)));
		Assert.assertArrayEquals("the other keeps their place", before.get(2), pause.get(2));
		midCast[0] = true;
		for (int t = 11; t <= 20; t++)
		{
			Map<Integer, int[]> at = spots(tick(planner, t, onTheTile(id -> id == 1, 1, 2)));
			Assert.assertTrue("tick " + t + ": casting again, they keep it", inTheMiddle(at.get(1)));
			Assert.assertArrayEquals(before.get(2), at.get(2));
		}
	}

	/** High Alchemy's frames as the game has them, each so many game cycles: 2.8 seconds in all. */
	private static final int[] HIGH_ALCHEMY = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 7, 7, 7, 7, 7, 7, 7, 7, 4};

	/** An action with these frames, or skeletal and {@code duration} cycles long if there are none. */
	private static Animation action(int[] frames, int frameStep, int duration)
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(), new Class<?>[]{Animation.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getFrameLengths":
					return frames;
				case "getFrameStep":
					return frameStep;
				case "isMayaAnim":
					return frames == null;
				case "getDuration":
					return duration;
				default:
					return null;
			}
		});
	}

	/** The frame an action with these frames is on {@code cycles} game cycles after it started. */
	private static int frameAt(int[] frames, int cycles)
	{
		int frame = 0;
		for (int left = cycles; frame < frames.length && left >= frames[frame]; frame++)
		{
			left -= frames[frame];
		}
		return frame;
	}

	@Test
	public void aCastThatEndsBeforeTheNextTickCountsAsABreak()
	{
		// High Alchemy as fast as it goes, a cast every five ticks: it's still playing at every tick,
		// so the tick it ends after is the only break there is.
		Animation alch = action(HIGH_ALCHEMY, -1, 0);
		for (int t = 0; t < 5; t++)
		{
			int frame = frameAt(HIGH_ALCHEMY, t * 30);
			Assert.assertTrue("tick " + t + ": still casting", frame < HIGH_ALCHEMY.length);
			Assert.assertEquals("tick " + t, t == 4, PersonalSpacePlugin.endsWithinATick(alch, frame));
		}
		// A skeletal one goes by its cycles; one that repeats, or can't be read, never ends.
		Assert.assertTrue(PersonalSpacePlugin.endsWithinATick(action(null, -1, 140), 120));
		Assert.assertFalse(PersonalSpacePlugin.endsWithinATick(action(null, -1, 140), 90));
		Assert.assertFalse(PersonalSpacePlugin.endsWithinATick(action(HIGH_ALCHEMY, 1, 0), HIGH_ALCHEMY.length - 1));
		Assert.assertFalse(PersonalSpacePlugin.endsWithinATick(alch, HIGH_ALCHEMY.length));
		Assert.assertFalse(PersonalSpacePlugin.endsWithinATick(null, 0));
	}

	@Test
	public void aHighAlcherWithASpotStepsIntoTheMiddleAsACastEnds()
	{
		// A pair standing together, and one of them starts High Alchemy as fast as it goes. No tick
		// ever falls between two casts, so going by whether a cast is playing they never stepped in,
		// and their spell stayed in the middle without them. They step in as their first cast at the
		// spot ends, and nobody is moved at any other tick.
		Animation alch = action(HIGH_ALCHEMY, -1, 0);
		int firstCast = 6;
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner asBefore = new CrowdPlanner();
		int[] now = {0};
		planner.casting = id -> id == 1 && now[0] >= firstCast;
		asBefore.casting = planner.casting;
		IntPredicate animating = id -> id == 1 && now[0] >= firstCast && (now[0] - firstCast) % 5 * 30 < 140;
		planner.midAction = id -> animating.test(id)
			&& !PersonalSpacePlugin.endsWithinATick(alch, frameAt(HIGH_ALCHEMY, (now[0] - firstCast) % 5 * 30));
		asBefore.midAction = animating;
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 40; t++)
		{
			now[0] = t;
			Assert.assertEquals("tick " + t + ": a tick between casts", t < firstCast, !animating.test(1));
			List<StackSpreader.Entry> still = onTheTile(id -> id == 1 && now[0] >= firstCast, 1, 2);
			Map<Integer, int[]> at = spots(tick(planner, t, still));
			Assert.assertFalse("tick " + t + ": without a break they stepped in", inTheMiddle(spots(tick(asBefore, t, still)).get(1)));
			if (t < firstCast)
			{
				before = at;
				continue;
			}
			Assert.assertArrayEquals("tick " + t + ": the other was moved", before.get(2), at.get(2));
			if (t < firstCast + 4)
			{
				Assert.assertArrayEquals("tick " + t + ": moved mid-cast", before.get(1), at.get(1));
			}
			else
			{
				Assert.assertTrue("tick " + t + ": not in the middle as their cast ended", inTheMiddle(at.get(1)));
			}
		}
	}

	@Test
	public void youKeepYourOwnRulesBesideACaster()
	{
		// With Give me a spot on, you're given a spot beside an alcher, who keeps the middle.
		CrowdPlanner planner = new CrowdPlanner();
		planner.casting = id -> id == 1;
		planner.midAction = id -> id == 1;
		for (int t = 1; t <= 10; t++)
		{
			Map<Integer, int[]> at = spots(tick(planner, t, onTheTile(id -> id == 1, 1, YOU)));
			Assert.assertNotNull("tick " + t + ": you have a spot", at.get(YOU));
			Assert.assertFalse(inTheMiddle(at.get(YOU)));
			Assert.assertTrue("tick " + t + ": the alcher keeps the middle", inTheMiddle(at.get(1)));
		}
		// With it off you stay where you really stand, as ever: the middle is yours, and the alcher
		// is given a spot like anyone.
		CrowdPlanner yoursAnyway = new CrowdPlanner();
		yoursAnyway.casting = id -> id == 1;
		yoursAnyway.midAction = id -> id == 1;
		for (int t = 1; t <= 10; t++)
		{
			CrowdPlanner.Plan plan = yoursAnyway.plan(onTheTile(id -> id == 1, 1, YOU), id -> true, PersonalSpaceConfig.SPACING_WIDE,
				PersonalSpaceConfig.DEFAULT_STACK, true, false, t, OPEN);
			Map<Integer, int[]> at = spots(plan);
			Assert.assertNull("tick " + t + ": you were moved", at.get(YOU));
			Assert.assertNotNull(at.get(1));
			Assert.assertFalse("tick " + t + ": the alcher was put in your middle", inTheMiddle(at.get(1)));
		}
		// And alching yourself, the middle is yours.
		CrowdPlanner yourCast = new CrowdPlanner();
		yourCast.casting = id -> id == YOU;
		yourCast.midAction = id -> id == YOU;
		for (int t = 1; t <= 10; t++)
		{
			Map<Integer, int[]> at = spots(tick(yourCast, t, onTheTile(id -> id == YOU, 1, YOU)));
			Assert.assertTrue("tick " + t + ": alching, you were moved", inTheMiddle(at.get(YOU)));
			Assert.assertFalse(inTheMiddle(at.get(1)));
		}
	}

	@Test
	public void slotBookKeepsEveryoneInPlaceRoundACaster()
	{
		// Two with spots either side of an alcher; the one in the better spot leaves. Filling the gap
		// would walk the other straight through the alcher.
		SlotBook book = new SlotBook();
		Map<Long, List<Integer>> both = new HashMap<>();
		both.put(TILE, Arrays.asList(1, 2));
		Map<Long, Map<Integer, Integer>> slots = book.update(both, t -> 2, 1);
		Assert.assertEquals(Integer.valueOf(1), slots.get(TILE).get(2));
		book.keepPlaces = Set.of(TILE);
		Map<Long, List<Integer>> one = new HashMap<>();
		one.put(TILE, Arrays.asList(2));
		for (int t = 2; t <= SlotBook.HOLD_TICKS + 5; t++)
		{
			Assert.assertEquals("tick " + t, Integer.valueOf(1), book.update(one, x -> 2, t).get(TILE).get(2));
		}
		book.keepPlaces = Set.of();
		Assert.assertEquals("with nobody in the middle the gap is filled as ever", Integer.valueOf(0),
			book.update(one, x -> 2, SlotBook.HOLD_TICKS + 6).get(TILE).get(2));
	}

	/** Plans the same people twice, once with the casters known, and checks nothing differs. */
	private static void assertUnchanged(String where, List<StackSpreader.Entry> still, int spacing, CrowdPlanner.Surroundings around, IntPredicate casters)
	{
		CrowdPlanner with = new CrowdPlanner();
		with.casting = casters;
		with.midAction = casters;
		CrowdPlanner without = new CrowdPlanner();
		for (int t = 1; t <= 15; t++)
		{
			CrowdPlanner.Plan a = with.plan(still, id -> true, spacing, PersonalSpaceConfig.DEFAULT_STACK, true, true, t, around);
			CrowdPlanner.Plan b = without.plan(still, id -> true, spacing, PersonalSpaceConfig.DEFAULT_STACK, true, true, t, around);
			Assert.assertTrue(where + ": a caster was put in the middle", a.castersInTheMiddle.isEmpty());
			Map<Integer, int[]> sa = spots(a);
			Map<Integer, int[]> sb = spots(b);
			Assert.assertEquals(where + " tick " + t, sb.keySet(), sa.keySet());
			for (int id : sb.keySet())
			{
				Assert.assertArrayEquals(where + " tick " + t + ": " + id, sb.get(id), sa.get(id));
			}
		}
	}

	@Test
	public void bigGroupsCountersFiresAnvilsAndCloseSpacingAreLaidOutAsBefore()
	{
		IntPredicate busy = id -> id == 1;
		// Four or more: just a crowd, whoever is alching.
		assertUnchanged("four", onTheTile(busy, 1, 2, 3, 4), PersonalSpaceConfig.SPACING_WIDE, OPEN, busy);
		assertUnchanged("five", onTheTile(busy, 1, 2, 3, 4, 5), PersonalSpaceConfig.SPACING_WIDE, OPEN, busy);
		// At a bank counter: a row along it.
		assertUnchanged("counter", onTheTile(busy, 1, 2, 3), PersonalSpaceConfig.SPACING_WIDE, surroundings(true, true), busy);
		// So close together that the others would stand inside the alcher.
		assertUnchanged("close", onTheTile(busy, 1, 2), PersonalSpaceConfig.SPACING_CLOSE, OPEN, busy);
		// At an anvil, with someone smithing from the next side too: one crowd all round it.
		int[][][] flags = new int[4][104][104];
		flags[0][50][51] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		CrowdPlanner.Surroundings smithy = new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz)
			{
				int x = StackRegistry.sceneX(t) * 128 + 64;
				int z = StackRegistry.sceneY(t) * 128 + 64;
				return terrain.canStand(0, x, z, x + dx, z + dz);
			}
			public boolean facesObstacle(long t, double a) { return terrain.facesObstacle(0, StackRegistry.sceneX(t), StackRegistry.sceneY(t), a); }
			public boolean isCounter(long t, double a) { return terrain.isCounter(0, StackRegistry.sceneX(t), StackRegistry.sceneY(t), a); }
			public boolean facesFire(long t, double a) { return false; }
			public List<int[]> firesNear(long t) { return new ArrayList<>(); }
		};
		List<StackSpreader.Entry> anvil = new ArrayList<>(onTheTile(busy, 1, 2));
		anvil.add(new StackSpreader.Entry(3, StackRegistry.key(0, 49, 51), false, OffsetTable.facing(1, 0), true));
		anvil.add(new StackSpreader.Entry(4, StackRegistry.key(0, 49, 51), false, OffsetTable.facing(1, 0), true));
		CrowdPlanner look = new CrowdPlanner();
		Assert.assertEquals(CrowdPlanner.ROUND_OBJECT, look.plan(anvil, id -> true, PersonalSpaceConfig.SPACING_WIDE,
			PersonalSpaceConfig.DEFAULT_STACK, true, true, 1, smithy).tiles.get(TILE).shape);
		assertUnchanged("anvil", anvil, PersonalSpaceConfig.SPACING_WIDE, smithy, busy);
		// At a fire just north of them: gathered round it.
		CrowdPlanner.Surroundings fire = new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz) { return dz < 40; }
			public boolean facesObstacle(long t, double a) { return false; }
			public boolean isCounter(long t, double a) { return false; }
			public boolean facesFire(long t, double a) { return Math.abs(Math.cos(a) + 1) < 0.3; }
			public List<int[]> firesNear(long t) { return new ArrayList<>(Arrays.asList(new int[][]{{0, 1}})); }
		};
		CrowdPlanner peek = new CrowdPlanner();
		Assert.assertTrue(peek.plan(onTheTile(busy, 1, 2, 3), id -> true, PersonalSpaceConfig.SPACING_WIDE,
			PersonalSpaceConfig.DEFAULT_STACK, true, true, 1, fire).tiles.get(TILE).shape.contains("fire"));
		assertUnchanged("fire", onTheTile(busy, 1, 2, 3), PersonalSpaceConfig.SPACING_WIDE, fire, busy);
	}
}
