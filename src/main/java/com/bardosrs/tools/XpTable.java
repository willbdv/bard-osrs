package com.bardosrs.tools;

/** The OSRS experience table. It is the same for every skill. */
public final class XpTable
{
	/** Levels above 99 are "virtual" levels; 126 is the last reachable one (200M xp cap). */
	public static final int MAX_LEVEL = 126;
	public static final long MAX_XP = 200_000_000L;

	/** Total xp needed to reach each level; index = level, entry 0 unused. */
	private static final int[] XP = new int[MAX_LEVEL + 1];

	static
	{
		double points = 0;
		for (int level = 1; level < MAX_LEVEL; level++)
		{
			points += Math.floor(level + 300 * Math.pow(2, level / 7.0));
			XP[level + 1] = (int) Math.floor(points / 4);
		}
	}

	private XpTable()
	{
	}

	public static int xpForLevel(int level)
	{
		if (level < 1 || level > MAX_LEVEL)
		{
			throw new IllegalArgumentException("level must be between 1 and " + MAX_LEVEL);
		}
		return XP[level];
	}

	/** The highest level whose xp requirement is at most {@code xp}. */
	public static int levelForXp(long xp)
	{
		int level = 1;
		while (level < MAX_LEVEL && XP[level + 1] <= xp)
		{
			level++;
		}
		return level;
	}
}
