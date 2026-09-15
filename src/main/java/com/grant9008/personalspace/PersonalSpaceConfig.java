package com.grant9008.personalspace;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PersonalSpaceConfig.GROUP)
public interface PersonalSpaceConfig extends Config
{
	String GROUP = "personalspace";

	enum Mode
	{
		SPREAD("Spread stacked players"),
		TEST_SHIFT_ME("Test: shift my character");

		private final String label;

		Mode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Separation
	{
		SMALL("Small", 20),
		MEDIUM("Medium", 32),
		LARGE("Large", 44);

		private final String label;
		private final int units;

		Separation(String label, int units)
		{
			this.label = label;
			this.units = units;
		}

		/** Ring radius in local units. One tile is 128 units, so even Large stays inside the tile. */
		public int getUnits()
		{
			return units;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "mode",
		name = "Mode",
		description = "Normal use is 'Spread stacked players'. The test mode ignores everyone else and just draws your own character a fixed distance east of where it really is, so you can check the basic effect on your own.",
		position = 0
	)
	default Mode mode()
	{
		return Mode.SPREAD;
	}

	@ConfigItem(
		keyName = "separation",
		name = "Separation",
		description = "How far apart stacked players are pushed. Large is still well inside a single tile.",
		position = 1
	)
	default Separation separation()
	{
		return Separation.MEDIUM;
	}

	@Range(min = 2, max = 5)
	@ConfigItem(
		keyName = "maxStack",
		name = "Max players per tile",
		description = "Spread at most this many players on one tile. Any extra players stay in the middle as normal.",
		position = 2
	)
	default int maxStack()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "includeLocalPlayer",
		name = "Move my character too",
		description = "Off: your own character always stays exactly where it really is and other players step around you. On: you take a slot in the ring like everyone else.",
		position = 3
	)
	default boolean includeLocalPlayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "smoothing",
		name = "Smooth movement",
		description = "Ease players into their slot over a fraction of a second instead of snapping there.",
		position = 4
	)
	default boolean smoothing()
	{
		return true;
	}

	@Range(min = 0, max = 64)
	@ConfigItem(
		keyName = "testOffset",
		name = "Test offset (units)",
		description = "Only used by the test mode. Draws your own character this many local units east of its real spot. 128 units is one tile, so 32 is a quarter tile.",
		position = 5
	)
	default int testOffset()
	{
		return 32;
	}
}
