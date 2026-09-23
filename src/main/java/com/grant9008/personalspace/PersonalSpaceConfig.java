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
	String KEY_DRAW_ME_IN_FRONT = "drawMeInFront";
	String KEY_NAMES = "namesFollowPlayers";
	String KEY_HOVER = "hoverShowsWho";
	String KEY_HOVER_ARROW = "hoverArrow";
	String KEY_HIDE_BELOW = "hideBelowLevel";
	/** Nobody is below level 3, where everyone starts, so up to 3 the filter hides nobody: it's off. */
	int MIN_HIDE_BELOW = 3;
	int MAX_HIDE_BELOW = 126;
	String KEY_TEST_OFFSET = "testOffset";
	String KEY_SHOW_SIDEBAR = "showSidebarButton";

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
	 * whatever the slider says: a body's width, so seen from the side nobody runs into the person
	 * beside them, as they did at 42. Close enough to read as a busy bank rather than a queue; much
	 * wider and a short stretch of counter runs out of room, leaving people stacked in the middle.
	 */
	int COUNTER_SPACING = 48;
	/**
	 * How close a line shared by several booths or along a riverbank closes up when it gets busy
	 * (more than {@code CrowdPlanner.ROOMY_LINE_MAX} people on one of its tiles), with Auto-space on.
	 * Otherwise it keeps {@link #COUNTER_SPACING}: at 42 bodies and capes ran into each other seen
	 * from the side, but a packed line only has so much counter, and at 50 one ran out of spots and
	 * left people hidden in the middle.
	 */
	int LINE_SPACING = 42;
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
		description = "Off: you stay put and others step around you. On: you take a spot too, and people step out of your view where there's room.",
		position = 4
	)
	default boolean includeLocalPlayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_DRAW_ME_IN_FRONT,
		name = "Draw me in front",
		description = "Off: people are drawn front to back as they really stand, so someone between you and the camera covers you. On: you're drawn over everyone in your crowd, whichever way the camera faces. Either way nobody moves out of your way: crowds huddle as they would.",
		position = 5
	)
	default boolean drawMeInFront()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_PAUSE_IN_COMBAT,
		name = "Pause while I'm fighting",
		description = "On: everyone goes back to where they really stand while you fight. Keep it on for raids and group bosses.",
		position = 11
	)
	default boolean pauseInCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_NAMES,
		name = "Names over players",
		description = "Off by default. On: names follow the bodies. Who gets a name, the colours and the rank icons are Player Indicators' settings, so change them there. Turn off Player Indicators' name position (or the plugin) so names aren't doubled.",
		position = 8
	)
	default boolean namesFollowPlayers()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_HOVER,
		name = "Hover shows who",
		description = "On: hover a body drawn away from its tile and a tooltip says who it is. Clicking is unchanged: you still click people where they really stand.",
		position = 9
	)
	default boolean hoverShowsWho()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_HOVER_ARROW,
		name = "Arrow to their tile",
		description = "On: hover a body drawn away from its tile and a small arrow points to where they really stand. Works with or without the tooltip.",
		position = 10
	)
	default boolean hoverArrow()
	{
		return true;
	}

	@Range(min = MIN_HIDE_BELOW, max = MAX_HIDE_BELOW)
	@ConfigItem(
		keyName = KEY_HIDE_BELOW,
		name = "Hide players below level",
		description = "3 (the default) is off: nobody is below level 3. Set 4 to hide the level-3s, or higher. Anyone below this combat level isn't drawn or clickable, as with Entity Hider. Friends, friends chat and clan members always stay. Off in PvP areas and while you fight.",
		position = 12
	)
	default int hideBelowLevel()
	{
		return MIN_HIDE_BELOW;
	}

	@ConfigItem(
		keyName = KEY_SHOW_SIDEBAR,
		name = "Show sidebar button",
		description = "Off: no Personal Space button in the sidebar. Every setting is still here.",
		position = 13
	)
	default boolean showSidebarButton()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_SMALL_GROUPS_CLOSE,
		name = "Auto-space",
		description = "On: small groups, banks, walls and fires stay close whatever the slider says. Off: the slider decides everywhere.",
		position = 6
	)
	default boolean smallGroupsClose()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_POSE,
		name = "Small group pose",
		description = "How two or three players in the open are turned. Natural: as they really face. Angled: half towards each other. Facing: towards each other.",
		position = 7
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
