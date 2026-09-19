package com.grant9008.personalspace;

/**
 * Everything the sidebar panel shows, captured at one moment on the client thread and then handed
 * to the Swing thread. A fresh instance is built for every update and never changed afterwards.
 */
final class Snapshot
{
	/** Why the effect is switched off by the safety rules, or {@link #SAFE}. */
	enum Gate
	{
		SAFE("Safe area"),
		NOT_LOGGED_IN("Not logged in"),
		WILDERNESS("In the Wilderness"),
		PVP_AREA("In a PvP area"),
		PVP_WORLD("On a PvP-type world"),
		PVP_ACTIVITY("Players can fight here"),
		IN_COMBAT("You are in combat");

		final String label;

		Gate(String label)
		{
			this.label = label;
		}
	}

	String pluginVersion = PersonalSpacePlugin.VERSION;

	// settings
	boolean active;
	PersonalSpaceConfig.Arrangement arrangement = PersonalSpaceConfig.Arrangement.AUTO;
	PersonalSpaceConfig.Mode mode = PersonalSpaceConfig.Mode.SPREAD;
	int spacing = PersonalSpaceConfig.SPACING_WIDE;
	int maxStack;
	boolean includeLocal;
	boolean smallGroupsClose = true;
	boolean pauseInCombat = true;
	PersonalSpaceConfig.Pose pose = PersonalSpaceConfig.Pose.NATURAL;
	/** Plain words for the shape you are standing in right now, for the sidebar; null when you aren't in one. */
	String yourShape;
	/** Players drawn mid-step with their walk animation; total. */
	long walkDraws;
	/** Players and NPCs drawn farthest from the camera first, so whoever is nearer is in front; total. */
	long orderedDraws;
	/** Frames where draws were kept back but the renderer never asked for its opaque pass; should stay 0. */
	long passMissedFrames;
	/** Times someone waiting in the middle of a tile in front of you was left undrawn; total. */
	long hiddenInYourWay;
	/** Moving players drawn without a walk animation because they were busy with an emote or action; total. */
	long walkSkippedBusy;
	/** Moving players drawn without a walk animation because it couldn't be loaded; total. */
	long walkSkippedNoAnimation;
	int testOffset;

	// safety
	Gate gate = Gate.NOT_LOGGED_IN;

	// renderer hook
	/** Display name of the renderer we sit in front of, or null when no GPU renderer is running. */
	String renderer;
	boolean hooked;
	int playerDrawsPerSec;
	/** Draws of players the game showed, drawn at a ring slot. */
	int nudgedDrawsPerSec;
	/** Draws of stackmates the game hid, drawn by Personal Space. */
	int revealedDrawsPerSec;
	int sceneMismatchesPerSec;
	/** Players ever seen in drawDynamic or the legacy draw call. Should always be 0; players are drawn through drawTemp. */
	long playersInOtherCalls;
	/** Player draws ever made off the client thread. Should always be 0. */
	long offThreadDraws;
	/** Errors ever hit while drawing a hidden stackmate. */
	long revealErrors;
	/** Players briefly held back so the game confirms the rest of their tile; total. */
	long probeHeld;
	/** Players never confirmed by the game (e.g. hidden by the server), so never drawn; total. */
	long probeGaveUp;
	/** Times a tile switched between a row and a crowd, or a row turned; total. */
	long shapeChanges;
	/** Times a player with a spot was moved to a different spot to fill a gap; total. */
	long spotMoves;
	/** Players standing still on a crowded tile whom the game isn't showing, last tick. */
	int unseenStacked;
	/** What was decided for the spread tile nearest to you, or null if none. */
	String nearestTile;
	/** No player draws at all for over a second while connected and logged in. */
	boolean noPlayerDrawsSustained;
	/** Players (or you, in test mode) should be shifted but nothing was drawn shifted for over a second. */
	boolean nothingMovedSustained;

	// what the last game tick found
	int nearby;
	int still;
	int stackedTiles;
	int moving;
	int skippedIds;
}
