package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class CrowdPlannerTest
{
	private static final long TILE = StackRegistry.key(0, 50, 50);
	private static final int NORTH = 1024;
	/** The least room anyone may be given, as a distance between two drawn players. */
	private static final double MIN_SHARED_SPACING_FOR_TEST = 25;
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
			Assert.assertTrue("player " + p.id + " stands " + p.dz + " back, further than the bow allows",
				Math.abs(p.dz) <= StackSpreader.MAX_BOW);
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
								gap >= PersonalSpaceConfig.LINE_SPACING - 1);
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
			Assert.assertTrue("player " + p.id + " is in a row behind, not at the edge (" + p.dz + ")",
				Math.abs(p.dz) <= StackSpreader.MAX_BOW);
		}
		List<int[]> sorted = new ArrayList<>(at.values());
		sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int i = 1; i < sorted.size(); i++)
		{
			Assert.assertTrue("neighbours only " + (sorted.get(i)[0] - sorted.get(i - 1)[0]) + " apart",
				sorted.get(i)[0] - sorted.get(i - 1)[0] >= PersonalSpaceConfig.LINE_SPACING - 1);
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
	public void aBoxedInBankTileSqueezesEveryoneIntoARingRatherThanHidingThem()
	{
		// A little point of land: water and rock all round, room only inside the tile itself.
		CrowdPlanner.Surroundings point = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return Math.abs(dx) <= 45 && Math.abs(dz) <= 45;
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
		for (int i = 0; i < 7; i++)
		{
			still.add(new StackSpreader.Entry(1 + i, TILE, false, NORTH));
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, point);
		Assert.assertEquals("all seven fishers get a spot", 7, plan.placements.size());
		Assert.assertTrue(plan.tiles.get(TILE).shape.endsWith("squeezed into a ring"));
	}

	@Test
	public void tooManyFishersForTheEdgeStandInTidyRowsBehindTheirOwnStretch()
	{
		// Four bank tiles, seven fishers each, water north, but only those four tiles of edge.
		CrowdPlanner.Surroundings shortBank = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz <= 0 && x >= 50 * 128 - 40 && x <= 53 * 128 + 40;
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
		for (int t = 0; t < 4; t++)
		{
			for (int i = 0; i < 7; i++)
			{
				still.add(new StackSpreader.Entry(t * 10 + i + 1, StackRegistry.key(0, 50 + t, 50), false, NORTH));
			}
		}
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, shortBank);
		Assert.assertEquals("everyone is shown", 28, plan.placements.size());
		Map<Integer, int[]> at = drawnAt(plan);
		int edge = 0;
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertTrue("player " + p.id + " is on the land side", p.dz <= 0);
			// At the edge, allowing for the gentle bow round each tile's own stretch of it.
			edge += Math.abs(p.dz) <= StackSpreader.MAX_BOW ? 1 : 0;
		}
		Assert.assertTrue("the edge is filled first: " + edge, edge >= 9);
		List<int[]> all = new ArrayList<>(at.values());
		for (int a = 0; a < all.size(); a++)
		{
			for (int b = a + 1; b < all.size(); b++)
			{
				double gap = Math.hypot(all.get(a)[0] - all.get(b)[0], all.get(a)[1] - all.get(b)[1]);
				Assert.assertTrue("two fishers only " + gap + " apart", gap >= 40);
			}
		}
		// Within each row, the tiles keep their people in order along the bank.
		for (int t = 0; t < 3; t++)
		{
			for (int i = 1; i <= 7; i++)
			{
				for (int k = 1; k <= 7; k++)
				{
					int[] mine = at.get(t * 10 + i);
					int[] next = at.get((t + 1) * 10 + k);
					if (mine[1] == next[1])
					{
						Assert.assertTrue("tile " + t + " passes tile " + (t + 1) + " in a row", mine[0] < next[0]);
					}
				}
			}
		}
	}

	@Test
	public void aCrampedSharedLineNeverPutsPeopleOnTopOfEachOther()
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
		List<int[]> all = new ArrayList<>(drawnAt(plan).values());
		for (int a = 0; a < all.size(); a++)
		{
			for (int b = a + 1; b < all.size(); b++)
			{
				Assert.assertTrue("two people on top of each other",
					Math.hypot(all.get(a)[0] - all.get(b)[0], all.get(a)[1] - all.get(b)[1]) >= 40);
			}
		}
	}

	/** Four bank tiles facing water to the north, open land along them. */
	private static List<StackSpreader.Entry> bank(int[] perTile, int firstId)
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		int id = firstId;
		for (int t = 0; t < perTile.length; t++)
		{
			for (int i = 0; i < perTile[t]; i++)
			{
				still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, 50 + t, 50), false, NORTH));
			}
		}
		return still;
	}

	@Test
	public void onASharedLineNobodyElseMovesWhenSomeoneArrivesOrLeaves()
	{
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings open = surroundings(true, true, false);
		List<StackSpreader.Entry> before = bank(new int[]{3, 4, 3, 4}, 1);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(before, x -> true, 128, 10, true, false, t, open));
		}
		// Someone new joins the second tile.
		List<StackSpreader.Entry> joined = new ArrayList<>(before);
		joined.add(new StackSpreader.Entry(99, StackRegistry.key(0, 51, 50), false, NORTH));
		Map<Integer, int[]> after = spots(planner.plan(joined, x -> true, 128, 10, true, false, 4, open));
		for (Map.Entry<Integer, int[]> e : settled.entrySet())
		{
			Assert.assertArrayEquals("player " + e.getKey() + " moved when someone joined", e.getValue(), after.get(e.getKey()));
		}
		Assert.assertTrue(after.containsKey(99));
		// Someone leaves the third tile.
		List<StackSpreader.Entry> left = new ArrayList<>(joined);
		left.removeIf(en -> en.id == 8);
		Map<Integer, int[]> gone = spots(planner.plan(left, x -> true, 128, 10, true, false, 5, open));
		for (Map.Entry<Integer, int[]> e : gone.entrySet())
		{
			Assert.assertArrayEquals("player " + e.getKey() + " moved when someone left", after.get(e.getKey()), e.getValue());
		}
	}

	@Test
	public void youGetAPlaceAtTheEdgeOfABusyBankAndOnlyOnePersonMakesRoom()
	{
		// Two bank tiles with water only in front of them and land only behind them: five spots at the
		// edge for fourteen people, so most stand in rows behind.
		CrowdPlanner.Surroundings shortBank = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz <= 0 && x >= 50 * 128 - 40 && x <= 51 * 128 + 40;
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
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> others = bank(new int[]{7, 6}, 1);
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 3; t++)
		{
			before = spots(planner.plan(others, x -> true, 128, 10, true, true, t, shortBank));
		}
		List<StackSpreader.Entry> withYou = new ArrayList<>(others);
		withYou.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
		Map<Integer, int[]> after = null;
		for (int t = 4; t <= 4 + SlotBook.LOCAL_SWAP_DELAY; t++)
		{
			after = spots(planner.plan(withYou, x -> true, 128, 10, true, true, t, shortBank));
		}
		Assert.assertTrue("you stand " + after.get(99)[1] + " back, not at the water's edge",
			Math.abs(after.get(99)[1]) <= StackSpreader.MAX_BOW);
		int moved = 0;
		for (Map.Entry<Integer, int[]> e : before.entrySet())
		{
			moved += java.util.Arrays.equals(e.getValue(), after.get(e.getKey())) ? 0 : 1;
		}
		Assert.assertEquals("only the person who swapped with you moved", 1, moved);
		Map<Integer, int[]> later = spots(planner.plan(withYou, x -> true, 128, 10, true, true, 30, shortBank));
		for (Map.Entry<Integer, int[]> e : after.entrySet())
		{
			Assert.assertArrayEquals("player " + e.getKey() + " moved again", e.getValue(), later.get(e.getKey()));
		}
	}

	@Test
	public void aTileDroppingToOneFisherDoesNotBreakUpTheLine()
	{
		// Fishing tiles go between one and two people all the time. The line must not send everyone
		// standing over that tile to a row behind each time it happens.
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings open = surroundings(true, true, false);
		List<StackSpreader.Entry> before = bank(new int[]{2, 4, 4, 2}, 1);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(before, x -> true, 128, 10, true, false, t, open));
		}
		List<StackSpreader.Entry> after = new ArrayList<>(before);
		after.removeIf(en -> en.id == 12); // the last tile drops to one fisher
		for (int t = 4; t <= 14; t++)
		{
			Map<Integer, int[]> now = spots(planner.plan(after, x -> true, 128, 10, true, false, t, open));
			for (Map.Entry<Integer, int[]> e : now.entrySet())
			{
				Assert.assertArrayEquals("tick " + t + ": player " + e.getKey() + " moved", settled.get(e.getKey()), e.getValue());
			}
			Assert.assertTrue("the fisher left alone keeps their place in the line", now.containsKey(11));
		}
	}

	@Test
	public void someoneStoppingBesideOneBusyBankTileDoesNotReshuffleIt()
	{
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings open = surroundings(true, true, false);
		List<StackSpreader.Entry> alone = bank(new int[]{4}, 1);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(alone, x -> true, 128, 10, true, false, t, open));
		}
		List<StackSpreader.Entry> withPasser = new ArrayList<>(alone);
		withPasser.add(new StackSpreader.Entry(50, StackRegistry.key(0, 51, 50), false, NORTH));
		for (int t = 4; t <= 12; t++)
		{
			Map<Integer, int[]> now = spots(planner.plan(t < 9 ? withPasser : alone, x -> true, 128, 10, true, false, t, open));
			for (Map.Entry<Integer, int[]> e : settled.entrySet())
			{
				Assert.assertArrayEquals("tick " + t + ": player " + e.getKey() + " moved", e.getValue(), now.get(e.getKey()));
			}
		}
	}

	@Test
	public void aTileStaysOnItsLineWhenTheTileBesideItEmpties()
	{
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings open = surroundings(true, true, false);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(bank(new int[]{5, 2}, 1), x -> true, 128, 10, true, false, t, open));
		}
		for (int t = 4; t <= 20; t++)
		{
			Map<Integer, int[]> now = spots(planner.plan(bank(new int[]{5}, 1), x -> true, 128, 10, true, false, t, open));
			for (int id = 1; id <= 5; id++)
			{
				Assert.assertArrayEquals("tick " + t + ": player " + id + " moved", settled.get(id), now.get(id));
			}
		}
	}

	@Test
	public void someoneStandingByTheBankFacingAwayIsLeftAlone()
	{
		List<StackSpreader.Entry> still = bank(new int[]{3, 3}, 1);
		still.add(new StackSpreader.Entry(50, StackRegistry.key(0, 52, 50), false, SOUTH));
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, surroundings(true, true, false));
		Assert.assertFalse(spots(plan).containsKey(50));
	}

	@Test
	public void aLineKeepsClearOfACrowdOnTheTileBehindIt()
	{
		// Two bank tiles with little edge, so rows form behind, and a crowd of five on the tile behind.
		CrowdPlanner.Surroundings shortBank = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				int z = StackRegistry.sceneY(tile) * 128 + dz;
				return z <= 50 * 128 && x >= 50 * 128 - 40 && x <= 51 * 128 + 40;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return StackRegistry.sceneY(tile) == 50 && Math.abs(angle - Math.PI) < 0.01;
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
		List<StackSpreader.Entry> still = bank(new int[]{7, 7}, 1);
		int[] facings = {0, 512, 1024, 1536, 300};
		for (int i = 0; i < 5; i++)
		{
			still.add(new StackSpreader.Entry(60 + i, StackRegistry.key(0, 50, 49), false, facings[i]));
		}
		Map<Integer, int[]> at = drawnAt(new CrowdPlanner().plan(still, x -> true, 128, 10, true, false, 1, shortBank));
		for (Map.Entry<Integer, int[]> a : at.entrySet())
		{
			for (Map.Entry<Integer, int[]> b : at.entrySet())
			{
				if (a.getKey() < 60 && b.getKey() >= 60)
				{
					double gap = Math.hypot(a.getValue()[0] - b.getValue()[0], a.getValue()[1] - b.getValue()[1]);
					Assert.assertTrue("fisher " + a.getKey() + " is " + gap + " from " + b.getKey() + " on the tile behind",
						gap >= PersonalSpaceConfig.LINE_SPACING - 1);
				}
			}
		}
	}

	@Test
	public void nobodyAtTheEdgeIsDrawnOnTopOfSomeoneLeftInTheMiddle()
	{
		// Land only one tile deep and little edge: some fishers get no spot and stay in the middle.
		CrowdPlanner.Surroundings shallow = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz == 0 && x >= 50 * 128 - 40 && x <= 51 * 128 + 40;
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
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(bank(new int[]{10, 10}, 1), x -> true, 128, 10, true, false, 1, shallow);
		Assert.assertFalse("some fishers don't fit", plan.unplaced.isEmpty());
		for (StackSpreader.Placement u : plan.unplaced)
		{
			for (StackSpreader.Placement p : plan.placements)
			{
				int ux = StackRegistry.sceneX(u.tile) * 128;
				int px = StackRegistry.sceneX(p.tile) * 128 + p.dx;
				Assert.assertTrue("player " + p.id + " is drawn on top of " + u.id + " in the middle of their tile",
					p.dz != 0 || Math.abs(px - ux) >= PersonalSpaceConfig.COUNTER_SPACING);
			}
		}
	}

	@Test
	public void twoCounterRowsEitherSideOfALonePlayerDontMeetInTheGap()
	{
		// Two busy stretches of counter with one person standing alone at the end of the first one and
		// an empty tile between the stretches. The lone player stands out on their neighbours' row, so
		// the other row must not reach across them: everybody's spot is their own.
		CrowdPlanner.Surroundings counters = surroundings(true, true, false);
		for (int gap = 1; gap <= 2; gap++)
		{
			for (int spacing : new int[]{42, 72, 128, 256})
			{
				int[][] tiles = {{53, 2}, {54, 7}, {55, 1}, {55 + gap + 1, 6}, {56 + gap + 1, 6}, {57 + gap + 1, 2}};
				List<StackSpreader.Entry> still = new ArrayList<>();
				int id = 1;
				for (int[] tile : tiles)
				{
					for (int n = 0; n < tile[1]; n++)
					{
						still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, tile[0], 50), false, NORTH));
					}
				}
				CrowdPlanner planner = new CrowdPlanner();
				Map<Integer, int[]> at = new HashMap<>();
				for (int t = 1; t <= 3; t++)
				{
					at.clear();
					for (StackSpreader.Placement p : planner.plan(still, x -> true, spacing, 10, true, false, t, counters).placements)
					{
						at.put(p.id, new int[]{StackRegistry.sceneX(p.tile) * 128 + p.dx, p.dz});
					}
				}
				for (Map.Entry<Integer, int[]> a : at.entrySet())
				{
					for (Map.Entry<Integer, int[]> b : at.entrySet())
					{
						if (a.getKey() >= b.getKey())
						{
							continue;
						}
						double away = Math.hypot(a.getValue()[0] - b.getValue()[0], a.getValue()[1] - b.getValue()[1]);
						Assert.assertTrue("gap " + gap + " at " + spacing + ": players " + a.getKey() + " and "
							+ b.getKey() + " only " + away + " apart", away >= Math.min(Math.min(spacing, PersonalSpaceConfig.LINE_SPACING), LineBook.ROW_GAP) - 1);
					}
				}
			}
		}
	}

	@Test
	public void aLineStopsWhereTheWaterEnds()
	{
		// Water only in front of tiles 50 and 51; land past them.
		CrowdPlanner.Surroundings shortWater = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return true;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				int x = StackRegistry.sceneX(tile);
				return x == 50 || x == 51;
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
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(bank(new int[]{10, 10}, 1), x -> true, 128, 10, true, false, 1, shortWater);
		for (StackSpreader.Placement p : plan.placements)
		{
			if (p.dz == 0)
			{
				int x = (int) Math.round((StackRegistry.sceneX(p.tile) * 128 + p.dx) / 128.0);
				Assert.assertTrue("player " + p.id + " lined up at x=" + x + " with no water in front", x == 50 || x == 51);
			}
		}
	}

	@Test
	public void aCounterRowRunsAlongTheCounterEvenIfItFormedAtASlant()
	{
		// Facing north-north-east: still a counter row, laid out exactly east-west.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, 1100, 2, 1100, 3, 1100),
			id -> true, 72, 5, true, false, 1, surroundings(true, true, false));
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertTrue("stands " + p.dz + " back, further than the bow allows", Math.abs(p.dz) <= StackSpreader.MAX_BOW);
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
	public void theLineArrangementStandsThemSideBySideWhereSmartWouldMakeARing()
	{
		// Three players in the open with nothing to face: Smart rings them, which puts one behind the
		// others. Line stands them shoulder to shoulder instead, for a photo.
		List<StackSpreader.Entry> three = players(1, NORTH, 2, NORTH, 3, NORTH);
		CrowdPlanner.Plan ring = new CrowdPlanner().plan(three, id -> true, 128, 10, true, false, 1, OPEN);
		Assert.assertEquals("crowd", ring.tiles.get(TILE).shape);

		CrowdPlanner lineUp = new CrowdPlanner();
		lineUp.arrangement = PersonalSpaceConfig.Arrangement.ROW;
		CrowdPlanner.Plan plan = lineUp.plan(three, id -> true, 128, 10, true, false, 1, OPEN);
		Assert.assertEquals("line", plan.tiles.get(TILE).shape);
		Assert.assertTrue("a line is straight", plan.curvedRows.isEmpty());
		Map<Integer, int[]> at = spots(plan);
		Assert.assertEquals(3, at.size());
		int depth = at.values().iterator().next()[1];
		for (Map.Entry<Integer, int[]> e : at.entrySet())
		{
			Assert.assertEquals("player " + e.getKey() + " stands out of line", depth, e.getValue()[1]);
		}
	}

	@Test
	public void aCurveWithNothingToHugOpensOutWithTheSlider()
	{
		// A curve round an anvil or a fire hugs it, so neighbours are about half a tile apart however
		// wide the slider is set. A curve asked for in the open has nothing to hug, so it opens out
		// until people stand as far apart as the slider says.
		List<StackSpreader.Entry> three = players(1, NORTH, 2, NORTH, 3, NORTH);

		CrowdPlanner open = new CrowdPlanner();
		open.arrangement = PersonalSpaceConfig.Arrangement.ARC;
		open.smallGroupsClose = false;
		double wide = nearestPair(open.plan(three, id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, OPEN));
		Assert.assertTrue("a curve in the open only stands them " + wide + " apart",
			wide >= PersonalSpaceConfig.SPACING_WIDE - 8);

		// Auto-space on: a curve round something keeps hugging it, while one in the open still opens
		// out to the distance auto-spacing picked.
		CrowdPlanner hugging = new CrowdPlanner();
		hugging.arrangement = PersonalSpaceConfig.Arrangement.ARC;
		double close = nearestPair(hugging.plan(three, id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1,
			surroundings(true, false, false)));
		Assert.assertTrue("a curve round something should hug it, not stand " + close + " apart", close < 70);

		CrowdPlanner autoOpen = new CrowdPlanner();
		autoOpen.arrangement = PersonalSpaceConfig.Arrangement.ARC;
		double auto = nearestPair(autoOpen.plan(three, id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, OPEN));
		Assert.assertTrue("a curve in the open is stuck at a hugging distance: " + auto, auto > 70);
	}

	/** The distance between the two closest players in a plan. */
	private static double nearestPair(CrowdPlanner.Plan plan)
	{
		double nearest = Double.MAX_VALUE;
		List<StackSpreader.Placement> all = plan.placements;
		for (int i = 0; i < all.size(); i++)
		{
			for (int j = i + 1; j < all.size(); j++)
			{
				nearest = Math.min(nearest, Math.hypot(all.get(i).dx - all.get(j).dx, all.get(i).dz - all.get(j).dz));
			}
		}
		return nearest;
	}

	@Test
	public void walkingInBesideSomeoneSettlesWithoutAShuffle()
	{
		// Someone stands alone, so nothing is spread. You walk onto their tile: both of you get a
		// spot that same tick, and neither of you may be moved again afterwards.
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> alone = new ArrayList<>();
		alone.add(new StackSpreader.Entry(7, TILE, false, NORTH));
		for (int t = 1; t <= 3; t++)
		{
			Assert.assertTrue("nothing to spread yet",
				planner.plan(alone, id -> true, 128, 10, true, true, t, OPEN).placements.isEmpty());
		}
		List<StackSpreader.Entry> pair = new ArrayList<>(alone);
		pair.add(new StackSpreader.Entry(99, TILE, true, NORTH));
		Map<Integer, int[]> settled = spots(planner.plan(pair, id -> true, 128, 10, true, true, 4, OPEN));
		Assert.assertEquals(2, settled.size());
		for (int t = 5; t <= 4 + SlotBook.LOCAL_SWAP_DELAY + 3; t++)
		{
			Map<Integer, int[]> now = spots(planner.plan(pair, id -> true, 128, 10, true, true, t, OPEN));
			for (Map.Entry<Integer, int[]> e : settled.entrySet())
			{
				Assert.assertArrayEquals("player " + e.getKey() + " shuffled at tick " + t,
					e.getValue(), now.get(e.getKey()));
			}
		}
	}

	@Test
	public void theArcArrangementCurvesThemWhereSmartWouldMakeARing()
	{
		CrowdPlanner arc = new CrowdPlanner();
		arc.arrangement = PersonalSpaceConfig.Arrangement.ARC;
		CrowdPlanner.Plan plan = arc.plan(players(1, NORTH, 2, NORTH, 3, NORTH), id -> true, 128, 10, true, false, 1, OPEN);
		Assert.assertTrue("the tile curves", plan.curvedRows.contains(TILE));
		Assert.assertEquals(3, plan.placements.size());
	}

	/** Three people at a bank counter on your tile, you among them. */
	private static List<StackSpreader.Entry> youAtTheCounter()
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		still.add(new StackSpreader.Entry(1, TILE, false, NORTH));
		still.add(new StackSpreader.Entry(2, TILE, false, NORTH));
		still.add(new StackSpreader.Entry(99, TILE, true, NORTH));
		return still;
	}

	/** Puts the camera a long way off from the tile, towards (east, north). */
	private static void cameraAt(CrowdPlanner planner, int east, int north)
	{
		planner.cameraX = StackRegistry.sceneX(TILE) * 128 + 64 + east * 2000;
		planner.cameraY = StackRegistry.sceneY(TILE) * 128 + 64 + north * 2000;
	}

	private static int[] yours(CrowdPlanner.Plan plan)
	{
		return spots(plan).get(99);
	}

	@Test
	public void peopleComingAndGoingNeverMoveYouOnceYouveSettled()
	{
		// At a busy bank people come and go all the time. Your spot is chosen when you arrive, and
		// after that only settling the camera somewhere new moves you: nobody else's comings and
		// goings, and no gap-filling.
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		cameraAt(planner, 1, -1);
		int tick = 0;
		int[] settled = null;
		for (int t = 0; t < 10; t++)
		{
			settled = yours(planner.plan(youAtTheCounter(), x -> true, 128, 10, true, true, ++tick, counter));
		}
		java.util.Random churn = new java.util.Random(7);
		for (int t = 0; t < 120; t++)
		{
			List<StackSpreader.Entry> now = new ArrayList<>(youAtTheCounter());
			int others = churn.nextInt(7);
			for (int i = 0; i < others; i++)
			{
				now.add(new StackSpreader.Entry(10 + churn.nextInt(12), TILE, false, NORTH));
			}
			Map<Integer, StackSpreader.Entry> unique = new java.util.LinkedHashMap<>();
			for (StackSpreader.Entry e : now)
			{
				unique.putIfAbsent(e.id, e);
			}
			int[] you = yours(planner.plan(new ArrayList<>(unique.values()), x -> true, 128, 10, true, true, ++tick, counter));
			Assert.assertArrayEquals("tick " + tick + ": you moved because other people came and went", settled, you);
		}
	}

	private static String inYourWay(CrowdPlanner.Plan plan, double east, double north)
	{
		double len = Math.hypot(east, north);
		double vx = east / len;
		double vy = north / len;
		double[] me = null;
		Map<Integer, double[]> at = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			double[] where = {StackRegistry.sceneX(p.tile) * 128 + p.dx, StackRegistry.sceneY(p.tile) * 128 + p.dz};
			at.put(p.id, where);
			me = p.id == 99 ? where : me;
		}
		for (Map.Entry<Integer, double[]> e : at.entrySet())
		{
			double dx = e.getValue()[0] - me[0];
			double dz = e.getValue()[1] - me[1];
			if (e.getKey() != 99 && dx * vx + dz * vy > 24 && Math.abs(dz * vx - dx * vy) < 48)
			{
				return "player " + e.getKey();
			}
		}
		return null;
	}

	@Test
	public void youKeepTheFrontAtYourBoothWhicheverWayTheCameraLooks()
	{
		// You take the front spot at your own booth, and whichever way the camera looks, anyone who
		// would stand between you and it moves out of the way instead of you.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		int[][] cameras = {{1, 0}, {-1, 0}, {1, -1}, {-1, -1}, {0, -1}};
		int[] front = null;
		for (int[] camera : cameras)
		{
			CrowdPlanner planner = new CrowdPlanner();
			cameraAt(planner, camera[0], camera[1]);
			List<StackSpreader.Entry> still = new ArrayList<>(youAtTheCounter());
			for (int i = 3; i <= 7; i++)
			{
				still.add(new StackSpreader.Entry(i, TILE, false, NORTH));
			}
			CrowdPlanner.Plan plan = null;
			for (int t = 1; t <= 12; t++)
			{
				plan = planner.plan(still, x -> true, 128, 16, true, true, t, counter);
			}
			Assert.assertEquals("everyone still has a spot", still.size(), plan.placements.size());
			if (front == null)
			{
				front = yours(plan);
			}
			Assert.assertArrayEquals("the camera looking from (" + camera[0] + "," + camera[1] + ") moved you",
				front, yours(plan));
		}
	}

	@Test
	public void turningTheCameraDoesntWalkYouRoundTheCrowd()
	{
		// Only a camera that stays somewhere new moves you; one being turned back and forth doesn't.
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		cameraAt(planner, 1, 0);
		int tick = 0;
		int[] settled = null;
		for (int t = 0; t < 8; t++)
		{
			settled = yours(planner.plan(youAtTheCounter(), x -> true, 128, 10, true, true, ++tick, counter));
		}
		for (int t = 0; t < 12; t++)
		{
			cameraAt(planner, t % 2 == 0 ? -1 : 1, 0);
			Assert.assertArrayEquals("you moved while the camera was still turning", settled,
				yours(planner.plan(youAtTheCounter(), x -> true, 128, 10, true, true, ++tick, counter)));
		}
	}

	@Test
	public void fromStraightBehindYouKeepTheBestSpotAtTheCounter()
	{
		// Every spot along the counter is as near a camera straight behind the row as any other, so
		// nothing changes: you keep the best one, exactly as with no camera at all.
		CrowdPlanner withCamera = new CrowdPlanner();
		cameraAt(withCamera, 0, -1);
		CrowdPlanner without = new CrowdPlanner();
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		int[] seen = null;
		int[] unseen = null;
		for (int t = 1; t <= 6; t++)
		{
			seen = yours(withCamera.plan(youAtTheCounter(), x -> true, 128, 10, true, true, t, counter));
			unseen = yours(without.plan(youAtTheCounter(), x -> true, 128, 10, true, true, t, counter));
		}
		Assert.assertArrayEquals(unseen, seen);
	}

	@Test
	public void aBankCrowdFacingTheBoothAtASlantStillLinesUpAlongTheCounter()
	{
		// People walk up to a bank booth at whatever slant they arrive at. A counter only reads as one
		// square on, since counters run along the edges of tiles, so a crowd mostly facing the booth
		// from the side used to form a curve out in the open instead of a row along the counter - and
		// one person standing at a slant was enough to decide it for everybody.
		CrowdPlanner.Surroundings booth = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return true;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				// The counter is north; its corner also blocks the way looking north-west.
				return Math.abs(angle - Math.PI) < 0.01 || Math.abs(angle - 3 * Math.PI / 4) < 0.01;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				// Only square on: this is what the game's own collision map gives us.
				return Math.abs(angle - Math.PI) < 0.01;
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
		int slant = 768;
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, slant, 2, slant, 3, slant, 4, slant, 5, slant),
			id -> true, PersonalSpaceConfig.SPACING_NORMAL, 10, true, false, 1, booth);
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
		Assert.assertTrue("a counter row runs straight, it doesn't curve", plan.curvedRows.isEmpty());
	}

	@Test
	public void aBoothWithADozenPeopleOnItLeavesNobodyInTheHeap()
	{
		// A busy bank booth really does get a dozen people on one tile. At the old cap of ten, three of
		// them were left drawn on top of each other in the middle; the cap now reaches far enough that
		// everyone gets a spot, and nobody is put on top of anybody else to manage it.
		CrowdPlanner.Surroundings bank = surroundings(true, true, false);
		List<StackSpreader.Entry> still = new ArrayList<>();
		int id = 1;
		for (int i = 0; i < 13; i++)
		{
			still.add(new StackSpreader.Entry(id++, TILE, false, NORTH));
		}
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 3; t++)
		{
			plan = planner.plan(still, x -> true, PersonalSpaceConfig.SPACING_NORMAL, PersonalSpaceConfig.MAX_STACK, true,
				false, t, bank);
		}
		Assert.assertEquals("everyone on the tile got a spot", 13, plan.placements.size());
		for (StackSpreader.Placement a : plan.placements)
		{
			Assert.assertTrue("drawn " + Math.hypot(a.dx, a.dz) + " from where they stand, further than a row reaches",
				Math.hypot(a.dx, a.dz) <= StackSpreader.MAX_LINE_EXTENT);
			for (StackSpreader.Placement b : plan.placements)
			{
				if (a.id < b.id)
				{
					double away = Math.hypot(a.dx - b.dx, a.dz - b.dz);
					Assert.assertTrue("players " + a.id + " and " + b.id + " only " + away + " apart",
						away >= PersonalSpaceConfig.COUNTER_SPACING - 1);
				}
			}
		}
	}

	@Test
	public void twoTilesAtOneAnvilArentPinnedToTheTightestSpacing()
	{
		// Sharing what you face with the tile next door limits how much of the ring is yours. That
		// used to shrink both crowds to the least room anyone may have, whatever the slider said, so
		// a busy anvil looked the same at Close as at Wide and both looked cramped.
		CrowdPlanner.Surroundings anvil = surroundings(true, false, false);
		long neighbour = StackRegistry.key(0, StackRegistry.sceneX(TILE) + 1, StackRegistry.sceneY(TILE));
		for (boolean autoSpace : new boolean[]{true, false})
		{
			double closest = 0;
			int tightest = 0;
			for (int slider : new int[]{PersonalSpaceConfig.SPACING_CLOSE, PersonalSpaceConfig.SPACING_WIDE})
			{
				List<StackSpreader.Entry> group = new ArrayList<>();
				int id = 1;
				for (long tile : new long[]{TILE, neighbour})
				{
					for (int i = 0; i < 5; i++)
					{
						group.add(new StackSpreader.Entry(id++, tile, false, NORTH));
					}
				}
				CrowdPlanner planner = new CrowdPlanner();
				planner.smallGroupsClose = autoSpace;
				CrowdPlanner.Plan plan = null;
				for (int t = 1; t <= 3; t++)
				{
					plan = planner.plan(group, x -> true, slider, 10, true, false, t, anvil);
				}
				double nearest = Double.MAX_VALUE;
				List<int[]> at = new ArrayList<>();
				for (StackSpreader.Placement placement : plan.placements)
				{
					at.add(new int[]{StackRegistry.sceneX(placement.tile) * 128 + placement.dx,
						StackRegistry.sceneY(placement.tile) * 128 + placement.dz});
				}
				for (int i = 0; i < at.size(); i++)
				{
					for (int j = i + 1; j < at.size(); j++)
					{
						nearest = Math.min(nearest, Math.hypot(at.get(i)[0] - at.get(j)[0], at.get(i)[1] - at.get(j)[1]));
					}
				}
				Assert.assertTrue("auto-space " + autoSpace + " at " + slider + ": two people drawn " + nearest + " apart",
					nearest >= MIN_SHARED_SPACING_FOR_TEST);
				if (slider == PersonalSpaceConfig.SPACING_CLOSE)
				{
					closest = nearest;
					tightest = plan.tiles.get(TILE).spacing;
				}
				else
				{
					Assert.assertTrue("Wide is no roomier than Close: " + closest + " then " + nearest, nearest > closest);
					Assert.assertTrue("both tiles were pinned to the tightest spacing there is",
						plan.tiles.get(TILE).spacing > tightest);
				}
			}
		}
	}

	@Test
	public void askingForMoreRoomNeverDrawsPeopleCloserTogether()
	{
		// A cramped spot can't give everyone the room the slider asks for, and falls back to a ring.
		// Whatever it falls back to, a wider setting must never end up tighter than a narrower one:
		// at an anvil in a small room, Wide used to draw a crowd closer together than Close did.
		CrowdPlanner.Surroundings smallRoom = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return Math.abs(dx) <= 128 && Math.abs(dz) <= 128;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
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
		for (boolean autoSpace : new boolean[]{true, false})
		{
			for (int people = 3; people <= 8; people++)
			{
				List<StackSpreader.Entry> group = new ArrayList<>();
				for (int i = 0; i < people; i++)
				{
					group.add(new StackSpreader.Entry(i + 1, TILE, false, NORTH));
				}
				double narrower = 0;
				for (int slider : new int[]{PersonalSpaceConfig.SPACING_CLOSE, PersonalSpaceConfig.SPACING_NORMAL, 200,
					PersonalSpaceConfig.SPACING_WIDE})
				{
					CrowdPlanner planner = new CrowdPlanner();
					planner.smallGroupsClose = autoSpace;
					CrowdPlanner.Plan plan = null;
					for (int t = 1; t <= 3; t++)
					{
						plan = planner.plan(group, x -> true, slider, 10, true, false, t, smallRoom);
					}
					double nearest = Double.MAX_VALUE;
					List<StackSpreader.Placement> all = plan.placements;
					for (int i = 0; i < all.size(); i++)
					{
						for (int j = i + 1; j < all.size(); j++)
						{
							nearest = Math.min(nearest, Math.hypot(all.get(i).dx - all.get(j).dx, all.get(i).dz - all.get(j).dz));
						}
					}
					Assert.assertTrue("auto-space " + autoSpace + ", " + people + " people: " + slider + " drew them "
						+ nearest + " apart, closer than the setting below it did (" + narrower + ")", nearest >= narrower - 0.5);
					narrower = nearest;
				}
			}
		}
	}

	@Test
	public void aWideSpacingWithSomeoneEitherSideStillGivesEveryoneASpot()
	{
		// Keeping clear of a neighbour who stands alone in the middle of their tile costs ground. At
		// a wide spacing with someone either side there was none left, no spot passed the check, and
		// the whole tile was drawn stacked on one point.
		for (int spacing : new int[]{40, 120, 128, 192, PersonalSpaceConfig.SPACING_WIDE})
		{
			for (int people = 2; people <= 5; people++)
			{
				List<StackSpreader.Entry> still = new ArrayList<>();
				int id = 1;
				for (int i = 0; i < people; i++)
				{
					still.add(new StackSpreader.Entry(id++, TILE, false, NORTH));
				}
				still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, 49, 50), false, NORTH));
				still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, 51, 50), false, NORTH));
				CrowdPlanner planner = new CrowdPlanner();
				planner.smallGroupsClose = false;
				CrowdPlanner.Plan plan = null;
				for (int t = 1; t <= 3; t++)
				{
					plan = planner.plan(still, x -> true, spacing, 10, true, false, t, surroundings(true, true, false));
				}
				int placed = 0;
				for (StackSpreader.Placement p : plan.placements)
				{
					placed += p.tile == TILE ? 1 : 0;
				}
				Assert.assertTrue("at " + spacing + " with " + people + " of us, only " + placed + " got a spot",
					placed > 0);
			}
		}
	}

	@Test
	public void aBankRowCurvesRoundTheBoothAndKeepsItsSpacing()
	{
		// Smart's counter rows bow like a crowd round an anvil: the middle stands at the booth and
		// the wings fall back, rather than everyone standing in a flat line along the counter.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(
			players(1, NORTH, 2, NORTH, 3, NORTH, 4, NORTH, 5, NORTH, 6, NORTH, 7, NORTH),
			id -> true, PersonalSpaceConfig.COUNTER_SPACING, 10, true, false, 1, surroundings(true, true, false));
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
		List<StackSpreader.Placement> row = new ArrayList<>(plan.placements);
		row.sort(Comparator.comparingInt(p -> p.dx));
		int middle = row.size() / 2;
		Assert.assertTrue("the wings should fall back from the booth",
			Math.abs(row.get(0).dz) > Math.abs(row.get(middle).dz));
		Assert.assertTrue("but no further than the bow allows", Math.abs(row.get(0).dz) <= StackSpreader.MAX_BOW);
		for (StackSpreader.Placement a : row)
		{
			for (StackSpreader.Placement b : row)
			{
				// Further out along the counter is further back from it, with no kinks in the curve.
				Assert.assertFalse("the curve doubles back at " + a.dx + " and " + b.dx,
					Math.abs(a.dx) < Math.abs(b.dx) && Math.abs(a.dz) > Math.abs(b.dz));
			}
		}
		for (int i = 0; i < row.size(); i++)
		{
			for (int j = i + 1; j < row.size(); j++)
			{
				double away = Math.hypot(row.get(i).dx - row.get(j).dx, row.get(i).dz - row.get(j).dz);
				Assert.assertTrue("a bowed row put two people " + away + " apart",
					away >= PersonalSpaceConfig.COUNTER_SPACING - 1);
			}
		}
	}

	@Test
	public void theLineArrangementStillRunsAlongACounter()
	{
		CrowdPlanner lineUp = new CrowdPlanner();
		lineUp.arrangement = PersonalSpaceConfig.Arrangement.ROW;
		CrowdPlanner.Plan plan = lineUp.plan(players(1, NORTH, 2, NORTH, 3, NORTH), id -> true, 128, 10, true, false, 1,
			surroundings(true, true, false));
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
		Assert.assertEquals(PersonalSpaceConfig.COUNTER_SPACING, plan.tiles.get(TILE).spacing);
	}

	@Test
	public void withAutoSpaceOffAPairAgainstAWallUsesTheFullSpacing()
	{
		// Two players up against a castle wall: a counter row, which normally stands shoulder to
		// shoulder. With auto-spacing off the slider decides instead, so a photo can be set up
		// anywhere, wall or no wall.
		CrowdPlanner.Surroundings wall = surroundings(true, true, false);
		CrowdPlanner.Plan close = new CrowdPlanner()
			.plan(players(1, NORTH, 2, NORTH), id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, wall);
		Assert.assertEquals("auto-spacing on keeps them at the counter distance",
			PersonalSpaceConfig.COUNTER_SPACING, close.tiles.get(TILE).spacing);

		CrowdPlanner unlocked = new CrowdPlanner();
		unlocked.smallGroupsClose = false;
		CrowdPlanner.Plan wide = unlocked
			.plan(players(1, NORTH, 2, NORTH), id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, wall);
		Assert.assertEquals("with it off the slider decides", PersonalSpaceConfig.SPACING_WIDE, wide.tiles.get(TILE).spacing);
		List<int[]> where = new ArrayList<>();
		for (StackSpreader.Placement placement : wide.placements)
		{
			where.add(new int[]{placement.dx, placement.dz});
		}
		Assert.assertEquals(2, where.size());
		double apart = Math.hypot(where.get(0)[0] - where.get(1)[0], where.get(0)[1] - where.get(1)[1]);
		Assert.assertTrue("they only stand " + apart + " apart", apart >= PersonalSpaceConfig.SPACING_WIDE - 1);
	}

	@Test
	public void withAutoSpaceOffACrowdRoundAFireUsesTheFullSpacing()
	{
		CrowdPlanner.Surroundings fire = new CrowdPlanner.Surroundings()
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
		List<StackSpreader.Entry> five = players(1, NORTH, 2, NORTH, 3, NORTH, 4, NORTH, 5, NORTH);
		CrowdPlanner unlocked = new CrowdPlanner();
		unlocked.smallGroupsClose = false;
		CrowdPlanner.Plan plan = unlocked.plan(five, id -> true, PersonalSpaceConfig.SPACING_WIDE, 10, true, false, 1, fire);
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
	public void aBankCrowdLinesUpAlongTheCounterWhicheverWayTheyFace()
	{
		// Three banking, two casting spells facing elsewhere.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH, 3, NORTH, 4, 1536, 5, SOUTH),
			id -> true, 72, 5, true, false, 1, COUNTER_NORTH);
		Assert.assertEquals("counter row", plan.tiles.get(TILE).shape);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertTrue("player " + p.id + " stands " + p.dz + " back, further than the bow allows",
				Math.abs(p.dz) <= StackSpreader.MAX_BOW);
		}

		// Only one of five faces the booth, the rest alching or chatting every which way: still a
		// row along the counter. Packed into a ring between the booths, they buried whoever was in it.
		CrowdPlanner fewFacing = new CrowdPlanner();
		Assert.assertEquals("counter row",
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

	/** Where everyone is drawn, in scene units: placed people at their spot, one person at the middle of each other tile. */
	private static Map<Integer, double[]> drawnAt(CrowdPlanner.Plan plan, List<StackSpreader.Entry> still)
	{
		Map<Integer, double[]> at = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			at.put(p.id, new double[]{StackRegistry.sceneX(p.tile) * 128 + p.dx, StackRegistry.sceneY(p.tile) * 128 + p.dz});
		}
		Set<Long> middleDrawn = new HashSet<>();
		for (StackSpreader.Entry e : still)
		{
			if (!at.containsKey(e.id) && middleDrawn.add(e.tile))
			{
				at.put(e.id, new double[]{StackRegistry.sceneX(e.tile) * 128, StackRegistry.sceneY(e.tile) * 128});
			}
		}
		return at;
	}

	@Test
	public void groupsOnNeighbouringTilesInTheOpenAreNeverDrawnInsideEachOther()
	{
		// Groups side by side in the open, as on the Grand Exchange floor. Each used to spread into
		// the gap between them, and people from the two were drawn inside each other, one poking
		// through the other as the camera turned.
		java.util.Random rnd = new java.util.Random(1822);
		for (int trial = 0; trial < 200; trial++)
		{
			int w = 2 + rnd.nextInt(2);
			int h = 1 + rnd.nextInt(2);
			int[][] count = new int[w][h];
			for (int x = 0; x < w; x++)
			{
				for (int y = 0; y < h; y++)
				{
					count[x][y] = rnd.nextInt(4) == 0 ? 1 : 2 + rnd.nextInt(5);
				}
			}
			int spacing = new int[]{72, 128, 256}[rnd.nextInt(3)];
			CrowdPlanner planner = new CrowdPlanner();
			planner.smallGroupsClose = rnd.nextBoolean();
			for (int t = 1; t <= 6; t++)
			{
				List<StackSpreader.Entry> still = new ArrayList<>();
				Map<Integer, Long> tileOf = new HashMap<>();
				int id = 1;
				for (int x = 0; x < w; x++)
				{
					for (int y = 0; y < h; y++)
					{
						for (int i = 0; i < count[x][y]; i++, id++)
						{
							long tile = StackRegistry.key(0, 50 + x, 50 + y);
							still.add(new StackSpreader.Entry(id, tile, false, (x * 700 + y * 300 + i * 97) % 2048));
							tileOf.put(id, tile);
						}
					}
				}
				CrowdPlanner.Plan plan = planner.plan(still, q -> true, spacing, 10, true, false, t, OPEN);
				Map<Integer, double[]> at = drawnAt(plan, still);
				for (int a : at.keySet())
				{
					for (int b : at.keySet())
					{
						if (a < b && !tileOf.get(a).equals(tileOf.get(b)))
						{
							double gap = Math.hypot(at.get(a)[0] - at.get(b)[0], at.get(a)[1] - at.get(b)[1]);
							Assert.assertTrue("trial " + trial + " tick " + t + ": " + a + " and " + b + " from tiles next to each other only "
								+ gap + " apart", gap >= CrowdPlanner.NEIGHBOUR_GAP - 1);
						}
					}
				}
			}
		}
	}

	/** Each person's bearing from the middle of their group, which only changes if people swap places or walk round. */
	private static Map<Integer, Double> bearings(Map<Integer, double[]> at, int from, int to)
	{
		double cx = 0;
		double cz = 0;
		for (int i = from; i <= to; i++)
		{
			cx += at.get(i)[0] / (to - from + 1);
			cz += at.get(i)[1] / (to - from + 1);
		}
		Map<Integer, Double> out = new HashMap<>();
		for (int i = from; i <= to; i++)
		{
			out.put(i, Math.atan2(at.get(i)[1] - cz, at.get(i)[0] - cx));
		}
		return out;
	}

	private static void assertSameShape(String what, Map<Integer, Double> before, Map<Integer, Double> now)
	{
		for (Map.Entry<Integer, Double> e : before.entrySet())
		{
			double turn = Math.abs(Math.atan2(Math.sin(now.get(e.getKey()) - e.getValue()), Math.cos(now.get(e.getKey()) - e.getValue())));
			Assert.assertTrue(what + ": player " + e.getKey() + " went round the others (" + turn + " rad)", turn < 0.15);
		}
	}

	@Test
	public void aGroupMakesRoomForPeopleNextDoorWithoutAnyoneSwappingPlaces()
	{
		// People come and go on the tiles around a group of five. The group moves over and closes up
		// as a whole to leave them room, but nobody swaps places or walks round the others, and
		// once everyone has gone it settles back exactly where it was.
		CrowdPlanner planner = new CrowdPlanner();
		java.util.Random rnd = new java.util.Random(5);
		int[][] around = new int[5][5];
		Map<Integer, double[]> settled = null;
		Map<Integer, Double> shape = null;
		int changed = 0;
		for (int t = 1; t <= 180; t++)
		{
			if (t > 8 && t < 150 && t % 4 == 0)
			{
				int x = rnd.nextInt(5);
				int y = rnd.nextInt(5);
				if (x != 2 || y != 2)
				{
					around[x][y] = rnd.nextInt(4);
					changed = t;
				}
			}
			if (t == 150)
			{
				around = new int[5][5];
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			Map<Integer, Long> tileOf = new HashMap<>();
			for (int i = 1; i <= 5; i++)
			{
				still.add(new StackSpreader.Entry(i, TILE, false, NORTH));
				tileOf.put(i, TILE);
			}
			int id = 100;
			for (int x = 0; x < 5; x++)
			{
				for (int y = 0; y < 5; y++)
				{
					for (int i = 0; i < around[x][y]; i++, id++)
					{
						long tile = StackRegistry.key(0, 48 + x, 48 + y);
						still.add(new StackSpreader.Entry(id, tile, false, SOUTH));
						tileOf.put(id, tile);
					}
				}
			}
			Map<Integer, double[]> at = drawnAt(planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), still);
			if (t == 8)
			{
				settled = at;
				shape = bearings(at, 1, 5);
			}
			if (t > 8)
			{
				assertSameShape("tick " + t, shape, bearings(at, 1, 5));
			}
			// Someone who has only just stopped isn't made room for until they have stood a moment.
			for (int i = 1; t > 8 && t - changed >= CrowdPlanner.SETTLE_TICKS && i <= 5; i++)
			{
				for (int other : at.keySet())
				{
					if (!tileOf.get(other).equals(TILE))
					{
						double gap = Math.hypot(at.get(i)[0] - at.get(other)[0], at.get(i)[1] - at.get(other)[1]);
						Assert.assertTrue("tick " + t + ": " + i + " and " + other + " only " + gap + " apart", gap >= CrowdPlanner.NEIGHBOUR_GAP - 2);
					}
				}
			}
			for (int i = 1; t == 180 && i <= 5; i++)
			{
				Assert.assertArrayEquals("player " + i + " back where they were", settled.get(i), at.get(i), 0);
			}
		}
	}

	@Test
	public void twoGroupsSideBySideBothMakeRoomAndKeepTheirShapes()
	{
		// Five standing, then three stop on the tile next door. Both groups keep to their own side
		// of the ground between them, each keeping its shape; when the three leave, the five ease
		// back out a few seconds later, not the moment they go.
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> five = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			five.add(new StackSpreader.Entry(i, TILE, false, NORTH));
		}
		Map<Integer, double[]> alone = null;
		for (int t = 1; t <= 5; t++)
		{
			alone = drawnAt(planner.plan(five, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), five);
		}
		Map<Integer, Double> shape = bearings(alone, 1, 5);
		List<StackSpreader.Entry> both = new ArrayList<>(five);
		long east = StackRegistry.key(0, 51, 50);
		for (int i = 6; i <= 8; i++)
		{
			both.add(new StackSpreader.Entry(i, east, false, NORTH));
		}
		for (int t = 6; t <= 10; t++)
		{
			Map<Integer, double[]> at = drawnAt(planner.plan(both, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), both);
			assertSameShape("tick " + t, shape, bearings(at, 1, 5));
			for (int i = 1; t >= 6 + CrowdPlanner.SETTLE_TICKS && i <= 5; i++)
			{
				for (int j = 6; j <= 8; j++)
				{
					double gap = Math.hypot(at.get(i)[0] - at.get(j)[0], at.get(i)[1] - at.get(j)[1]);
					Assert.assertTrue("tick " + t + ": " + i + " and " + j + " only " + gap + " apart", gap >= CrowdPlanner.NEIGHBOUR_GAP - 2);
				}
			}
		}
		Map<Integer, double[]> justLeft = drawnAt(planner.plan(five, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, 11, OPEN), five);
		Assert.assertFalse("the five eased straight back out", java.util.Arrays.equals(alone.get(1), justLeft.get(1)));
		Map<Integer, double[]> later = null;
		for (int t = 12; t <= 11 + CrowdPlanner.RELAX_TICKS + 1; t++)
		{
			later = drawnAt(planner.plan(five, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), five);
		}
		for (int i = 1; i <= 5; i++)
		{
			Assert.assertArrayEquals("player " + i + " back out where they were", alone.get(i), later.get(i), 0);
		}
	}

	/** A group of five on {@link #TILE}, and whoever else is given, all standing still. */
	private static List<StackSpreader.Entry> fiveAnd(StackSpreader.Entry... others)
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			still.add(new StackSpreader.Entry(i, TILE, false, NORTH));
		}
		still.addAll(java.util.Arrays.asList(others));
		return still;
	}

	@Test
	public void aPasserByStoppingForAMomentDoesntMoveAGroup()
	{
		// Someone walking past pauses on the tile next to a settled group for a moment. The group
		// only makes room for people who stay: it doesn't slide over and back for a passer-by.
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, double[]> settled = null;
		for (int t = 1; t <= 40; t++)
		{
			List<StackSpreader.Entry> still = t >= 20 && t < 20 + CrowdPlanner.SETTLE_TICKS
				? fiveAnd(new StackSpreader.Entry(9, StackRegistry.key(0, 51, 50), false, SOUTH)) : fiveAnd();
			Map<Integer, double[]> at = drawnAt(planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), still);
			if (t == 19)
			{
				settled = at;
			}
			for (int i = 1; t > 19 && i <= 5; i++)
			{
				Assert.assertArrayEquals("tick " + t + ": player " + i + " moved for a passer-by", settled.get(i), at.get(i), 0);
			}
		}
	}

	@Test
	public void aGroupDoesntMakeRoomForPeopleTheGameIsntShowing()
	{
		// Players another plugin or the game itself hides aren't drawn, so nobody makes room for them.
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> alone = fiveAnd();
		Map<Integer, double[]> before = null;
		for (int t = 1; t <= 5; t++)
		{
			before = drawnAt(planner.plan(alone, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN), alone);
		}
		List<StackSpreader.Entry> hidden = fiveAnd(new StackSpreader.Entry(20, StackRegistry.key(0, 51, 50), false, SOUTH),
			new StackSpreader.Entry(21, StackRegistry.key(0, 51, 50), false, SOUTH),
			new StackSpreader.Entry(22, StackRegistry.key(0, 49, 50), false, SOUTH));
		for (int t = 6; t <= 20; t++)
		{
			CrowdPlanner.Plan plan = planner.plan(hidden, q -> q < 20, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, OPEN);
			Map<Integer, double[]> at = drawnAt(plan, alone);
			for (int i = 1; i <= 5; i++)
			{
				Assert.assertArrayEquals("tick " + t + ": player " + i + " made room for hidden players", before.get(i), at.get(i), 0);
			}
		}
	}

	@Test
	public void aGroupMakingRoomAgainstAWallKeepsClearOfYouInTheMiddle()
	{
		// You stand still in the middle of your tile with five others; a wall runs along the east
		// edge and people stand to the west. The ring moves over, the wall closes it up, and its near
		// side used to land on you, a unit away.
		CrowdPlanner.Surroundings wall = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return tile != TILE || dx <= 37;
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
		List<StackSpreader.Entry> still = fiveAnd(new StackSpreader.Entry(99, TILE, true, NORTH),
			new StackSpreader.Entry(30, StackRegistry.key(0, 49, 50), false, SOUTH),
			new StackSpreader.Entry(31, StackRegistry.key(0, 49, 49), false, SOUTH),
			new StackSpreader.Entry(32, StackRegistry.key(0, 49, 49), false, SOUTH));
		CrowdPlanner planner = new CrowdPlanner();
		for (int t = 1; t <= 20; t++)
		{
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, t, wall);
			for (StackSpreader.Placement p : plan.placements)
			{
				if (p.tile == TILE)
				{
					Assert.assertTrue("tick " + t + ": player " + p.id + " drawn " + Math.hypot(p.dx, p.dz) + " from you",
						Math.hypot(p.dx, p.dz) >= CrowdPlanner.NEIGHBOUR_GAP - 1);
				}
			}
		}
	}

	/** A bank counter along three booths, with these many people on each, everyone facing it. */
	private static List<StackSpreader.Entry> atThreeBooths(int... counts)
	{
		List<StackSpreader.Entry> still = new ArrayList<>();
		int id = 1;
		for (int b = 0; b < counts.length; b++)
		{
			for (int i = 0; i < counts[b]; i++)
			{
				still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, 50 + b, 50), false, NORTH));
			}
		}
		return still;
	}

	private static double closestPair(CrowdPlanner.Plan plan)
	{
		double closest = Double.MAX_VALUE;
		for (StackSpreader.Placement a : plan.placements)
		{
			for (StackSpreader.Placement b : plan.placements)
			{
				if (a.id < b.id)
				{
					closest = Math.min(closest, Math.hypot(StackRegistry.sceneX(a.tile) * 128 + a.dx - StackRegistry.sceneX(b.tile) * 128 - b.dx,
						a.dz - b.dz));
				}
			}
		}
		return closest;
	}

	@Test
	public void aBankLineStandsABodysWidthApartUnlessItIsPacked()
	{
		// Along a counter shared by three booths, people stand a body's width apart, so seen from
		// the side nobody runs into the person beside them. Only when a booth gets packed does the
		// line close up, to fit everyone rather than leave someone hidden in the middle.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		CrowdPlanner quiet = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = quiet.plan(atThreeBooths(4, 4, 3), q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, false, t, counter);
		}
		Assert.assertEquals(11, plan.placements.size());
		Assert.assertTrue("quiet line only " + closestPair(plan) + " apart", closestPair(plan) >= PersonalSpaceConfig.COUNTER_SPACING - 1);

		CrowdPlanner packed = new CrowdPlanner();
		for (int t = 1; t <= 5; t++)
		{
			plan = packed.plan(atThreeBooths(4, CrowdPlanner.ROOMY_LINE_MAX + 2, 4), q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, false, t, counter);
		}
		Assert.assertEquals("everyone on the packed line has a spot", CrowdPlanner.ROOMY_LINE_MAX + 10, plan.placements.size());
		Assert.assertEquals(PersonalSpaceConfig.LINE_SPACING, plan.tiles.get(TILE).spacing);
	}

	@Test
	public void whenABankLineClosesUpEveryoneKeepsTheirPlaceInIt()
	{
		// A quiet line at a body's width; then a booth fills up and the line closes up. Everyone
		// slides a little along the counter and nobody passes anyone.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, Double> before = new HashMap<>();
		for (int t = 1; t <= 5; t++)
		{
			CrowdPlanner.Plan plan = planner.plan(atThreeBooths(3, 3, 3), q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, false, t, counter);
			before.clear();
			for (StackSpreader.Placement p : plan.placements)
			{
				if (p.dz > -24)
				{
					before.put(p.id, StackRegistry.sceneX(p.tile) * 128.0 + p.dx);
				}
			}
		}
		List<StackSpreader.Entry> busier = atThreeBooths(3, 3, 3);
		for (int i = 0; i < CrowdPlanner.ROOMY_LINE_MAX; i++)
		{
			busier.add(new StackSpreader.Entry(100 + i, StackRegistry.key(0, 51, 50), false, NORTH));
		}
		CrowdPlanner.Plan plan = planner.plan(busier, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, false, 6, counter);
		Map<Integer, Double> after = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			if (before.containsKey(p.id))
			{
				after.put(p.id, StackRegistry.sceneX(p.tile) * 128.0 + p.dx);
			}
		}
		List<Integer> order = new ArrayList<>(before.keySet());
		order.sort(Comparator.comparingDouble(before::get));
		for (int i = 1; i < order.size(); i++)
		{
			if (after.containsKey(order.get(i - 1)) && after.containsKey(order.get(i)))
			{
				Assert.assertTrue(order.get(i - 1) + " and " + order.get(i) + " swapped places along the counter",
					after.get(order.get(i - 1)) < after.get(order.get(i)));
			}
		}
	}

	@Test
	public void onAFullTileThePeopleLeftWaitingAreTheBusyOnes()
	{
		// Eight on a tile with room for five: the three left without a spot are people busy alching,
		// not whoever arrived last.
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int i = 1; i <= 8; i++)
		{
			still.add(new StackSpreader.Entry(i, TILE, false, NORTH, i % 2 == 0));
		}
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 5, true, false, t, OPEN);
		}
		Assert.assertEquals(5, plan.placements.size());
		for (StackSpreader.Placement p : plan.unplaced)
		{
			Assert.assertTrue("player " + p.id + " waits though they aren't busy", p.id % 2 == 0);
		}
	}

	@Test
	public void inACrampedNookNobodyIsDrawnInsideYou()
	{
		// An anvil ahead, a wall just behind the middle of the tile, crates either side, and eleven
		// smiths on the tile with you (you aren't smithing). With nowhere else to go, people were
		// squeezed in right on top of you; now anyone who'd be drawn inside you waits out of sight.
		CrowdPlanner.Surroundings nook = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return dz <= 32 && dz >= -40 && Math.abs(dx) <= 90;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
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
		for (int smiths = 4; smiths <= 12; smiths++)
		{
			for (int k = 0; k < 8; k++)
			{
				List<StackSpreader.Entry> still = new ArrayList<>();
				for (int i = 1; i <= smiths; i++)
				{
					still.add(new StackSpreader.Entry(i, TILE, false, NORTH, true));
				}
				still.add(new StackSpreader.Entry(99, TILE, true, 0, false));
				CrowdPlanner planner = new CrowdPlanner();
				planner.cameraX = StackRegistry.sceneX(TILE) * 128 + 64 + (int) Math.round(Math.cos(k * Math.PI / 4) * 1500);
				planner.cameraY = StackRegistry.sceneY(TILE) * 128 + 64 + (int) Math.round(Math.sin(k * Math.PI / 4) * 1500);
				for (int t = 1; t <= 30; t++)
				{
					CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, nook);
					if (t < 20)
					{
						continue;
					}
					String when = smiths + " smiths, camera " + k + ", tick " + t + ": ";
					double[] you = new double[2];
					for (StackSpreader.Placement p : plan.placements)
					{
						if (p.id == 99)
						{
							you = new double[]{p.dx, p.dz};
						}
					}
					for (StackSpreader.Placement p : plan.placements)
					{
						double apart = Math.hypot(p.dx - you[0], p.dz - you[1]);
						Assert.assertTrue(when + "player " + p.id + " drawn " + apart + " from you", p.id == 99 || apart >= 40);
					}
					double middle = Math.hypot(you[0], you[1]);
					boolean someoneInMiddle = false;
					for (StackSpreader.Placement p : plan.unplaced)
					{
						someoneInMiddle |= p.id != 99;
					}
					Assert.assertTrue(when + "someone waiting in the middle is drawn " + middle + " from you",
						!someoneInMiddle || middle >= 40 || plan.middleOutOfSight.contains(TILE));
				}
			}
		}
	}

	@Test
	public void atABankYouTakeAnEdgeSpotHeldForSomeoneStraightAway()
	{
		// A short counter with four spots at the edge and six people, so two stand behind. Someone
		// at the edge steps away; you arrive while their spot is still held for them. You take it
		// the tick you arrive, nobody else moves, and when they come back they get a free spot.
		CrowdPlanner.Surroundings shortBank = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz <= 0 && x >= 50 * 128 - 100 && x <= 50 * 128 + 100;
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
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> six = bank(new int[]{6}, 1);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(six, x -> true, 128, 10, true, true, t, shortBank));
		}
		int atEdge = 0;
		int leaver = -1;
		for (Map.Entry<Integer, int[]> e : settled.entrySet())
		{
			if (Math.abs(e.getValue()[1]) <= StackSpreader.MAX_BOW)
			{
				atEdge++;
				leaver = leaver < 0 || Math.abs(e.getValue()[0]) < Math.abs(settled.get(leaver)[0]) ? e.getKey() : leaver;
			}
		}
		Assert.assertTrue(atEdge + " at the edge: some at the edge, some behind", atEdge >= 3 && atEdge <= 5);
		List<StackSpreader.Entry> without = new ArrayList<>();
		for (StackSpreader.Entry e : six)
		{
			if (e.id != leaver)
			{
				without.add(e);
			}
		}
		spots(planner.plan(without, x -> true, 128, 10, true, true, 4, shortBank));
		List<StackSpreader.Entry> withYou = new ArrayList<>(without);
		withYou.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
		Map<Integer, int[]> arrived = spots(planner.plan(withYou, x -> true, 128, 10, true, true, 5, shortBank));
		Assert.assertTrue("you stand " + arrived.get(99)[1] + " back the tick you arrive, not at the counter",
			Math.abs(arrived.get(99)[1]) <= StackSpreader.MAX_BOW);
		for (StackSpreader.Entry e : without)
		{
			Assert.assertArrayEquals("player " + e.id + " moved", settled.get(e.id), arrived.get(e.id));
		}
		List<StackSpreader.Entry> back = new ArrayList<>(withYou);
		back.add(new StackSpreader.Entry(leaver, StackRegistry.key(0, 50, 50), false, NORTH));
		Map<Integer, int[]> returned = null;
		for (int t = 6; t <= 8; t++)
		{
			returned = spots(planner.plan(back, x -> true, 128, 10, true, true, t, shortBank));
		}
		Assert.assertArrayEquals("you keep the edge", arrived.get(99), returned.get(99));
		Assert.assertNotNull("they get a spot", returned.get(leaver));
		for (StackSpreader.Entry e : without)
		{
			Assert.assertArrayEquals("player " + e.id + " moved when they came back", settled.get(e.id), returned.get(e.id));
		}
	}

	/** A short counter: four spots at the edge, so six people means two stand behind. */
	private static CrowdPlanner.Surroundings shortCounter()
	{
		return new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz <= 0 && x >= 50 * 128 - 100 && x <= 50 * 128 + 100;
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
	}

	@Test
	public void aStrangerWalksStraightIntoAHeldSpotAtTheCounter()
	{
		// Someone at the counter steps away and their spot is held for them. A stranger arriving
		// then used to stand behind for five seconds and step forward when the hold ran out; now
		// they walk straight into the gap, and nobody else moves.
		CrowdPlanner.Surroundings counter = shortCounter();
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> six = bank(new int[]{6}, 1);
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(six, x -> true, 128, 10, true, false, t, counter));
		}
		int leaver = -1;
		for (Map.Entry<Integer, int[]> e : settled.entrySet())
		{
			if (Math.abs(e.getValue()[1]) <= StackSpreader.MAX_BOW)
			{
				leaver = leaver < 0 || Math.abs(e.getValue()[0]) < Math.abs(settled.get(leaver)[0]) ? e.getKey() : leaver;
			}
		}
		Assert.assertTrue("someone is at the counter", leaver > 0);
		List<StackSpreader.Entry> without = new ArrayList<>();
		for (StackSpreader.Entry e : six)
		{
			if (e.id != leaver)
			{
				without.add(e);
			}
		}
		spots(planner.plan(without, x -> true, 128, 10, true, false, 4, counter));
		List<StackSpreader.Entry> withStranger = new ArrayList<>(without);
		withStranger.add(new StackSpreader.Entry(50, StackRegistry.key(0, 50, 50), false, NORTH));
		Map<Integer, int[]> arrived = spots(planner.plan(withStranger, x -> true, 128, 10, true, false, 5, counter));
		Assert.assertTrue("the stranger stands " + arrived.get(50)[1] + " back, not at the counter",
			Math.abs(arrived.get(50)[1]) <= StackSpreader.MAX_BOW);
		for (StackSpreader.Entry e : without)
		{
			Assert.assertArrayEquals("player " + e.id + " moved", settled.get(e.id), arrived.get(e.id));
		}
	}

	@Test
	public void aSpotHeldForYouAtTheCounterStaysYours()
	{
		// You step away from the counter for a moment; a stranger arrives meanwhile. They don't
		// take your spot, and when you come back it is still yours.
		CrowdPlanner.Surroundings counter = shortCounter();
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> six = bank(new int[]{5}, 1);
		six.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 3; t++)
		{
			settled = spots(planner.plan(six, x -> true, 128, 10, true, true, t, counter));
		}
		int[] yours = settled.get(99);
		Assert.assertTrue("you're at the counter", Math.abs(yours[1]) <= StackSpreader.MAX_BOW);
		List<StackSpreader.Entry> without = new ArrayList<>();
		for (StackSpreader.Entry e : six)
		{
			if (e.id != 99)
			{
				without.add(e);
			}
		}
		spots(planner.plan(without, x -> true, 128, 10, true, true, 4, counter));
		List<StackSpreader.Entry> withStranger = new ArrayList<>(without);
		withStranger.add(new StackSpreader.Entry(50, StackRegistry.key(0, 50, 50), false, NORTH));
		Map<Integer, int[]> arrived = spots(planner.plan(withStranger, x -> true, 128, 10, true, true, 5, counter));
		Assert.assertFalse("the stranger took your spot", java.util.Arrays.equals(yours, arrived.get(50)));
		List<StackSpreader.Entry> back = new ArrayList<>(withStranger);
		back.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
		Map<Integer, int[]> returned = spots(planner.plan(back, x -> true, 128, 10, true, true, 6, counter));
		Assert.assertArrayEquals("your spot is still yours", yours, returned.get(99));
	}

	/** A bank so narrow the water is only in front of one spot: rows behind have room, the edge doesn't. */
	private static CrowdPlanner.Surroundings narrowBank()
	{
		return new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 128 + dx;
				return dz <= 0 && (dz < -10 || Math.abs(x - 50 * 128) <= 20);
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
	}

	@Test
	public void youGetTheOneSpotAtTheWaterEvenIfSomeoneFromTheNextTileHasIt()
	{
		// Fishers queued up a narrow bank, the one spot at the water taken by someone from the tile
		// beside yours: you swap into it, as at a bank counter, rather than fish from the back.
		CrowdPlanner.Surroundings bank = narrowBank();
		for (int spacing : new int[]{42, 128})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{4, 3}, 1);
			Map<Integer, int[]> settled = null;
			for (int t = 1; t <= 3; t++)
			{
				settled = spots(planner.plan(still, x -> true, spacing, 10, true, true, t, bank));
			}
			int edgeHolder = -1;
			for (Map.Entry<Integer, int[]> e : settled.entrySet())
			{
				edgeHolder = Math.abs(e.getValue()[1]) <= StackSpreader.MAX_BOW ? e.getKey() : edgeHolder;
			}
			Assert.assertTrue("spacing " + spacing + ": someone has the spot at the water", edgeHolder > 0);
			List<StackSpreader.Entry> withYou = new ArrayList<>(still);
			withYou.add(new StackSpreader.Entry(99, StackRegistry.key(0, 51, 50), true, NORTH));
			Map<Integer, int[]> now = null;
			for (int t = 4; t <= 6; t++)
			{
				now = spots(planner.plan(withYou, x -> true, spacing, 10, true, true, t, bank));
			}
			int[] yours = now.get(99);
			Assert.assertNotNull("spacing " + spacing + ": you have a spot", yours);
			Assert.assertTrue("spacing " + spacing + ": you're " + yours[1] + " back from the water", Math.abs(yours[1]) <= StackSpreader.MAX_BOW);
			Assert.assertNotNull("spacing " + spacing + ": whoever was there still has a spot", now.get(edgeHolder));
		}
	}

	@Test
	public void someoneMidSpellKeepsTheirSpotWhenAFullTileGetsANewcomer()
	{
		// Five on a tile with room for five, one of them alching. Someone who isn't busy arrives:
		// they wait, rather than the alcher sliding off to the middle mid-cast.
		List<StackSpreader.Entry> five = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			five.add(new StackSpreader.Entry(i, TILE, false, NORTH, i == 3));
		}
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> settled = null;
		for (int t = 1; t <= 5; t++)
		{
			settled = spots(planner.plan(five, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 5, true, false, t, OPEN));
		}
		Assert.assertNotNull("the alcher has a spot", settled.get(3));
		List<StackSpreader.Entry> six = new ArrayList<>(five);
		six.add(new StackSpreader.Entry(6, TILE, false, NORTH, false));
		for (int t = 6; t <= 10; t++)
		{
			Map<Integer, int[]> now = spots(planner.plan(six, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 5, true, false, t, OPEN));
			Assert.assertNotNull("tick " + t + ": the alcher was sent to the middle mid-cast", now.get(3));
			double moved = Math.hypot(now.get(3)[0] - settled.get(3)[0], now.get(3)[1] - settled.get(3)[1]);
			Assert.assertTrue("tick " + t + ": the alcher was moved " + moved + " mid-cast", moved < 16);
			Assert.assertNull("the newcomer waits", now.get(6));
		}
	}

	@Test
	public void someoneJoiningYouAtAFireGoesStraightToYourSideFromAnyAngle()
	{
		// You at a fire on your own; someone joins you. From every camera direction they are put
		// beside you the tick they arrive and stay there: not round the far side of you first
		// (judged from where you stood a moment ago), and not round the far side for good (with the
		// camera on their side, you take that side and they the other).
		CrowdPlanner.Surroundings fire = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return dz <= 60;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				List<int[]> out = new ArrayList<>();
				out.add(new int[]{0, 1});
				return out;
			}
		};
		int[][] cameras = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
		for (int[] camera : cameras)
		{
			CrowdPlanner planner = new CrowdPlanner();
			cameraAt(planner, camera[0], camera[1]);
			List<StackSpreader.Entry> alone = new ArrayList<>();
			alone.add(new StackSpreader.Entry(99, TILE, true, NORTH));
			for (int t = 1; t <= 5; t++)
			{
				planner.plan(alone, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, fire);
			}
			List<StackSpreader.Entry> pair = new ArrayList<>(alone);
			pair.add(new StackSpreader.Entry(7, TILE, false, NORTH));
			Map<Integer, int[]> first = null;
			for (int t = 6; t <= 10; t++)
			{
				Map<Integer, int[]> now = spots(planner.plan(pair, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, fire));
				String where = "camera " + camera[0] + "," + camera[1] + " tick " + t + ": ";
				Assert.assertEquals(where + "side by side, level with you", now.get(99)[1], now.get(7)[1]);
				Assert.assertTrue(where + "right beside you", Math.abs(now.get(99)[0] - now.get(7)[0]) <= 64);
				if (first == null)
				{
					first = now;
				}
				Assert.assertArrayEquals(where + "they moved", first.get(7), now.get(7));
			}
		}
	}

	@Test
	public void aPairAtAFireBesideYouKeepsItsShapeUnlessYouAskToBeDrawnFirst()
	{
		// A pair at a fire; you stand on the tile diagonally beside theirs, the camera off to one
		// side, so one of the pair is between you and it. They stay side by side (natural: they
		// cover you now and then), unless "Draw me in front" asks for you to come first.
		CrowdPlanner.Surroundings fire = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				return dz <= 60;
			}

			@Override
			public boolean facesObstacle(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
			}

			@Override
			public boolean isCounter(long tile, double angle)
			{
				return false;
			}

			@Override
			public boolean facesFire(long tile, double angle)
			{
				return Math.abs(angle - Math.PI) < 0.01;
			}

			@Override
			public List<int[]> firesNear(long tile)
			{
				List<int[]> out = new ArrayList<>();
				out.add(new int[]{0, 1});
				return out;
			}
		};
		for (boolean youFirst : new boolean[]{false})
		{
			CrowdPlanner planner = new CrowdPlanner();
			planner.cameraX = 49 * 128 + 64 + 1061;
			planner.cameraY = 49 * 128 + 64 + 1061;
			List<StackSpreader.Entry> still = new ArrayList<>();
			still.add(new StackSpreader.Entry(1, TILE, false, NORTH));
			still.add(new StackSpreader.Entry(2, TILE, false, NORTH));
			still.add(new StackSpreader.Entry(99, StackRegistry.key(0, 49, 49), true, NORTH));
			boolean split = false;
			for (int t = 1; t <= 12; t++)
			{
				Map<Integer, int[]> now = spots(planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, fire));
				split |= t >= 4 && Math.abs(now.get(1)[1] - now.get(2)[1]) > 20;
			}
			Assert.assertEquals(youFirst ? "with Draw me in front, one steps out of your view" : "the pair stays side by side",
				youFirst, split);
		}
	}

	@Test
	public void standingATileAwayFromAPileLeavesItAsItIs()
	{
		// A pile at an anvil; you stand alone on the tile beside it (level with the anvil's end, or
		// diagonally), the camera looking across both. The pile stays as it is whether you're
		// there or not: nobody steps out of your line of sight, it doesn't back away from you, and
		// its curve doesn't stop short of you.
		CrowdPlanner.Surroundings anvil = surroundings(true, false, false);
		int[][] cameras = {{0, -1}, {1, 0}, {-1, 0}, {0, 1}};
		for (int[] camera : cameras)
		{
			List<StackSpreader.Entry> pile = new ArrayList<>();
			for (int i = 1; i <= 8; i++)
			{
				pile.add(new StackSpreader.Entry(i, TILE, false, NORTH, i % 3 == 0));
			}
			CrowdPlanner planner = new CrowdPlanner();
			cameraAt(planner, camera[0], camera[1]);
			Map<Integer, int[]> without = null;
			for (int t = 1; t <= 12; t++)
			{
				without = spots(planner.plan(pile, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, anvil));
			}
			List<StackSpreader.Entry> withYou = new ArrayList<>(pile);
			withYou.add(new StackSpreader.Entry(99, StackRegistry.key(0, camera[0] == 0 ? 49 : 51, camera[0] == 0 ? 50 : 49), true, NORTH));
			for (int t = 13; t <= 24; t++)
			{
				Map<Integer, int[]> now = spots(planner.plan(withYou, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, anvil));
				for (int i = 1; i <= 8; i++)
				{
					Assert.assertArrayEquals("camera " + camera[0] + "," + camera[1] + " tick " + t + ": player " + i + " moved for you",
						without.get(i), now.get(i));
				}
			}
		}
	}

	@Test
	public void nobodyStepsOutOfYourLineOfSight()
	{
		// A crowd huddles as it would wherever the camera is: at a busy booth, along a bank line
		// of three booths, in a pile at an anvil. Everyone else stands exactly where they would
		// with no camera to go by. (Seeing yourself over them is "Draw me in front".)
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		CrowdPlanner.Surroundings anvil = surroundings(true, false, false);
		int[][] cameras = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
		for (int scene = 0; scene < 3; scene++)
		{
			List<StackSpreader.Entry> still = new ArrayList<>();
			int id = 1;
			int tiles = scene == 0 ? 1 : 3;
			int each = scene == 0 ? 8 : scene == 1 ? 4 : 6;
			for (int x = 0; x < tiles; x++)
			{
				for (int i = 0; i < each; i++)
				{
					boolean you = x == tiles / 2 && i == 0;
					still.add(new StackSpreader.Entry(you ? 99 : id++, StackRegistry.key(0, 50 + x, 50), you, NORTH, scene == 2 && !you));
				}
			}
			CrowdPlanner.Surroundings around = scene == 2 ? anvil : counter;
			CrowdPlanner blind = new CrowdPlanner();
			Map<Integer, int[]> without = null;
			for (int t = 1; t <= 16; t++)
			{
				without = spots(blind.plan(still, q -> true, 128, 16, true, true, t, around));
			}
			for (int[] camera : cameras)
			{
				CrowdPlanner planner = new CrowdPlanner();
				cameraAt(planner, camera[0], camera[1]);
				Map<Integer, int[]> with = null;
				for (int t = 1; t <= 16; t++)
				{
					with = spots(planner.plan(still, q -> true, 128, 16, true, true, t, around));
				}
				for (StackSpreader.Entry e : still)
				{
					if (e.id != 99)
					{
						Assert.assertArrayEquals("scene " + scene + ", camera " + camera[0] + "," + camera[1] + ": player " + e.id + " moved out of your way",
							without.get(e.id), with.get(e.id));
					}
				}
			}
		}
	}
}
