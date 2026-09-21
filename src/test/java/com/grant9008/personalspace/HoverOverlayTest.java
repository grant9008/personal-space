package com.grant9008.personalspace;

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
}
