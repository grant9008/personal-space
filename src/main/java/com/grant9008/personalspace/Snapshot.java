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
	boolean drawMeInFront;
	boolean depthTest;
	/** Idle players sit, and how many are sat down now. */
	boolean idleSit;
	int sitting;
	boolean clickPast;
	boolean names;
	boolean hover;
	boolean hoverArrow;
	int hideBelow;
	boolean hideChat;
	int chatRange;
	PersonalSpaceConfig.Alchers alchers = PersonalSpaceConfig.Alchers.NORMAL;
	/** Other players' alch casts drawn still or as an emote, this session. */
	long alchersRestyled;
	boolean smallGroupsClose = true;
	boolean pauseInCombat = true;
	PersonalSpaceConfig.Pose pose = PersonalSpaceConfig.Pose.NATURAL;
	/** Plain words for the shape you are standing in right now, for the sidebar; null when you aren't in one. */
	String yourShape;
	/** Players drawn mid-step with their walk animation; total. */
	long walkDraws;
	/** Players and NPCs drawn farthest from the camera first, so whoever is nearer is in front; total. */
	long orderedDraws;
	long depthDraws;
	/** Drawn without depth although they overlapped someone, because no depth spacer could be made; expected 0. */
	long depthNoSpacer;
	long keptDips;
	long probeBlackouts;
	long droppedUndrawn;
	long resets;
	long planDips;
	long shimInstalls;
	long blinkNoMiddle;
	long blinkUnconfirmed;
	long blinkOthersHid;
	long blinkMoved;
	long blinkOther;
	long keptWithoutModel;
	long spotLosses;
	long insideYouHidden;
	long swapsCameraMoving;
	long swapsCameraStill;
	long stillSwapsMoved;
	long stillSwapsOther;
	long stillSwapsWalking;
	long stillSwapsStanding;
	long swapsBack;
	long skippedUnconfirmed;
	long skippedOthersHid;
	long skippedMoved;
	/** Of the probe's blackouts and the unconfirmed stackmates left undrawn, those at a fire; totals. */
	long fireBlackouts;
	long fireUnconfirmed;
	/** Frames someone the game drew in the middle of a tile gave way to whoever is shown there; total. */
	long middleKeptSteady;
	/** Waits for a break in someone's action: ended in a break, ran out, or not started (never pauses); totals. */
	long waitBreaks;
	long waitTimeouts;
	long waitSkipped;
	/** Who went longest undrawn lately and why, or null. */
	String longestHidden;
	/** Who flickered most lately (undrawn for under a second, again and again) and why, or null. */
	String flickering;
	/** Players drawn at their spot although the game had no room for them on their tile; frames, total. */
	long refusedDrawn;
	/** ...of which alone on their tile, not moved at all. */
	long aloneRefusedDrawn;
	/** ...and walking across a full tile (a campfire's). */
	long movingRefusedDrawn;
	/** Pets and other followers drawn though the game left them out of a crowded or full tile; frames, total. */
	long followersRefusedDrawn;
	/** Pets and other followers drawn beside whoever stands on them, or on their way there or back; frames, total. */
	long followersBesideDrawn;
	/** Walks drawn whose body kept the same shape too long to be walking: all, and of busy people. */
	long walksLegsStill;
	long walksLegsStillBusy;
	long[] legsStillBy = new long[4];
	String legsStillAnims = "";
	/** Who slid most lately (drawn moving with no walk to match), how and how far, or null. */
	String sliding;
	/** Frames someone walking off a spot was drawn with legs keeping pace with their body; total. */
	long legsMatched;
	/** Frames someone walking for real while busy was drawn walking, their action left out; total. */
	long movingBusyWalks;
	/** Frames where draws were kept back but the renderer never asked for its opaque pass; should stay 0. */
	long passMissedFrames;
	/** Times someone waiting in the middle of a tile in front of you was left undrawn; total. */
	long hiddenInYourWay;
	/** Moving players drawn without a walk animation because they were busy and busy walks were off; total. */
	long walkSkippedBusy;
	/** Moving players drawn with their own action, not a walk, because they were fighting; frames, total. */
	long walkKeptFighting;
	/** Moving players shown walking although busy with an emote or action; total. */
	long walkBusy;
	/** Whether busy walks were switched off because the game reported an animation change from one. */
	boolean busyWalksOff;
	String busyAnimations = "";
	long driftFrames;
	/** Frames someone walking off was kept off a wall: their offset ran out faster, they waited at it, they followed their way round it, or it was cut back at once. */
	long wallHurries;
	long wallHolds;
	long wallFollows;
	long wallShrinks;
	long slideBusy;
	long slideWalkingOff;
	long slideOther;
	long slideBusyArriving;
	long slideBusyBetween;
	long slideBusyToMiddle;
	/** Moving players drawn without a walk animation because it couldn't be loaded; total. */
	long walkSkippedNoAnimation;
	int testOffset;

	// safety
	Gate gate = Gate.NOT_LOGGED_IN;

	// renderer hook
	/** Display name of the renderer we sit in front of, or null when no GPU renderer is running. */
	String renderer;
	/** Whether 117 HD is switched on in the plugin list (running or not), so we don't offer the GPU plugin over it. */
	boolean hdEnabled;
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
	long probeBackOffs;
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
	/** Of those, how many are aboard a boat. */
	int aboard;
	int still;
	int stackedTiles;
	int moving;
	int skippedIds;
}
