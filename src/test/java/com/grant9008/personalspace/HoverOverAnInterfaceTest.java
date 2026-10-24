package com.grant9008.personalspace;

import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import org.junit.Assert;
import org.junit.Test;

/**
 * Hover shows who, with an interface drawn over the world: the Grand Exchange or a bank, or in
 * resizable mode the inventory and the chatbox, with someone moved, or a pet, behind it under the
 * mouse. Each client tick, before the frame the overlay draws in, the game builds its menu from
 * what's under the mouse: "Walk here" and the options on whoever is there where the world shows,
 * and only Cancel and the interface's own options where an interface covers it. The game of
 * {@link WhosePetTest} is hovered with the menu as the game would have built it.
 */
public class HoverOverAnInterfaceTest
{
	private static final int TILE = 50;
	private static final int MIDDLE = TILE * 128 + 64;

	private static final MenuEntry CANCEL = WhosePetTest.entry("Cancel", MenuAction.CANCEL, null);
	private static final MenuEntry WALK = WhosePetTest.entry("Walk here", MenuAction.WALK, null);

	/** The game's menu with the mouse over the world: Cancel, and "Walk here" on top. */
	private static MenuEntry[] world()
	{
		return new MenuEntry[]{CANCEL, WALK};
	}

	/** The game's menu with the mouse over one of the Grand Exchange's empty offer slots. */
	private static MenuEntry[] grandExchangeSlot()
	{
		return new MenuEntry[]{CANCEL, WhosePetTest.entry("Create <col=ff9040>Sell offer</col>", MenuAction.CC_OP, null)};
	}

	/** Two of the crowd on a tile, spread out round it: Player 1 drawn 96 east of where they stand. */
	private static WhosePetTest.Hover spreadOut()
	{
		WhosePetTest.Hover h = new WhosePetTest.Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 4, TILE + 4);
		FollowerOnCrowdedTileTest.Body a = g.player(1, TILE, TILE);
		FollowerOnCrowdedTileTest.Body b = g.player(2, TILE, TILE);
		g.crowd(TILE, TILE, a, b);
		h.outlines();
		g.run(a, 100);
		Assert.assertEquals("spread east", 96, g.offsets.dx(a.id));
		h.arrow = true;
		String them = h.hover(MIDDLE + 96, MIDDLE, 100);
		Assert.assertTrue("named over the world: " + them, them != null && them.startsWith("Player 1  "));
		Assert.assertTrue("and the arrow to where they stand", h.drewOnScreen());
		return h;
	}

	@Test
	public void someoneMovedBehindTheGrandExchangeIsNotNamed()
	{
		WhosePetTest.Hover h = spreadOut();
		// The offers window is open over them, and the mouse is on one of its slots.
		h.menu = grandExchangeSlot();
		Assert.assertNull(h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertFalse("no arrow either", h.drewOnScreen());
		h.menu = new MenuEntry[]{CANCEL};
		Assert.assertNull("its background", h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertFalse(h.drewOnScreen());
		// The window still open, the mouse over the world beside it: named as before.
		h.menu = world();
		String them = h.hover(MIDDLE + 96, MIDDLE, 100);
		Assert.assertTrue(them, them != null && them.startsWith("Player 1  "));
		Assert.assertTrue(h.drewOnScreen());
	}

	@Test
	public void aPetBehindTheBankIsNotNamed()
	{
		WhosePetTest.Hover h = new WhosePetTest.Hover();
		FollowerOnCrowdedTileTest.Game g = h.g;
		g.you = g.player(0, TILE + 3, TILE - 2);
		FollowerOnCrowdedTileTest.Body owner = g.player(1, TILE - 2, TILE);
		FollowerOnCrowdedTileTest.Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		h.outlines();
		g.run(pet, 20);
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
		// The bank open over it: its background, and an item in it.
		h.menu = new MenuEntry[]{CANCEL};
		Assert.assertNull(h.hover(MIDDLE, MIDDLE, 30));
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Withdraw-1", MenuAction.CC_OP, null)};
		Assert.assertNull(h.hover(MIDDLE, MIDDLE, 30));
		// Beside the bank, over the world.
		h.menu = world();
		Assert.assertEquals("Nexling - Player 1's pet", h.hover(MIDDLE, MIDDLE, 30));
	}

	@Test
	public void inResizableModeTheInventoryAndTheChatboxCoverTheWorld()
	{
		WhosePetTest.Hover h = spreadOut();
		// An item in the inventory, and a plugin's own option on it.
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Examine", MenuAction.CC_OP_LOW_PRIORITY, null),
			WhosePetTest.entry("Drop", MenuAction.CC_OP, null), WhosePetTest.entry("Use", MenuAction.WIDGET_TARGET, null),
			WhosePetTest.entry("Tag", MenuAction.RUNELITE, null), WhosePetTest.entry("Wield", MenuAction.CC_OP, null)};
		Assert.assertNull("an item", h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertFalse(h.drewOnScreen());
		// Alching it: a spell to cast on it, and no "Walk here" anywhere.
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Cast", MenuAction.WIDGET_TARGET_ON_WIDGET, null)};
		Assert.assertNull("alching", h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertFalse(h.drewOnScreen());
		// The chatbox: between its lines, and a name in it.
		h.menu = new MenuEntry[]{CANCEL};
		Assert.assertNull("the chatbox", h.hover(MIDDLE + 96, MIDDLE, 100));
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Report", MenuAction.CC_OP, null), WhosePetTest.entry("Add friend", MenuAction.CC_OP, null)};
		Assert.assertNull("a name in it", h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertFalse(h.drewOnScreen());
		// Off them, over the world.
		h.menu = world();
		String them = h.hover(MIDDLE + 96, MIDDLE, 100);
		Assert.assertTrue(them, them != null && them.startsWith("Player 1  "));
	}

	@Test
	public void withNoInterfaceOverTheWorldItIsAsBefore()
	{
		WhosePetTest.Hover h = spreadOut();
		// Someone where they really stand under the mouse too: the game's options on them, as ever.
		h.menu = new MenuEntry[]{CANCEL, WALK, WhosePetTest.entry("Trade with", MenuAction.PLAYER_SECOND_OPTION, null),
			WhosePetTest.entry("Follow", MenuAction.PLAYER_THIRD_OPTION, null)};
		String them = h.hover(MIDDLE + 96, MIDDLE, 100);
		Assert.assertTrue(them, them != null && them.startsWith("Player 1  "));
		Assert.assertTrue(h.drewOnScreen());
		// A spell picked to cast: no "Walk here", but someone to cast it on.
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Cast", MenuAction.WIDGET_TARGET_ON_PLAYER, null)};
		Assert.assertEquals(them, h.hover(MIDDLE + 96, MIDDLE, 100));
		// On a boat: "Set heading" for "Walk here".
		h.menu = new MenuEntry[]{CANCEL, WhosePetTest.entry("Set heading", MenuAction.SET_HEADING, null)};
		Assert.assertEquals(them, h.hover(MIDDLE + 96, MIDDLE, 100));
		Assert.assertTrue(h.drewOnScreen());
	}
}
