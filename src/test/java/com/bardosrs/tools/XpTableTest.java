package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XpTableTest
{
	@Test
	public void matchesKnownRunescapeValues()
	{
		assertEquals(0, XpTable.xpForLevel(1));
		assertEquals(83, XpTable.xpForLevel(2));
		assertEquals(1154, XpTable.xpForLevel(10));
		assertEquals(45529, XpTable.xpForLevel(42));
		assertEquals(101333, XpTable.xpForLevel(50));
		assertEquals(6517253, XpTable.xpForLevel(92));
		assertEquals(13034431, XpTable.xpForLevel(99));
		assertEquals(14391160, XpTable.xpForLevel(100));
		assertEquals(188884740, XpTable.xpForLevel(126));
	}

	@Test
	public void levelForXpFindsTheHighestReachedLevel()
	{
		assertEquals(1, XpTable.levelForXp(0));
		assertEquals(1, XpTable.levelForXp(82));
		assertEquals(2, XpTable.levelForXp(83));
		assertEquals(98, XpTable.levelForXp(13034430));
		assertEquals(99, XpTable.levelForXp(13034431));
		assertEquals(126, XpTable.levelForXp(200_000_000L));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsLevelZero()
	{
		XpTable.xpForLevel(0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsLevelAboveTheCap()
	{
		XpTable.xpForLevel(127);
	}
}
