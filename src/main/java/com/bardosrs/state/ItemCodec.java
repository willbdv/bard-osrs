package com.bardosrs.state;

import java.util.ArrayList;
import java.util.List;

/** Compact text form of a bank snapshot for RuneLite's settings: "id:quantity,id:quantity,...". */
final class ItemCodec
{
	/** A bank has at most a few thousand slots; anything larger in saved data is corrupt. */
	static final int MAX_ENTRIES = 5000;

	private ItemCodec()
	{
	}

	static String encode(int[] ids, int[] quantities)
	{
		StringBuilder text = new StringBuilder(ids.length * 9);
		for (int i = 0; i < ids.length && i < MAX_ENTRIES; i++)
		{
			if (i > 0)
			{
				text.append(',');
			}
			text.append(ids[i]).append(':').append(quantities[i]);
		}
		return text.toString();
	}

	/** Parses saved data, skipping anything malformed, so damaged settings never break the plugin. */
	static List<int[]> decode(String text)
	{
		List<int[]> entries = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			return entries;
		}
		for (String part : text.split(",", MAX_ENTRIES + 1))
		{
			if (entries.size() >= MAX_ENTRIES)
			{
				break;
			}
			int colon = part.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			try
			{
				int id = Integer.parseInt(part.substring(0, colon).trim());
				int quantity = Integer.parseInt(part.substring(colon + 1).trim());
				if (id > 0 && quantity > 0)
				{
					entries.add(new int[]{id, quantity});
				}
			}
			catch (NumberFormatException e)
			{
				// skip the damaged entry
			}
		}
		return entries;
	}
}
