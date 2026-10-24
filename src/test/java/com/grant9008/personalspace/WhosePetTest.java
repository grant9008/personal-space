package com.grant9008.personalspace;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * Hover shows who, for pets: hovering one says whose it is. The game of
 * {@link FollowerOnCrowdedTileTest} draws its frames through the shim, and after a frame the
 * overlay is asked what's under the mouse, as RuneLite asks it once the scene is drawn. It looks
 * through a camera south of the pet's tile and above it, pitched down a little, and everyone's
 * outline is a box round them where they really stand, as the game works one out.
 */
public class WhosePetTest
{
	private static final int TILE = 50;
	private static final int MIDDLE = TILE * 128 + 64;
	/** A pet's height and half its width on the screen, and a person's: a pet is the wider of the two. */
	private static final int PET_HEIGHT = 80;
	private static final int PET_HALF_WIDTH = 10;
	private static final int PERSON_HEIGHT = 200;
	private static final int PERSON_HALF_WIDTH = 8;

	/** The game, and the overlay over it with the mouse where a test puts it. */
	static final class Hover
	{
		final FollowerOnCrowdedTileTest.Game g = new FollowerOnCrowdedTileTest.Game();
		final Client client;
		final LevelFilter filter;
		final TooltipManager tooltips = new TooltipManager();
		final HoverOverlay overlay;
		final BufferedImage screen = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D graphics = screen.createGraphics();
		boolean who = true;
		boolean arrow;
		boolean hooked = true;
		Point mouse = new Point(-1, -1);
		/** The game's menu as it last built it: the mouse over the world, unless a test says otherwise. */
		MenuEntry[] menu = {entry("Cancel", MenuAction.CANCEL, null), entry("Walk here", MenuAction.WALK, null)};
		Object follower;

		Hover()
		{
			Map<String, Object> view = new HashMap<>();
			view.put("worldViews", FakeMeshes.fake(IndexedObjectSet.class, Map.of("iterator", (Function<Object[], Object>) args -> Collections.emptyIterator())));
			Map<String, Object> c = new HashMap<>();
			c.put("getTopLevelWorldView", over(WorldView.class, g.wv, view));
			c.put("getCameraZ", -621);
			c.put("getCameraPitch", 128);
			c.put("getMouseCanvasPosition", (Function<Object[], Object>) args -> mouse);
			c.put("getFollower", (Function<Object[], Object>) args -> follower);
			c.put("getMenu", FakeMeshes.fake(Menu.class, Map.of("getMenuEntries", (Function<Object[], Object>) args -> menu)));
			client = over(Client.class, g.client, c);
			filter = new LevelFilter(client);
			filter.enabled = true;
			PersonalSpaceConfig config = FakeMeshes.fake(PersonalSpaceConfig.class, Map.of("hoverShowsWho", (Function<Object[], Object>) args -> who,
				"hoverArrow", (Function<Object[], Object>) args -> arrow));
			overlay = new HoverOverlay(client, config, g.offsets, g.stacks, tooltips, filter);
		}

		/** Everyone so far given a height and an outline: a box round them where they really stand. */
		void outlines()
		{
			for (FollowerOnCrowdedTileTest.Body b : g.players)
			{
				outline(b, PERSON_HEIGHT, PERSON_HALF_WIDTH);
			}
			for (FollowerOnCrowdedTileTest.Body b : g.npcs)
			{
				outline(b, PET_HEIGHT, PET_HALF_WIDTH);
			}
		}

		private void outline(FollowerOnCrowdedTileTest.Body b, int height, int halfWidth)
		{
			b.answers.put("getLogicalHeight", height);
			b.answers.put("getConvexHull", (Function<Object[], Object>) args -> box(b.x, b.z, height, halfWidth));
		}

		/** A body standing at (x, z) on the screen: from its feet to its head, {@code halfWidth} either side. */
		Rectangle box(int x, int z, int height, int halfWidth)
		{
			Point feet = Perspective.localToCanvas(client, new LocalPoint(x, z, 0), 0);
			Point head = Perspective.localToCanvas(client, new LocalPoint(x, z, 0), 0, height);
			return new Rectangle(feet.getX() - halfWidth, head.getY(), 2 * halfWidth + 1, feet.getY() - head.getY() + 1);
		}

		Rectangle petAt(int x, int z)
		{
			return box(x, z, PET_HEIGHT, PET_HALF_WIDTH);
		}

		Rectangle personAt(int x, int z)
		{
			return box(x, z, PERSON_HEIGHT, PERSON_HALF_WIDTH);
		}

		/** The mouse over (x, z), {@code up} off the ground there: what the tooltip says, or null. */
		String hover(int x, int z, int up)
		{
			return hover(Perspective.localToCanvas(client, new LocalPoint(x, z, 0), 0, up));
		}

