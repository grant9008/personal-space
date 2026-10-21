package com.grant9008.personalspace;

import java.util.Map;
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

	private static NPCComposition actions(String... actions)
	{
		return FakeMeshes.fake(NPCComposition.class, Map.of("getActions", actions));
	}
}
