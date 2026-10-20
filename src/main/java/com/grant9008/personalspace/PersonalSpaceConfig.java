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
	String KEY_HIDE_CHAT = "hideTheirChat";
	String KEY_CHAT_RANGE = "hideChatFurtherThan";
	String KEY_OTHER_ALCHERS = "otherAlchers";
	/** Public chat reaches you from about this far at most. */
	int MAX_CHAT_RANGE = 15;
	/** Nobody is below level 3, where everyone starts, so up to 3 the filter hides nobody: it's off. */
	int MIN_HIDE_BELOW = 3;
	int MAX_HIDE_BELOW = 126;
	String KEY_TEST_OFFSET = "testOffset";
	String KEY_DEPTH = "depthBetweenPlayers";
	String KEY_IDLE_SIT = "idlePlayersSit";
	String KEY_CLICK_PAST = "clickPastMoved";
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
	/**
	 * ...and a small group round a fire, at most this many, this far apart: at 46, about one body's
	 * width, three people round a fire stood so close that from most angles one vanished into the
	 * next. A big crowd keeps to 46, or it pushes into its neighbours and gets squeezed.
	 */
	int SMALL_FIRE_GROUP = 4;
	int SMALL_FIRE_SPACING = 60;

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

	/** How other players casting skilling spells (alching, superheating, charging orbs, enchanting) are shown. */
	enum Alchers
	{
		NORMAL("Normal"),
		STILL("Still"),
		PARTY("Party");

		private final String label;

		Alchers(String label)
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
		name = "Shape",
		description = "The shape people spread into. Smart: picks one for the place (rows at banks and counters, one crowd round an anvil or fire, a ring in the open). Circle, Line and Arc: that shape everywhere.",
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
		description = "How far apart spread-out people stand. With Auto spacing on, banks, counters and fires stay close anyway.",
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
		description = "How many people on one tile get spread out. 5 suits most places. Raise it if a busy bank still leaves a pile in the middle.",
		position = 3
	)
	default int maxStack()
	{
		return DEFAULT_STACK;
	}

	@ConfigItem(
		keyName = KEY_INCLUDE_LOCAL,
		name = "Give me a spot",
		description = "On (recommended): you're spread out too, taking a spot in the crowd like everyone else (the front one, at a bank counter). Off: you always stay exactly where you really stand, and the people on your tile spread out around you.",
		position = 4
	)
	default boolean includeLocalPlayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_DRAW_ME_IN_FRONT,
		name = "Draw me in front",
		description = "On: you're always drawn on top of your crowd, so you never lose sight of yourself. Off: people between you and the camera cover you, as they really would.",
		position = 5
	)
	default boolean drawMeInFront()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_PAUSE_IN_COMBAT,
		name = "Pause while I'm fighting",
		description = "On (recommended): while you're fighting, everyone goes back to where they really stand, so you can see who's where. Keep it on for raids and bosses; turn it off for training spots like rock crabs.",
		position = 11
	)
	default boolean pauseInCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_NAMES,
		name = "Names over players",
		description = "On: names float over where people are drawn, not over where they stand. Uses Player Indicators' settings (who, colours, icons). Turn off Player Indicators' own names so you don't see two.",
		position = 8
	)
	default boolean namesFollowPlayers()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_HOVER,
		name = "Hover shows who",
		description = "On: hover someone who's been moved to see their name and level. You still click people where they really stand.",
		position = 9
	)
	default boolean hoverShowsWho()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_HOVER_ARROW,
		name = "Arrow to their tile",
		description = "On: hover someone who's been moved and an arrow points to where they really stand.",
		position = 10
	)
	default boolean hoverArrow()
	{
		return false;
	}

	@Range(min = MIN_HIDE_BELOW, max = MAX_HIDE_BELOW)
	@ConfigItem(
		keyName = KEY_HIDE_BELOW,
		name = "Hide players below level",
		description = "Hide anyone under this combat level, bodies and overhead chat, like Entity Hider. Set 4 to hide the level-3 bots at the Grand Exchange. Friends, friends chat and clan are never hidden. Off in PvP areas and while you fight.",
		position = 12
	)
	default int hideBelowLevel()
	{
		return MIN_HIDE_BELOW;
	}

	@ConfigItem(
		keyName = KEY_HIDE_CHAT,
		name = "Hide their chat too",
		description = "On: the players hidden by 'Hide players below' are also taken out of your chat box. Does nothing while that's Off.",
		position = 13
	)
	default boolean hideTheirChat()
	{
		return true;
	}

	@Range(min = 0, max = MAX_CHAT_RANGE)
	@ConfigItem(
		keyName = KEY_CHAT_RANGE,
		name = "Hide chat further than",
		description = "Hide public chat from anyone further away than this many tiles, over their heads and in the chat box. Handy at an anvil next to a noisy bank. Friends, friends chat and clan always show. 0 is off.",
		position = 14
	)
	default int hideChatFurtherThan()
	{
		return 0;
	}

	@ConfigItem(
		keyName = KEY_OTHER_ALCHERS,
		name = "Alchers behavior",
		description = "How other players' skilling spells look to you: alching, superheat, charge orb and enchanting. Normal: as the game shows them. Still: no spell animation or glow, so a crowd of alchers stops flashing. Party: each cast becomes a random dance. Your own spells never change.",
		position = 14
	)
	default Alchers otherAlchers()
	{
		return Alchers.NORMAL;
	}

	@ConfigItem(
		keyName = KEY_SHOW_SIDEBAR,
		name = "Show sidebar button",
		description = "Off: no Personal Space button in the sidebar. Every setting is still here.",
		position = 15
	)
	default boolean showSidebarButton()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_SMALL_GROUPS_CLOSE,
		name = "Auto spacing",
		description = "On (recommended): the gaps suit the place, staying close at banks, counters, walls and fires and for small groups. Off: the Spacing slider sets every gap, everywhere, which is handy for photos.",
		position = 6
	)
	default boolean smallGroupsClose()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_POSE,
		name = "Pose for 2 or 3 players",
		description = "How 2 or 3 people standing together are turned. Natural: the way they really face. Angled: half turned to each other. Facing: face to face.",
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
		section = TROUBLESHOOTING,
		hidden = true
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
		section = TROUBLESHOOTING,
		hidden = true
	)
	default int testOffset()
	{
		return 32;
	}

	@ConfigItem(
		keyName = KEY_DEPTH,
		name = "Depth between players",
		description = "On (recommended): in a crowd, shields, capes and hats stay properly in front of or behind the people next to them as you turn the camera. Off: everyone is drawn whole, one over another, so gear pops between people. Only worth turning off if something looks wrong. GPU plugin only.",
		position = 5
	)
	default boolean depthBetweenPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_IDLE_SIT,
		name = "Idle players sit",
		description = "Off by default. On: other players who've done nothing for 30 seconds (AFK) sit down. They stand up as soon as they move, talk or do something. Only on your screen.",
		position = 5
	)
	default boolean idlePlayersSit()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_CLICK_PAST,
		name = "Use items through players",
		description = "On (recommended): when you use an item or spell on something (a fire, a booth), moved or hidden people don't take the left-click. Right-click still lists them.",
		position = 5
	)
	default boolean clickPastMoved()
	{
		return true;
	}
}
