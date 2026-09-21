package com.grant9008.personalspace;

import java.awt.Color;
import org.junit.Assert;
import org.junit.Test;

public class NamesOverlayTest
{
	private static Color pick(NamesOverlay.Look look, boolean pvp, boolean you, boolean party, boolean friend, boolean friendsChat, boolean team, boolean clan)
	{
		return NamesOverlay.colourFor(look, pvp, you, party, friend, friendsChat, team, clan);
	}

	@Test
	public void playerIndicatorsDefaultsNameFriendsChatTeamAndClanButNotYouOrStrangers()
	{
		NamesOverlay.Look look = new NamesOverlay.Look();
		Assert.assertEquals(look.friendColour, pick(look, false, false, false, true, true, true, true));
		Assert.assertEquals(look.friendsChatColour, pick(look, false, false, false, false, true, true, true));
		Assert.assertEquals(look.teamColour, pick(look, false, false, false, false, false, true, true));
		Assert.assertEquals(look.clanColour, pick(look, false, false, false, false, false, false, true));
		Assert.assertEquals(look.partyColour, pick(look, false, false, true, true, false, false, false));
		Assert.assertNull("a stranger gets no name", pick(look, false, false, false, false, false, false, false));
		Assert.assertNull("nor do you", pick(look, false, true, false, true, true, true, true));
	}

	@Test
	public void switchesAreHonouredInPlayerIndicatorsOrder()
	{
		NamesOverlay.Look look = new NamesOverlay.Look();
		look.own = NamesOverlay.Highlight.ENABLED;
		look.others = NamesOverlay.Highlight.ENABLED;
		look.friend = NamesOverlay.Highlight.DISABLED;
		Assert.assertEquals("you get your own colour", look.ownColour, pick(look, false, true, false, false, false, false, false));
		Assert.assertEquals("strangers get the others colour", look.othersColour, pick(look, false, false, false, false, false, false, false));
		Assert.assertEquals("a friend with friends off falls through to others", look.othersColour, pick(look, false, false, false, true, false, false, false));
		Assert.assertNull("but a friends chat member never counts as others", pick(look, false, false, false, true, true, false, false) == look.friendsChatColour ? null : "x");
	}

	@Test
	public void pvpOnlySettingsApplyOnlyWhereYouCanBeAttacked()
	{
		NamesOverlay.Look look = new NamesOverlay.Look();
		look.others = NamesOverlay.Highlight.PVP;
		Assert.assertNull(pick(look, false, false, false, false, false, false, false));
		Assert.assertEquals(look.othersColour, pick(look, true, false, false, false, false, false, false));
		Assert.assertTrue(NamesOverlay.on(NamesOverlay.Highlight.ENABLED, false));
		Assert.assertFalse(NamesOverlay.on(NamesOverlay.Highlight.DISABLED, true));
	}
}
