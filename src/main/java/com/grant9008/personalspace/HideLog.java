package com.grant9008.personalspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics for the copy report: who has gone undrawn longest lately, and why. One streak per
 * player: the frames in a row they were left undrawn for the same reason.
 *
 * <p>Kept by the plugin rather than the draw shim, which is made afresh on every map load: a
 * teleport wiped the minute just before it. Client thread only. Knows nothing about RuneLite so
 * it can be unit tested.
 */
final class HideLog
{
	static final int NO_SPOT_OUT_OF_SIGHT = 1;
	static final int WAITING_OUT_OF_SIGHT = 2;
	static final int NO_SPOT_MIDDLE_TAKEN = 3;
	static final int WAITING_MIDDLE_TAKEN = 4;
	static final int UNCONFIRMED = 5;
	static final int OTHER_PLUGIN = 6;
	/** Drawn away from their tile, but the game didn't hand them over to be drawn this frame. */
	static final int NOT_HANDED_OVER = 7;
	/** Handed over by the game, then left undrawn on a path none of the others name. */
	static final int SKIPPED_OTHER = 8;
	/** Not handed over, their real tile off screen though they're drawn on it: drawn anyway, so only counted if that fails. */
	static final int TILE_OFF_SCREEN = 9;
	private static final String[] REASONS = {
		"",
		"no spot, middle not drawn there",
		"waiting for a break, middle not drawn there",
		"no spot, middle shown to",
		"waiting for a break, middle shown to",
		"not shown by the game lately",
		"hidden by another plugin",
		"the game didn't draw them",
		"left undrawn, reason unknown",
		"the game didn't draw them, their real tile off screen",
	};
	/** How many short streaks (flickers) are kept for the report; the oldest go first. */
	static final int MAX_FLICKERS = 512;
	/** A streak shorter than this (a second, in game cycles of 20 ms) isn't worth reporting. */
	static final int REPORT_CYCLES = 50;
	/** How far back finished streaks are reported: a minute, in game cycles. */
	static final int WINDOW_CYCLES = 3000;
	static final int MAX_HISTORY = 64;

	private static final class Streak
	{
		final String name;
		final int why;
		final String to;
		final int start;
		final int end;
		final boolean atFire;
		final boolean ongoing;

		Streak(String name, int why, String to, int start, int end, boolean atFire, boolean ongoing)
		{
			this.name = name;
			this.why = why;
			this.to = to;
			this.start = start;
			this.end = end;
			this.atFire = atFire;
			this.ongoing = ongoing;
		}

		int length()
		{
			return end - start;
		}
	}

	private final int[] frame = new int[OffsetTable.CAPACITY];
	private final int[] start = new int[OffsetTable.CAPACITY];
	private final int[] end = new int[OffsetTable.CAPACITY];
	private final byte[] why = new byte[OffsetTable.CAPACITY];
	private final String[] name = new String[OffsetTable.CAPACITY];
	private final int[] toId = new int[OffsetTable.CAPACITY];
	private final String[] to = new String[OffsetTable.CAPACITY];
	private final boolean[] atFire = new boolean[OffsetTable.CAPACITY];
	private final ArrayDeque<Streak> history = new ArrayDeque<>();
	/** Streaks shorter than {@link #REPORT_CYCLES}: someone flickering, out for a frame or a few. */
	private final ArrayDeque<Streak> flickers = new ArrayDeque<>();

	static final int SLIDE_WALKING_OFF = 0;
	static final int SLIDE_BUSY = 1;
	static final int SLIDE_OTHER = 2;
	/** Drawn walking, but their body didn't change shape: the walk didn't show. */
	static final int SLIDE_LEGS_STILL = 3;
	private static final String[] SLIDE_KINDS = {"walking off a spot", "busy", "no walk to show", "drawn walking but legs still"};
	private static final int MAX_SLIDES = 2048;

	private static final class Slide
	{
		final String name;
		final int kind;
		final int cycle;
		final double distance;
		final boolean atFire;

		Slide(String name, int kind, int cycle, double distance, boolean atFire)
		{
			this.name = name;
			this.kind = kind;
			this.cycle = cycle;
			this.distance = distance;
			this.atFire = atFire;
		}
	}

	/** Frames someone was drawn moving across the ground with no walk to match: who, how, how far. */
	private final ArrayDeque<Slide> slides = new ArrayDeque<>();

