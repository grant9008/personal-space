package com.grant9008.personalspace;

import java.util.Map;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPCComposition;
import org.junit.Assert;
import org.junit.Test;

public class HoverOverlayTest
{
	@Test
	public void theLevelIsColouredAsTheGameColoursIt()
	{
		Assert.assertEquals("far below you: green", "00ff00", HoverOverlay.levelColour(126, 6));
		Assert.assertEquals("a little below: yellow-green", "c0ff00", HoverOverlay.levelColour(93, 91));
		Assert.assertEquals("your level: yellow", "ffff00", HoverOverlay.levelColour(93, 93));
		Assert.assertEquals("a little above: orange", "ffb000", HoverOverlay.levelColour(93, 95));
		Assert.assertEquals("far above: red", "ff0000", HoverOverlay.levelColour(3, 126));
	}

	@Test
	public void aPetSaysWhoseItIs()
	{
		Assert.assertEquals("Smolcano - Zezima's pet", HoverOverlay.petText("Smolcano", "Zezima", false, true));
		Assert.assertEquals("a space in their name, as the game sends it", "Smolcano - Lil Zez's pet", HoverOverlay.petText("Smolcano", "Lil Zez", false, true));
		Assert.assertEquals("Smolcano - your pet", HoverOverlay.petText("Smolcano", null, true, true));
		Assert.assertEquals("its owner not known: its name alone", "Smolcano", HoverOverlay.petText("Smolcano", null, false, true));
		Assert.assertEquals("Smolcano", HoverOverlay.petText("Smolcano", "", false, true));
		Assert.assertNull("no name: nothing to say", HoverOverlay.petText("null", "Zezima", false, true));
		Assert.assertNull(HoverOverlay.petText(null, "Zezima", false, true));
	}

	@Test
	public void aQuestCompanionSaysWhoItFollowsNotWhosePetItIs()
	{
		Assert.assertEquals("Zanik - following Zezima", HoverOverlay.petText("Zanik", "Zezima", false, false));
		Assert.assertEquals("Zanik - following you", HoverOverlay.petText("Zanik", null, true, false));
		Assert.assertEquals("its owner not known: its name alone", "Zanik", HoverOverlay.petText("Zanik", null, false, false));
		// Every pet in the game's cache can be picked up; Zanik, Nieve and Grubfoot only talk.
		Assert.assertTrue("Smolcano", HoverOverlay.canPickUp(actions("Talk-to", null, "Pick-up", null, null)));
		Assert.assertTrue("a dog", HoverOverlay.canPickUp(actions("Interact", "Pet", "Pick-up", "Dig", null)));
		Assert.assertFalse("Zanik", HoverOverlay.canPickUp(actions("Talk-to", null, null, null, null)));
		Assert.assertFalse("a companion with no options of its own", HoverOverlay.canPickUp(actions((String) null)));
		Assert.assertFalse(HoverOverlay.canPickUp(FakeMeshes.fake(NPCComposition.class, Map.of())));
		Assert.assertFalse(HoverOverlay.canPickUp(null));
	}

	@Test
	public void theMouseIsOverTheWorldWhenTheGameSaysSo()
	{
		Assert.assertTrue(HoverOverlay.overTheWorld(menu(MenuAction.CANCEL, MenuAction.WALK)));
		Assert.assertTrue("on a boat", HoverOverlay.overTheWorld(menu(MenuAction.CANCEL, MenuAction.SET_HEADING)));
		Assert.assertTrue("a spell picked, someone to cast it on", HoverOverlay.overTheWorld(menu(MenuAction.CANCEL, MenuAction.WIDGET_TARGET_ON_NPC)));
		Assert.assertFalse("an interface over it: Cancel, and its own", HoverOverlay.overTheWorld(menu(MenuAction.CANCEL, MenuAction.CC_OP, MenuAction.RUNELITE)));
		Assert.assertFalse("a spell picked, over bare ground or an interface", HoverOverlay.overTheWorld(menu(MenuAction.CANCEL)));
		Assert.assertFalse(HoverOverlay.overTheWorld(menu(MenuAction.CANCEL, MenuAction.WIDGET_TARGET_ON_WIDGET, MenuAction.UNKNOWN)));
		Assert.assertFalse(HoverOverlay.overTheWorld(new MenuEntry[0]));
		Assert.assertFalse(HoverOverlay.overTheWorld(null));
	}

	private static MenuEntry[] menu(MenuAction... types)
	{
		MenuEntry[] menu = new MenuEntry[types.length];
		for (int i = 0; i < types.length; i++)
		{
			menu[i] = FakeMeshes.fake(MenuEntry.class, Map.of("getType", types[i]));
		}
		return menu;
	}

	private static NPCComposition actions(String... actions)
	{
		return FakeMeshes.fake(NPCComposition.class, Map.of("getActions", actions));
	}
}