		String hover(Point at)
		{
			mouse = at;
			tooltips.clear();
			graphics.setBackground(new Color(0, 0, 0, 0));
			graphics.clearRect(0, 0, screen.getWidth(), screen.getHeight());
			overlay.shim = hooked ? g.shim : null;
			overlay.render(graphics);
			List<Tooltip> all = tooltips.getTooltips();
			Assert.assertTrue("one tooltip at a time: " + all.size(), all.size() <= 1);
			return all.isEmpty() ? null : all.get(0).getText();
		}

		/** Whether the last hover drew anything on the screen: the arrow, all the overlay draws. */
		boolean drewOnScreen()
		{
			for (int y = 0; y < screen.getHeight(); y++)
			{
				for (int x = 0; x < screen.getWidth(); x++)
				{
					if ((screen.getRGB(x, y) >>> 24) != 0)
					{
						return true;
					}
				}
			}
			return false;
		}
	}

	/** A stand-in for {@code type} that answers as {@code answers} say, and as {@code target} does to anything else. */
	@SuppressWarnings("unchecked")
	private static <T> T over(Class<T> type, T target, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(WhosePetTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) ->
		{
			if (answers.containsKey(m.getName()))
			{
				Object v = answers.get(m.getName());
				return v instanceof Function ? ((Function<Object[], Object>) v).apply(args) : v;
			}
			try
			{
				return m.invoke(target, args);
			}
			catch (InvocationTargetException e)
			{
				throw e.getCause();
			}
		});
	}

	private static Point middleOf(Rectangle r)
	{
		return new Point(r.x + r.width / 2, r.y + r.height / 2);
	}

	/** A point in {@code a} clear of {@code b}, at the edge of {@code a} on its east side (or west), halfway up. */
	private static Point beside(Rectangle a, Rectangle b, boolean east)
	{
		Point p = new Point(east ? a.x + a.width - 2 : a.x + 1, a.y + a.height / 2);
		Assert.assertTrue("in the one", a.contains(p.getX(), p.getY()));
		Assert.assertFalse("clear of the other", b.contains(p.getX(), p.getY()));
		return p;
	}

	/** One of the game's menu entries: its option, its type, and the NPC it's on (if any). */
	static MenuEntry entry(String option, MenuAction type, Object npc)
	{
		Map<String, Object> e = new HashMap<>();
		e.put("getOption", option);
		e.put("getType", type);
		if (npc != null)
		{
			e.put("getNpc", npc);
		}
		return FakeMeshes.fake(MenuEntry.class, e);
	}

	@Test
	public void hoveringAPetTheGameDrawsSaysWhoseItIs()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
		Assert.assertNull("nothing beside it", h.hover(MIDDLE + 96, MIDDLE, 30));
		Assert.assertNull("nor over its head", h.hover(MIDDLE, MIDDLE, PET_HEIGHT + 60));
	}

	@Test
	public void aPetDrawnBesideSomeoneIsHoveredWhereItIsDrawnNotWhereItStands()
	{
		// You step onto a pet whose owner is three tiles west: it walks out beside you, their way.
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 3, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 512;
		h.outlines();
		g.run(pet, 20);
		g.trace(pet, 220, () -> g.stepTowards(g.you, MIDDLE, MIDDLE));
		int[] spot = g.at(pet);
		Assert.assertTrue("drawn beside you", spot[0] < MIDDLE - 50);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(spot[0], spot[1], 30));
		// Where it really stands, the edge of its outline there that reaches out past yours, on the
		// side away from where it's drawn: it isn't drawn there.
		Point real = beside(h.petAt(MIDDLE, MIDDLE), h.personAt(MIDDLE, MIDDLE), true);
		Assert.assertNull(h.hover(real));
		// You step off east: the game hands it over again, and it walks back from beside where you were.
		g.trace(pet, 3, () -> g.stepTowards(g.you, MIDDLE + 256, MIDDLE));
		int[] back = g.at(pet);
		Assert.assertTrue("on its way back", back[0] < MIDDLE - 30);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(back[0], back[1], 30));
	}

	@Test
	public void aPetWalkingOffAFullCampfireIsHoveredWhereItIsDrawn()
	{
		// Beside the fire, then walking off across it while the game has no room there for it, and
		// leaves it out: drawn here all the way (see FollowerOnCrowdedTileTest#aPetWalkingOffAFullCampfireIsDrawnAllTheWay).
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE + 3);
		g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body owner = g.player(2, TILE - 3, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		g.fires.add(StackRegistry.key(0, TILE, TILE));
		g.scenery[TILE][TILE] = 4;
		h.outlines();
		g.run(pet, 150);
		int[] spot = g.at(pet);
		Assert.assertTrue("off the flames", Math.hypot(spot[0] - MIDDLE, spot[1] - MIDDLE) > 50);
		Assert.assertEquals("Nexling - Player 2's pet", h.hover(spot[0], spot[1], 30));
		g.trace(pet, 6, () -> g.stepTowards(pet, MIDDLE + 128, MIDDLE));
		int[] going = g.at(pet);
		Assert.assertTrue("still crossing the fire's tile", pet.x < MIDDLE + 64);
		Assert.assertEquals("Nexling - Player 2's pet", h.hover(going[0], going[1], 30));
	}

	@Test
	public void yourOwnPetSaysSo()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = g.you.actor;
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("facing you", "Nexling - your pet", h.hover(MIDDLE, MIDDLE, 30));
		pet.interacting = null;
		h.follower = pet.actor;
		g.run(pet, 2);
		Assert.assertEquals("or the game says it's yours", "Nexling - your pet", h.hover(MIDDLE, MIDDLE, 30));
		h.menu = new MenuEntry[]{entry("Walk here", MenuAction.WALK, null)};
		Assert.assertEquals("Nexling - your pet", h.hover(MIDDLE, MIDDLE, 30));
		// With the game's left-click on it (Talk-to, Pick-up), its hover text, and Mouse Tooltips', name it already.
		h.menu = new MenuEntry[]{entry("Walk here", MenuAction.WALK, null), entry("Talk-to", MenuAction.NPC_FIRST_OPTION, pet.actor)};
		Assert.assertNull(h.hover(MIDDLE, MIDDLE, 30));
	}

	@Test
	public void yourOwnPetBesideYouSaysSoWhereItIsDrawn()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = g.you.actor;
		pet.orientation = 1536;
		h.outlines();
		g.run(pet, 20);
		g.trace(pet, 220, () -> g.stepTowards(g.you, MIDDLE, MIDDLE));
		int[] spot = g.at(pet);
		Assert.assertTrue("drawn beside you, east", spot[0] > MIDDLE + 50);
		// Its click box is where it stands, under you: the game's left-click there is about you, not it.
		h.menu = new MenuEntry[]{entry("Walk here", MenuAction.WALK, null)};
		Assert.assertEquals("Nexling - your pet", h.hover(spot[0], spot[1], 30));
	}

	@Test
	public void withNoOwnerInSightItsNameAlone()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		FollowerOnCrowdedTileTest.Body rat = g.npc(8, TILE - 3, TILE, false);
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("its owner out of sight", "Nexling", h.hover(MIDDLE, MIDDLE, 30));
		pet.interacting = rat.actor;
		g.run(pet, 2);
		Assert.assertEquals("chasing a rat", "Nexling", h.hover(MIDDLE, MIDDLE, 30));
		Assert.assertNull("and the rat is no pet", h.hover(MIDDLE - 3 * 128, MIDDLE, 30));
	}

	@Test
	public void aQuestCompanionSaysWhoItFollows()
	{
		// Zanik follows whoever is doing her quest, but she's no pet: there's no picking her up.
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body zanik = g.npc(7, TILE, TILE, true);
		NPCComposition talks = FakeMeshes.fake(NPCComposition.class, Map.of("isFollower", true, "getSize", 1,
			"getActions", new String[]{"Talk-to", null, null, null, null}));
		zanik.answers.put("getName", "Zanik");
		zanik.answers.put("getComposition", talks);
		zanik.answers.put("getTransformedComposition", talks);
		zanik.interacting = owner.actor;
		h.outlines();
		g.run(zanik, 20);
		Assert.assertEquals("Zanik - following Player 1", h.hover(MIDDLE, MIDDLE, 30));
		zanik.interacting = g.you.actor;
		g.run(zanik, 2);
		Assert.assertEquals("Zanik - following you", h.hover(MIDDLE, MIDDLE, 30));
	}

	@Test
	public void aPetThatIsNotDrawnIsNotThereToHover()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		boolean[] hiding = {false};
		g.hide(r -> hiding[0] && r == pet.actor);
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
		// Another plugin (Entity Hider) hides it.
		hiding[0] = true;
		Assert.assertEquals("hidden", "00000", g.run(pet, 5));
		Assert.assertNull(h.hover(MIDDLE, MIDDLE, 30));
		hiding[0] = false;
		g.run(pet, 5);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
	}

	@Test
	public void aPetLeftUnderSomeoneIsNotThereToHover()
	{
		// A middle between you and the camera: whoever waits there isn't drawn, and nor is the pet
		// under them (see FollowerOnCrowdedTileTest#aPetUnderAMiddleOutOfSightStaysUnderIt).
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE, TILE + 2);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE - 3, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE - 3, TILE + 1);
		FollowerOnCrowdedTileTest.Body waiting = g.player(3, TILE - 3, TILE - 1);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		h.outlines();
		g.run(pet, 20);
		Point edge = beside(h.petAt(MIDDLE, MIDDLE), h.personAt(MIDDLE, MIDDLE), true);
		Assert.assertEquals("on its own", "Nexling", h.hover(edge));
		// They crowd onto it.
		g.crowd(TILE, TILE, waiting, true, a, b);
		long key = StackRegistry.key(0, TILE, TILE);
		List<StackSpreader.Placement> members = new ArrayList<>();
		members.add(new StackSpreader.Placement(waiting.id, key, 0, 0));
		members.add(new StackSpreader.Placement(a.id, key, 96, 0));
		members.add(new StackSpreader.Placement(b.id, key, -96, 0));
		g.stacks.rebuild(members, new HashSet<>(), Set.of(waiting.id), new HashMap<>(), new HashMap<>(), Set.of(key));
		g.run(pet, 100);
		Assert.assertEquals("not drawn", "00000", g.run(pet, 5));
		Assert.assertNull(h.hover(edge));
	}

	@Test
	public void nothingWithHoverShowsWhoOffOrTheEffectNotRunning()
	{
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
		h.who = false;
		Assert.assertNull("Hover shows who off", h.hover(MIDDLE, MIDDLE, 30));
		h.who = true;
		h.filter.enabled = false;
		Assert.assertNull("off, paused while you fight, or in a PvP area", h.hover(MIDDLE, MIDDLE, 30));
		h.filter.enabled = true;
		h.hooked = false;
		Assert.assertNull("no renderer hooked: nobody knows where it's drawn", h.hover(MIDDLE, MIDDLE, 30));
		h.hooked = true;
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
	}

	@Test
	public void whoeverIsNearestTheCameraIsTheOneNamed()
	{
		// A crowd spread round its tile; one pet a tile in front of the one spread east, and another
		// a tile behind them, each overlapping them on the screen.
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 4, TILE + 4);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE, TILE);
		FollowerOnCrowdedTileTest.Body owner = g.player(3, TILE - 4, TILE + 4);
		FollowerOnCrowdedTileTest.Body front = g.npc(7, TILE + 1, TILE - 1, true);
		FollowerOnCrowdedTileTest.Body behind = g.npc(8, TILE + 1, TILE + 1, true);
		front.interacting = owner.actor;
		behind.interacting = owner.actor;
		g.crowd(TILE, TILE, a, b);
		h.outlines();
		g.run(a, 100);
		Assert.assertEquals("spread east", 96, g.offsets.dx(a.id));
		Rectangle body = h.personAt(MIDDLE + 96, MIDDLE);
		Rectangle frontBox = h.petAt(MIDDLE + 128, MIDDLE - 128);
		Rectangle behindBox = h.petAt(MIDDLE + 128, MIDDLE + 128);
		Rectangle overFront = body.intersection(frontBox);
		Rectangle overBehind = body.intersection(behindBox);
		Assert.assertTrue("they overlap on the screen: " + overFront + " " + overBehind, overFront.width >= 3 && overFront.height >= 3 && overBehind.width >= 3 && overBehind.height >= 3);
		String them = h.hover(MIDDLE + 96, MIDDLE, PERSON_HEIGHT - 20);
		Assert.assertTrue("the one spread out, as before: " + them, them != null && them.startsWith("Player 1  "));
		Assert.assertEquals("the pet in front of them", "Nexling - Player 3's pet", h.hover(middleOf(overFront)));
		Assert.assertEquals("them, in front of the pet behind", them, h.hover(middleOf(overBehind)));
		Assert.assertEquals("that pet where they don't cover it", "Nexling - Player 3's pet", h.hover(beside(behindBox, body, true)));
	}

	@Test
	public void aPetBehindSomeoneStandingWhereTheyAreIsCoveredByThem()
	{
		// You stand a tile in front of a pet: pointing at you, it isn't the pet you're pointing at.
		Hover h = new Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE, TILE - 1);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 3, TILE + 3);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		h.outlines();
		g.run(pet, 20);
		Rectangle you = h.personAt(MIDDLE, MIDDLE - 128);
		Rectangle petBox = h.petAt(MIDDLE, MIDDLE);
		Rectangle both = you.intersection(petBox);
		Assert.assertTrue("they overlap on the screen: " + both, both.width >= 3 && both.height >= 3);
		Assert.assertNull(h.hover(middleOf(both)));
		Assert.assertEquals("its edge out past you", "Nexling - Player 1's pet", h.hover(beside(petBox, you, false)));
	}
}
