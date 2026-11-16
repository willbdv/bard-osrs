package com.bardosrs.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ItemsSnapshotTest
{
	private static ItemStack stack(int id, String name, long quantity)
	{
		return new ItemStack(id, name, quantity);
	}

	private static ItemsSnapshot sample(long bankAgeMinutes)
	{
		return ItemsSnapshot.of(
			Arrays.asList(stack(1, "Molten glass", 10), stack(2, "Glassblowing pipe", 1), stack(1, "Molten glass", 4)),
			Collections.singletonList(stack(3, "Dragon scimitar", 1)),
			Arrays.asList(stack(995, "Coins", 2_345_678), stack(1, "Molten glass", 120), stack(4, "Soda ash", 300)),
			bankAgeMinutes);
	}

	@Test
	public void stacksWithTheSameNameAreCombined()
	{
		List<ItemsSnapshot.Match> matches = sample(5).search("molten glass");
		assertEquals(2, matches.size());
		assertEquals(ItemsSnapshot.Where.INVENTORY, matches.get(0).where);
		assertEquals(14, matches.get(0).quantity);
		assertEquals(ItemsSnapshot.Where.BANK, matches.get(1).where);
		assertEquals(120, matches.get(1).quantity);
	}

	@Test
	public void searchIsCaseInsensitivePartialAndOrderedByPlace()
	{
		List<ItemsSnapshot.Match> matches = sample(5).search("  GLASS ");
		assertEquals(3, matches.size());
		// Inventory first, then equipment, then bank; alphabetical within each place.
		assertEquals("Glassblowing pipe", matches.get(0).item);
		assertEquals(ItemsSnapshot.Where.INVENTORY, matches.get(0).where);
		assertEquals("Molten glass", matches.get(1).item);
		assertEquals(ItemsSnapshot.Where.INVENTORY, matches.get(1).where);
		assertEquals(ItemsSnapshot.Where.BANK, matches.get(2).where);
		assertTrue(sample(5).search("nothing like this").isEmpty());
		assertTrue(sample(5).search("   ").isEmpty());
	}

	@Test
	public void searchResultsAreCapped()
	{
		List<ItemStack> many = new ArrayList<>();
		for (int i = 0; i < 50; i++)
		{
			many.add(stack(100 + i, "Rune item " + i, 1));
		}
		ItemsSnapshot snapshot = ItemsSnapshot.of(many, Collections.emptyList(), null, 0);
		assertEquals(12, snapshot.search("rune item").size());
	}

	@Test
	public void anUnopenedBankIsUnknownAndSaysSo()
	{
		ItemsSnapshot snapshot = ItemsSnapshot.of(Collections.singletonList(stack(1, "Coins", 5)),
			Collections.emptyList(), null, 0);
		assertFalse(snapshot.bankKnown());
		assertEquals("Bank: not opened yet", snapshot.summaryLine());
		String text = snapshot.promptSection();
		assertTrue(text, text.contains("contents are unknown"));
		assertTrue(text, text.contains("Coins x5"));
		assertTrue(text, text.contains("Equipped (right now): nothing"));
	}

	@Test
	public void aKnownBankIsSummarisedNotListed()
	{
		ItemsSnapshot snapshot = sample(25);
		assertTrue(snapshot.bankKnown());
		assertEquals(3, snapshot.bankDistinctItems());
		assertEquals(2_345_678, snapshot.bankCoins());
		assertEquals("Bank: 3 items (25m ago)", snapshot.summaryLine());

		String text = snapshot.promptSection();
		assertTrue(text, text.contains("Inventory (right now): Molten glass x14; Glassblowing pipe."));
		assertTrue(text, text.contains("Equipped (right now): Dragon scimitar."));
		assertTrue(text, text.contains("Bank: 3 different items, 2,345,678 coins. Last seen 25 minutes ago"));
		assertTrue(text, text.contains("search_items"));
		assertFalse("the bank's contents must not be dumped into the prompt", text.contains("Soda ash"));
		assertTrue(text, text.contains("do not tell them to gather or buy it again"));
	}

	@Test
	public void longInventoryListsAreCutOff()
	{
		List<ItemStack> many = new ArrayList<>();
		for (int i = 0; i < 45; i++)
		{
			many.add(stack(100 + i, "Item " + i, 1));
		}
		String text = ItemsSnapshot.of(many, Collections.emptyList(), null, 0).promptSection();
		assertTrue(text, text.contains("; and 5 more"));
		assertFalse(text, text.contains("Item 44"));
	}

	@Test
	public void ageInWords()
	{
		assertEquals("just now", ItemsSnapshot.age(0));
		assertEquals("1 minute ago", ItemsSnapshot.age(1));
		assertEquals("25 minutes ago", ItemsSnapshot.age(25));
		assertEquals("1 hour ago", ItemsSnapshot.age(60));
		assertEquals("3 hours ago", ItemsSnapshot.age(190));
		assertEquals("2 days ago", ItemsSnapshot.age(3000));
		assertEquals("just now", ItemsSnapshot.shortAge(0));
		assertEquals("25m ago", ItemsSnapshot.shortAge(25));
		assertEquals("3h ago", ItemsSnapshot.shortAge(190));
		assertEquals("2d ago", ItemsSnapshot.shortAge(3000));
	}
}
