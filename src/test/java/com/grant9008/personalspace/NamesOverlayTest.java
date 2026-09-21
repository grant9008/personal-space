package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class NamesOverlayTest
{
	@Test
	public void friendsAndClanGetPlayerIndicatorsColoursAndStrangersGetNone()
	{
		PersonalSpaceConfig.Names some = PersonalSpaceConfig.Names.FRIENDS;
		Assert.assertEquals(NamesOverlay.FRIEND, NamesOverlay.colourFor(some, true, true, true, true));
		Assert.assertEquals(NamesOverlay.FRIENDS_CHAT, NamesOverlay.colourFor(some, false, true, true, true));
		Assert.assertEquals(NamesOverlay.TEAM, NamesOverlay.colourFor(some, false, false, true, true));
		Assert.assertEquals(NamesOverlay.CLAN, NamesOverlay.colourFor(some, false, false, false, true));
		Assert.assertNull("a stranger gets no name", NamesOverlay.colourFor(some, false, false, false, false));
	}

	@Test
	public void everyoneGivesStrangersANameAndOffGivesNobodyOne()
	{
		Assert.assertEquals(NamesOverlay.OTHERS, NamesOverlay.colourFor(PersonalSpaceConfig.Names.EVERYONE, false, false, false, false));
		Assert.assertEquals(NamesOverlay.FRIEND, NamesOverlay.colourFor(PersonalSpaceConfig.Names.EVERYONE, true, false, false, false));
		Assert.assertNull(NamesOverlay.colourFor(PersonalSpaceConfig.Names.OFF, true, true, true, true));
	}
}
