package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Snapshot} into plain-English status for the sidebar: one headline saying whether
 * the plugin is working and, if not, what to do; a list of individual checks; and a text report
 * that can be copied and pasted when asking for help. Pure logic, unit tested.
 */
final class StatusSummary
{
	enum Level
	{
		OK,
		WAITING,
		PAUSED,
		PROBLEM
	}

	static final class Headline
	{
		final Level level;
		final String title;
		final String detail;

		Headline(Level level, String title, String detail)
		{
			this.level = level;
			this.title = title;
			this.detail = detail;
		}
	}

	static final class Check
	{
		final Level level;
		final String label;
		final String value;

		Check(Level level, String label, String value)
		{
			this.level = level;
			this.label = label;
			this.value = value;
		}
	}

	private StatusSummary()
	{
	}

	static Headline headline(Snapshot s)
	{
		if (!s.active)
		{
			return new Headline(Level.PAUSED, "Off",
				"Flip the switch above to spread out crowds again.");
		}
		if (s.gate == Snapshot.Gate.NOT_LOGGED_IN)
		{
			return new Headline(Level.WAITING, "Waiting for you to log in", "Crowds are spread out once you're in game.");
		}
		if (s.gate != Snapshot.Gate.SAFE)
		{
			return new Headline(Level.PAUSED, "Paused for safety",
				s.gate.label + ". Everyone is shown where they really stand until then.");
		}
		if (s.renderer == null)
		{
			return new Headline(Level.PROBLEM, "Needs the GPU plugin",
				"Turn on the GPU plugin (or 117 HD) in RuneLite's plugin list.");
		}
		if (!s.hooked)
		{
			return new Headline(Level.PROBLEM, "Couldn't connect",
				"Turn Personal Space off and on again in the plugin list.");
		}
		if (s.noPlayerDrawsSustained)
		{
			return new Headline(Level.PROBLEM, "Something's not right",
				"Players aren't coming through. Open Troubleshooting below and press Copy report.");
		}

		if (s.mode == PersonalSpaceConfig.Mode.TEST_SHIFT_ME)
		{
			if (s.nudgedDrawsPerSec > 0)
			{
				return new Headline(Level.OK, "Test mode is on",
					"Your character is drawn " + s.testOffset + " units to the east. Turn test mode off in Troubleshooting when you're done.");
			}
			if (s.testOffset == 0)
			{
				return new Headline(Level.WAITING, "Test mode is on",
					"The test distance is 0. Drag the slider in Troubleshooting to see your character move.");
			}
			if (s.nothingMovedSustained)
			{
				return new Headline(Level.PROBLEM, "Test mode isn't moving you",
					"Open Troubleshooting below and press Copy report.");
			}
			return new Headline(Level.WAITING, "Test mode is on", "Moving your character now.");
		}

		if (s.stackedTiles == 0)
		{
			return new Headline(Level.WAITING, "No crowds here",
				"When players stand on the same tile, they'll be spread out automatically.");
		}
		if (s.nudgedDrawsPerSec + s.revealedDrawsPerSec > 0)
		{
			return new Headline(Level.OK, "Spreading " + plural(s.moving, "player") + " on " + plural(s.stackedTiles, "tile"),
				"Crowded tiles are opened up so you can see everyone.");
		}
		if (s.nothingMovedSustained)
		{
			return new Headline(Level.PROBLEM, "Something's not right",
				"Crowds were found but nobody was spread out. Open Troubleshooting below and press Copy report.");
		}
		return new Headline(Level.WAITING, "Found " + plural(s.stackedTiles, "crowded tile"), "Spreading them out now.");
	}

