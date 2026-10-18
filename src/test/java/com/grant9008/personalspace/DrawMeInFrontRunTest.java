package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

/** With "Draw me in front" and depth, who is drawn in one run with you (see SpreadingDrawCallbacks.drawKept). */
public class DrawMeInFrontRunTest
{
	private static SpreadingDrawCallbacks.Kept at(int x, int z)
	{
		SpreadingDrawCallbacks.Kept k = new SpreadingDrawCallbacks.Kept();
		k.x = x;
		k.z = z;
		return k;
	}

	@Test
	public void theRunStartsAtTheFirstOfYourCrowdDrawn()
	{
		SpreadingDrawCallbacks.Kept you = at(1000, 1000);
		SpreadingDrawCallbacks.Kept[] kept = {
			at(5000, 5000), // far off: drawn with depth as usual
			at(1000 + SpreadingDrawCallbacks.NEAR_YOU, 1000 - 50), // in your crowd: the run starts here
			at(3000, 1000), // drawn between, not in your crowd: in the run too, so you're over everyone in it
			at(1000, 1100),
			you,
			at(1100, 1000), // after you: depth as usual
		};
		int youAt = SpreadingDrawCallbacks.indexOf(kept, kept.length, you);
		Assert.assertEquals(4, youAt);
		Assert.assertEquals(1, SpreadingDrawCallbacks.frontRunStart(kept, youAt));
	}

	@Test
	public void withNobodyOfYourCrowdBeforeYouTheRunIsYouAlone()
	{
		SpreadingDrawCallbacks.Kept you = at(1000, 1000);
		SpreadingDrawCallbacks.Kept[] kept = {at(1000 + SpreadingDrawCallbacks.NEAR_YOU + 1, 1000), you};
		Assert.assertEquals(1, SpreadingDrawCallbacks.frontRunStart(kept, 1));
		Assert.assertEquals(-1, SpreadingDrawCallbacks.indexOf(kept, 2, null));
		Assert.assertEquals(-1, SpreadingDrawCallbacks.indexOf(kept, 1, you));
	}
}
