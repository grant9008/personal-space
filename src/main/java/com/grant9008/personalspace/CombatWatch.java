package com.grant9008.personalspace;

/**
 * Decides whether you count as in combat, so spreading pauses while you fight.
 *
 * <p>You're fighting if your own health bar is showing (something hit you), or if you're attacking
 * something whose health bar is showing. The second matters in multi-combat: you can hit a monster
 * that is attacking someone else, and your own health bar never appears. Skilling targets such as
 * fishing spots have no health bar, so fishing or talking to someone doesn't count.
 *
 * <p>After a fight you stay "in combat" for {@link #LINGER_TICKS} more ticks, so crowds don't
 * snap in and out between hits or kills.
 *
 * <p>Client thread only. Knows nothing about RuneLite; unit tested.
 */
final class CombatWatch
{
	/** Ticks you stay in combat after the last sign of fighting: 8 ticks, about 5 seconds. */
	static final int LINGER_TICKS = 8;

	private int lastFightTick = Integer.MIN_VALUE / 2;

	/**
	 * Record this tick and say whether you count as in combat.
	 *
	 * @param ownHealthBar    your own health bar is showing
	 * @param attacking       you're interacting with a monster or player
	 * @param targetHealthBar the health bar of what you're interacting with is showing
	 * @param tick            this tick's number
	 */
	boolean update(boolean ownHealthBar, boolean attacking, boolean targetHealthBar, int tick)
	{
		if (ownHealthBar || (attacking && targetHealthBar))
		{
			lastFightTick = tick;
		}
		return tick - lastFightTick <= LINGER_TICKS;
	}

	void clear()
	{
		lastFightTick = Integer.MIN_VALUE / 2;
	}
}
