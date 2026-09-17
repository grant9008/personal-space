package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CrowdPlannerTest
{
	private static final long TILE = StackRegistry.key(0, 50, 50);
	private static final int NORTH = 1024;
	private static final int SOUTH = 0;

	private static CrowdPlanner.Surroundings surroundings(boolean obstacle, boolean counter, boolean fire)
	{
		return new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return true;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return obstacle;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return counter;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return fire;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				return new ArrayList<>();
			}
		};
	}

	private static final CrowdPlanner.Surroundings OPEN = surroundings(false, false, false);
	private static final CrowdPlanner.Surroundings ANVIL = surroundings(true, false, false);

	/** Players on {@link #TILE}, given as id, orientation pairs. */
	private static List<StackSpreader.Entry> players(int... idAndOrientation)
	{
		List<StackSpreader.Entry> out = new ArrayList<>();
		for (int i = 0; i < idAndOrientation.length; i += 2)
		{
			out.add(new StackSpreader.Entry(idAndOrientation[i], TILE, false, idAndOrientation[i + 1]));
		}
		return out;
	}

	private static CrowdPlanner.Plan tick(CrowdPlanner planner, int tick, List<StackSpreader.Entry> still)
	{
		return planner.plan(still, id -> true, 72, 5, true, false, tick, OPEN);
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

	@Test
	public void aPlayerLeftOnTheirOwnWalksBackToTheMiddleAndStaysThere()
	{
		// The last player on a tile used to be sent out to their spot and back every tick, forever.
		CrowdPlanner planner = new CrowdPlanner();
		int t = 1;
		for (; t <= 3; t++)
		{
			Assert.assertEquals(2, tick(planner, t, players(1, NORTH, 2, SOUTH)).placements.size());
		}
		for (; t <= 40; t++)
		{
			Assert.assertTrue("tick " + t + ": the lone player must stay in the middle",
				tick(planner, t, players(1, NORTH)).placements.isEmpty());
		}
	}

	@Test
	public void aPairThatSplitsAndMeetsAgainGoesBackToTheSameSpots()
	{
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> before = spots(tick(planner, 1, players(1, NORTH, 2, SOUTH)));
		Assert.assertTrue(tick(planner, 2, players(1, NORTH)).placements.isEmpty());
		Map<Integer, int[]> after = spots(tick(planner, 3, players(1, NORTH, 2, SOUTH)));
		Assert.assertArrayEquals(before.get(1), after.get(1));
		Assert.assertArrayEquals(before.get(2), after.get(2));
	}

	@Test
	public void theRestKeepTheirSpotsWhileSomeoneStepsAwayAndBack()
	{
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> before = spots(tick(planner, 1, players(1, NORTH, 2, SOUTH, 3, NORTH)));
		for (int t = 2; t <= 4; t++)
		{
			Map<Integer, int[]> away = spots(tick(planner, t, players(1, NORTH, 2, SOUTH)));
			Assert.assertArrayEquals(before.get(1), away.get(1));
			Assert.assertArrayEquals(before.get(2), away.get(2));
		}
		Map<Integer, int[]> back = spots(tick(planner, 5, players(1, NORTH, 2, SOUTH, 3, NORTH)));
		for (int id = 1; id <= 3; id++)
		{
			Assert.assertArrayEquals("player " + id, before.get(id), back.get(id));
		}
	}

	@Test
	public void playersTheGameIsNotShowingDoNotPushAnyoneAside()
	{
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = planner.plan(players(1, NORTH, 2, SOUTH), id -> id == 1, 72, 5, true, false, 1, OPEN);
		Assert.assertTrue(plan.placements.isEmpty());
		Assert.assertEquals(1, plan.unseen);
		Assert.assertEquals("both stay listed so the game can be asked about the hidden one", 2, plan.unplaced.size());
	}

	@Test
	public void youStayInTheMiddleAndTheOthersMakeRoom()
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		still.add(new StackSpreader.Entry(1, TILE, true, NORTH));
		still.add(new StackSpreader.Entry(2, TILE, false, NORTH));
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, 72, 5, true, false, 1, OPEN);
		Map<Integer, int[]> spots = spots(plan);
		Assert.assertFalse(spots.containsKey(1));
		Assert.assertTrue(spots.get(2)[0] != 0 || spots.get(2)[1] != 0);
		Assert.assertEquals(1, plan.unplaced.size());
		Assert.assertEquals(1, plan.unplaced.get(0).id);
	}

	@Test
	public void aCounterGetsAStraightCloseRowWhosePlayersKeepFacingIt()
	{
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH, 3, NORTH, 4, NORTH),
			id -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, 1, surroundings(true, true, false));
		Assert.assertEquals(4, plan.placements.size());
		List<Integer> xs = new ArrayList<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals("everyone level with the counter", 0, p.dz);
			xs.add(p.dx);
		}
		xs.sort(Integer::compare);
		for (int i = 1; i < xs.size(); i++)
		{
			Assert.assertEquals("close together", PersonalSpaceConfig.COUNTER_SPACING, xs.get(i) - xs.get(i - 1), 1);
		}
		Assert.assertTrue("a straight row doesn't turn anyone", plan.curvedRows.isEmpty());
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
	}

	@Test
	public void rowsAtNeighbouringBoothsNeverLandOnTopOfEachOther()
	{
		long east = StackRegistry.key(0, 51, 50);
		CrowdPlanner.Surroundings counters = surroundings(true, true, false);
		for (int onA = 2; onA <= 5; onA++)
		{
			for (int onB = 1; onB <= 5; onB++)
			{
				List<StackSpreader.Entry> still = new ArrayList<>();
				for (int i = 0; i < onA; i++)
				{
					still.add(new StackSpreader.Entry(1 + i, TILE, false, NORTH));
				}
				for (int i = 0; i < onB; i++)
				{
					still.add(new StackSpreader.Entry(20 + i, east, false, NORTH));
				}
				CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, 72, 5, true, false, 1, counters);
				Map<Integer, int[]> at = new HashMap<>();
				for (StackSpreader.Entry e : still)
				{
					at.put(e.id, new int[]{StackRegistry.sceneX(e.tile) * 128, 0});
				}
				for (StackSpreader.Placement p : plan.placements)
				{
					at.get(p.id)[0] += p.dx;
					at.get(p.id)[1] += p.dz;
				}
				for (StackSpreader.Entry a : still)
				{
					for (StackSpreader.Entry b : still)
					{
						if (a.tile == TILE && b.tile == east)
						{
							double gap = Math.hypot(at.get(a.id)[0] - at.get(b.id)[0], at.get(a.id)[1] - at.get(b.id)[1]);
							Assert.assertTrue(onA + " and " + onB + ": players " + a.id + " and " + b.id + " only " + gap + " apart",
								gap >= PersonalSpaceConfig.COUNTER_SPACING - 1);
						}
					}
				}
			}
		}
	}

	@Test
	public void rowsAtBoothsEitherSideOfAnEmptyBoothDontMeetInTheGap()
	{
		long twoOver = StackRegistry.key(0, 52, 50);
		CrowdPlanner.Surroundings counters = surroundings(true, true, false);
		for (int onA = 2; onA <= 10; onA++)
		{
			for (int onC = 2; onC <= 10; onC++)
			{
				List<StackSpreader.Entry> still = new ArrayList<>();
				for (int i = 0; i < onA; i++)
				{
					still.add(new StackSpreader.Entry(1 + i, TILE, false, NORTH));
				}
				for (int i = 0; i < onC; i++)
				{
					still.add(new StackSpreader.Entry(20 + i, twoOver, false, NORTH));
				}
				CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, 72, 10, true, false, 1, counters);
				Map<Integer, int[]> at = new HashMap<>();
				for (StackSpreader.Placement p : plan.placements)
				{
					at.put(p.id, new int[]{StackRegistry.sceneX(p.tile) * 128 + p.dx, p.dz});
				}
				for (Map.Entry<Integer, int[]> a : at.entrySet())
				{
					for (Map.Entry<Integer, int[]> c : at.entrySet())
					{
						if (a.getKey() < 20 && c.getKey() >= 20)
						{
							double gap = Math.hypot(a.getValue()[0] - c.getValue()[0], a.getValue()[1] - c.getValue()[1]);
							Assert.assertTrue(onA + " and " + onC + ": " + gap + " apart", gap >= PersonalSpaceConfig.COUNTER_SPACING - 1);
						}
					}
				}
			}
		}
	}

	@Test
	public void theMiddleIsNotGivenAwayWhenWallsLeaveTooFewSpots()
	{
		// A corridor running north-south: only spots straight north or south of the middle are free.
		CrowdPlanner.Surroundings corridor = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return dx == 0;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return false;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				return new ArrayList<>();
			}
		};
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, SOUTH, 3, NORTH, 4, SOUTH),
			id -> true, 72, 5, true, false, 1, corridor);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertFalse("player " + p.id + " was given the middle", p.dx == 0 && p.dz == 0);
		}
		Assert.assertFalse(plan.unplaced.isEmpty());
	}

	/** Where each placed player is drawn, in scene local units: {x, z}. */
	private static Map<Integer, int[]> drawnAt(CrowdPlanner.Plan plan)
	{
		Map<Integer, int[]> at = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			at.put(p.id, new int[]{StackRegistry.sceneX(p.tile) * 128 + p.dx, StackRegistry.sceneY(p.tile) * 128 + p.dz});
		}
		return at;
	}

	@Test
	public void fishersOnNeighbouringBankTilesShareOneLineAlongTheBank()
	{
		// The reported case: four bank tiles in a row, 3 or 4 fishers on each, all facing the water to
		// the north. Everyone should stand at the edge, nobody in a row behind.
		CrowdPlanner.Surroundings bank = surroundings(true, true, false);
		List<StackSpreader.Entry> still = new ArrayList<>();
		int id = 1;
		int[] perTile = {3, 4, 3, 4};
		for (int t = 0; t < perTile.length; t++)
		{
			for (int i = 0; i < perTile[t]; i++)
			{
				still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, 50 + t, 50), false, NORTH));
			}
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, bank);
		Assert.assertEquals(14, plan.placements.size());
		Map<Integer, int[]> at = drawnAt(plan);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals("player " + p.id + " is at the edge, not in a row behind", 0, p.dz);
		}
		List<int[]> sorted = new ArrayList<>(at.values());
		sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int i = 1; i < sorted.size(); i++)
		{
			Assert.assertTrue("neighbours only " + (sorted.get(i)[0] - sorted.get(i - 1)[0]) + " apart",
				sorted.get(i)[0] - sorted.get(i - 1)[0] >= PersonalSpaceConfig.COUNTER_SPACING - 1);
		}
		Assert.assertTrue(plan.tiles.get(StackRegistry.key(0, 51, 50)).shape.startsWith("counter row shared"));
	}

	@Test
	public void aSharedLineKeepsEachTilesPeopleTogetherInOrder()
	{
		CrowdPlanner.Surroundings bank = surroundings(true, true, false);
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			still.add(new StackSpreader.Entry(1 + i, StackRegistry.key(0, 50, 50), false, NORTH));
			still.add(new StackSpreader.Entry(20 + i, StackRegistry.key(0, 51, 50), false, NORTH));
		}
		Map<Integer, int[]> at = drawnAt(new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, bank));
		for (int a = 1; a <= 5; a++)
		{
			for (int b = 20; b <= 24; b++)
			{
				Assert.assertTrue("west tile's player " + a + " is west of east tile's player " + b, at.get(a)[0] < at.get(b)[0]);
			}
		}
	}

	@Test
	public void aSharedLineThatCantFitFallsBackToRows()
	{
		// Walls a tile either side of the pair of tiles: not enough edge for ten people in one line.
		CrowdPlanner.Surroundings cramped = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return x >= 50 * 128 - 40 && x <= 51 * 128 + 40;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return true;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return true;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return false;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				return new ArrayList<>();
			}
		};
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			still.add(new StackSpreader.Entry(1 + i, StackRegistry.key(0, 50, 50), false, NORTH));
			still.add(new StackSpreader.Entry(20 + i, StackRegistry.key(0, 51, 50), false, NORTH));
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, cramped);
		Assert.assertEquals("counter row", plan.tiles.get(StackRegistry.key(0, 50, 50)).shape);
	}

	@Test
	public void aCounterRowRunsAlongTheCounterEvenIfItFormedAtASlant()
	{
		// Facing north-north-east: still a counter row, laid out exactly east-west.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, 1100, 2, 1100, 3, 1100),
			id -> true, 72, 5, true, false, 1, surroundings(true, true, false));
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals("level with the counter", 0, p.dz);
		}
	}

	@Test
	public void youAreListedBeforeOthersWhoStayInTheMiddle()
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 1; i <= 7; i++)
		{
			still.add(new StackSpreader.Entry(i, TILE, false, i * 300));
		}
		still.add(new StackSpreader.Entry(9, TILE, true, 0));
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, 72, 5, true, false, 1, OPEN);
		Assert.assertEquals(9, plan.unplaced.get(0).id);
	}

	@Test
	public void smallGroupsStandCloseAndBigCrowdsGetTheFullSpacing()
	{
		int wide = PersonalSpaceConfig.SPACING_WIDE;
		Assert.assertEquals(PersonalSpaceConfig.PAIR_SPACING, CrowdPlanner.spacingFor(wide, 2));
		Assert.assertEquals(wide, CrowdPlanner.spacingFor(wide, PersonalSpaceConfig.FULL_CROWD));
		Assert.assertEquals(wide, CrowdPlanner.spacingFor(wide, 10));
		Assert.assertTrue(CrowdPlanner.spacingFor(wide, 4) > CrowdPlanner.spacingFor(wide, 3));
		Assert.assertEquals("a close setting is never widened", 40, CrowdPlanner.spacingFor(40, 8));

		// A pair in the open at the widest setting stays within a tile of each other.
		Map<Integer, int[]> pair = spots(new CrowdPlanner().plan(players(1, NORTH, 2, SOUTH), id -> true, wide, 10, true, false, 1, OPEN));
		Assert.assertTrue(Math.hypot(pair.get(1)[0] - pair.get(2)[0], pair.get(1)[1] - pair.get(2)[1]) < 128);
	}

	@Test
	public void aCrowdDoesNotShrinkTheMomentSomeoneStepsAway()
	{
		CrowdPlanner planner = new CrowdPlanner();
		int wide = PersonalSpaceConfig.SPACING_WIDE;
		List<StackSpreader.Entry> six = players(1, NORTH, 2, SOUTH, 3, NORTH, 4, SOUTH, 5, NORTH, 6, SOUTH);
		List<StackSpreader.Entry> five = players(1, NORTH, 2, SOUTH, 3, NORTH, 4, SOUTH, 5, NORTH);
		Map<Integer, int[]> before = spots(planner.plan(six, id -> true, wide, 10, true, false, 1, OPEN));
		int t = 2;
		for (; t <= 1 + SlotBook.HOLD_TICKS; t++)
		{
			Map<Integer, int[]> away = spots(planner.plan(five, id -> true, wide, 10, true, false, t, OPEN));
			Assert.assertArrayEquals("tick " + t, before.get(1), away.get(1));
		}
		Map<Integer, int[]> later = spots(planner.plan(five, id -> true, wide, 10, true, false, t + 1, OPEN));
		Assert.assertTrue("closes in once they've been gone a while",
			Math.hypot(later.get(1)[0], later.get(1)[1]) < Math.hypot(before.get(1)[0], before.get(1)[1]));
	}

	@Test
	public void playersFacingTheSameWayInTheOpenStandSideBySideAsSeenFromTheCamera()
	{
		// Both facing east with nothing in front of them: a crowd, so they stand east and west of
		// each other rather than one behind the other.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, 1536, 2, 1536), id -> true, 72, 5, true, false, 1, OPEN);
		Assert.assertTrue(plan.curvedRows.isEmpty());
		Assert.assertEquals("crowd", plan.tiles.get(TILE).shape);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals(0, p.dz);
		}
	}

	@Test
	public void withSmallGroupsCloseOffThePairUsesTheFullSpacing()
	{
		CrowdPlanner planner = new CrowdPlanner();
		planner.smallGroupsClose = false;
		CrowdPlanner.Plan plan = planner.plan(players(1, NORTH, 2, SOUTH), id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, OPEN);
		Assert.assertEquals(PersonalSpaceConfig.SPACING_WIDE, plan.tiles.get(TILE).spacing);
	}

	@Test
	public void aBigCrowdRoundAFireStaysCloseAtAnySpacing()
	{
		CrowdPlanner.Surroundings fireNorth = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return true;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return false;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				List<int[]> fires = new ArrayList<>();
				fires.add(new int[]{0, 1});
				return fires;
			}
		};
		// Ten players, most facing the fire to the north but not all the same way: a crowd round the fire.
		List<StackSpreader.Entry> ten = players(1, NORTH, 2, 900, 3, 1150, 4, NORTH, 5, 950, 6, 1100, 7, NORTH, 8, SOUTH, 9, 600, 10, NORTH);
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(ten, id -> true, PersonalSpaceConfig.MAX_SPACING, 10, true, false, 1, fireNorth);
		Assert.assertEquals(PersonalSpaceConfig.FIRE_SPACING, plan.tiles.get(TILE).spacing);
		Assert.assertArrayEquals(new int[]{0, 128}, plan.fires.get(TILE));
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertTrue("player " + p.id + " wandered off: " + p.dx + "," + p.dz, Math.hypot(p.dx, p.dz) <= 2 * PersonalSpaceConfig.FIRE_SPACING);
		}
	}

	@Test
	public void smallGroupsInTheOpenArePosedButBigOnesAndRowsAreNot()
	{
		CrowdPlanner planner = new CrowdPlanner();
		planner.pose = PersonalSpaceConfig.Pose.FACING;
		Assert.assertEquals(PersonalSpaceConfig.Pose.FACING,
			planner.plan(players(1, NORTH, 2, SOUTH), id -> true, 72, 5, true, false, 1, OPEN).poses.get(TILE));
		CrowdPlanner three = new CrowdPlanner();
		three.pose = PersonalSpaceConfig.Pose.FACING;
		Assert.assertEquals(PersonalSpaceConfig.Pose.FACING,
			three.plan(players(1, NORTH, 2, SOUTH, 3, 512), id -> true, 72, 5, true, false, 1, OPEN).poses.get(TILE));

		CrowdPlanner four = new CrowdPlanner();
		four.pose = PersonalSpaceConfig.Pose.FACING;
		Assert.assertNull(four.plan(players(1, NORTH, 2, SOUTH, 3, 512, 4, 1536), id -> true, 72, 5, true, false, 1, OPEN).poses.get(TILE));

		CrowdPlanner anvil = new CrowdPlanner();
		anvil.pose = PersonalSpaceConfig.Pose.FACING;
		Assert.assertNull("an anvil row keeps facing the anvil",
			anvil.plan(players(1, NORTH, 2, NORTH), id -> true, 72, 5, true, false, 1, ANVIL).poses.get(TILE));

		Assert.assertTrue("natural by default", new CrowdPlanner().plan(players(1, NORTH, 2, SOUTH), id -> true, 72, 5, true, false, 1, OPEN).poses.isEmpty());
	}

	@Test
	public void aCrowdFacingAFireIsTurnedToFaceIt()
	{
		List<int[]> fireNorth = new ArrayList<>();
		fireNorth.add(new int[]{0, 1});
		List<StackSpreader.Entry> facingIt = players(1, NORTH, 2, NORTH, 3, 1200, 4, SOUTH);
		Assert.assertArrayEquals(new int[]{0, 1}, CrowdPlanner.fireFaced(facingIt, fireNorth));
		Assert.assertNull("most face away from it", CrowdPlanner.fireFaced(players(1, SOUTH, 2, SOUTH, 3, NORTH), fireNorth));
		Assert.assertNull("no fire", CrowdPlanner.fireFaced(facingIt, new ArrayList<>()));

		// Drawn south of the tile, a player faces north to the fire; drawn past it, they turn back south.
		Assert.assertEquals(1024, StackSpreader.faceTowards(0, 0, -100, 0, 128));
		Assert.assertEquals(0, StackSpreader.faceTowards(1024, 0, 250, 0, 128));
	}

	/** A bank counter to the north only: nothing to face in any other direction. */
	private static final CrowdPlanner.Surroundings COUNTER_NORTH = new CrowdPlanner.Surroundings()
	{
		@Override
		public boolean canStand(long tile, int dx, int dz)
		{
			return true;
		}

		@Override
		public boolean facesObstacle(long tile, double angle)
		{
			return Math.abs(angle - Math.PI) < 0.01;
		}

		@Override
		public boolean isCounter(long tile, double angle)
		{
			return facesObstacle(tile, angle);
		}

		@Override
		public boolean facesFire(long tile, double angle)
		{
			return false;
		}

		@Override
		public List<int[]> firesNear(long tile)
		{
			return new ArrayList<>();
		}
	};

	@Test
	public void aBankCrowdLinesUpWhenMostOfThemFaceTheBooth()
	{
		// Three banking, two casting spells facing elsewhere.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH, 3, NORTH, 4, 1536, 5, SOUTH),
			id -> true, 72, 5, true, false, 1, COUNTER_NORTH);
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals("player " + p.id + " level with the counter", 0, p.dz);
		}

		CrowdPlanner fewFacing = new CrowdPlanner();
		Assert.assertEquals("only one of five faces the booth: a crowd", "crowd",
			fewFacing.plan(players(1, NORTH, 2, 1536, 3, SOUTH, 4, 512, 5, SOUTH), id -> true, 72, 5, true, false, 1, COUNTER_NORTH)
				.tiles.get(TILE).shape);
	}

	@Test
	public void aBankRowStaysLinedUpWhenTheCastersTurnAround()
	{
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> first = spots(planner.plan(players(1, NORTH, 2, NORTH, 3, NORTH, 4, 1536, 5, SOUTH),
			id -> true, 72, 5, true, false, 1, COUNTER_NORTH));
		CrowdPlanner.Plan later = planner.plan(players(1, NORTH, 2, SOUTH, 3, 1536, 4, 1536, 5, SOUTH),
			id -> true, 72, 5, true, false, 2, COUNTER_NORTH);
		Assert.assertEquals("counter row", later.tiles.get(TILE).shape);
		Map<Integer, int[]> second = spots(later);
		for (int id = 1; id <= 5; id++)
		{
			Assert.assertArrayEquals("player " + id + " kept their spot", first.get(id), second.get(id));
		}
	}

	@Test
	public void peopleOnTwoSidesOfATreeDontEndUpBehindEachOther()
	{
		// A tree at (50, 51). Five chop from the tile south of it, facing north; five from the tile west
		// of it, facing east.
		long south = StackRegistry.key(0, 50, 50);
		long west = StackRegistry.key(0, 49, 51);
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			still.add(new StackSpreader.Entry(1 + i, south, false, NORTH));
			still.add(new StackSpreader.Entry(20 + i, west, false, 1536));
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, 1, ANVIL);
		Assert.assertEquals(10, plan.placements.size());
		Map<Integer, int[]> at = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			at.put(p.id, new int[]{StackRegistry.sceneX(p.tile) * 128 + p.dx, StackRegistry.sceneY(p.tile) * 128 + p.dz});
		}
		for (int a = 1; a <= 5; a++)
		{
			for (int b = 20; b <= 24; b++)
			{
				double gap = Math.hypot(at.get(a)[0] - at.get(b)[0], at.get(a)[1] - at.get(b)[1]);
				Assert.assertTrue("players " + a + " and " + b + " only " + gap + " apart", gap >= PersonalSpaceConfig.COUNTER_SPACING);
			}
		}
	}

	@Test
	public void aTileAloneAtATreeWrapsRoundItBeforeFormingASecondRow()
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 0; i < 8; i++)
		{
			still.add(new StackSpreader.Entry(1 + i, TILE, false, NORTH));
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, id -> true, 72, 10, true, false, 1, ANVIL);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertEquals("player " + p.id + " is in the front ring round the tree", 128, Math.hypot(p.dx, 128 - p.dz), 1.5);
		}
	}

	@Test
	public void aRowAtAnAnvilCurvesAndTurnsPlayersToFaceIt()
	{
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH, 3, NORTH), id -> true, 72, 5, true, false, 1, ANVIL);
		Assert.assertTrue(plan.curvedRows.contains(TILE));
		Assert.assertEquals("curved row", plan.tiles.get(TILE).shape);
	}

	@Test
	public void peopleAtAFireStayClose()
	{
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH),
			id -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, 1, surroundings(false, false, true));
		Assert.assertEquals(PersonalSpaceConfig.FIRE_SPACING, plan.tiles.get(TILE).spacing);
		Assert.assertTrue(plan.curvedRows.contains(TILE));
	}
}