	/** Someone was drawn moving this far this frame with no walk to match. */
	void noteSlide(String who, int kind, int cycle, double distance, boolean atFire)
	{
		while (!slides.isEmpty() && (slides.size() >= MAX_SLIDES || cycle - slides.peekFirst().cycle > WINDOW_CYCLES))
		{
			slides.removeFirst();
		}
		slides.addLast(new Slide(who == null ? "someone" : who, kind, cycle, distance, atFire));
	}

	/** Who slid most in the last minute, mostly how, and how far in all: the three who did most. Null if nobody. */
	String sliding(int cycle)
	{
		Map<String, double[]> by = new HashMap<>();
		for (Slide s : slides)
		{
			if (cycle - s.cycle > WINDOW_CYCLES)
			{
				continue;
			}
			// frames, distance, then frames of each kind, then 1 if any at a fire
			double[] d = by.computeIfAbsent(s.name, n -> new double[3 + SLIDE_KINDS.length]);
			d[0]++;
			d[1] += s.distance;
			d[2 + s.kind]++;
			d[2 + SLIDE_KINDS.length] = s.atFire ? 1 : d[2 + SLIDE_KINDS.length];
		}
		if (by.isEmpty())
		{
			return null;
		}
		List<Map.Entry<String, double[]>> top = new ArrayList<>(by.entrySet());
		top.sort((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(3, top.size()); i++)
		{
			double[] d = top.get(i).getValue();
			int most = 0;
			for (int k = 1; k < SLIDE_KINDS.length; k++)
			{
				most = d[2 + k] > d[2 + most] ? k : most;
			}
			out.append(i == 0 ? "" : ", ").append(top.get(i).getKey()).append(' ')
				.append(String.format("%.1f", d[1] / 128.0)).append(" tiles over ").append((int) d[0]).append(" frames (")
				.append(SLIDE_KINDS[most]).append(d[2 + SLIDE_KINDS.length] > 0 ? ", at a fire" : "").append(')');
		}
		return out.toString();
	}

	HideLog()
	{
		Arrays.fill(frame, Integer.MIN_VALUE / 2);
	}

	/** True if this player has already been noted as undrawn this frame. */
	boolean notedThisFrame(int id, int frameNow)
	{
		return id < 0 || id >= OffsetTable.CAPACITY || frame[id] == frameNow;
	}

	/** True if this player was left undrawn this frame or the one before. */
	boolean notedLately(int id, int frameNow)
	{
		return id >= 0 && id < OffsetTable.CAPACITY && frame[id] <= frameNow && frame[id] >= frameNow - 1;
	}

	/** True if this player was left undrawn last frame for the same reason: the same streak goes on. */
	boolean continues(int id, int frameNow, int reason)
	{
		return name[id] != null && frame[id] == frameNow - 1 && why[id] == reason;
	}

	void extend(int id, int frameNow, int cycle)
	{
		frame[id] = frameNow;
		end[id] = cycle;
	}

	/** Who the middle went to in this player's streak; -1 if nobody. */
	int toId(int id)
	{
		return toId[id];
	}

	/** The middle changed hands during the streak: name the one who has it now. */
	void setTo(int id, int holder, String holderName)
	{
		toId[id] = holder;
		to[id] = holderName;
	}

	void start(int id, int frameNow, int cycle, int reason, String who, int holder, String holderName, boolean fire)
	{
		finish(id, cycle);
		frame[id] = frameNow;
		start[id] = cycle;
		end[id] = cycle;
		why[id] = (byte) reason;
		name[id] = who == null ? "someone" : who;
		toId[id] = holder;
		to[id] = holderName;
		atFire[id] = fire;
	}

	/** Close the streaks of everyone drawn again since. */
	private void finishEnded(int frameNow, int cycle)
	{
		for (int id = 0; id < OffsetTable.CAPACITY; id++)
		{
			if (name[id] != null && frame[id] < frameNow - 1)
			{
				finish(id, cycle);
			}
		}
	}

	/**
	 * Who flickered most in the last minute (undrawn for under a second, again and again), and mostly
	 * why: the three who did it most often. Null if nobody did.
	 */
	String flickering(int frameNow, int cycle)
	{
		finishEnded(frameNow, cycle);
		Map<String, int[]> counts = new HashMap<>();
		Map<String, Boolean> fire = new HashMap<>();
		for (Streak s : flickers)
		{
			if (cycle - s.end > WINDOW_CYCLES)
			{
				continue;
			}
			int[] c = counts.computeIfAbsent(s.name, n -> new int[REASONS.length]);
			c[0]++;
			if (s.why > 0 && s.why < REASONS.length)
			{
				c[s.why]++;
			}
			fire.merge(s.name, s.atFire, Boolean::logicalOr);
		}
		if (counts.isEmpty())
		{
			return null;
		}
		List<Map.Entry<String, int[]>> top = new ArrayList<>(counts.entrySet());
		top.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(3, top.size()); i++)
		{
			int[] c = top.get(i).getValue();
			int most = 1;
			for (int w = 2; w < REASONS.length; w++)
			{
				most = c[w] > c[most] ? w : most;
			}
			out.append(i == 0 ? "" : ", ").append(top.get(i).getKey()).append(' ').append(c[0])
				.append(c[0] == 1 ? " time (" : " times (").append(REASONS[most])
				.append(most == NO_SPOT_MIDDLE_TAKEN || most == WAITING_MIDDLE_TAKEN ? " someone else" : "")
				.append(Boolean.TRUE.equals(fire.get(top.get(i).getKey())) ? ", at a fire" : "").append(')');
		}
		return out.toString();
	}

