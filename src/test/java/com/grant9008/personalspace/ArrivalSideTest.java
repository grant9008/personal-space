package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Whoever walks onto someone keeps the side they came from, and whoever was there steps to the
 * other side: frame after frame, with the plugin's own ticks (see {@link FollowerOnCrowdedTileTest.Game}).
 * The video: walking onto someone from the left, you were sent on to their right, and they stepped
 * out to the left behind you; stepping onto your pet from the right, it came out under you and
 * stood on your right, the way you came.
 */
public class ArrivalSideTest
{
	private static final int TILE = 50;
	private static final int MIDDLE = TILE * 128 + 64;
	/** The furthest anyone walking is drawn from one frame to the next: a stride at walking pace, rounded to whole units each way. */
	private static final int STRIDE = 6;
	/** Frames to walk two tiles at the game's pace and stand a good while after. */
	private static final int WALK_ON = 64 + 300;

	private static FollowerOnCrowdedTileTest.Game game()
	{
		FollowerOnCrowdedTileTest.Game g = new FollowerOnCrowdedTileTest.Game();
		g.ticking = true;
		return g;
	}

	/** Where each of {@code bodies} is drawn in each of the next {@code frames} frames, {@code before} each. */
	private static List<List<int[]>> watch(FollowerOnCrowdedTileTest.Game g, int frames, Runnable before, FollowerOnCrowdedTileTest.Body... bodies)
	{
		List<List<int[]>> out = new ArrayList<>();
		for (FollowerOnCrowdedTileTest.Body ignored : bodies)
		{
			out.add(new ArrayList<>());
		}
		for (int i = 0; i < frames; i++)
		{
			before.run();
			g.frame();
			for (int k = 0; k < bodies.length; k++)
			{
				out.get(k).add(g.at(bodies[k]));
			}
		}
		return out;
	}

	/** {@code walker} walks from where it stands into the middle of the tile, and everyone stands a while. */
	private static List<List<int[]>> walkOn(FollowerOnCrowdedTileTest.Game g, FollowerOnCrowdedTileTest.Body walker,
		FollowerOnCrowdedTileTest.Body... bodies)
	{
		return watch(g, WALK_ON, () -> g.stepTowards(walker, MIDDLE, MIDDLE), bodies);
	}

	private static int[] last(List<int[]> trace)
	{
		for (int i = trace.size() - 1; i >= 0; i--)
		{
			if (trace.get(i) != null)
			{
				return trace.get(i);
			}
		}
		return null;
	}

	/** How far from the middle of the tile, the way (east, north) points, {@code at} is drawn. */
	private static double along(int[] at, int east, int north)
	{
		return ((at[0] - MIDDLE) * east + (at[1] - MIDDLE) * north) / Math.hypot(east, north);
	}

	/** The furthest the way (east, north) points that {@code who} was drawn, over the frames after {@code from}. */
	private static double furthest(List<int[]> trace, int from, int east, int north, boolean most)
	{
		double out = most ? -Double.MAX_VALUE : Double.MAX_VALUE;
		for (int[] at : trace.subList(from, trace.size()))
		{
			if (at != null)
			{
				out = most ? Math.max(out, along(at, east, north)) : Math.min(out, along(at, east, north));
			}
		}
		return out;
	}

	/** The furthest it was drawn from one frame to the next, along the frames it was drawn in. */
	private static double largestStep(List<int[]> trace)
	{
		double most = 0;
		int[] before = null;
		for (int[] at : trace)
		{
			if (at != null && before != null)
			{
				most = Math.max(most, Math.hypot(at[0] - before[0], at[1] - before[1]));
			}
			before = at == null ? before : at;
		}
		return most;
	}