	static List<Check> checks(Snapshot s)
	{
		List<Check> out = new ArrayList<>();
		out.add(new Check(s.active ? Level.OK : Level.PAUSED, "Effect", s.active ? "On" : "Paused"));
		boolean loggedIn = s.gate != Snapshot.Gate.NOT_LOGGED_IN;
		out.add(new Check(loggedIn ? Level.OK : Level.WAITING, "Logged in", loggedIn ? "Yes" : "No"));
		if (loggedIn)
		{
			out.add(new Check(s.gate == Snapshot.Gate.SAFE ? Level.OK : Level.PAUSED, "Safety", s.gate.label));
		}
		out.add(new Check(s.renderer != null ? Level.OK : Level.PROBLEM, "Renderer", s.renderer != null ? s.renderer : "None (turn on GPU)"));
		out.add(new Check(s.hooked ? Level.OK : (s.renderer == null ? Level.WAITING : Level.PROBLEM), "Connected", s.hooked ? "Yes" : "No"));
		if (loggedIn && s.hooked)
		{
			out.add(new Check(s.playerDrawsPerSec > 0 ? Level.OK : (s.noPlayerDrawsSustained ? Level.PROBLEM : Level.WAITING),
				"Players drawn", s.playerDrawsPerSec + " / sec"));
		}
		out.add(new Check(Level.WAITING, "Players nearby", Integer.toString(s.nearby)));
		out.add(new Check(Level.WAITING, "Standing still", Integer.toString(s.still)));
		out.add(new Check(s.stackedTiles > 0 ? Level.OK : Level.WAITING, "Stacked tiles", Integer.toString(s.stackedTiles)));
		out.add(new Check(s.moving > 0 ? Level.OK : Level.WAITING, "Being spread", Integer.toString(s.moving)));
		boolean anyDrawn = s.nudgedDrawsPerSec + s.revealedDrawsPerSec > 0;
		Level drawnLevel = anyDrawn ? Level.OK : (s.nothingMovedSustained ? Level.PROBLEM : Level.WAITING);
		out.add(new Check(drawnLevel, "Drawn moved", s.nudgedDrawsPerSec + " / sec"));
		if (s.mode == PersonalSpaceConfig.Mode.SPREAD)
		{
			out.add(new Check(drawnLevel, "Hidden shown", s.revealedDrawsPerSec + " / sec"));
		}
		if (s.playersInOtherCalls > 0 || s.offThreadDraws > 0 || s.revealErrors > 0 || s.skippedIds > 0)
		{
			out.add(new Check(Level.PROBLEM, "Oddities", "see report"));
		}
		return out;
	}

	static String report(Snapshot s)
	{
		Headline h = headline(s);
		StringBuilder b = new StringBuilder();
		b.append("Personal Space ").append(s.pluginVersion).append(" report\n");
		b.append("Status: ").append(h.level).append(" - ").append(h.title).append('\n');
		b.append("Settings: effect ").append(s.active ? "on" : "paused")
			.append(", mode ").append(s.mode)
			.append(", arrangement ").append(s.arrangement)
			.append(", spacing ").append(s.spacing)
			.append(", max per tile ").append(s.maxStack)
			.append(", move me ").append(yesNo(s.includeLocal))

			.append(", test offset ").append(s.testOffset).append('\n');
		b.append("Safety: ").append(s.gate.label).append('\n');
		b.append("Renderer: ").append(s.renderer == null ? "none" : s.renderer)
			.append(", connected ").append(yesNo(s.hooked)).append('\n');
		b.append("Player draws/sec: ").append(s.playerDrawsPerSec)
			.append(", drawn moved/sec: ").append(s.nudgedDrawsPerSec)
			.append(", hidden shown/sec: ").append(s.revealedDrawsPerSec)
			.append(", other-scene draws/sec: ").append(s.sceneMismatchesPerSec)
			.append(", players in other calls (should be 0): ").append(s.playersInOtherCalls)
			.append(", off-thread draws (should be 0): ").append(s.offThreadDraws)
			.append(", reveal errors: ").append(s.revealErrors)
			.append(", walk frames drawn: ").append(s.walkDraws)
			.append(", no walk (busy): ").append(s.walkSkippedBusy)
			.append(", no walk (animation not loaded): ").append(s.walkSkippedNoAnimation)
			.append(", held for confirmation: ").append(s.probeHeld)
			.append(", never confirmed: ").append(s.probeGaveUp)
			.append(", tile shape changes: ").append(s.shapeChanges)
			.append(", spot moves: ").append(s.spotMoves)
			.append(", no draws sustained: ").append(yesNo(s.noPlayerDrawsSustained))
			.append(", nothing moved sustained: ").append(yesNo(s.nothingMovedSustained)).append('\n');
		b.append("Nearby: ").append(s.nearby)
			.append(", standing still: ").append(s.still)
			.append(", stacked tiles: ").append(s.stackedTiles)
			.append(", being spread: ").append(s.moving)
			.append(", skipped ids: ").append(s.skippedIds).append('\n');
		return b.toString();
	}

	private static String plural(int n, String word)
	{
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private static String yesNo(boolean b)
	{
		return b ? "yes" : "no";
	}
}
