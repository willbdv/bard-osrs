package com.bardosrs.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ItemCodecTest
{
	@Test
	public void roundTrips()
	{
		String text = ItemCodec.encode(new int[]{4151, 995, 1925}, new int[]{1, 2345678, 12});
		assertEquals("4151:1,995:2345678,1925:12", text);
		List<int[]> entries = ItemCodec.decode(text);
		assertEquals(3, entries.size());
		assertArrayEquals(new int[]{995, 2345678}, entries.get(1));
	}

	@Test
	public void emptyAndMissingDataDecodeToNothing()
	{
		assertTrue(ItemCodec.decode(null).isEmpty());
		assertTrue(ItemCodec.decode("").isEmpty());
		assertEquals("", ItemCodec.encode(new int[0], new int[0]));
	}

	@Test
	public void damagedEntriesAreSkippedOneByOne()
	{
		List<int[]> entries = ItemCodec.decode("garbage,4151:1,:5,7:0,x:y,12:-3,995:500,3:");
		assertEquals(2, entries.size());
		assertArrayEquals(new int[]{4151, 1}, entries.get(0));
		assertArrayEquals(new int[]{995, 500}, entries.get(1));
	}

	@Test
	public void absurdlyLongDataIsCapped()
	{
		StringBuilder text = new StringBuilder();
		for (int i = 1; i <= ItemCodec.MAX_ENTRIES + 500; i++)
		{
			text.append(i).append(":1,");
		}
		assertEquals(ItemCodec.MAX_ENTRIES, ItemCodec.decode(text.toString()).size());
	}
}