	/** Close this player's streak, keeping it if it was long enough to report. */
	private void finish(int id, int cycle)
	{
		if (name[id] != null && end[id] - start[id] < REPORT_CYCLES)
		{
			while (!flickers.isEmpty() && (flickers.size() >= MAX_FLICKERS || cycle - flickers.peekFirst().end > WINDOW_CYCLES))
			{
				flickers.removeFirst();
			}
			flickers.addLast(new Streak(name[id], why[id], to[id], start[id], end[id], atFire[id], false));
		}
		if (name[id] != null && end[id] - start[id] >= REPORT_CYCLES)
		{
			// Old ones first, then the shortest: the long vanish this exists to catch must stay.
			while (!history.isEmpty() && cycle - history.peekFirst().end > WINDOW_CYCLES)
			{
				history.removeFirst();
			}
			history.addLast(new Streak(name[id], why[id], to[id], start[id], end[id], atFire[id], false));
			if (history.size() > MAX_HISTORY)
			{
				Streak shortest = history.peekFirst();
				for (Streak s : history)
				{
					shortest = s.length() < shortest.length() ? s : shortest;
				}
				history.remove(shortest);
			}
		}
		name[id] = null;
	}

	/**
	 * The longest undrawn streaks seen in the last minute, still going or not: the longest of each
	 * reason, then the three longest of those, so people kept out of a full tile by design don't
	 * crowd out someone vanishing. Null if nobody was undrawn for a second or more.
	 */
	String longest(int frameNow, int cycle)
	{
		finishEnded(frameNow, cycle);
		List<Streak> streaks = new ArrayList<>();
		for (Streak s : history)
		{
			if (cycle - s.end <= WINDOW_CYCLES)
			{
				streaks.add(s);
			}
		}
		for (int id = 0; id < OffsetTable.CAPACITY; id++)
		{
			if (name[id] != null && end[id] - start[id] >= REPORT_CYCLES)
			{
				streaks.add(new Streak(name[id], why[id], to[id], start[id], end[id], atFire[id], true));
			}
		}
		Map<Integer, Streak> longestOfEach = new HashMap<>();
		for (Streak s : streaks)
		{
			Streak best = longestOfEach.get(s.why);
			if (best == null || s.length() > best.length())
			{
				longestOfEach.put(s.why, s);
			}
		}
		if (longestOfEach.isEmpty())
		{
			return null;
		}
		List<Streak> top = new ArrayList<>(longestOfEach.values());
		top.sort((a, b) -> Integer.compare(b.length(), a.length()));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(3, top.size()); i++)
		{
			Streak s = top.get(i);
			out.append(i == 0 ? "" : ", ").append(s.name).append(' ')
				.append(String.format("%.1f", s.length() / 50.0)).append(" s (")
				.append(s.why > 0 && s.why < REASONS.length ? REASONS[s.why] : "other");
			if (s.why == NO_SPOT_MIDDLE_TAKEN || s.why == WAITING_MIDDLE_TAKEN)
			{
				out.append(' ').append(s.to == null ? "someone" : s.to);
			}
			out.append(s.atFire ? ", at a fire" : "").append(s.ongoing ? ", still hidden" : "").append(')');
		}
		return out.toString();
	}
}
