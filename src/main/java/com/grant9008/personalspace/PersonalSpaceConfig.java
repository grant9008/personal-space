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
	String KEY_POSE = "smallGroupPose";
	String KEY_PAUSE_IN_COMBAT = "pauseInCombat";
	String KEY_TEST_OFFSET = "testOffset";

	int MIN_STACK = 2;
	int MAX_STACK = 16;
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
	/**
	 * With Auto-space on, people at a bank counter or row of booths stand at most this far apart,
	 * whatever the slider says. Close enough to read as a busy bank rather than a queue, far enough
	 * that a kiteshield doesn't land on the person beside you, as it did at 42. Much wider and a short
	 * stretch of counter runs out of room, leaving people stacked in the middle.
	 */
	int COUNTER_SPACING = 50;
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

	/** How two or three players standing together in the open are turned. */
	enum Pose
	{
		NATURAL("Natural"),
		ANGLED("Angled"),
		FACING("Facing");

		private final String label;

		Pose(String label)
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
		CIRCLE("Circle"),
		ROW("Line"),
		ARC("Arc");

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
		description = "Untick to pause. Everyone goes back to where they really stand.",
		position = 0
	)
	default boolean active()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_ARRANGEMENT,
		name = "Arrangement",
		description = "Smart: lines up at counters, anvils and fires, and makes a ring in the open. Circle: always a ring. Line: side by side. Arc: a curve.",
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
		description = "How far apart players stand. 128 is one tile. With Auto-space on, banks, walls and fires stay close.",
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
		description = "How many players on one tile get their own spot. 5 suits most places. Raise it if a busy bank leaves a heap in the middle.",
		position = 3
	)
	default int maxStack()
	{
		return DEFAULT_STACK;
	}

	@ConfigItem(
		keyName = KEY_INCLUDE_LOCAL,
		name = "Move my character too",
		description = "Off: you stay put and others step around you. On: you take a spot too, where nobody blocks your view of yourself.",
		position = 4
	)
	default boolean includeLocalPlayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_PAUSE_IN_COMBAT,
		name = "Pause while I'm fighting",
		description = "On: everyone goes back to where they really stand while you fight. Keep it on for raids and group bosses.",
		position = 7
	)
	default boolean pauseInCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_SMALL_GROUPS_CLOSE,
		name = "Auto-space",
		description = "On: small groups, banks, walls and fires stay close whatever the slider says. Off: the slider decides everywhere.",
		position = 5
	)
	default boolean smallGroupsClose()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_POSE,
		name = "Small group pose",
		description = "How two or three players in the open are turned. Natural: as they really face. Angled: half towards each other. Facing: towards each other.",
		position = 6
	)
	default Pose pose()
	{
		return Pose.NATURAL;
	}

	@ConfigItem(
		keyName = KEY_MODE,
		name = "Mode",
		description = "Leave on 'Spread stacked players'. The test mode moves just your own character, to check the plugin works.",
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
		description = "Test mode only. How far east to draw your own character. 128 is one tile.",
		position = 12,
		section = TROUBLESHOOTING
	)
	default int testOffset()
	{
		return 32;
	}
}