	/**
	 * Someone who came from (east, north) of the tile keeps that side and whoever was there stands
	 * on the other, and neither got there by walking past the other: the one who came never went
	 * on past the middle, and the one who was there never stepped out towards them.
	 */
	private static void assertKeptTheirSide(String what, List<int[]> came, List<int[]> was, int east, int north)
	{
		Assert.assertTrue(what + ": stands on the side they came from, " + along(last(came), east, north),
			along(last(came), east, north) >= 40);
		Assert.assertTrue(what + ": the other steps to the other side, " + along(last(was), east, north),
			along(last(was), east, north) <= -40);
		Assert.assertTrue(what + ": went on past the middle, " + furthest(came, 0, east, north, false),
			furthest(came, 0, east, north, false) >= -STRIDE);
		Assert.assertTrue(what + ": the other stepped out towards them, " + furthest(was, 0, east, north, true),
			furthest(was, 0, east, north, true) <= STRIDE);
		Assert.assertTrue(what + ": never jumps", largestStep(came) <= STRIDE && largestStep(was) <= STRIDE);
	}

	/** Where the camera may be, from the middle of the tile: south of it (as usual), north, east and west. */
	private static final int[][] CAMERAS = {{0, -1500}, {0, 1500}, {1500, 0}, {-1500, 0}};

	/** The two of them stand in a line along the way (east, north), the one who came at that end of it. */
	private static void assertInLine(String what, List<int[]> came, List<int[]> was, int east, int north)
	{
		int[] a = last(came);
		int[] b = last(was);
		double off = Math.toDegrees(Math.abs(Math.atan2((a[0] - b[0]) * north - (a[1] - b[1]) * east,
			(a[0] - b[0]) * east + (a[1] - b[1]) * north)));
		Assert.assertTrue(what + ": in a line the way they came, " + off + " degrees off", off < 10);
	}

	/** Where they end up, as {east, north} of the middle. */
	private static int[] spot(List<int[]> trace)
	{
		int[] at = last(trace);
		return new int[]{at[0] - MIDDLE, at[1] - MIDDLE};
	}

