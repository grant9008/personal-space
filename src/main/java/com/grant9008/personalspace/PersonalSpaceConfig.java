package com.grant9008.personalspace;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
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
	String KEY_ARRANGEMENT = "arrangement";
	String KEY_MODE = "mode";
	String KEY_SPACING = "spacingUnits";
	String KEY_MAX_STACK = "maxStack";
	String KEY_INCLUDE_LOCAL = "includeLocalPlayer";
	String KEY_SMALL_GROUPS_CLOSE = "smallGroupsClose";
	String KEY_TEST_OFFSET = "testOffset";

	int MIN_STACK = 2;
	int MAX_STACK = 10;
	int DEFAULT_STACK = 5;
	int MIN_TEST_OFFSET = 0;
	int MAX_TEST_OFFSET = 64;

	/** Spacing between players, in local units (128 is one tile). */
	int MIN_SPACING = 16;
	int MAX_SPACING = 256;
	int SPACING_CLOSE = 40;
	int SPACING_NORMAL = 128;
	/** Two tiles apart: what looks best in a big Grand Exchange crowd, so it's the default. Banks, fires and anvils stay closer on their own. */
	int SPACING_WIDE = 256;
	/**
	 * The slider sets the spacing for a full crowd. Two players on a tile stand at most this far apart
	 * (a bit over half a tile), and bigger groups spread further, reaching the slider's spacing at
	 * {@link #FULL_CROWD} players: two people two tiles apart just look lost.
	 */
	int PAIR_SPACING = 72;
	int FULL_CROWD = 8;
	/** Smart keeps people at a bank counter or row of booths at most this far apart, whatever the slider says. */
	int COUNTER_SPACING = 42;
	/** Smart keeps people around a fire at most this far apart, whatever the slider says. */
	int FIRE_SPACING = 46;

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

	enum Arrangement
	{
		AUTO("Smart"),
		CIRCLE("Circle");

		private final String label;

		Arrangement(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(
		name = "Troubleshooting",
		description = "Test mode and settings for checking that Personal Space works.",
		position = 10,
		closedByDefault = true
	)
	String TROUBLESHOOTING = "troubleshooting";

	@ConfigItem(
		keyName = KEY_ACTIVE,
		name = "Spread out crowds",
		description = "Untick to pause without turning the plugin off. Everyone goes back to where they really stand.",
		position = 0
	)
	default boolean active()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_ARRANGEMENT,
		name = "Arrangement",
		description = "Smart: the crowd spreads into open space, stays out of booths, stalls, anvils and walls, and lines up at things people face. Circle: a simple ring on each tile.",
		position = 1
	)
	default Arrangement arrangement()
	{
		return Arrangement.AUTO;
	}

	@Range(min = MIN_SPACING, max = MAX_SPACING)
	@ConfigItem(
		keyName = KEY_SPACING,
		name = "Spacing",
		description = "How far apart players are drawn, in game units (128 is one tile). With 'Small groups stay close' on, this is for a big crowd and smaller groups stand closer.",
		position = 2
	)
	default int spacing()
	{
		return SPACING_WIDE;
	}

	@Range(min = MIN_STACK, max = MAX_STACK)
	@ConfigItem(
		keyName = KEY_MAX_STACK,
		name = "Players per tile",
		description = "Spread out at most this many players on one tile (5 is the sweet spot). Anyone past that stays hidden in the middle, as in the normal game.",
		position = 3
	)
	default int maxStack()
	{
		return DEFAULT_STACK;
	}

	@ConfigItem(
		keyName = KEY_INCLUDE_LOCAL,
		name = "Move my character too",
		description = "Off: your own character always stays exactly where it really is and other players step around you. On: you take a spot like everyone else.",
		position = 4
	)
	default boolean includeLocalPlayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_SMALL_GROUPS_CLOSE,
		name = "Small groups stay close",
		description = "On: two or three players on a tile stand close together and only big crowds get the full spacing. Off: the spacing applies to every group, for lining up the perfect outfit screenshot.",
		position = 5
	)
	default boolean smallGroupsClose()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_MODE,
		name = "Mode",
		description = "Normal use is 'Spread stacked players'. The test mode ignores everyone else and just draws your own character a fixed distance east of where it really is, so you can check the basic effect on your own.",
		position = 11,
		section = TROUBLESHOOTING
	)
	default Mode mode()
	{
		return Mode.SPREAD;
	}

	@Range(min = MIN_TEST_OFFSET, max = MAX_TEST_OFFSET)
	@ConfigItem(
		keyName = KEY_TEST_OFFSET,
		name = "Test offset (units)",
		description = "Only used by the test mode. Draws your own character this many local units east of its real spot. 128 units is one tile, so 32 is a quarter tile.",
		position = 12,
		section = TROUBLESHOOTING
	)
	default int testOffset()
	{
		return 32;
	}
}
