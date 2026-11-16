package com.bardosrs.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * What the player holds, by name: inventory and equipment as they are right now, and the bank as it was the last
 * time it was opened. Only the player's own items; nothing about anyone else.
 */
public final class ItemsSnapshot
{
	public enum Where
	{
		INVENTORY("inventory"),
		EQUIPPED("equipped"),
		BANK("bank");

		public final String label;

		Where(String label)
		{
			this.label = label;
		}
	}

	public static final class Match
	{
		public final String item;
		public final Where where;
		public final long quantity;

		Match(String item, Where where, long quantity)
		{
			this.item = item;
			this.where = where;
			this.quantity = quantity;
		}
	}

	private static final int MAX_MATCHES_PER_QUERY = 12;
	private static final int MAX_LISTED_ITEMS = 40;

	private final List<ItemStack> inventory;
	private final List<ItemStack> equipment;
	/** Null when the bank has never been opened, so its contents are unknown. */
	private final List<ItemStack> bank;
	private final long bankAgeMinutes;

	private ItemsSnapshot(List<ItemStack> inventory, List<ItemStack> equipment, List<ItemStack> bank, long bankAgeMinutes)
	{
		this.inventory = merge(inventory);
		this.equipment = merge(equipment);
		this.bank = bank == null ? null : merge(bank);
		this.bankAgeMinutes = Math.max(0, bankAgeMinutes);
	}

	/** @param bank null if the bank has not been seen */
	public static ItemsSnapshot of(List<ItemStack> inventory, List<ItemStack> equipment, List<ItemStack> bank,
		long bankAgeMinutes)
	{
		return new ItemsSnapshot(inventory, equipment, bank, bankAgeMinutes);
	}

	/** Combines stacks that share a name, keeping first-seen order. */
	private static List<ItemStack> merge(List<ItemStack> stacks)
	{
		Map<String, ItemStack> byName = new LinkedHashMap<>();
		for (ItemStack stack : stacks)
		{
			ItemStack existing = byName.get(stack.name);
			byName.put(stack.name, existing == null
				? stack : new ItemStack(existing.id, existing.name, existing.quantity + stack.quantity));
		}
		return Collections.unmodifiableList(new ArrayList<>(byName.values()));
	}

	public boolean bankKnown()
	{
		return bank != null;
	}

	public int bankDistinctItems()
	{
		return bank == null ? 0 : bank.size();
	}

	public long bankCoins()
	{
		if (bank != null)
		{
			for (ItemStack stack : bank)
			{
				if (stack.id == ItemID.COINS)
				{
					return stack.quantity;
				}
			}
		}
		return 0;
	}

	/** How stale the bank is, in words, e.g. "25 minutes ago". */
	public String bankAge()
	{
		return age(bankAgeMinutes);
	}

	/** Items whose name contains the text, case-insensitively, from every place the player holds them. */
	public List<Match> search(String query)
	{
		String needle = query.toLowerCase(Locale.ROOT).trim();
		List<Match> matches = new ArrayList<>();
		if (needle.isEmpty())
		{
			return matches;
		}
		collect(needle, inventory, Where.INVENTORY, matches);
		collect(needle, equipment, Where.EQUIPPED, matches);
		if (bank != null)
		{
			collect(needle, bank, Where.BANK, matches);
		}
		matches.sort(Comparator.<Match, Integer>comparing(m -> m.where.ordinal()).thenComparing(m -> m.item));
		return matches.size() > MAX_MATCHES_PER_QUERY ? matches.subList(0, MAX_MATCHES_PER_QUERY) : matches;
	}

	private static void collect(String needle, List<ItemStack> stacks, Where where, List<Match> out)
	{
		for (ItemStack stack : stacks)
		{
			if (stack.name.toLowerCase(Locale.ROOT).contains(needle))
			{
				out.add(new Match(stack.name, where, stack.quantity));
			}
		}
	}

	/**
	 * How many of an item the player holds across inventory, equipment and bank. An exact name (ignoring case) is
	 * preferred; otherwise a partial name counts only if it points at one item.
	 */
	public long countOf(String name)
	{
		String wanted = name.toLowerCase(Locale.ROOT).trim();
		long exact = 0;
		long partial = 0;
		java.util.Set<String> partialNames = new java.util.HashSet<>();
		for (List<ItemStack> container : java.util.Arrays.asList(inventory, equipment, bank))
		{
			if (container == null)
			{
				continue;
			}
			for (ItemStack stack : container)
			{
				String lower = stack.name.toLowerCase(Locale.ROOT);
				if (lower.equals(wanted))
				{
					exact += stack.quantity;
				}
				else if (!wanted.isEmpty() && lower.contains(wanted))
				{
					partial += stack.quantity;
					partialNames.add(lower);
				}
			}
		}
		if (exact > 0)
		{
			return exact;
		}
		return partialNames.size() == 1 ? partial : 0;
	}

	/** A short second line for the chat panel. */
	public String summaryLine()
	{
		return bank == null ? "Bank: not opened yet" : "Bank: " + bank.size() + " items (" + shortAge(bankAgeMinutes) + ")";
	}

	/** The items part of the system prompt. */
	public String promptSection()
	{
		StringBuilder text = new StringBuilder();
		text.append("- Inventory (right now): ").append(list(inventory, "empty")).append(".\n");
		text.append("- Equipped (right now): ").append(list(equipment, "nothing")).append(".\n");
		if (bank == null)
		{
			text.append("- Bank: not opened since Bard started tracking it, so its contents are unknown. Ask the "
				+ "player to open their bank if it matters.\n");
		}
		else
		{
			text.append("- Bank: ").append(bank.size()).append(" different items");
			if (bankCoins() > 0)
			{
				text.append(", ").append(String.format(Locale.ROOT, "%,d", bankCoins())).append(" coins");
			}
			text.append(". Last seen ").append(bankAge()).append(", so it may have changed since.")
				.append(" Use the search_items tool to check for specific items.\n");
		}
		text.append("Count what they already hold toward what they need, and do not tell them to gather or buy it again.");
		return text.toString();
	}

	private static String list(List<ItemStack> stacks, String whenEmpty)
	{
		if (stacks.isEmpty())
		{
			return whenEmpty;
		}
		StringBuilder line = new StringBuilder();
		int shown = 0;
		for (ItemStack stack : stacks)
		{
			if (shown == MAX_LISTED_ITEMS)
			{
				line.append("; and ").append(stacks.size() - shown).append(" more");
				break;
			}
			if (shown > 0)
			{
				line.append("; ");
			}
			line.append(stack.name);
			if (stack.quantity != 1)
			{
				line.append(" x").append(String.format(Locale.ROOT, "%,d", stack.quantity));
			}
			shown++;
		}
		return line.toString();
	}

	static String age(long minutes)
	{
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
		}
		long hours = minutes / 60;
		if (hours < 48)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		return hours / 24 + " days ago";
	}

	static String shortAge(long minutes)
	{
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		return hours < 48 ? hours + "h ago" : hours / 24 + "d ago";
	}
}