	@Test
	public void walkingOntoSomeoneFromTheLeftYouStayOnTheirLeft()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("from the west", t.get(0), t.get(1), -1, 0);
		// And it stays that way: nobody re-sorts once placed.
		int[] you = last(t.get(0));
		them.orientation = 1024;
		g.you.orientation = 1536;
		List<List<int[]>> after = watch(g, 600, () -> { }, g.you, them);
		Assert.assertArrayEquals(new int[]{you[0], you[1]}, new int[]{last(after.get(0))[0], last(after.get(0))[1]});
	}

	@Test
	public void walkingOntoSomeoneFromTheRightYouStayOnTheirRightWhereverTheCameraIs()
	{
		// With the camera off to the west you used to take the spot on its side, whichever way you came.
		FollowerOnCrowdedTileTest.Game g = game();
		g.cameraX = MIDDLE - 1500;
		g.cameraY = MIDDLE;
		g.you = g.player(0, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("from the east", t.get(0), t.get(1), 1, 0);
	}

	@Test
	public void walkingOntoSomeoneFromTheFrontOrBackYouKeepThatSideWhereverTheCameraIs()
	{
		// A pair always stood east and west: walking straight on from the north or south there was
		// no side to keep, and you stood on whichever side the camera was. The two of you now stand
		// along the way you came, you at your end of it and them at the other.
		for (int north : new int[]{-2, 2})
		{
			for (int[] camera : CAMERAS)
			{
				FollowerOnCrowdedTileTest.Game g = game();
				g.cameraX = MIDDLE + camera[0];
				g.cameraY = MIDDLE + camera[1];
				g.you = g.player(0, TILE, TILE + north);
				FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
				g.run(them, 100);
				List<List<int[]>> t = walkOn(g, g.you, g.you, them);
				String what = (north > 0 ? "from the north" : "from the south") + ", camera " + camera[0] + "," + camera[1];
				assertKeptTheirSide(what, t.get(0), t.get(1), 0, north);
				assertInLine(what, t.get(0), t.get(1), 0, north);
			}
		}
	}

	@Test
	public void walkingOntoSomeoneOnADiagonalYouStandAtYourCornerWhereverTheCameraIs()
	{
		// East and west, coming from the north-east you stood east of them: on your side, but
		// across their front rather than at your corner.
		for (int[] f : new int[][]{{2, 2}, {-2, -2}, {-2, 2}, {2, -2}})
		{
			for (int[] camera : CAMERAS)
			{
				FollowerOnCrowdedTileTest.Game g = game();
				g.cameraX = MIDDLE + camera[0];
				g.cameraY = MIDDLE + camera[1];
				g.you = g.player(0, TILE + f[0], TILE + f[1]);
				FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
				g.run(them, 100);
				List<List<int[]>> t = walkOn(g, g.you, g.you, them);
				String what = "from " + f[0] + "," + f[1] + ", camera " + camera[0] + "," + camera[1];
				assertKeptTheirSide(what, t.get(0), t.get(1), f[0], f[1]);
				assertInLine(what, t.get(0), t.get(1), f[0], f[1]);
			}
		}
	}

	@Test
	public void walkingOntoSomeoneFromTheFrontLeftOrBackRightYouKeepThatSide()
	{
		int[][] from = {{-2, -2}, {2, 2}, {-2, 2}};
		for (int[] f : from)
		{
			FollowerOnCrowdedTileTest.Game g = game();
			g.you = g.player(0, TILE + f[0], TILE + f[1]);
			FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
			g.run(them, 100);
			List<List<int[]>> t = walkOn(g, g.you, g.you, them);
			Assert.assertTrue("from " + f[0] + "," + f[1] + ": " + spot(t.get(0))[0], spot(t.get(0))[0] * f[0] >= 40 * 2);
			Assert.assertTrue("from " + f[0] + "," + f[1] + ": " + spot(t.get(1))[0], spot(t.get(1))[0] * f[0] <= -40 * 2);
		}
	}

	@Test
	public void someoneWalkingOntoAnotherPlayerKeepsTheirSideAndTheOtherStepsAway()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body comes = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body was = g.player(2, TILE, TILE);
		g.run(was, 100);
		List<List<int[]>> t = walkOn(g, comes, comes, was);
		assertKeptTheirSide("someone from the west", t.get(0), t.get(1), -1, 0);
	}

	@Test
	public void withGiveMeASpotOffWhoeverYouWalkOntoStepsAwayFromYou()
	{
		// You stay in the middle of the tile: they used to step out east whichever way you came, so
		// walking in from the east you walked into them as they stepped out to meet you.
		FollowerOnCrowdedTileTest.Game g = game();
		g.giveMeASpot = false;
		g.you = g.player(0, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		Assert.assertArrayEquals("you stay in the middle", new int[]{0, 0}, spot(t.get(0)));
		Assert.assertTrue("they step away from you: " + spot(t.get(1))[0], spot(t.get(1))[0] <= -40);
		Assert.assertTrue("never towards you", furthest(t.get(1), 0, 1, 0, true) <= STRIDE);
	}

	@Test
	public void withGiveMeASpotOffWhoeverWalksOntoYouKeepsTheirSide()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.giveMeASpot = false;
		g.you = g.player(0, TILE, TILE);
		FollowerOnCrowdedTileTest.Body comes = g.player(1, TILE - 2, TILE);
		g.run(g.you, 100);
		List<List<int[]>> t = walkOn(g, comes, comes, g.you);
		Assert.assertArrayEquals("you stay in the middle", new int[]{0, 0}, spot(t.get(1)));
		Assert.assertTrue("they keep the west: " + spot(t.get(0))[0], spot(t.get(0))[0] <= -40);
		Assert.assertTrue("never past you", furthest(t.get(0), 0, -1, 0, false) >= -STRIDE);
	}

	@Test
	public void aThirdWalkingOntoAPairStandsOnTheSideTheyCameFrom()
	{
		// The pair keeps its places. The third of three used to be sent through the middle of
		// them to the far side; the other side is the same shape turned round, or seen in a mirror.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body c = g.player(3, TILE + 2, TILE - 2);
		g.run(a, 100);
		// The pair forms with the second walking straight down onto the first: north and south.
		watch(g, WALK_ON, () -> g.stepTowards(b, MIDDLE, MIDDLE), a, b);
		int[] aWas = new int[]{g.at(a)[0], g.at(a)[1]};
		int[] bWas = new int[]{g.at(b)[0], g.at(b)[1]};
		Assert.assertTrue(aWas[1] < MIDDLE - 40 && bWas[1] > MIDDLE + 40);
		List<List<int[]>> t = walkOn(g, c, c, a, b);
		int[] cAt = spot(t.get(0));
		Assert.assertTrue("on the side they came from, south-east: " + cAt[0] + "," + cAt[1],
			cAt[0] > 20 && cAt[1] < -20 && along(last(t.get(0)), 1, -1) >= 50);
		Assert.assertTrue("never past the middle", furthest(t.get(0), 0, 1, -1, false) >= -STRIDE);
		// The pair only opened out a little for the third: each still on their own side.
		Assert.assertTrue(spot(t.get(1))[1] < -40 && Math.abs(spot(t.get(1))[0]) < 10);
		Assert.assertTrue(spot(t.get(2))[1] > 40 && Math.abs(spot(t.get(2))[0]) < 10);
		for (int i = 1; i <= 2; i++)
		{
			int[] other = spot(t.get(i));
			Assert.assertTrue("nobody inside anybody", Math.hypot(other[0] - cAt[0], other[1] - cAt[1]) >= CrowdPlanner.NEIGHBOUR_GAP);
		}
		// And nobody moves the third round to the spot they'd have had before: the three open out
		// to the spacing for three in a while, each the way they stand.
		List<List<int[]>> later = watch(g, 600, () -> { }, c);
		int[] cLater = spot(later.get(0));
		double turned = Math.toDegrees(Math.abs(Math.atan2(cLater[1], cLater[0]) - Math.atan2(cAt[1], cAt[0])));
		Assert.assertTrue("went round by " + turned + " degrees", turned < 5);
	}

	@Test
	public void aCasterKeepsTheMiddleAndWhoeverStepsOntoThemKeepsTheirSide()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.casting = id -> id == 1;
		g.you = g.player(0, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body caster = g.player(1, TILE, TILE);
		g.run(caster, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, caster);
		Assert.assertArrayEquals("the caster stays where they stand", new int[]{0, 0}, spot(t.get(1)));
		Assert.assertTrue("you keep the west: " + spot(t.get(0))[0], spot(t.get(0))[0] <= -40);
		Assert.assertTrue("never through them", furthest(t.get(0), 0, -1, 0, false) >= -STRIDE);
	}

	@Test
	public void aCasterWalkingOntoSomeoneKeepsTheMiddleAndTheyStepAway()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.casting = id -> id == 1;
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body caster = g.player(1, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body was = g.player(2, TILE, TILE);
		g.run(was, 100);
		List<List<int[]>> t = walkOn(g, caster, caster, was);
		Assert.assertArrayEquals("the caster stays where they stand", new int[]{0, 0}, spot(t.get(0)));
		Assert.assertTrue("they step away from the caster: " + spot(t.get(1))[0], spot(t.get(1))[0] <= -40);
		Assert.assertTrue("never towards them", furthest(t.get(1), 0, 1, 0, true) <= STRIDE);
	}

	@Test
	public void flamesOnTheSideYouCameFromKeepYouAsNearItAsTheyAllow()
	{
		// You walk across a Forester's Campfire onto someone. Your side is in its flames: you stand
		// as near it as is clear of them, and they step to the other side.
		FollowerOnCrowdedTileTest.Game g = game();
		g.fires.add(StackRegistry.key(0, TILE - 1, TILE));
		g.you = g.player(0, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		int[] you = spot(t.get(0));
		Assert.assertTrue("on the west of them, as near as the flames allow: " + you[0] + "," + you[1], you[0] < -20);
		Assert.assertTrue("clear of the flames", MIDDLE + you[0] - PersonalSpacePlugin.FIRE_CLEARANCE >= TILE * 128);
		Assert.assertTrue("they step east: " + spot(t.get(1))[0], spot(t.get(1))[0] >= 40);
	}

	@Test
	public void appearingOnSomeonesTileLeavesThePairAsItWas()
	{
		// A teleport, or logging in: nobody walked in from anywhere, so it's as it always was.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE - 8, TILE);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		g.you.x = MIDDLE;
		g.you.z = MIDDLE;
		List<List<int[]>> t = watch(g, 300, () -> { }, g.you, them);
		Assert.assertTrue(spot(t.get(0))[0] >= 40);
		Assert.assertTrue(spot(t.get(1))[0] <= -40);
	}

	@Test
	public void twoWhoArriveTogetherAreLaidOutAsBefore()
	{
		// Neither was there first: in order of who they are, as always. The one from the west was
		// laid out east, and walks past the other.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE + 2, TILE);
		g.run(a, 100);
		List<List<int[]>> t = watch(g, WALK_ON, () ->
		{
			g.stepTowards(a, MIDDLE, MIDDLE);
			g.stepTowards(b, MIDDLE, MIDDLE);
		}, a, b);
		Assert.assertTrue(spot(t.get(0))[0] >= 40);
		Assert.assertTrue(spot(t.get(1))[0] <= -40);
	}

	@Test
	public void steppingOntoYourPetFromAnySideItStepsOutAwayFromYou()
	{
		// It faces you as you come, as followers face their owner, and stepped out that way: back the
		// way you came, under you.
		int[][] from = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
		for (int[] f : from)
		{
			FollowerOnCrowdedTileTest.Game g = game();
			g.you = g.player(0, TILE + f[0], TILE + f[1]);
			FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
			pet.interacting = g.you.actor;
			pet.orientation = OffsetTable.facing(f[0], f[1]);
			g.run(pet, 100);
			List<List<int[]>> t = walkOn(g, g.you, pet);
			List<int[]> trace = t.get(0);
			String way = f[0] + "," + f[1];
			for (int[] at : trace)
			{
				Assert.assertNotNull("drawn every frame, from " + way, at);
			}
			Assert.assertTrue("away from you, from " + way + ": " + along(last(trace), f[0], f[1]), along(last(trace), f[0], f[1]) <= -50);
			Assert.assertTrue("never back towards you, from " + way, furthest(trace, 0, f[0], f[1], true) <= STRIDE);
			Assert.assertTrue("never jumps", largestStep(trace) <= STRIDE);
		}
	}

	@Test
	public void someoneSteppingOntoAPetItStepsOutAwayFromThemEvenTowardsItsOwner()
	{
		// Its owner stands where they came from: it used to step out towards its owner, under them.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE + 2, TILE + 1);
		FollowerOnCrowdedTileTest.Body comes = g.player(2, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 1536;
		g.run(pet, 100);
		List<int[]> trace = walkOn(g, comes, pet).get(0);
		Assert.assertTrue("away from them: " + along(last(trace), 1, 0), along(last(trace), 1, 0) <= -50);
		Assert.assertTrue("never back towards them", furthest(trace, 0, 1, 0, true) <= STRIDE);
	}

	@Test
	public void yourPetWalkingOntoYouKeepsTheSideItCameFrom()
	{
		// It stops on your tile, facing on the way it walked: it used to walk on through you and
		// stand on your far side.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE + 2, TILE, true);
		pet.interacting = g.you.actor;
		pet.orientation = 512;
		g.run(pet, 100);
		List<int[]> trace = walkOn(g, pet, pet).get(0);
		for (int[] at : trace)
		{
			Assert.assertNotNull("drawn every frame", at);
		}
		Assert.assertTrue("on the side it came from: " + along(last(trace), 1, 0), along(last(trace), 1, 0) >= 50);
		Assert.assertTrue("never past you", furthest(trace, 0, 1, 0, false) >= -STRIDE);
		Assert.assertTrue("never jumps", largestStep(trace) <= STRIDE);
		Assert.assertTrue("you're drawn where you stand", last(watch(g, 1, () -> { }, g.you).get(0))[0] == MIDDLE);
	}

	@Test
	public void someoneWalkingOntoAnotherPlayerFromTheNorthKeepsThatSide()
	{
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body comes = g.player(1, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body was = g.player(2, TILE, TILE);
		g.run(was, 100);
		List<List<int[]>> t = walkOn(g, comes, comes, was);
		assertKeptTheirSide("someone from the north", t.get(0), t.get(1), 0, 1);
		assertInLine("someone from the north", t.get(0), t.get(1), 0, 1);
	}

	@Test
	public void withGiveMeASpotOffWalkingOnFromTheSouthTheyStepAwayNorth()
	{
		// You stay in the middle: they used to step out east or west, across your way in.
		FollowerOnCrowdedTileTest.Game g = game();
		g.giveMeASpot = false;
		g.you = g.player(0, TILE, TILE - 2);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		Assert.assertArrayEquals("you stay in the middle", new int[]{0, 0}, spot(t.get(0)));
		int[] at = spot(t.get(1));
		Assert.assertTrue("they step away north: " + at[0] + "," + at[1], at[1] >= 40 && Math.abs(at[0]) < 10);
		Assert.assertTrue("never towards you", furthest(t.get(1), 0, 0, -1, true) <= STRIDE);
	}

	@Test
	public void aThirdFromTheNorthOntoAPairStandingEastAndWestStandsNorthOfThem()
	{
		// The pair formed walking in from the west, so it stands east and west; the third, from the
		// north, stands on the north side between them, and the pair stay where they are.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body c = g.player(3, TILE, TILE + 2);
		g.run(a, 100);
		watch(g, WALK_ON, () -> g.stepTowards(b, MIDDLE, MIDDLE), a, b);
		Assert.assertTrue(g.at(a)[0] > MIDDLE + 40 && g.at(b)[0] < MIDDLE - 40);
		List<List<int[]>> t = walkOn(g, c, c, a, b);
		int[] cAt = spot(t.get(0));
		Assert.assertTrue("on the north side: " + cAt[0] + "," + cAt[1], cAt[1] >= 40);
		Assert.assertTrue("never past the middle", furthest(t.get(0), 0, 0, 1, false) >= -STRIDE);
		Assert.assertTrue(spot(t.get(1))[0] > 40 && Math.abs(spot(t.get(1))[1]) < 10);
		Assert.assertTrue(spot(t.get(2))[0] < -40 && Math.abs(spot(t.get(2))[1]) < 10);
		for (int i = 1; i <= 2; i++)
		{
			int[] other = spot(t.get(i));
			Assert.assertTrue("nobody inside anybody", Math.hypot(other[0] - cAt[0], other[1] - cAt[1]) >= CrowdPlanner.NEIGHBOUR_GAP);
		}
	}

	@Test
	public void aThirdComingSquareOnToAPairOnADiagonalStandsOnTheirSide()
	{
		// A pair from the north-east stands on the diagonal. Of the two spots for a third, the one
		// after the pair's and the one across from it, both lay well round from the south: the spots
		// round the pair go round the other way instead, so one of them is.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE + 5, TILE + 5);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE + 2, TILE + 2);
		FollowerOnCrowdedTileTest.Body c = g.player(3, TILE, TILE - 2);
		g.run(a, 100);
		List<List<int[]>> pair = watch(g, WALK_ON, () -> g.stepTowards(b, MIDDLE, MIDDLE), b, a);
		assertKeptTheirSide("the second, from the north-east", pair.get(0), pair.get(1), 1, 1);
		assertInLine("the second, from the north-east", pair.get(0), pair.get(1), 1, 1);
		int[] aWas = spot(pair.get(1));
		int[] bWas = spot(pair.get(0));
		List<List<int[]>> t = walkOn(g, c, c, a, b);
		int[] cAt = spot(t.get(0));
		double off = Math.toDegrees(Math.abs(Math.atan2(cAt[0], -cAt[1])));
		Assert.assertTrue("on the south side, " + off + " degrees round from it: " + cAt[0] + "," + cAt[1], off < 20 && cAt[1] <= -40);
		Assert.assertTrue("never past the middle", furthest(t.get(0), 0, 0, -1, false) >= -STRIDE);
		// The pair only opened out for the third, each along their own line.
		Assert.assertTrue("the pair stayed put", Math.abs(Math.atan2(spot(t.get(1))[1], spot(t.get(1))[0]) - Math.atan2(aWas[1], aWas[0])) < 0.05
			&& Math.abs(Math.atan2(spot(t.get(2))[1], spot(t.get(2))[0]) - Math.atan2(bWas[1], bWas[0])) < 0.05);
		for (int i = 1; i <= 2; i++)
		{
			int[] other = spot(t.get(i));
			Assert.assertTrue("nobody inside anybody", Math.hypot(other[0] - cAt[0], other[1] - cAt[1]) >= CrowdPlanner.NEIGHBOUR_GAP);
		}
	}

	@Test
	public void aCasterKeepsTheMiddleAndWhoeverStepsOntoThemFromTheNorthStandsNorth()
	{
		// You used to be stood east of them, whichever of the front or back you came from.
		FollowerOnCrowdedTileTest.Game g = game();
		g.casting = id -> id == 1;
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body caster = g.player(1, TILE, TILE);
		g.run(caster, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, caster);
		Assert.assertArrayEquals("the caster stays where they stand", new int[]{0, 0}, spot(t.get(1)));
		int[] you = spot(t.get(0));
		Assert.assertTrue("you keep the north: " + you[0] + "," + you[1], you[1] >= 40 && Math.abs(you[0]) < 10);
		Assert.assertTrue("never through them", furthest(t.get(0), 0, 0, 1, false) >= -STRIDE);
	}

	@Test
	public void flamesAtYourEndOfTheWayYouCameTurnTheTwoOfYouTheNearestWayWithRoom()
	{
		// You walk across a Forester's Campfire from the north onto someone. Straight north of them
		// is in its flames: the two of you stand on the next way round, a diagonal, you still on
		// the north side and them on the south.
		FollowerOnCrowdedTileTest.Game g = game();
		g.fires.add(StackRegistry.key(0, TILE, TILE + 1));
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		int[] you = spot(t.get(0));
		int[] was = spot(t.get(1));
		Assert.assertTrue("on the north side: " + you[0] + "," + you[1], you[1] >= 30);
		Assert.assertTrue("clear of the flames", MIDDLE + you[1] + PersonalSpacePlugin.FIRE_CLEARANCE < (TILE + 1) * 128);
		Assert.assertTrue("they step south: " + was[0] + "," + was[1], was[1] <= -30);
		Assert.assertTrue("in a line through the middle", Math.abs(you[0] + was[0]) <= 2 && Math.abs(you[1] + was[1]) <= 2);
		Assert.assertTrue("never past the middle", furthest(t.get(0), 0, 0, 1, false) >= -STRIDE);
		Assert.assertTrue("they never step towards you", furthest(t.get(1), 0, 0, 1, true) <= STRIDE);
	}

	@Test
	public void thePairStaysAlongTheWayYouCameWhileTheCameraTurnsAndSomeoneComesAndGoes()
	{
		// Turning the camera moves nobody. Someone joining and leaving again doesn't put the two of
		// you back east and west either: whoever fills the gap the one you walked onto left behind
		// stands where they stood.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body third = g.player(2, TILE - 2, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		int[] you = spot(t.get(0));
		int[] was = spot(t.get(1));
		Assert.assertTrue("you on the north: " + you[0] + "," + you[1], you[1] >= 40 && Math.abs(you[0]) < 10);
		for (int[] camera : CAMERAS)
		{
			g.cameraX = MIDDLE + camera[0] + camera[1] / 2;
			g.cameraY = MIDDLE + camera[1] - camera[0] / 2;
			List<List<int[]>> after = watch(g, 300, () -> { }, g.you, them);
			Assert.assertArrayEquals("camera " + camera[0] + "," + camera[1], you, spot(after.get(0)));
			Assert.assertArrayEquals("camera " + camera[0] + "," + camera[1], was, spot(after.get(1)));
		}
		walkOn(g, third, third);
		watch(g, 64 * 3 + 900, () -> g.stepTowards(them, MIDDLE + 3 * 128, MIDDLE));
		int[] youNow = spot(watch(g, 1, () -> { }, g.you).get(0));
		int[] thirdNow = spot(watch(g, 1, () -> { }, third).get(0));
		Assert.assertTrue("you still on the north: " + youNow[0] + "," + youNow[1], youNow[1] >= 40 && Math.abs(youNow[0]) < 10);
		Assert.assertTrue("the third filled the gap to the south: " + thirdNow[0] + "," + thirdNow[1],
			thirdNow[1] <= -40 && Math.abs(thirdNow[0]) < 10);
	}

	@Test
	public void aPairThatBreaksUpAndMeetsAgainFromAnotherWayStandsTheNewWay()
	{
		// From the north: north and south. Off to the east and back a while later, or west and
		// straight back: along the new way each time, you at your end.
		FollowerOnCrowdedTileTest.Game g = game();
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("from the north", t.get(0), t.get(1), 0, 1);
		watch(g, 64 + 600, () -> g.stepTowards(g.you, MIDDLE + 2 * 128, MIDDLE));
		t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("back from the east", t.get(0), t.get(1), 1, 0);
		assertInLine("back from the east", t.get(0), t.get(1), 1, 0);
		watch(g, 64 + 60, () -> g.stepTowards(g.you, MIDDLE - 2 * 128, MIDDLE));
		t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("straight back from the west", t.get(0), t.get(1), -1, 0);
		assertInLine("straight back from the west", t.get(0), t.get(1), -1, 0);
	}

	@Test
	public void facingEachOtherAPairFromTheFrontFacesAlongTheWayItStands()
	{
		// With the small group pose set to Facing, the two of you face each other: north and south,
		// standing north and south.
		FollowerOnCrowdedTileTest.Game g = game();
		g.planner.pose = PersonalSpaceConfig.Pose.FACING;
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body them = g.player(1, TILE, TILE);
		them.orientation = 1536;
		g.run(them, 100);
		List<List<int[]>> t = walkOn(g, g.you, g.you, them);
		assertKeptTheirSide("from the north", t.get(0), t.get(1), 0, 1);
		Assert.assertEquals("you face south, at them", 0, last(t.get(0))[2]);
		Assert.assertEquals("they face north, at you", 1024, last(t.get(1))[2]);
	}

	@Test
	public void yourPetWalkingOntoYouFromTheFrontOrBackKeepsThatSide()
	{
		// Pets already step out on any of sixteen ways: the front and back as well as the sides.
		for (int north : new int[]{2, -2})
		{
			FollowerOnCrowdedTileTest.Game g = game();
			g.you = g.player(0, TILE, TILE);
			FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE + north, true);
			pet.interacting = g.you.actor;
			pet.orientation = north > 0 ? 0 : 1024;
			g.run(pet, 100);
			List<int[]> trace = walkOn(g, pet, pet).get(0);
			String way = north > 0 ? "from the north" : "from the south";
			for (int[] at : trace)
			{
				Assert.assertNotNull("drawn every frame, " + way, at);
			}
			Assert.assertTrue("on the side it came from, " + way + ": " + along(last(trace), 0, north), along(last(trace), 0, north) >= 50);
			Assert.assertTrue("never past you, " + way, furthest(trace, 0, 0, north, false) >= -STRIDE);
			Assert.assertTrue("never jumps", largestStep(trace) <= STRIDE);
		}
	}
}
