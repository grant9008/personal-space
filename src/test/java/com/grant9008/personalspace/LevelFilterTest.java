package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class LevelFilterTest
{
	@Test
	public void playersBelowTheLevelAreHiddenButNeverFriendsOrClan()
	{
		Assert.assertTrue("a level 3 with the filter at 4", LevelFilter.hides(4, 3, false, false, false));
		Assert.assertFalse("level 4 itself stays", LevelFilter.hides(4, 4, false, false, false));
		Assert.assertFalse("a friend stays", LevelFilter.hides(4, 3, true, false, false));
		Assert.assertFalse("a friends chat member stays", LevelFilter.hides(4, 3, false, true, false));
		Assert.assertFalse("a clan member stays", LevelFilter.hides(4, 3, false, false, true));
		Assert.assertFalse("off at 0", LevelFilter.hides(0, 3, false, false, false));
		Assert.assertFalse("the slider's Off, 3, hides nobody: nobody is below level 3",
			LevelFilter.hides(PersonalSpaceConfig.MIN_HIDE_BELOW, 3, false, false, false));
		Assert.assertFalse("an unknown level is never hidden", LevelFilter.hides(4, 0, false, false, false));
	}
}
