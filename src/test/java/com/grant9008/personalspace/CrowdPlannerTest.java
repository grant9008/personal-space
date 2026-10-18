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
		// Those left without a spot aren't drawn in the middle of their tile at all, so nobody at the
		// edge is drawn on top of them, and nobody with a spot is hidden for their sake.
		for (StackSpreader.Placement u : plan.unplaced)
		{
			Assert.assertTrue("whoever waits in the middle of " + u.tile + " isn't drawn", plan.middleOutOfSight.contains(u.tile));
		}
		for (StackSpreader.Placement u : plan.unplaced)
		{
			for (StackSpreader.Placement p : plan.placements)
			{
				int ux = StackRegistry.sceneX(u.tile) * 128;
				int px = StackRegistry.sceneX(p.tile) * 128 + p.dx;
				Assert.assertTrue("player " + p.id + " is drawn on top of " + u.id + " in the middle of their tile",
					p.dz != 0 || Math.abs(px - ux) >= PersonalSpaceConfig.COUNTER_SPACING || plan.middleOutOfSight.contains(u.tile));
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
		// A pair stays close, with a gap between them; a big crowd closer still.
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(players(1, NORTH, 2, NORTH),
			id -> true, PersonalSpaceConfig.SPACING_WIDE, 5, true, false, 1, surroundings(false, false, true));
		Assert.assertEquals(PersonalSpaceConfig.SMALL_FIRE_SPACING, plan.tiles.get(TILE).spacing);
		Assert.assertTrue(plan.curvedRows.contains(TILE));
		List<StackSpreader.Entry> crowd = new ArrayList<>();
		for (int i = 1; i <= 8; i++)
		{
			crowd.add(new StackSpreader.Entry(i, TILE, false, NORTH));
		}
		CrowdPlanner.Plan big = new CrowdPlanner().plan(crowd, id -> true, PersonalSpaceConfig.SPACING_WIDE, 16, true, false, 1, surroundings(false, false, true));
		Assert.assertEquals(PersonalSpaceConfig.FIRE_SPACING, big.tiles.get(TILE).spacing);
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
		// Their shares are remembered RELAX_TICKS, then the room has to last WIDEN_TICKS before it opens out.
		for (int t = 12; t <= 11 + CrowdPlanner.RELAX_TICKS + CrowdPlanner.WIDEN_TICKS + 1; t++)
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

	@Test
	public void aBusyLineReachesPastYouStandingAloneBesideIt()
	{
		// Two packed tiles along a riverbank and you alone on the next tile along, not fishing. The
		// line used to stop short of you and leave the bank beyond you empty.
		CrowdPlanner.Surroundings bank = surroundings(true, true, false);
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> still = bank(new int[]{10, 10}, 1);
		still.add(new StackSpreader.Entry(99, StackRegistry.key(0, 52, 50), true, SOUTH));
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 4; t++)
		{
			plan = planner.plan(still, x -> true, 128, 16, true, true, t, bank);
		}
		int farthest = Integer.MIN_VALUE;
		for (StackSpreader.Placement p : plan.placements)
		{
			if (p.id != 99)
			{
				farthest = Math.max(farthest, StackRegistry.sceneX(p.tile) * 128 + p.dx);
				Assert.assertTrue("player " + p.id + " is drawn on top of you",
					Math.hypot(StackRegistry.sceneX(p.tile) * 128 + p.dx - 52 * 128, p.dz) >= CrowdPlanner.YOUR_SPACE);
			}
		}
		Assert.assertTrue("the line stops at " + farthest + ", short of you at " + 52 * 128, farthest > 52 * 128);
	}

	@Test
	public void nobodyOnABusyLineIsDrawnFarAlongFromTheirOwnTile()
	{
		// Two packed tiles at a long counter with free counter beyond them. The edge used to win over
		// a row behind however far along it was, and people went to the far end of the free stretch.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		for (int spacing : new int[]{42, 128, 256})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{14, 14}, 1);
			CrowdPlanner.Plan plan = null;
			for (int t = 1; t <= 4; t++)
			{
				plan = planner.plan(still, x -> true, spacing, 16, true, false, t, counter);
			}
			Assert.assertFalse(plan.placements.isEmpty());
			for (StackSpreader.Placement p : plan.placements)
			{
				Assert.assertTrue("spacing " + spacing + ": player " + p.id + " is drawn " + p.dx + " along from their tile",
					Math.abs(p.dx) <= Math.max(LineBook.MAX_REACH, 1.5 * spacing) + 1);
			}
		}
	}

	@Test
	public void atABusyCounterYouStandInFrontOfYourOwnTile()
	{
		// A packed counter shared by two tiles; you arrive last on the first. You used to get whatever
		// front spot was left, out at the end by the wall; now you stand at the counter in front of
		// where you really are, and whoever had that spot takes yours.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		for (int spacing : new int[]{42, 128})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{12, 12}, 1);
			for (int t = 1; t <= 3; t++)
			{
				planner.plan(still, x -> true, spacing, 16, true, true, t, counter);
			}
			List<StackSpreader.Entry> withYou = new ArrayList<>(still);
			withYou.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
			Map<Integer, int[]> now = null;
			for (int t = 4; t <= 7; t++)
			{
				now = spots(planner.plan(withYou, x -> true, spacing, 16, true, true, t, counter));
			}
			int[] yours = now.get(99);
			Assert.assertNotNull("spacing " + spacing + ": you have a spot", yours);
			Assert.assertTrue("spacing " + spacing + ": you're " + yours[1] + " back from the counter", Math.abs(yours[1]) <= StackSpreader.MAX_BOW);
			Assert.assertTrue("spacing " + spacing + ": you're " + yours[0] + " along from your tile", Math.abs(yours[0]) <= 48);
			Assert.assertEquals("spacing " + spacing + ": everyone still has a spot", 25, now.size());
		}
	}

	@Test
	public void atTheCounterYouEndUpInFrontOfYourOwnTileAsItFillsUp()
	{
		// You get to the counter while your tile is quiet and the next one packed, so your tile's
		// share of the counter is out at the end; then your tile fills up too. You used to keep your
		// spot out at the end by the wall; you move along to the counter in front of where you stand.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		for (int spacing : new int[]{48, 128})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{2, 8}, 1);
			still.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
			int t = 1;
			for (; t <= 4; t++)
			{
				planner.plan(still, x -> true, spacing, 8, true, true, t, counter);
			}
			for (int i = 0; i < 5; i++)
			{
				still.add(new StackSpreader.Entry(200 + i, StackRegistry.key(0, 50, 50), false, NORTH));
			}
			Map<Integer, int[]> now = null;
			for (; t <= 10; t++)
			{
				now = spots(planner.plan(still, x -> true, spacing, 8, true, true, t, counter));
			}
			int[] yours = now.get(99);
			Assert.assertNotNull("spacing " + spacing + ": you have a spot", yours);
			Assert.assertTrue("spacing " + spacing + ": you're " + yours[1] + " back from the counter", Math.abs(yours[1]) <= StackSpreader.MAX_BOW);
			Assert.assertTrue("spacing " + spacing + ": you're " + yours[0] + " along from your tile", Math.abs(yours[0]) <= spacing);
		}
	}

	@Test
	public void swappingYouToTheFrontNeverLeavesAnyoneWithoutASpot()
	{
		// A narrow bank with room at the water for one, a packed tile beside yours. Whoever you swap
		// with must have somewhere within reach to go, or they were left without a spot and their
		// tile's middle hid the spot you'd taken, both of you vanishing for good.
		CrowdPlanner.Surroundings bank = narrowBank();
		for (int spacing : new int[]{42, 128})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{9, 4}, 1);
			int t = 1;
			for (; t <= 3; t++)
			{
				planner.plan(still, x -> true, spacing, 16, true, true, t, bank);
			}
			int before = spots(planner.plan(still, x -> true, spacing, 16, true, true, t++, bank)).size();
			still.add(new StackSpreader.Entry(99, StackRegistry.key(0, 51, 50), true, NORTH));
			for (; t <= 12; t++)
			{
				Map<Integer, int[]> now = spots(planner.plan(still, x -> true, spacing, 16, true, true, t, bank));
				Assert.assertTrue("spacing " + spacing + " tick " + t + ": you have no spot", now.containsKey(99));
				Assert.assertTrue("spacing " + spacing + " tick " + t + ": " + now.size() + " placed, " + before + " before you came",
					now.size() >= before);
			}
		}
	}

	@Test
	public void aWideLineStillHasRoomForEveryone()
	{
		// Auto-space off and the slider wide: the line's spacing is wider than a tile. A fixed reach
		// of a tile and a half left people without a spot and hid the whole front row.
		CrowdPlanner.Surroundings counter = surroundings(true, true, false);
		for (int spacing : new int[]{192, 256})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = bank(new int[]{5, 5}, 1);
			Map<Integer, int[]> now = null;
			for (int t = 1; t <= 6; t++)
			{
				now = spots(planner.plan(still, x -> true, spacing, 5, false, false, t, counter));
			}
			Assert.assertEquals("spacing " + spacing + ": everyone has a spot", 10, now.size());
			int atCounter = 0;
			for (int[] spot : now.values())
			{
				atCounter += Math.abs(spot[1]) <= StackSpreader.MAX_BOW ? 1 : 0;
			}
			Assert.assertTrue("spacing " + spacing + ": nobody at the counter", atCounter > 0);
		}
	}

	@Test
	public void aCampfireCrowdWithBusyNeighboursDoesntKeepRearranging()
	{
		// Eight people tending a fire, people coming and going on every tile round them. The crowd
		// flipped between a row and a ring, and opened out and closed up, every few ticks, and the
		// whole crowd slid about each time.
		CrowdPlanner.Surroundings fireToTheEast = new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz)
			{
				int x = StackRegistry.sceneX(t) * 128 + dx;
				int z = StackRegistry.sceneY(t) * 128 + dz;
				return !(x > 51 * 128 - 84 && x < 51 * 128 + 84 && z > 50 * 128 - 84 && z < 50 * 128 + 84);
			}
			public boolean facesObstacle(long t, double a) { return false; }
			public boolean isCounter(long t, double a) { return false; }
			public boolean facesFire(long t, double a)
			{
				return t == StackRegistry.key(0, 50, 50) && Math.abs(Math.sin(a) + 1) < 0.2;
			}
			public List<int[]> firesNear(long t)
			{
				List<int[]> out = new ArrayList<>();
				int dx = 51 - StackRegistry.sceneX(t);
				int dz = 50 - StackRegistry.sceneY(t);
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0))
				{
					out.add(new int[]{dx, dz});
				}
				return out;
			}
		};
		long[] around = {
			StackRegistry.key(0, 49, 49), StackRegistry.key(0, 49, 50), StackRegistry.key(0, 49, 51),
			StackRegistry.key(0, 50, 49), StackRegistry.key(0, 50, 51), StackRegistry.key(0, 51, 49),
			StackRegistry.key(0, 51, 51), StackRegistry.key(0, 52, 49), StackRegistry.key(0, 52, 51)};
		java.util.Random rnd = new java.util.Random(1);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Long, List<Integer>> there = new HashMap<>();
		for (long tile : around)
		{
			there.put(tile, new ArrayList<>());
		}
		int nextId = 100;
		int wholeCrowdMoves = 0;
		Map<Integer, int[]> last = null;
		for (int t = 1; t <= 220; t++)
		{
			for (long tile : around)
			{
				List<Integer> list = there.get(tile);
				if (rnd.nextInt(100) < 30)
				{
					if (!list.isEmpty() && (list.size() >= 4 || rnd.nextBoolean()))
					{
						list.remove(rnd.nextInt(list.size()));
					}
					else
					{
						list.add(nextId++);
					}
				}
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (int i = 1; i <= 8; i++)
			{
				still.add(new StackSpreader.Entry(i, StackRegistry.key(0, 50, 50), false, 1536, true));
			}
			for (long tile : around)
			{
				for (int id : there.get(tile))
				{
					still.add(new StackSpreader.Entry(id, tile, false, rnd.nextInt(4) * 512, false));
				}
			}
			Map<Integer, int[]> now = spots(planner.plan(still, q -> true, 79, 16, true, false, t, fireToTheEast));
			if (t > 20 && last != null)
			{
				int moved = 0;
				for (int i = 1; i <= 8; i++)
				{
					int[] a = last.get(i);
					int[] b = now.get(i);
					if (a != null && b != null && Math.hypot(a[0] - b[0], a[1] - b[1]) > 4)
					{
						moved++;
					}
				}
				wholeCrowdMoves += moved >= 7 ? 1 : 0;
			}
			last = now;
		}
		Assert.assertTrue("the whole crowd moved " + wholeCrowdMoves + " times in 200 ticks", wholeCrowdMoves <= 8);
	}

	@Test
	public void nobodyFromTheNextTileIsEverDrawnInsideYou()
	{
		// A packed tile beside yours, every kind of layout and spacing: whoever would be drawn
		// within a body's width of you waits in the middle of their tile instead.
		for (CrowdPlanner.Surroundings around : new CrowdPlanner.Surroundings[]{OPEN, surroundings(true, true, false), surroundings(true, false, true)})
		{
			for (int spacing : new int[]{40, 79, 128, 256})
			{
				CrowdPlanner planner = new CrowdPlanner();
				List<StackSpreader.Entry> still = new ArrayList<>();
				for (int i = 1; i <= 14; i++)
				{
					still.add(new StackSpreader.Entry(i, StackRegistry.key(0, 51, 50), false, NORTH));
				}
				still.add(new StackSpreader.Entry(99, StackRegistry.key(0, 50, 50), true, NORTH));
				CrowdPlanner.Plan plan = null;
				for (int t = 1; t <= 4; t++)
				{
					plan = planner.plan(still, x -> true, spacing, 16, true, true, t, around);
				}
				double[] you = {50 * 128 + 64, 50 * 128 + 64};
				for (StackSpreader.Placement p : plan.placements)
				{
					if (p.id == 99)
					{
						you = new double[]{StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx, StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz};
					}
				}
				for (StackSpreader.Placement p : plan.placements)
				{
					if (p.id != 99)
					{
						double d = Math.hypot(StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx - you[0], StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz - you[1]);
						Assert.assertTrue("spacing " + spacing + ": player " + p.id + " is drawn " + d + " from you", d >= CrowdPlanner.YOUR_SPACE);
					}
				}
			}
		}
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
	public void beSideAFireTheSpotNearestItIsYours()
	{
		// Tending a fire with someone on your tile who was just standing about with their back to
		// it, you were drawn further off than them. Wherever the fire and the camera are, and
		// however many are with you, your spot is the one nearest the fire.
		int[][] fires = {{0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}};
		int[][] cameras = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
		int checked = 0;
		for (int[] at : fires)
		{
			CrowdPlanner.Surroundings fire = new CrowdPlanner.Surroundings()
			{
				public boolean canStand(long tile, int dx, int dz) { return true; }
				public boolean facesObstacle(long tile, double angle) { return false; }
				public boolean isCounter(long tile, double angle) { return false; }
				public boolean facesFire(long tile, double angle) { return false; }
				public List<int[]> firesNear(long tile)
				{
					List<int[]> out = new ArrayList<>();
					out.add(at);
					return out;
				}
			};
			int toFire = OffsetTable.facing(at[0], at[1]);
			for (int others = 1; others <= 3; others++)
			{
				for (int[] camera : cameras)
				{
					CrowdPlanner planner = new CrowdPlanner();
					cameraAt(planner, camera[0], camera[1]);
					List<StackSpreader.Entry> here = new ArrayList<>();
					for (int i = 1; i <= others; i++)
					{
						here.add(new StackSpreader.Entry(i, TILE, false, i % 2 == 1 ? (toFire + 1024) % 2048 : toFire));
					}
					for (int t = 1; t <= 5; t++)
					{
						planner.plan(here, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, fire);
					}
					here.add(new StackSpreader.Entry(99, TILE, true, toFire));
					for (int t = 6; t <= 20; t++)
					{
						CrowdPlanner.Plan plan = planner.plan(here, q -> true, PersonalSpaceConfig.SPACING_NORMAL, 16, true, true, t, fire);
						int[] faced = plan.fires.get(TILE);
						if (t < 12 || faced == null)
						{
							continue;
						}
						Map<Integer, int[]> now = spots(plan);
						double yours = Math.hypot(now.get(99)[0] - faced[0], now.get(99)[1] - faced[1]);
						for (Map.Entry<Integer, int[]> o : now.entrySet())
						{
							double theirs = Math.hypot(o.getValue()[0] - faced[0], o.getValue()[1] - faced[1]);
							Assert.assertTrue("fire " + at[0] + "," + at[1] + ", camera " + camera[0] + "," + camera[1] + ", " + others
								+ " with you, tick " + t + ": you're " + (int) yours + " from the fire, player " + o.getKey() + " " + (int) theirs,
								yours <= theirs + CrowdPlanner.NEARER_THE_FIRE);
						}
						checked++;
					}
				}
			}
		}
		Assert.assertTrue("never laid out round the fire", checked > 0);
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

	/**
	 * A Forester's Campfire on {@link #TILE}, which can be stood on, while {@code lit[0]}: nobody is
	 * drawn within the plugin's fire clearance of its tile, as in the game.
	 */
	private static CrowdPlanner.Surroundings campfireUnderfoot(boolean[] lit)
	{
		return new CrowdPlanner.Surroundings()
		{
			private boolean fireOn(int sceneX, int sceneY)
			{
				return lit[0] && sceneX == 50 && sceneY == 50;
			}

			public boolean canStand(long t, int dx, int dz)
			{
				int x = StackRegistry.sceneX(t) * 128 + 64 + dx;
				int z = StackRegistry.sceneY(t) * 128 + 64 + dz;
				for (int cx : new int[]{x - PersonalSpacePlugin.FIRE_CLEARANCE, x + PersonalSpacePlugin.FIRE_CLEARANCE})
				{
					for (int cz : new int[]{z - PersonalSpacePlugin.FIRE_CLEARANCE, z + PersonalSpacePlugin.FIRE_CLEARANCE})
					{
						if (fireOn(cx >> 7, cz >> 7))
						{
							return false;
						}
					}
				}
				return true;
			}
			public boolean facesObstacle(long t, double a) { return false; }
			public boolean isCounter(long t, double a) { return false; }
			public boolean facesFire(long t, double a)
			{
				return fireOn(StackRegistry.sceneX(t) + (int) Math.round(-Math.sin(a)), StackRegistry.sceneY(t) + (int) Math.round(-Math.cos(a)));
			}
			public List<int[]> firesNear(long t)
			{
				List<int[]> out = new ArrayList<>();
				for (int east = -1; east <= 1; east++)
				{
					for (int north = -1; north <= 1; north++)
					{
						if (fireOn(StackRegistry.sceneX(t) + east, StackRegistry.sceneY(t) + north))
						{
							out.add(new int[]{east, north});
						}
					}
				}
				return out;
			}
		};
	}

	@Test
	public void peopleStandingOnACampfireStandRoundItAndStayPut()
	{
		// Three people tending a Forester's Campfire stand on it, with people tending it on every tile
		// round them, coming and going. Their tile closed up to make room for the neighbours until
		// none of its spots cleared the fire: they all waited out of sight in the fire, and glided out
		// and back every time someone next door came or went.
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(new boolean[]{true});
		java.util.Random rnd = new java.util.Random(3);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Long, List<Integer>> there = new java.util.LinkedHashMap<>();
		int nextId = 100;
		for (int east = -1; east <= 1; east++)
		{
			for (int north = -1; north <= 1; north++)
			{
				if (east != 0 || north != 0)
				{
					List<Integer> ids = new ArrayList<>();
					ids.add(nextId++);
					ids.add(nextId++);
					there.put(StackRegistry.key(0, 50 + east, 50 + north), ids);
				}
			}
		}
		Map<Integer, int[]> last = new HashMap<>();
		int moves = 0;
		for (int t = 1; t <= 300; t++)
		{
			for (List<Integer> ids : there.values())
			{
				if (t > 20 && rnd.nextInt(100) < 5)
				{
					if (ids.size() > 1 && (ids.size() >= 3 || rnd.nextBoolean()))
					{
						ids.remove(rnd.nextInt(ids.size()));
					}
					else
					{
						ids.add(nextId++);
					}
				}
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (int id = 1; id <= 3; id++)
			{
				still.add(new StackSpreader.Entry(id, TILE, false, id * 512, true));
			}
			for (Map.Entry<Long, List<Integer>> e : there.entrySet())
			{
				int facing = OffsetTable.facing(50 - StackRegistry.sceneX(e.getKey()), 50 - StackRegistry.sceneY(e.getKey()));
				for (int id : e.getValue())
				{
					still.add(new StackSpreader.Entry(id, e.getKey(), false, facing, true));
				}
			}
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.DEFAULT_STACK,
				true, false, t, campfire);
			if (t < 4)
			{
				continue;
			}
			Assert.assertArrayEquals("tick " + t + ": they face the fire", new int[]{0, 0}, plan.fires.get(TILE));
			Map<Integer, int[]> now = spots(plan);
			Map<Integer, double[]> at = drawnAt(plan, still);
			for (int id = 1; id <= 3; id++)
			{
				int[] spot = now.get(id);
				Assert.assertNotNull("tick " + t + ": player " + id + " is left waiting in the fire", spot);
				Assert.assertTrue("tick " + t + ": player " + id + " is drawn in the fire", campfire.canStand(TILE, spot[0], spot[1]));
				for (Map.Entry<Integer, double[]> other : at.entrySet())
				{
					double d = Math.hypot(other.getValue()[0] - at.get(id)[0], other.getValue()[1] - at.get(id)[1]);
					Assert.assertTrue("tick " + t + ": player " + id + " is drawn " + d + " from player " + other.getKey(),
						other.getKey() == id || d >= CrowdPlanner.YOUR_SPACE);
				}
				int[] was = last.get(id);
				moves += was != null && (was[0] != spot[0] || was[1] != spot[1]) ? 1 : 0;
			}
			last = now;
		}
		Assert.assertTrue("the people on the fire moved " + moves + " times", moves <= 3);
	}

	@Test
	public void nobodyIsLeftStandingInACampfireWhileThereIsRoomBesideIt()
	{
		// A busy campfire's crowd comes and goes. Everyone kept their spot round it while it stayed
		// clear, and the gaps left between them could be too small for anyone new, who waited in the
		// middle, drawn standing in the flames, though turning the plugin off and on found room for
		// them all. Nobody waits where there's room, and whoever does wait isn't drawn in the fire.
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(new boolean[]{true});
		java.util.Random rnd = new java.util.Random(11);
		CrowdPlanner planner = new CrowdPlanner();
		List<Integer> onIt = new ArrayList<>();
		int nextId = 1;
		int waited = 0;
		for (int t = 1; t <= 600; t++)
		{
			if (onIt.size() < 3 || onIt.size() < 14 && rnd.nextInt(100) < 30)
			{
				onIt.add(nextId++);
			}
			if (onIt.size() > 3 && rnd.nextInt(100) < 25)
			{
				onIt.remove(rnd.nextInt(onIt.size()));
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (int id : onIt)
			{
				still.add(new StackSpreader.Entry(id, TILE, false, (id * 300) % 2048, true));
			}
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.DEFAULT_STACK,
				true, false, t, campfire);
			CrowdPlanner.Plan afresh = new CrowdPlanner().plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE,
				PersonalSpaceConfig.DEFAULT_STACK, true, false, t, campfire);
			int placed = spots(plan).size();
			int placedAfresh = spots(afresh).size();
			Assert.assertTrue("tick " + t + ": " + placed + " of " + onIt.size() + " given a spot, " + placedAfresh + " laid out afresh",
				placed >= placedAfresh);
			if (placed < onIt.size())
			{
				waited++;
				Assert.assertTrue("tick " + t + ": someone waiting is drawn standing in the fire", plan.middleOutOfSight.contains(TILE));
			}
		}
		Assert.assertTrue("the crowd never outgrew the spots round the fire: " + waited, waited > 0);
	}

	@Test
	public void nobodyBesideACampfireIsDrawnOnTopOfSomeoneStandingNextToIt()
	{
		// Drawn at the middle of a side of the fire, 43 from someone standing in the middle of the
		// tile beyond it, the two took turns showing through each other.
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(new boolean[]{true});
		for (boolean everySideTaken : new boolean[]{false, true})
		{
			CrowdPlanner planner = new CrowdPlanner();
			List<StackSpreader.Entry> still = new ArrayList<>();
			still.add(new StackSpreader.Entry(1, TILE, false, 1536, true));
			int[] before = null;
			for (int t = 1; t <= 30; t++)
			{
				if (t == 10)
				{
					// Someone comes to stand next to the fire, by the side they're drawn at.
					Assert.assertNotNull("alone on the fire, they stand beside it", before);
					int nx = before[0] > 0 ? 51 : before[0] < 0 ? 49 : 50;
					int nz = before[1] > 0 ? 51 : before[1] < 0 ? 49 : 50;
					still.add(new StackSpreader.Entry(2, StackRegistry.key(0, nx, nz), false, 0, false));
					if (everySideTaken)
					{
						int id = 3;
						for (int[] side : new int[][]{{51, 50}, {49, 50}, {50, 51}, {50, 49}})
						{
							if (side[0] != nx || side[1] != nz)
							{
								still.add(new StackSpreader.Entry(id++, StackRegistry.key(0, side[0], side[1]), false, 0, false));
							}
						}
					}
				}
				CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE,
					PersonalSpaceConfig.DEFAULT_STACK, true, false, t, campfire);
				int[] spot = spots(plan).get(1);
				if (spot != null)
				{
					for (StackSpreader.Entry other : still)
					{
						if (other.id == 1)
						{
							continue;
						}
						double gap = Math.hypot(50 * 128 + spot[0] - StackRegistry.sceneX(other.tile) * 128,
							50 * 128 + spot[1] - StackRegistry.sceneY(other.tile) * 128);
						Assert.assertTrue("tick " + t + ": drawn " + Math.round(gap) + " from someone standing by the fire",
							gap >= 48);
					}
				}
				if (t >= 12 && !everySideTaken)
				{
					Assert.assertNotNull("tick " + t + ": with room on the other sides, they stand there", spot);
				}
				before = spot;
			}
		}
	}

	@Test
	public void someoneAloneOnACampfireStandsBesideItNotInIt()
	{
		// Alone on their tile they were left to the game, which draws them in the flames.
		boolean[] lit = {true};
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(lit);
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> alone = new ArrayList<>();
		alone.add(new StackSpreader.Entry(1, TILE, false, NORTH, true));
		int[] spot = null;
		for (int t = 1; t <= 30; t++)
		{
			if (t == 21)
			{
				lit[0] = false;
			}
			CrowdPlanner.Plan plan = planner.plan(alone, q -> true, PersonalSpaceConfig.SPACING_WIDE,
				PersonalSpaceConfig.DEFAULT_STACK, true, false, t, campfire);
			Map<Integer, int[]> now = spots(plan);
			if (t <= 20)
			{
				Assert.assertNotNull("tick " + t + ": they're drawn in the fire", now.get(1));
				Assert.assertTrue("tick " + t + ": their spot is in the fire", campfire.canStand(TILE, now.get(1)[0], now.get(1)[1]));
				Assert.assertArrayEquals("tick " + t + ": they face the fire", new int[]{0, 0}, plan.fires.get(TILE));
				if (spot != null)
				{
					Assert.assertArrayEquals("tick " + t + ": they moved", spot, now.get(1));
				}
				spot = now.get(1);
			}
			else
			{
				Assert.assertTrue("tick " + t + ": with the fire out, they're left where they stand", plan.placements.isEmpty());
			}
		}
	}

	@Test
	public void yourOwnCharacterAloneOnACampfireIsLeftAloneUnlessMoveMeIsOn()
	{
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(new boolean[]{true});
		List<StackSpreader.Entry> you = new ArrayList<>();
		you.add(new StackSpreader.Entry(1, TILE, true, NORTH, true));
		CrowdPlanner off = new CrowdPlanner();
		CrowdPlanner on = new CrowdPlanner();
		CrowdPlanner.Plan notMoved = null;
		CrowdPlanner.Plan moved = null;
		for (int t = 1; t <= 5; t++)
		{
			notMoved = off.plan(you, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.DEFAULT_STACK, true, false, t, campfire);
			moved = on.plan(you, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.DEFAULT_STACK, true, true, t, campfire);
		}
		Assert.assertTrue(notMoved.placements.isEmpty());
		Assert.assertNotNull(spots(moved).get(1));
	}

	@Test
	public void theLastOneLeftOnACampfireStaysBesideItUntilItGoesOut()
	{
		// Walking back to the middle of their tile, the last one tending the fire glided into it, and
		// out again as soon as anyone else came.
		boolean[] lit = {true};
		CrowdPlanner.Surroundings campfire = campfireUnderfoot(lit);
		CrowdPlanner planner = new CrowdPlanner();
		List<StackSpreader.Entry> both = new ArrayList<>();
		both.add(new StackSpreader.Entry(1, TILE, false, NORTH, true));
		both.add(new StackSpreader.Entry(2, TILE, false, SOUTH, true));
		List<StackSpreader.Entry> one = both.subList(0, 1);
		int[] spot = null;
		for (int t = 1; t <= 50; t++)
		{
			if (t == 41)
			{
				lit[0] = false;
			}
			CrowdPlanner.Plan plan = planner.plan(t <= 5 ? both : one, q -> true, PersonalSpaceConfig.SPACING_WIDE,
				PersonalSpaceConfig.DEFAULT_STACK, true, false, t, campfire);
			Map<Integer, int[]> now = spots(plan);
			if (t <= 5)
			{
				spot = now.get(1);
				Assert.assertNotNull("tick " + t + ": player 1 is left waiting in the fire", spot);
				Assert.assertTrue("tick " + t + ": player 1 is drawn in the fire", campfire.canStand(TILE, spot[0], spot[1]));
			}
			else if (t <= 40)
			{
				Assert.assertArrayEquals("tick " + t + ": the last one left moved", spot, now.get(1));
				Assert.assertArrayEquals("tick " + t + ": they face the fire", new int[]{0, 0}, plan.fires.get(TILE));
			}
			else
			{
				Assert.assertTrue("tick " + t + ": with the fire out, they're alone on their tile", plan.placements.isEmpty());
				Assert.assertNull("tick " + t + ": there is no fire to face", plan.fires.get(TILE));
			}
		}
	}

	private static final long AT_NORTH = StackRegistry.key(0, 50, 52);
	private static final long AT_WEST = StackRegistry.key(0, 49, 51);
	private static final long AT_EAST = StackRegistry.key(0, 51, 51);
	private static final long AT_SOUTH = StackRegistry.key(0, 50, 50);
	private static final int YOU = 999;

	/**
	 * A smithy like Varrock's, with the game's own collision rules: a walled room from (45, 45) to
	 * (54, 52), an anvil at (50, 51) a tile out from the north wall, and crates at (53, 51) and
	 * (53, 52). Open floor to the south and east.
	 */
	private static int[][][] smithyFlags()
	{
		int[][][] flags = new int[4][][];
		flags[0] = new int[104][104];
		for (int x = 0; x < 104; x++)
		{
			for (int y = 0; y < 104; y++)
			{
				if (x < 45 || x > 54 || y < 45 || y > 52)
				{
					flags[0][x][y] = CollisionTerrain.BLOCK_FULL;
				}
			}
		}
		flags[0][50][51] = CollisionTerrain.BLOCK_FULL;
		flags[0][53][51] = CollisionTerrain.BLOCK_FULL;
		flags[0][53][52] = CollisionTerrain.BLOCK_FULL;
		return flags;
	}

	/** {@link #smithyFlags} as the plugin sees it. */
	private static CrowdPlanner.Surroundings smithy()
	{
		CollisionTerrain terrain = new CollisionTerrain(smithyFlags());
		return new CrowdPlanner.Surroundings()
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
	}

	private static int facingTheAnvil(long tile)
	{
		return OffsetTable.facing(50 - StackRegistry.sceneX(tile), 51 - StackRegistry.sceneY(tile));
	}

	/** Smithing at the anvil: this many on its north, west, east and south sides (ids from 1 up), and you on the east side. */
	private static List<StackSpreader.Entry> smithing(int north, int west, int east, int south, boolean you)
	{
		List<StackSpreader.Entry> out = new ArrayList<>();
		long[] sides = {AT_NORTH, AT_WEST, AT_EAST, AT_SOUTH};
		int[] counts = {north, west, east, south};
		int id = 1;
		for (int k = 0; k < 4; k++)
		{
			for (int i = 0; i < counts[k]; i++)
			{
				out.add(new StackSpreader.Entry(id++, sides[k], false, facingTheAnvil(sides[k]), true));
			}
		}
		if (you)
		{
			out.add(new StackSpreader.Entry(YOU, AT_EAST, true, facingTheAnvil(AT_EAST), true));
		}
		return out;
	}

	private static CrowdPlanner.Plan atTheAnvil(CrowdPlanner planner, List<StackSpreader.Entry> still, int tick)
	{
		return planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, tick, smithy());
	}

	/** How far from the middle of the anvil someone drawn at {@code at} (as {@link #drawnAt(CrowdPlanner.Plan, List)} gives it) is. */
	private static double fromTheAnvil(double[] at)
	{
		return Math.hypot(at[0] - 50 * 128, at[1] - 51 * 128);
	}

	/**
	 * Checks what everyone at the anvil should always be able to count on: nobody drawn inside anyone
	 * else, nor near you, nor walking through the anvil, a crate or a wall to their spot, nor out on
	 * their own across the room; everyone turned to face the anvil.
	 */
	private static void assertStandingRoundTheAnvil(String when, CrowdPlanner.Plan plan, List<StackSpreader.Entry> still)
	{
		Map<Integer, double[]> at = drawnAt(plan, still);
		// Nobody waiting in the middle of a tile kept out of sight is drawn there (you always are).
		Set<Integer> placed = spots(plan).keySet();
		for (StackSpreader.Entry e : still)
		{
			if (!placed.contains(e.id) && !e.local && plan.middleOutOfSight.contains(e.tile))
			{
				at.remove(e.id);
			}
		}
		CollisionTerrain terrain = new CollisionTerrain(smithyFlags());
		Map<Integer, StackSpreader.Entry> who = new HashMap<>();
		for (StackSpreader.Entry e : still)
		{
			who.put(e.id, e);
		}
		for (StackSpreader.Placement p : plan.placements)
		{
			double[] a = at.get(p.id);
			Assert.assertTrue(when + ": player " + p.id + " is drawn " + Math.round(fromTheAnvil(a)) + " from the anvil",
				fromTheAnvil(a) <= CrowdPlanner.OBJECT_REACH + 1);
			Assert.assertTrue(when + ": player " + p.id + " doesn't face the anvil", plan.curvedRows.contains(p.tile));
			// Their walk there, straight from the middle of their tile, as it's drawn: every step of it
			// somewhere a body could stand.
			int fromX = StackRegistry.sceneX(p.tile) * 128 + 64;
			int fromZ = StackRegistry.sceneY(p.tile) * 128 + 64;
			for (int k = 0; k <= 64; k++)
			{
				int x = fromX + (int) Math.round(p.dx * k / 64.0);
				int z = fromZ + (int) Math.round(p.dz * k / 64.0);
				int tileX = Math.floorDiv(x, 128) * 128 + 64;
				int tileZ = Math.floorDiv(z, 128) * 128 + 64;
				Assert.assertTrue(when + ": player " + p.id + " walks through something to (" + p.dx + ", " + p.dz + ")",
					terrain.canStand(0, tileX, tileZ, x, z));
			}
			for (Map.Entry<Integer, double[]> other : at.entrySet())
			{
				if (other.getKey() == p.id)
				{
					continue;
				}
				double d = Math.hypot(other.getValue()[0] - a[0], other.getValue()[1] - a[1]);
				int least = other.getKey() == YOU || p.id == YOU ? CrowdPlanner.OBJECT_YOUR_GAP : CrowdPlanner.OBJECT_KEEP;
				Assert.assertTrue(when + ": players " + p.id + " and " + other.getKey() + " are drawn " + Math.round(d) + " apart",
					d >= least - 0.5);
			}
		}
	}

	@Test
	public void aBusyAnvilIsStoodRoundOnEverySideWithRoomBeforeAnyoneStandsFarOut()
	{
		// Forty smithing at one anvil, most on its wall side and its west side: they piled up there,
		// two rows deep and squeezed into a heap, while the open floor to the south and east stood
		// empty and the overflow was drawn out on its own a tile or two away.
		List<StackSpreader.Entry> still = smithing(15, 12, 2, 0, true);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheAnvil(planner, still, t);
		}
		assertStandingRoundTheAnvil("a busy anvil", plan, still);
		Map<Integer, double[]> at = drawnAt(plan, still);
		int east = 0;
		int south = 0;
		for (StackSpreader.Placement p : plan.placements)
		{
			double[] a = at.get(p.id);
			east += a[0] > 50 * 128 + 64 ? 1 : 0;
			south += a[1] < 51 * 128 - 64 ? 1 : 0;
		}
		Assert.assertTrue("only " + east + " stand on the open east side", east >= 8);
		// (With people right beside you on the east side, one fewer spills round to the south.)
		Assert.assertTrue("only " + south + " stand on the open south side", south >= 3);
		Assert.assertEquals("everyone is given a spot", still.size(), plan.placements.size());
		Assert.assertEquals(CrowdPlanner.ROUND_OBJECT, plan.tiles.get(AT_WEST).shape);
	}

	@Test
	public void aFewAtAnAnvilAllStandRightAtItWhileThereIsRoom()
	{
		// Four on the wall side and four on the west side: with room at the anvil itself round its
		// corner, a second row was started behind each side instead, nearly two tiles out.
		List<StackSpreader.Entry> still = smithing(4, 4, 0, 0, false);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheAnvil(planner, still, t);
		}
		assertStandingRoundTheAnvil("a few at the anvil", plan, still);
		Map<Integer, double[]> at = drawnAt(plan, still);
		Assert.assertEquals(still.size(), plan.placements.size());
		// Right up against it, but for a couple a step behind: each side's stretch at the anvil holds three.
		int atIt = 0;
		for (StackSpreader.Placement p : plan.placements)
		{
			double d = fromTheAnvil(at.get(p.id));
			Assert.assertTrue("player " + p.id + " stands back from the anvil: " + (int) d,
				d <= CrowdPlanner.OBJECT_NEAR_RING + CrowdPlanner.OBJECT_RING_STEP + 1.5);
			atIt += Math.abs(d - CrowdPlanner.OBJECT_NEAR_RING) <= 1.5 ? 1 : 0;
		}
		Assert.assertTrue("only " + atIt + " of " + still.size() + " right at the anvil", atIt >= still.size() - 2);
	}

	@Test
	public void peopleRoundABusyAnvilKeepTheirSpotsAsOthersComeAndGo()
	{
		// Smithers come and go all the time. Every time a side gained or lost someone, the whole of it
		// was laid out afresh (a closer spacing, a row turned into a ring, a share of the anvil given
		// up to the side next door) and everyone on it walked somewhere new.
		java.util.Random rnd = new java.util.Random(5);
		CrowdPlanner planner = new CrowdPlanner();
		long[] sides = {AT_NORTH, AT_WEST, AT_EAST, AT_SOUTH};
		int[] least = {8, 6, 1, 0};
		int[] most = {16, 14, 4, 3};
		Map<Long, List<Integer>> there = new HashMap<>();
		int nextId = 1;
		for (int k = 0; k < 4; k++)
		{
			List<Integer> ids = new ArrayList<>();
			for (int i = 0; i < (least[k] + most[k]) / 2; i++)
			{
				ids.add(nextId++);
			}
			there.put(sides[k], ids);
		}
		Map<Integer, int[]> last = new HashMap<>();
		Map<Integer, Long> lastTile = new HashMap<>();
		int moves = 0;
		int stays = 0;
		for (int t = 1; t <= 300; t++)
		{
			for (int k = 0; k < 4; k++)
			{
				List<Integer> ids = there.get(sides[k]);
				if (t > 10 && rnd.nextInt(100) < 8)
				{
					if (ids.size() > least[k] && (ids.size() >= most[k] || rnd.nextBoolean()))
					{
						ids.remove(rnd.nextInt(ids.size()));
					}
					else if (ids.size() < most[k])
					{
						ids.add(nextId++);
					}
				}
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (long side : sides)
			{
				for (int id : there.get(side))
				{
					still.add(new StackSpreader.Entry(id, side, false, facingTheAnvil(side), true));
				}
			}
			still.add(new StackSpreader.Entry(YOU, AT_EAST, true, facingTheAnvil(AT_EAST), true));
			CrowdPlanner.Plan plan = atTheAnvil(planner, still, t);
			assertStandingRoundTheAnvil("tick " + t, plan, still);
			Map<Integer, int[]> now = spots(plan);
			Map<Integer, Long> nowTile = new HashMap<>();
			for (StackSpreader.Placement p : plan.placements)
			{
				nowTile.put(p.id, p.tile);
			}
			for (Map.Entry<Integer, int[]> e : now.entrySet())
			{
				int[] was = last.get(e.getKey());
				if (t > 10 && was != null && nowTile.get(e.getKey()).equals(lastTile.get(e.getKey())))
				{
					stays++;
					moves += was[0] != e.getValue()[0] || was[1] != e.getValue()[1] ? 1 : 0;
				}
			}
			last = now;
			lastTile = nowTile;
		}
		Assert.assertTrue("people who stayed at the anvil moved " + moves + " times in " + stays, moves <= 3);
	}

	@Test
	public void youStandAtTheAnvilOnYourSideWithRoomRoundYou()
	{
		// Packed in round the anvil you could lose yourself: you take the front place on your side,
		// and nobody stands right beside you.
		List<StackSpreader.Entry> still = smithing(12, 10, 4, 3, true);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheAnvil(planner, still, t);
		}
		assertStandingRoundTheAnvil("with you on the east side", plan, still);
		int[] yours = spots(plan).get(YOU);
		Assert.assertNotNull("you have a spot", yours);
		// The anvil is west of you: you step up to it, off your tile's middle.
		Assert.assertArrayEquals("you're at the front of your side of the anvil", new int[]{CrowdPlanner.OBJECT_NEAR_RING - 128, 0}, yours);
		// ...and people work it right beside you: kept a wide berth, the places either side stood empty.
		Map<Integer, double[]> at = drawnAt(plan, still);
		double nearest = Double.MAX_VALUE;
		for (Map.Entry<Integer, double[]> o : at.entrySet())
		{
			if (o.getKey() != YOU)
			{
				nearest = Math.min(nearest, Math.hypot(o.getValue()[0] - at.get(YOU)[0], o.getValue()[1] - at.get(YOU)[1]));
			}
		}
		Assert.assertTrue("nobody right beside you: the nearest is " + (int) nearest + " away", nearest < 72);
		// With "Move me" off you stay where you are, and the crowd keeps its distance all the same.
		CrowdPlanner stayPut = new CrowdPlanner();
		for (int t = 1; t <= 5; t++)
		{
			plan = stayPut.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, false, t, smithy());
		}
		Assert.assertNull("you were moved", spots(plan).get(YOU));
		assertStandingRoundTheAnvil("with you staying put", plan, still);
	}

	@Test
	public void someoneLeftAloneBesideABusyAnvilKeepsTheirSpot()
	{
		// When the side of the anvil they're on is down to them alone, they kept their place rather
		// than walking back to the middle of their tile, inside whoever had stepped round there.
		List<StackSpreader.Entry> pair = smithing(10, 8, 0, 2, false);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 5; t++)
		{
			before = spots(atTheAnvil(planner, pair, t));
		}
		int last = pair.get(pair.size() - 1).id;
		List<StackSpreader.Entry> alone = new ArrayList<>(pair);
		alone.remove(pair.size() - 2);
		for (int t = 6; t <= 20; t++)
		{
			CrowdPlanner.Plan plan = atTheAnvil(planner, alone, t);
			assertStandingRoundTheAnvil("tick " + t, plan, alone);
			Assert.assertArrayEquals("tick " + t + ": the one left on the south side moved", before.get(last), spots(plan).get(last));
		}
		// With nobody else at the anvil, there's no crowd: they're left to stand where they are.
		List<StackSpreader.Entry> nobodyElse = new ArrayList<>();
		nobodyElse.add(pair.get(pair.size() - 1));
		CrowdPlanner.Plan plan = atTheAnvil(planner, nobodyElse, 21);
		Assert.assertTrue(plan.placements.isEmpty());
	}

	@Test
	public void whenEveryPlaceRoundTheAnvilIsTakenTheRestWaitOutOfSight()
	{
		// Better out of sight than drawn inside each other, or out across the room.
		List<StackSpreader.Entry> still = smithing(16, 16, 16, 16, false);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheAnvil(planner, still, t);
		}
		assertStandingRoundTheAnvil("a packed anvil", plan, still);
		Assert.assertTrue("only " + plan.placements.size() + " of 64 given a spot", plan.placements.size() >= 30);
		Assert.assertTrue(plan.placements.size() < still.size());
		Set<Long> waiting = new HashSet<>();
		for (StackSpreader.Placement p : plan.unplaced)
		{
			waiting.add(p.tile);
		}
		for (long tile : waiting)
		{
			Assert.assertTrue("those waiting are drawn in the middle of their tile", plan.middleOutOfSight.contains(tile));
		}
	}

	@Test
	public void onlySmartStandsACrowdAllRoundTheAnvil()
	{
		List<StackSpreader.Entry> still = smithing(6, 5, 0, 0, false);
		for (PersonalSpaceConfig.Arrangement arrangement : PersonalSpaceConfig.Arrangement.values())
		{
			CrowdPlanner planner = new CrowdPlanner();
			planner.arrangement = arrangement;
			boolean smart = arrangement != PersonalSpaceConfig.Arrangement.CIRCLE;
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK,
				smart, false, 1, smithy());
			Assert.assertEquals(arrangement.toString(), arrangement == PersonalSpaceConfig.Arrangement.AUTO,
				CrowdPlanner.ROUND_OBJECT.equals(plan.tiles.get(AT_WEST).shape));
		}
	}

	@Test
	public void peopleFacingAWallAreNotStoodRoundIt()
	{
		// The wall is something nobody can stand on too, but it runs on: there's no standing round it.
		long byTheWall = StackRegistry.key(0, 47, 52);
		List<StackSpreader.Entry> still = new ArrayList<>();
		for (int id = 1; id <= 4; id++)
		{
			still.add(new StackSpreader.Entry(id, byTheWall, false, NORTH, true));
		}
		CrowdPlanner.Plan plan = atTheAnvil(new CrowdPlanner(), still, 1);
		Assert.assertNotEquals(CrowdPlanner.ROUND_OBJECT, plan.tiles.get(byTheWall).shape);
	}

	@Test
	public void youArrivingAtABusyAnvilSendsNobodyWalkingThroughIt()
	{
		// Whoever stood where you arrived made room by walking to the nearest free place, and with
		// the near side full that was across the anvil: they walked straight through it.
		List<StackSpreader.Entry> still = smithing(12, 10, 0, 0, false);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 5; t++)
		{
			before = spots(atTheAnvil(planner, still, t));
		}
		still.add(new StackSpreader.Entry(YOU, AT_EAST, true, facingTheAnvil(AT_EAST), true));
		CollisionTerrain terrain = new CollisionTerrain(smithyFlags());
		for (int t = 6; t <= 12; t++)
		{
			CrowdPlanner.Plan plan = atTheAnvil(planner, still, t);
			assertStandingRoundTheAnvil("tick " + t, plan, still);
			for (StackSpreader.Placement p : plan.placements)
			{
				int[] was = before.get(p.id);
				if (was == null || was[0] == p.dx && was[1] == p.dz)
				{
					continue;
				}
				int fromX = StackRegistry.sceneX(p.tile) * 128 + 64;
				int fromZ = StackRegistry.sceneY(p.tile) * 128 + 64;
				for (int k = 0; k <= 64; k++)
				{
					int x = fromX + was[0] + (int) Math.round((p.dx - was[0]) * k / 64.0);
					int z = fromZ + was[1] + (int) Math.round((p.dz - was[1]) * k / 64.0);
					Assert.assertTrue("tick " + t + ": player " + p.id + " walks through something from (" + was[0] + ", " + was[1] + ") to ("
						+ p.dx + ", " + p.dz + ")", terrain.canStand(0, Math.floorDiv(x, 128) * 128 + 64, Math.floorDiv(z, 128) * 128 + 64, x, z));
				}
			}
			before = spots(plan);
		}
	}

	/**
	 * Checks that nobody round the object at ({@code objectX}, {@code objectY}) is drawn more than a
	 * tile from their own tile, the hover arrow reaching back across the crowd, while a spot within a
	 * tile of it, no more than a ring further out than theirs, stands free: clear of everyone drawn,
	 * and of you, and reached by a straight walk from the middle of their tile.
	 */
	private static void assertNobodyDrawnRoundWhileTheirSideHasRoom(String when, CrowdPlanner.Plan plan, List<StackSpreader.Entry> still,
		int objectX, int objectY, CollisionTerrain terrain)
	{
		Map<Integer, double[]> at = drawnAt(plan, still);
		Set<Integer> placed = spots(plan).keySet();
		for (StackSpreader.Entry e : still)
		{
			if (!placed.contains(e.id) && !e.local && plan.middleOutOfSight.contains(e.tile))
			{
				at.remove(e.id);
			}
		}
		for (StackSpreader.Placement p : plan.placements)
		{
			if (p.id == YOU || Math.hypot(p.dx, p.dz) <= 128)
			{
				continue;
			}
			double[] a = at.get(p.id);
			long ring = Math.round((Math.hypot(a[0] - objectX * 128, a[1] - objectY * 128) - CrowdPlanner.OBJECT_NEAR_RING) / (double) CrowdPlanner.OBJECT_RING_STEP);
			double tileX = StackRegistry.sceneX(p.tile) * 128;
			double tileZ = StackRegistry.sceneY(p.tile) * 128;
			for (int[] s : CrowdPlanner.OBJECT_SPOTS)
			{
				double x = objectX * 128 + s[0];
				double z = objectY * 128 + s[1];
				if (s[2] > ring + 1 || Math.hypot(x - tileX, z - tileZ) > 128 || !walksClear(terrain, p.tile, x - tileX, z - tileZ))
				{
					continue;
				}
				boolean free = true;
				for (Map.Entry<Integer, double[]> other : at.entrySet())
				{
					int gap = other.getKey() == YOU ? CrowdPlanner.OBJECT_YOUR_GAP : CrowdPlanner.OBJECT_GAP;
					free &= other.getKey() == p.id || Math.hypot(other.getValue()[0] - x, other.getValue()[1] - z) >= gap;
				}
				Assert.assertFalse(when + ": player " + p.id + " is drawn " + Math.round(Math.hypot(p.dx, p.dz)) + " from their tile while ("
					+ s[0] + ", " + s[1] + ") from the anvil, " + Math.round(Math.hypot(x - tileX, z - tileZ)) + " from it, is free", free);
			}
		}
	}

	/** Whether someone on {@code tile} can walk straight from its middle to (dx, dz) from it, every step somewhere a body could stand. */
	private static boolean walksClear(CollisionTerrain terrain, long tile, double dx, double dz)
	{
		int fromX = StackRegistry.sceneX(tile) * 128 + 64;
		int fromZ = StackRegistry.sceneY(tile) * 128 + 64;
		for (int k = 0; k <= 64; k++)
		{
			int x = fromX + (int) Math.round(dx * k / 64.0);
			int z = fromZ + (int) Math.round(dz * k / 64.0);
			if (!terrain.canStand(0, Math.floorDiv(x, 128) * 128 + 64, Math.floorDiv(z, 128) * 128 + 64, x, z))
			{
				return false;
			}
		}
		return true;
	}

	@Test
	public void aSmithByTheCratesIsNotDrawnRoundTheAnvilWhileTheirSideHasRoom()
	{
		// A smith on the corner tile by the crates was drawn right round the anvil, nearly two tiles
		// from their own tile, the hover arrow reaching back across the crowd: the nearest ring with
		// room came first wherever round the anvil it was, though there was room a ring further out
		// on their own side.
		List<StackSpreader.Entry> still = smithing(8, 6, 2, 2, false);
		long byTheCrates = StackRegistry.key(0, 51, 52);
		for (int id = 19; id <= 21; id++)
		{
			still.add(new StackSpreader.Entry(id, byTheCrates, false, facingTheAnvil(byTheCrates), true));
		}
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheAnvil(planner, still, t);
		}
		assertStandingRoundTheAnvil("smiths by the crates", plan, still);
		Assert.assertEquals("everyone is given a spot", still.size(), plan.placements.size());
		assertNobodyDrawnRoundWhileTheirSideHasRoom("smiths by the crates", plan, still, 50, 51, new CollisionTerrain(smithyFlags()));
	}

	@Test
	public void aBusySideOfAnAnvilStandsFurtherOutOnItsOwnSideBeforeGoingRoundIt()
	{
		// Twelve on one side of an anvil in the open, two on each side of them: once their front was
		// full, the last of them were sent round to the gaps left nearest the anvil on the sides next
		// to them, a tile and a half from their own tile, while there was room behind their own side.
		CrowdPlanner.Surroundings anvil = openGroundWith(new int[]{50, 50});
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		busyAt(still, id, 50, 51, 12, 50, 50);
		busyAt(still, id, 49, 50, 2, 50, 50);
		busyAt(still, id, 51, 50, 2, 50, 50);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, anvil);
		}
		Assert.assertEquals("everyone is given a spot", still.size(), plan.placements.size());
		int[][][] flags = new int[4][][];
		flags[0] = new int[104][104];
		flags[0][50][50] = CollisionTerrain.BLOCK_FULL;
		assertNobodyDrawnRoundWhileTheirSideHasRoom("a busy side", plan, still, 50, 50, new CollisionTerrain(flags));
	}

	@Test
	public void roomOpeningOnTheirSideWalksNobodyRoundTheAnvilBack()
	{
		// Whoever stands round the anvil keeps their place while it stays clear, however far round
		// it is: walking them back each time someone on their side left would set the crowd
		// sliding. The room goes to whoever comes next.
		CrowdPlanner.Surroundings anvil = openGroundWith(new int[]{50, 50});
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		busyAt(still, id, 50, 51, 12, 50, 50);
		busyAt(still, id, 49, 50, 2, 50, 50);
		busyAt(still, id, 51, 50, 2, 50, 50);
		long busy = StackRegistry.key(0, 50, 51);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, anvil);
		}
		Map<Integer, int[]> before = spots(plan);
		int front = -1;
		double farthest = 0;
		for (StackSpreader.Placement p : plan.placements)
		{
			if (p.tile != busy)
			{
				continue;
			}
			if (front < 0 || Math.hypot(p.dx, p.dz) < Math.hypot(before.get(front)[0], before.get(front)[1]))
			{
				front = p.id;
			}
			farthest = Math.max(farthest, Math.hypot(p.dx, p.dz));
		}
		Assert.assertTrue("someone on the busy side stands round the anvil, " + Math.round(farthest) + " from their tile", farthest > 128);
		// The one at the front of the busy side leaves.
		int gone = front;
		still.removeIf(e -> e.id == gone);
		for (int t = 6; t <= 15; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, anvil);
			Map<Integer, int[]> now = spots(plan);
			Assert.assertEquals("tick " + t + ": everyone left keeps a spot", still.size(), now.size());
			for (Map.Entry<Integer, int[]> e : now.entrySet())
			{
				Assert.assertArrayEquals("tick " + t + ": player " + e.getKey() + " moved", before.get(e.getKey()), e.getValue());
			}
		}
		// Someone new on the busy side takes the place at the front, and still nobody else moves.
		still.add(new StackSpreader.Entry(100, busy, false, OffsetTable.facing(0, -1), true));
		for (int t = 16; t <= 20; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, anvil);
			for (Map.Entry<Integer, int[]> e : spots(plan).entrySet())
			{
				if (e.getKey() != 100)
				{
					Assert.assertArrayEquals("tick " + t + ": player " + e.getKey() + " moved", before.get(e.getKey()), e.getValue());
				}
			}
		}
		Assert.assertArrayEquals("the newcomer takes the place at the front", before.get(gone), spots(plan).get(100));
	}

	/** Open ground with these tiles blocked, as the plugin sees it. */
	private static CrowdPlanner.Surroundings openGroundWith(int[]... blocked)
	{
		int[][][] flags = new int[4][][];
		flags[0] = new int[104][104];
		for (int[] b : blocked)
		{
			flags[0][b[0]][b[1]] = CollisionTerrain.BLOCK_FULL;
		}
		CollisionTerrain terrain = new CollisionTerrain(flags);
		return new CrowdPlanner.Surroundings()
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
	}

	/** This many on tile (x, y), busy at the middle of tile (ox, oy) next to it, ids from {@code id[0]} up. */
	private static void busyAt(List<StackSpreader.Entry> out, int[] id, int x, int y, int count, int ox, int oy)
	{
		for (int i = 0; i < count; i++)
		{
			out.add(new StackSpreader.Entry(id[0]++, StackRegistry.key(0, x, y), false, OffsetTable.facing(ox - x, oy - y), true));
		}
	}

	@Test
	public void aCrowdRoundAFurnaceKeepsItsDistanceFromYou()
	{
		// A furnace covers four tiles, and the people facing each of them are a crowd of their own.
		// The crowds laid out before yours didn't know where you'd be, and stood right up against you.
		CrowdPlanner.Surroundings furnace = openGroundWith(new int[]{50, 50}, new int[]{51, 50}, new int[]{50, 51}, new int[]{51, 51});
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		busyAt(still, id, 50, 52, 6, 50, 51);
		busyAt(still, id, 51, 52, 6, 51, 51);
		busyAt(still, id, 52, 51, 6, 51, 51);
		busyAt(still, id, 50, 49, 6, 50, 50);
		busyAt(still, id, 49, 50, 6, 50, 50);
		long yours = StackRegistry.key(0, 49, 51);
		still.add(new StackSpreader.Entry(YOU, yours, true, OffsetTable.facing(1, 0), true));
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, furnace);
		}
		Map<Integer, double[]> at = drawnAt(plan, still);
		double[] you = at.get(YOU);
		for (Map.Entry<Integer, double[]> e : at.entrySet())
		{
			double d = Math.hypot(e.getValue()[0] - you[0], e.getValue()[1] - you[1]);
			Assert.assertTrue("player " + e.getKey() + " is drawn " + Math.round(d) + " from you",
				e.getKey() == YOU || d >= CrowdPlanner.OBJECT_YOUR_GAP - 0.5);
		}
	}

	@Test
	public void peopleAtTwoAnvilsSideBySideKeepTheirSpotsAsOthersComeAndGo()
	{
		// Two anvils side by side, as in Varrock's west smithy. Someone new at one of them took the
		// place of someone standing there from the other, who then walked somewhere else.
		CrowdPlanner.Surroundings twin = openGroundWith(new int[]{50, 50}, new int[]{51, 50});
		java.util.Random rnd = new java.util.Random(5);
		int[][] sides = {{50, 51, 50, 50}, {51, 51, 51, 50}, {50, 49, 50, 50}, {51, 49, 51, 50}, {49, 50, 50, 50}, {52, 50, 51, 50}};
		int[] least = {5, 5, 3, 3, 4, 4};
		int[] most = {12, 12, 7, 7, 9, 9};
		Map<Integer, List<Integer>> there = new HashMap<>();
		int nextId = 1;
		for (int k = 0; k < sides.length; k++)
		{
			List<Integer> ids = new ArrayList<>();
			for (int i = 0; i < (least[k] + most[k]) / 2; i++)
			{
				ids.add(nextId++);
			}
			there.put(k, ids);
		}
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> last = new HashMap<>();
		Map<Integer, Long> lastTile = new HashMap<>();
		int moves = 0;
		int stays = 0;
		for (int t = 1; t <= 300; t++)
		{
			for (int k = 0; k < sides.length; k++)
			{
				List<Integer> ids = there.get(k);
				if (t > 10 && rnd.nextInt(100) < 8)
				{
					if (ids.size() > least[k] && (ids.size() >= most[k] || rnd.nextBoolean()))
					{
						ids.remove(rnd.nextInt(ids.size()));
					}
					else if (ids.size() < most[k])
					{
						ids.add(nextId++);
					}
				}
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (int k = 0; k < sides.length; k++)
			{
				for (int i : there.get(k))
				{
					still.add(new StackSpreader.Entry(i, StackRegistry.key(0, sides[k][0], sides[k][1]), false,
						OffsetTable.facing(sides[k][2] - sides[k][0], sides[k][3] - sides[k][1]), true));
				}
			}
			still.add(new StackSpreader.Entry(YOU, StackRegistry.key(0, 52, 50), true, OffsetTable.facing(-1, 0), true));
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, t, twin);
			Map<Integer, int[]> now = spots(plan);
			Map<Integer, Long> nowTile = new HashMap<>();
			for (StackSpreader.Placement p : plan.placements)
			{
				nowTile.put(p.id, p.tile);
			}
			for (Map.Entry<Integer, int[]> e : now.entrySet())
			{
				int[] was = last.get(e.getKey());
				if (t > 10 && was != null && nowTile.get(e.getKey()).equals(lastTile.get(e.getKey())))
				{
					stays++;
					moves += was[0] != e.getValue()[0] || was[1] != e.getValue()[1] ? 1 : 0;
				}
			}
			last = now;
			lastTile = nowTile;
		}
		Assert.assertTrue("people who stayed at the anvils moved " + moves + " times in " + stays, moves <= 2);
	}

	@Test
	public void nobodyAtABusyAnvilIsEverDrawnWalkingThroughItAsPeopleAndYouComeAndGo()
	{
		// Every walk as the plugin draws it, frame by frame, from wherever each person is at the time:
		// someone with nowhere near to go who walked back towards their tile, then turned halfway
		// for a spot across the anvil, cut its corner.
		java.util.Random rnd = new java.util.Random(5);
		long[] sides = {AT_NORTH, AT_WEST, AT_EAST, AT_SOUTH};
		int[] roster = {24, 18, 2, 2};
		List<int[]> people = new ArrayList<>();
		int nextId = 1;
		for (int k = 0; k < sides.length; k++)
		{
			for (int i = 0; i < roster[k]; i++)
			{
				// {id, side, back at (0 while here)}
				people.add(new int[]{nextId++, k, rnd.nextInt(4) == 0 ? 1 + rnd.nextInt(60) : 0});
			}
		}
		CollisionTerrain terrain = new CollisionTerrain(smithyFlags());
		CrowdPlanner planner = new CrowdPlanner();
		OffsetTable offsets = new OffsetTable();
		for (int t = 1; t <= 400; t++)
		{
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (int[] p : people)
			{
				if (p[2] == 0 && rnd.nextInt(1000) < 12)
				{
					p[2] = t + 10 + rnd.nextInt(50);
				}
				else if (p[2] != 0 && t >= p[2])
				{
					p[2] = 0;
				}
				if (p[2] == 0)
				{
					still.add(new StackSpreader.Entry(p[0], sides[p[1]], false, facingTheAnvil(sides[p[1]]), true));
				}
			}
			if (t % 50 < 35)
			{
				still.add(new StackSpreader.Entry(YOU, AT_EAST, true, facingTheAnvil(AT_EAST), true));
			}
			CrowdPlanner.Plan plan = atTheAnvil(planner, still, t);
			Map<Integer, Long> tileOf = new HashMap<>();
			for (StackSpreader.Entry e : still)
			{
				tileOf.put(e.id, e.tile);
			}
			offsets.clearTargets();
			for (StackSpreader.Placement p : plan.placements)
			{
				offsets.setTarget(p.id, p.dx, p.dz);
			}
			for (int frame = 0; frame < 30; frame++)
			{
				offsets.advance(0.02f);
				for (Map.Entry<Integer, Long> e : tileOf.entrySet())
				{
					int id = e.getKey();
					if (!offsets.isOffset(id))
					{
						continue;
					}
					int x = StackRegistry.sceneX(e.getValue()) * 128 + 64 + offsets.dx(id);
					int z = StackRegistry.sceneY(e.getValue()) * 128 + 64 + offsets.dz(id);
					Assert.assertTrue("tick " + t + ": player " + id + " is drawn at (" + offsets.dx(id) + ", " + offsets.dz(id) + ") from their tile, in something",
						terrain.canStand(0, Math.floorDiv(x, 128) * 128 + 64, Math.floorDiv(z, 128) * 128 + 64, x, z));
				}
			}
			// Anyone who walked off for real starts from the middle of wherever they come back to.
			for (int[] p : people)
			{
				if (p[2] != 0)
				{
					offsets.setTarget(p[0], 0, 0);
				}
			}
		}
	}

	/** The fire in the busy-fire tests below, on (50, 50), and the tiles round it. */
	private static final long FIRE = StackRegistry.key(0, 50, 50);
	private static final int[][] ROUND_IT = {{0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}};

	/** Open ground, as the game's collision map has it. */
	private static int[][][] openFlags()
	{
		int[][][] flags = new int[4][][];
		flags[0] = new int[104][104];
		return flags;
	}

	/**
	 * A fire on (50, 50) as the plugin sees it, with the game's collision rules: nobody drawn within
	 * the plugin's clearance of its tile. A Forester's Campfire, which can be stood on, unless
	 * {@code blocked}, as a fire someone lit or cooks on is.
	 */
	private static CrowdPlanner.Surroundings fireAt5050(int[][][] flags, boolean blocked)
	{
		if (blocked)
		{
			flags[0][50][50] = CollisionTerrain.BLOCK_FULL;
		}
		CollisionTerrain terrain = new CollisionTerrain(flags);
		return new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz)
			{
				int x = StackRegistry.sceneX(t) * 128 + 64;
				int z = StackRegistry.sceneY(t) * 128 + 64;
				return terrain.canStand(0, x, z, x + dx, z + dz) && !inFireClearance(x + dx, z + dz);
			}
			public boolean facesObstacle(long t, double a) { return terrain.facesObstacle(0, StackRegistry.sceneX(t), StackRegistry.sceneY(t), a); }
			public boolean isCounter(long t, double a) { return terrain.isCounter(0, StackRegistry.sceneX(t), StackRegistry.sceneY(t), a); }
			public boolean facesFire(long t, double a)
			{
				return StackRegistry.sceneX(t) + (int) Math.round(-Math.sin(a)) == 50 && StackRegistry.sceneY(t) + (int) Math.round(-Math.cos(a)) == 50;
			}
			public List<int[]> firesNear(long t)
			{
				List<int[]> out = new ArrayList<>();
				int east = 50 - StackRegistry.sceneX(t);
				int north = 50 - StackRegistry.sceneY(t);
				if (Math.abs(east) <= 1 && Math.abs(north) <= 1)
				{
					out.add(new int[]{east, north});
				}
				return out;
			}
		};
	}

	/** Whether a body at scene point (x, z) is within the plugin's clearance of the fire on (50, 50). */
	private static boolean inFireClearance(int x, int z)
	{
		int c = PersonalSpacePlugin.FIRE_CLEARANCE;
		return x + c >= 50 * 128 && x - c < 51 * 128 && z + c >= 50 * 128 && z - c < 51 * 128;
	}

	/** How far a scene point is from the fire's tile, (50, 50): 0 on it. */
	private static double fromTheFiresTile(double x, double z)
	{
		return Math.hypot(Math.max(0, Math.abs(x - (50 * 128 + 64)) - 64), Math.max(0, Math.abs(z - (50 * 128 + 64)) - 64));
	}

	/** {@code count} tending the fire on (50, 50) from tile (x, y), or standing on it, ids from {@code id[0]} up. */
	private static void tending(List<StackSpreader.Entry> out, int[] id, int x, int y, int count)
	{
		for (int i = 0; i < count; i++)
		{
			int facing = x == 50 && y == 50 ? (id[0] * 300) % 2048 : OffsetTable.facing(50 - x, 50 - y);
			out.add(new StackSpreader.Entry(id[0]++, StackRegistry.key(0, x, y), false, facing, true));
		}
	}

	/** A busy campfire: {@code onIt} on it, {@code round} on each tile round it, and you tending it from the south. */
	private static List<StackSpreader.Entry> busyCampfire(int onIt, int round, boolean you)
	{
		List<StackSpreader.Entry> out = new ArrayList<>();
		int[] id = {1};
		tending(out, id, 50, 50, onIt);
		for (int[] d : ROUND_IT)
		{
			tending(out, id, 50 + d[0], 50 + d[1], d[0] == 0 && d[1] == -1 && you ? round - 1 : round);
		}
		if (you)
		{
			out.add(new StackSpreader.Entry(YOU, StackRegistry.key(0, 50, 49), true, OffsetTable.facing(0, 1), true));
		}
		return out;
	}

	/**
	 * Where everyone is drawn, in scene units, as the plugin draws them: everyone given a spot there,
	 * and on each tile, whoever is left without one in its middle: you, or someone alone, or one of
	 * those waiting unless its middle is kept out of sight.
	 */
	private static Map<Integer, double[]> drawnAsThePluginDoes(CrowdPlanner.Plan plan, List<StackSpreader.Entry> still)
	{
		Map<Integer, double[]> at = new HashMap<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			at.put(p.id, new double[]{StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx, StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz});
		}
		Map<Long, List<StackSpreader.Entry>> byTile = new HashMap<>();
		for (StackSpreader.Entry e : still)
		{
			byTile.computeIfAbsent(e.tile, k -> new ArrayList<>()).add(e);
		}
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			StackSpreader.Entry middle = null;
			for (StackSpreader.Entry en : e.getValue())
			{
				if (!at.containsKey(en.id) && (en.local || middle == null && (e.getValue().size() == 1 || !plan.middleOutOfSight.contains(e.getKey()))))
				{
					middle = en.local || middle == null ? en : middle;
				}
			}
			if (middle != null)
			{
				at.put(middle.id, new double[]{StackRegistry.sceneX(e.getKey()) * 128 + 64, StackRegistry.sceneY(e.getKey()) * 128 + 64});
			}
		}
		return at;
	}

	/** Checks that nobody drawn is inside anybody else: a body's width apart, and a little more from you. */
	private static void assertNobodyInsideAnybody(String when, Map<Integer, double[]> at)
	{
		List<Integer> ids = new ArrayList<>(at.keySet());
		ids.sort(Comparator.naturalOrder());
		for (int i = 0; i < ids.size(); i++)
		{
			for (int j = i + 1; j < ids.size(); j++)
			{
				double d = Math.hypot(at.get(ids.get(i))[0] - at.get(ids.get(j))[0], at.get(ids.get(i))[1] - at.get(ids.get(j))[1]);
				int least = ids.get(i) == YOU || ids.get(j) == YOU ? CrowdPlanner.OBJECT_YOUR_GAP : CrowdPlanner.OBJECT_KEEP;
				Assert.assertTrue(when + ": players " + ids.get(i) + " and " + ids.get(j) + " are drawn " + Math.round(d) + " apart", d >= least - 0.5);
			}
		}
	}

	private static CrowdPlanner.Plan atTheFire(CrowdPlanner planner, List<StackSpreader.Entry> still, int tick, CrowdPlanner.Surroundings fire)
	{
		return planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK, true, true, tick, fire);
	}

	@Test
	public void aBusyCampfireIsStoodRoundByOneCrowdWithNobodyInsideAnybody()
	{
		// Sixteen tending a Forester's Campfire from on top of it and eight on every tile round it, you
		// among them. Each tile round it was laid out on its own, squeezed into its slice of the ring
		// until its people stood 26 apart, and the people on the fire, laid out last in whatever gaps
		// were left, waited out of sight in it.
		CrowdPlanner.Surroundings campfire = fireAt5050(openFlags(), false);
		List<StackSpreader.Entry> still = busyCampfire(16, 8, true);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 10; t++)
		{
			plan = atTheFire(planner, still, t, campfire);
			if (t >= 3)
			{
				assertNobodyInsideAnybody("tick " + t, drawnAsThePluginDoes(plan, still));
			}
		}
		Assert.assertArrayEquals("those on it face it", new int[]{0, 0}, plan.fires.get(FIRE));
		for (int[] d : ROUND_IT)
		{
			Assert.assertArrayEquals("those round it face it", new int[]{-d[0] * 128, -d[1] * 128}, plan.fires.get(StackRegistry.key(0, 50 + d[0], 50 + d[1])));
		}
		int onIt = 0;
		for (StackSpreader.Placement p : plan.placements)
		{
			onIt += p.tile == FIRE ? 1 : 0;
		}
		Assert.assertTrue("only " + onIt + " of the 16 on the fire stand round it", onIt >= 8);
	}

	@Test
	public void theCrowdRoundABusyCampfireIsReportedAsOne()
	{
		List<StackSpreader.Entry> still = busyCampfire(10, 6, true);
		CrowdPlanner.Plan plan = null;
		CrowdPlanner planner = new CrowdPlanner();
		for (int t = 1; t <= 3; t++)
		{
			plan = atTheFire(planner, still, t, fireAt5050(openFlags(), false));
		}
		Assert.assertEquals(CrowdPlanner.ROUND_FIRE, plan.tiles.get(FIRE).shape);
		for (int[] d : ROUND_IT)
		{
			CrowdPlanner.TileReport report = plan.tiles.get(StackRegistry.key(0, 50 + d[0], 50 + d[1]));
			Assert.assertEquals(CrowdPlanner.ROUND_FIRE, report.shape);
			Assert.assertEquals("in the open, nothing is in the way", 0, report.blocked);
		}
		Assert.assertEquals("You're in a ring round the fire", PersonalSpacePlugin.yourTileLine(plan.tiles.get(StackRegistry.key(0, 50, 49)).shape, false));
	}

	@Test
	public void nobodyAtABusyCampfireIsDrawnInOrWalksThroughTheFlames()
	{
		// People coming and going on every side, some coming back to another side. Nobody is drawn
		// nearer the fire than its own people stand when nobody crowds round it, and nobody walks to
		// their spot through it (only those standing on it walk out of it).
		CrowdPlanner.Surroundings campfire = fireAt5050(openFlags(), false);
		java.util.Random rnd = new java.util.Random(7);
		List<StackSpreader.Entry> roster = busyCampfire(12, 7, true);
		Map<Integer, Long> away = new HashMap<>();
		Map<Integer, Long> tileOf = new HashMap<>();
		for (StackSpreader.Entry e : roster)
		{
			tileOf.put(e.id, e.tile);
		}
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> last = new HashMap<>();
		Map<Integer, Long> lastTile = new HashMap<>();
		for (int t = 1; t <= 300; t++)
		{
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (StackSpreader.Entry e : roster)
			{
				Long back = away.get(e.id);
				if (back == null && e.id != YOU && rnd.nextInt(100) < 2)
				{
					away.put(e.id, (long) (t + 5 + rnd.nextInt(40)));
					// ...and some come back to another side.
					if (e.tile != FIRE && rnd.nextInt(3) == 0)
					{
						int[] d = ROUND_IT[rnd.nextInt(8)];
						tileOf.put(e.id, StackRegistry.key(0, 50 + d[0], 50 + d[1]));
					}
				}
				else if (back != null && t >= back)
				{
					away.remove(e.id);
				}
				if (!away.containsKey(e.id))
				{
					long tile = tileOf.get(e.id);
					int facing = tile == FIRE ? e.orientation : OffsetTable.facing(50 - StackRegistry.sceneX(tile), 50 - StackRegistry.sceneY(tile));
					still.add(new StackSpreader.Entry(e.id, tile, e.local, facing, true));
				}
			}
			CrowdPlanner.Plan plan = atTheFire(planner, still, t, campfire);
			Map<Integer, int[]> now = new HashMap<>();
			Map<Integer, Long> nowTile = new HashMap<>();
			for (StackSpreader.Placement p : plan.placements)
			{
				double x = StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx;
				double z = StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz;
				Assert.assertTrue("tick " + t + ": player " + p.id + " is drawn " + Math.round(fromTheFiresTile(x, z)) + " from the fire's tile",
					fromTheFiresTile(x, z) >= CrowdPlanner.FIRE_SIDE - 64 - 0.5);
				int[] was = lastTile.get(p.id) != null && lastTile.get(p.id) == p.tile ? last.get(p.id) : null;
				int[] from = was != null ? was : new int[]{0, 0};
				if (p.tile != FIRE || was != null)
				{
					for (int k = 0; k <= 64; k++)
					{
						int wx = StackRegistry.sceneX(p.tile) * 128 + 64 + from[0] + (int) Math.round((p.dx - from[0]) * k / 64.0);
						int wz = StackRegistry.sceneY(p.tile) * 128 + 64 + from[1] + (int) Math.round((p.dz - from[1]) * k / 64.0);
						Assert.assertFalse("tick " + t + ": player " + p.id + " walks through the fire from (" + from[0] + ", " + from[1] + ") to ("
							+ p.dx + ", " + p.dz + ")", inFireClearance(wx, wz));
					}
				}
				now.put(p.id, new int[]{p.dx, p.dz});
				nowTile.put(p.id, p.tile);
			}
			last = now;
			lastTile = nowTile;
		}
	}

	@Test
	public void everyoneWithNoRoomAtABusyCampfireWaitsOutOfSight()
	{
		// Far more at the fire than can stand round it a body's width apart. Whoever is left over
		// waits out of sight rather than being drawn in the middle of their tile, inside the people
		// standing there, or out on their own away from it. The people standing on the fire take
		// their share of the room round it: laid out last, they all waited in the flames.
		List<StackSpreader.Entry> still = busyCampfire(16, 12, true);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheFire(planner, still, t, fireAt5050(openFlags(), false));
		}
		Map<Integer, double[]> at = drawnAsThePluginDoes(plan, still);
		assertNobodyInsideAnybody("a packed campfire", at);
		Set<Integer> placed = spots(plan).keySet();
		Assert.assertTrue("only " + placed.size() + " of " + still.size() + " are given a spot", placed.size() >= 36 && placed.size() < still.size());
		int onIt = 0;
		for (StackSpreader.Entry e : still)
		{
			if (!placed.contains(e.id) && !e.local)
			{
				Assert.assertTrue("player " + e.id + " waits in the middle of their tile, in sight", plan.middleOutOfSight.contains(e.tile));
			}
			onIt += e.tile == FIRE && placed.contains(e.id) ? 1 : 0;
		}
		Assert.assertTrue("only " + onIt + " of the 16 on the fire stand round it", onIt >= 8);
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertTrue("player " + p.id + " is drawn " + Math.round(Math.hypot(p.dx, p.dz)) + " from their tile",
				Math.hypot(p.dx, p.dz) <= CrowdPlanner.FIRE_SIDE + 2 * 64 - 36);
		}
	}

	@Test
	public void peopleRoundABusyCampfireKeepTheirSpotsAsOthersComeAndGo()
	{
		// Every arrival or departure laid its whole tile out afresh, and everyone on it slid somewhere new.
		CrowdPlanner.Surroundings campfire = fireAt5050(openFlags(), false);
		java.util.Random rnd = new java.util.Random(11);
		List<StackSpreader.Entry> roster = busyCampfire(12, 8, true);
		Set<Integer> away = new HashSet<>();
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> last = new HashMap<>();
		int moves = 0;
		int stays = 0;
		for (int t = 1; t <= 300; t++)
		{
			for (StackSpreader.Entry e : roster)
			{
				if (e.id != YOU && t > 10 && rnd.nextInt(100) < 2)
				{
					if (!away.remove(e.id))
					{
						away.add(e.id);
					}
				}
			}
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (StackSpreader.Entry e : roster)
			{
				if (!away.contains(e.id))
				{
					still.add(e);
				}
			}
			Map<Integer, int[]> now = spots(atTheFire(planner, still, t, campfire));
			for (Map.Entry<Integer, int[]> e : now.entrySet())
			{
				int[] was = last.get(e.getKey());
				if (t > 10 && was != null)
				{
					stays++;
					moves += Math.hypot(was[0] - e.getValue()[0], was[1] - e.getValue()[1]) >= 16 ? 1 : 0;
				}
			}
			last = now;
		}
		Assert.assertTrue("people who stayed at the fire moved " + moves + " times in " + stays, stays > 5000 && moves <= 5);
	}

	@Test
	public void youStandNearestTheFireOnYourSideOfABusyCampfire()
	{
		// Packed round a busy fire you could lose yourself, someone a body's width from you on
		// every side: you take the place at the fire on your side, with a little room round you.
		List<StackSpreader.Entry> still = busyCampfire(16, 8, true);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheFire(planner, still, t, fireAt5050(openFlags(), false));
		}
		Assert.assertArrayEquals("you're at the fire, on your side of it", new int[]{0, 128 - CrowdPlanner.OBJECT_NEAR_RING}, spots(plan).get(YOU));
		Map<Integer, double[]> at = drawnAsThePluginDoes(plan, still);
		for (Map.Entry<Integer, double[]> o : at.entrySet())
		{
			double d = Math.hypot(o.getValue()[0] - at.get(YOU)[0], o.getValue()[1] - at.get(YOU)[1]);
			Assert.assertTrue("player " + o.getKey() + " is drawn " + Math.round(d) + " from you", o.getKey() == YOU || d >= CrowdPlanner.OBJECT_YOUR_GAP - 0.5);
		}
	}

	@Test
	public void aCampfireThatGetsBusyFormsItsCrowdRoundWhereEveryoneStands()
	{
		// Four tending a campfire from on top of it and two from beside it, then three more arrive. Laid
		// out afresh the moment it got busy, the people already there walked somewhere new, some of them
		// straight through the fire.
		CrowdPlanner.Surroundings campfire = fireAt5050(openFlags(), false);
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		tending(still, id, 50, 50, 4);
		tending(still, id, 50, 51, 2);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> before = null;
		for (int t = 1; t <= 20; t++)
		{
			before = spots(atTheFire(planner, still, t, campfire));
		}
		Assert.assertEquals("a few at a fire stand round it", 6, before.size());
		tending(still, id, 51, 50, 3);
		for (int t = 21; t <= 30; t++)
		{
			CrowdPlanner.Plan plan = atTheFire(planner, still, t, campfire);
			Map<Integer, int[]> now = spots(plan);
			assertNobodyInsideAnybody("tick " + t, drawnAsThePluginDoes(plan, still));
			for (int who = 1; who <= 6; who++)
			{
				Assert.assertNotNull("tick " + t + ": player " + who + " lost their spot", now.get(who));
				double moved = Math.hypot(now.get(who)[0] - before.get(who)[0], now.get(who)[1] - before.get(who)[1]);
				Assert.assertTrue("tick " + t + ": player " + who + " moved " + Math.round(moved), moved < 16);
			}
		}
	}

	@Test
	public void aFewAtACampfireStillStandInTheirOwnRows()
	{
		// Nobody crowding round it: the rows a few people at a fire stand in suit them, as before.
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		tending(still, id, 50, 50, 2);
		tending(still, id, 50, 51, 3);
		CrowdPlanner.Plan plan = null;
		CrowdPlanner planner = new CrowdPlanner();
		for (int t = 1; t <= 3; t++)
		{
			plan = atTheFire(planner, still, t, fireAt5050(openFlags(), false));
		}
		Assert.assertEquals("fire row", plan.tiles.get(StackRegistry.key(0, 50, 51)).shape);
		Assert.assertEquals(PersonalSpaceConfig.SMALL_FIRE_SPACING, plan.tiles.get(StackRegistry.key(0, 50, 51)).spacing);
		Assert.assertEquals("crowd on the fire", plan.tiles.get(FIRE).shape);
	}

	@Test
	public void onlySmartStandsOneCrowdRoundABusyFire()
	{
		List<StackSpreader.Entry> still = busyCampfire(8, 4, false);
		for (PersonalSpaceConfig.Arrangement arrangement : PersonalSpaceConfig.Arrangement.values())
		{
			CrowdPlanner planner = new CrowdPlanner();
			planner.arrangement = arrangement;
			CrowdPlanner.Plan plan = planner.plan(still, q -> true, PersonalSpaceConfig.SPACING_WIDE, PersonalSpaceConfig.MAX_STACK,
				arrangement != PersonalSpaceConfig.Arrangement.CIRCLE, false, 1, fireAt5050(openFlags(), false));
			CrowdPlanner.TileReport north = plan.tiles.get(StackRegistry.key(0, 50, 51));
			Assert.assertEquals(arrangement.toString(), arrangement == PersonalSpaceConfig.Arrangement.AUTO,
				north != null && CrowdPlanner.ROUND_FIRE.equals(north.shape));
		}
	}

	@Test
	public void aBusyCookingFireIsStoodRoundByOneCrowdToo()
	{
		// An ordinary fire, which nobody can stand on, with a crowd cooking on it from every side: each
		// side squeezed into its own slice as at a campfire. Like an anvil, it's stood round by one crowd,
		// out of its flames and walking round it, not through it.
		int[][][] flags = openFlags();
		CrowdPlanner.Surroundings fire = fireAt5050(flags, true);
		CollisionTerrain terrain = new CollisionTerrain(flags);
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		tending(still, id, 50, 51, 7);
		tending(still, id, 51, 50, 6);
		tending(still, id, 49, 50, 6);
		tending(still, id, 50, 49, 5);
		tending(still, id, 49, 49, 3);
		tending(still, id, 51, 51, 3);
		CrowdPlanner planner = new CrowdPlanner();
		CrowdPlanner.Plan plan = null;
		for (int t = 1; t <= 5; t++)
		{
			plan = atTheFire(planner, still, t, fire);
		}
		assertNobodyInsideAnybody("a busy cooking fire", drawnAsThePluginDoes(plan, still));
		Assert.assertEquals("everyone is given a spot", still.size(), plan.placements.size());
		for (StackSpreader.Placement p : plan.placements)
		{
			Assert.assertNotNull("player " + p.id + " faces the fire", plan.fires.get(p.tile));
			Assert.assertTrue("player " + p.id + " walks through the fire to (" + p.dx + ", " + p.dz + ")",
				walksClear(terrain, p.tile, p.dx, p.dz));
			double x = StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx;
			double z = StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz;
			Assert.assertTrue("player " + p.id + " is drawn in the flames", fromTheFiresTile(x, z) >= CrowdPlanner.FIRE_SIDE - 64 - 0.5);
		}
	}

	@Test
	public void theCrowdOnACampfireInACornerWalksOutOfItButNotThroughTheWalls()
	{
		// The fire's own tile in the corner of a room, walls along its west and south edges. Walking
		// out of the fire, someone headed for the gap between the two walls, through the corner.
		int[][][] flags = openFlags();
		for (int y = 50; y <= 60; y++)
		{
			flags[0][49][y] |= CollisionTerrain.BLOCK_EAST;
			flags[0][50][y] |= CollisionTerrain.BLOCK_WEST;
		}
		for (int x = 50; x <= 60; x++)
		{
			flags[0][x][49] |= CollisionTerrain.BLOCK_NORTH;
			flags[0][x][50] |= CollisionTerrain.BLOCK_SOUTH;
		}
		CrowdPlanner.Surroundings campfire = fireAt5050(flags, false);
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		tending(still, id, 50, 50, 14);
		tending(still, id, 50, 51, 4);
		tending(still, id, 51, 51, 4);
		tending(still, id, 51, 50, 4);
		CrowdPlanner planner = new CrowdPlanner();
		for (int t = 1; t <= 10; t++)
		{
			CrowdPlanner.Plan plan = atTheFire(planner, still, t, campfire);
			Assert.assertEquals(CrowdPlanner.ROUND_FIRE, plan.tiles.get(FIRE).shape);
			assertNobodyInsideAnybody("tick " + t, drawnAsThePluginDoes(plan, still));
			for (StackSpreader.Placement p : plan.placements)
			{
				int x = StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx;
				int z = StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz;
				Assert.assertTrue("tick " + t + ": player " + p.id + " is drawn outside the room at (" + p.dx + ", " + p.dz + ")",
					x >= 50 * 128 && z >= 50 * 128);
				// Their walk there, one tile to the next: never across a wall, nor through a corner both walls meet at.
				int px = StackRegistry.sceneX(p.tile);
				int pz = StackRegistry.sceneY(p.tile);
				for (int k = 1; k <= 64; k++)
				{
					int tx = Math.floorDiv(StackRegistry.sceneX(p.tile) * 128 + 64 + (int) Math.round(p.dx * k / 64.0), 128);
					int tz = Math.floorDiv(StackRegistry.sceneY(p.tile) * 128 + 64 + (int) Math.round(p.dz * k / 64.0), 128);
					if (tx != px || tz != pz)
					{
						Assert.assertTrue("tick " + t + ": player " + p.id + " walks through a wall to (" + p.dx + ", " + p.dz + ")",
							CollisionTerrain.canStep(flags[0], px, pz, tx - px, tz - pz));
						px = tx;
						pz = tz;
					}
				}
			}
		}
	}

	@Test
	public void theLastAtABusyCampfireKeepTheirPlacesRoundItAndNeverWalkThroughIt()
	{
		// A busy campfire empties until one is left on it and one beside it, each alone on their tile,
		// then only the one on it. With nobody left sharing a tile the crowd wasn't laid out at all:
		// the one on the fire was given the first place by it afresh, and walked straight through it.
		CrowdPlanner.Surroundings campfire = fireAt5050(openFlags(), false);
		List<StackSpreader.Entry> still = busyCampfire(10, 3, false);
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> was = null;
		for (int t = 1; t <= 10; t++)
		{
			was = spots(atTheFire(planner, still, t, campfire));
		}
		// The one on the fire standing furthest west, across it from the first place by it.
		StackSpreader.Entry onIt = null;
		StackSpreader.Entry beside = null;
		for (StackSpreader.Entry e : still)
		{
			if (e.tile == FIRE && was.containsKey(e.id) && (onIt == null || was.get(e.id)[0] < was.get(onIt.id)[0]))
			{
				onIt = e;
			}
			beside = beside == null && e.tile == StackRegistry.key(0, 50, 51) ? e : beside;
		}
		Assert.assertTrue("someone on the fire stands west of it", was.get(onIt.id)[0] < -64);
		List<StackSpreader.Entry> few = new ArrayList<>();
		few.add(onIt);
		few.add(beside);
		int[] at = was.get(onIt.id);
		for (int t = 11; t <= 30; t++)
		{
			List<StackSpreader.Entry> now = t <= 20 ? few : few.subList(0, 1);
			int[] spot = spots(atTheFire(planner, now, t, campfire)).get(onIt.id);
			Assert.assertNotNull("tick " + t + ": the last one on the fire is drawn in it", spot);
			for (int k = 0; k <= 64; k++)
			{
				int x = 50 * 128 + 64 + at[0] + (int) Math.round((spot[0] - at[0]) * k / 64.0);
				int z = 50 * 128 + 64 + at[1] + (int) Math.round((spot[1] - at[1]) * k / 64.0);
				Assert.assertFalse("tick " + t + ": the last one on the fire walks through it from (" + at[0] + ", " + at[1] + ") to ("
					+ spot[0] + ", " + spot[1] + ")", inFireClearance(x, z));
			}
			double moved = Math.hypot(spot[0] - at[0], spot[1] - at[1]);
			Assert.assertTrue("tick " + t + ": the last one on the fire moved " + Math.round(moved), t == 21 ? moved < 48 : moved < 16);
			at = spot;
		}
	}

	@Test
	public void twoBusyCampfiresSideBySideAreStoodRoundClearOfBothFires()
	{
		// Two Forester's Campfires on neighbouring tiles, both busy. The crowd round each stood clear of
		// its own flames, but up against the other fire's, and walked round the corner of them.
		int[][][] flags = openFlags();
		CollisionTerrain terrain = new CollisionTerrain(flags);
		long other = StackRegistry.key(0, 51, 50);
		CrowdPlanner.Surroundings two = new CrowdPlanner.Surroundings()
		{
			public boolean canStand(long t, int dx, int dz)
			{
				int x = StackRegistry.sceneX(t) * 128 + 64;
				int z = StackRegistry.sceneY(t) * 128 + 64;
				return terrain.canStand(0, x, z, x + dx, z + dz) && !inFireClearance(x + dx, z + dz) && !inFireClearance(x + dx - 128, z + dz);
			}
			public boolean facesObstacle(long t, double a) { return false; }
			public boolean isCounter(long t, double a) { return false; }
			public boolean facesFire(long t, double a)
			{
				int x = StackRegistry.sceneX(t) + (int) Math.round(-Math.sin(a));
				int y = StackRegistry.sceneY(t) + (int) Math.round(-Math.cos(a));
				return (x == 50 || x == 51) && y == 50;
			}
			public List<int[]> firesNear(long t)
			{
				List<int[]> out = new ArrayList<>();
				for (int fx = 50; fx <= 51; fx++)
				{
					int east = fx - StackRegistry.sceneX(t);
					int north = 50 - StackRegistry.sceneY(t);
					if (Math.abs(east) <= 1 && Math.abs(north) <= 1)
					{
						out.add(new int[]{east, north});
					}
				}
				return out;
			}
		};
		List<StackSpreader.Entry> still = new ArrayList<>();
		int[] id = {1};
		int[][] crowd = {{50, 50, 8, 50}, {51, 50, 8, 51}, {49, 50, 5, 50}, {52, 50, 5, 51}, {50, 51, 5, 50}, {51, 51, 5, 51},
			{50, 49, 4, 50}, {51, 49, 5, 51}, {49, 51, 3, 50}, {52, 49, 3, 51}};
		for (int[] c : crowd)
		{
			for (int i = 0; i < c[2]; i++)
			{
				int facing = c[0] == c[3] && c[1] == 50 ? (id[0] * 300) % 2048 : OffsetTable.facing(c[3] - c[0], 50 - c[1]);
				still.add(new StackSpreader.Entry(id[0]++, StackRegistry.key(0, c[0], c[1]), false, facing, true));
			}
		}
		CrowdPlanner planner = new CrowdPlanner();
		Map<Integer, int[]> last = new HashMap<>();
		for (int t = 1; t <= 10; t++)
		{
			CrowdPlanner.Plan plan = atTheFire(planner, still, t, two);
			assertNobodyInsideAnybody("tick " + t, drawnAsThePluginDoes(plan, still));
			Map<Integer, int[]> now = new HashMap<>();
			for (StackSpreader.Placement p : plan.placements)
			{
				Assert.assertEquals("tick " + t + ": player " + p.id + " is one of a crowd round a fire", CrowdPlanner.ROUND_FIRE, plan.tiles.get(p.tile).shape);
				int x = StackRegistry.sceneX(p.tile) * 128 + 64 + p.dx;
				int z = StackRegistry.sceneY(p.tile) * 128 + 64 + p.dz;
				for (int fire = 0; fire <= 1; fire++)
				{
					double clear = fromTheFiresTile(x - fire * 128, z);
					Assert.assertTrue("tick " + t + ": player " + p.id + " is drawn " + Math.round(clear) + " from a fire's tile", clear >= CrowdPlanner.FIRE_MARGIN - 0.5);
				}
				int[] from = last.getOrDefault(p.id, new int[]{0, 0});
				for (int fire = 0; fire <= 1; fire++)
				{
					if (from[0] == 0 && from[1] == 0 && p.tile == (fire == 0 ? FIRE : other))
					{
						continue; // walking out of the fire they stand on
					}
					for (int k = 0; k <= 64; k++)
					{
						int wx = StackRegistry.sceneX(p.tile) * 128 + 64 + from[0] + (int) Math.round((p.dx - from[0]) * k / 64.0);
						int wz = StackRegistry.sceneY(p.tile) * 128 + 64 + from[1] + (int) Math.round((p.dz - from[1]) * k / 64.0);
						Assert.assertFalse("tick " + t + ": player " + p.id + " walks through a fire from (" + from[0] + ", " + from[1] + ") to ("
							+ p.dx + ", " + p.dz + ")", inFireClearance(wx - fire * 128, wz));
					}
				}
				now.put(p.id, new int[]{p.dx, p.dz});
			}
			last = now;
		}
	}
}
