package com.grant9008.personalspace;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Settings. Everything here can also be changed from the Personal Space sidebar panel, which
 * writes through the same config keys, so the two always agree.
 */
@ConfigGroup(PersonalSpaceConfig.GROUP)
public interface PersonalSpaceConfig extends Config
{
	String GROUP = "personalspace";

	String KEY_ACTIVE = "active";
	String KEY_MODE = "mode";
	String KEY_SEPARATION = "separation";
	String KEY_MAX_STACK = "maxStack";
	String KEY_INCLUDE_LOCAL = "includeLocalPlayer";
	String KEY_SMOOTHING = "smoothing";
	String KEY_TEST_OFFSET = "testOffset";

	int MIN_STACK = 2;
	int MAX_STACK = 5;
	int MIN_TEST_OFFSET = 0;
	int MAX_TEST_OFFSET = 64;

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
		keyName = KEY_ACTIVE,
		name = "Effect on",
		description = "Untick to pause the effect without turning the plugin off. Everyone snaps back to where they really are.",
		position = 0
	)
	default boolean active()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_MODE,
		name = "Mode",
		description = "Normal use is 'Spread stacked players'. The test mode ignores everyone else and just draws your own character a fixed distance east of where it really is, so you can check the basic effect on your own.",
		position = 1
	)
	default Mode mode()
	{
		return Mode.SPREAD;
	}

	@ConfigItem(
		keyName = KEY_SEPARATION,
		name = "Separation",
		description = "How far apart stacked players are pushed. Large is still well inside a single tile.",
		position = 2
	)
	default Separation separation()
	{
		return Separation.MEDIUM;
	}

	@Range(min = MIN_STACK, max = MAX_STACK)
	@ConfigItem(
		keyName = KEY_MAX_STACK,
		name = "Max players per tile",
		description = "Spread at most this many players on one tile. Any extra players stay in the middle as normal.",
		position = 3
	)
	default int maxStack()
	{
		return MAX_STACK;
	}

	@ConfigItem(
		keyName = KEY_INCLUDE_LOCAL,
		name = "Move my character too",
		description = "Off: your own character always stays exactly where it really is and other players step around you. On: you take a slot in the ring like everyone else.",
		position = 4
	)
	default boolean includeLocalPlayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_SMOOTHING,
		name = "Smooth movement",
		description = "Ease players into their slot over a fraction of a second instead of snapping there.",
		position = 5
	)
	default boolean smoothing()
	{
		return true;
	}

	@Range(min = MIN_TEST_OFFSET, max = MAX_TEST_OFFSET)
	@ConfigItem(
		keyName = KEY_TEST_OFFSET,
		name = "Test offset (units)",
		description = "Only used by the test mode. Draws your own character this many local units east of its real spot. 128 units is one tile, so 32 is a quarter tile.",
		position = 6
	)
	default int testOffset()
	{
		return 32;
	}
}
