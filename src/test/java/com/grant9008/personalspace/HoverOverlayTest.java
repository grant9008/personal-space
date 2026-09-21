package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class HoverOverlayTest
{
	@Test
	public void theHoveredPersonsMenuLineIsLitAndTheOthersDimmed()
	{
		String target = "<col=ffffff>Siusam<col=40ff00>  (level-93)";
		String lit = HoverOverlay.lit(target);
		String dimmed = HoverOverlay.dimmed(target);
		Assert.assertTrue(lit, lit.contains("> Siusam  (level-93)"));
		Assert.assertTrue(lit, lit.startsWith("<col=ffe800>") && lit.endsWith("</col>"));
		Assert.assertTrue(dimmed, dimmed.contains("Siusam  (level-93)") && !dimmed.contains("> Siusam"));
		Assert.assertTrue(dimmed, dimmed.startsWith("<col=787878>"));
	}
}
