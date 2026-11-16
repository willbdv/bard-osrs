package com.bardosrs.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;

/** Turns item containers into named stacks. Must run on the client thread, where item names can be looked up. */
final class ItemReader
{
	private ItemReader()
	{
	}

	static ItemsSnapshot read(Client client, ItemManager itemManager, BankTracker bankTracker)
	{
		List<ItemStack> inventory = stacks(itemManager, client.getItemContainer(InventoryID.INV));
		List<ItemStack> equipment = stacks(itemManager, client.getItemContainer(InventoryID.WORN));

		List<ItemStack> bank = null;
		long ageMinutes = 0;
		BankTracker.Saved saved = bankTracker.saved();
		if (saved != null)
		{
			bank = stacks(itemManager, saved.ids, saved.quantities);
			ageMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - saved.capturedAtMillis);
		}
		return ItemsSnapshot.of(inventory, equipment, bank, ageMinutes);
	}

	private static List<ItemStack> stacks(ItemManager itemManager, ItemContainer container)
	{
		if (container == null)
		{
			return new ArrayList<>();
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] quantities = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			quantities[i] = items[i].getQuantity();
		}
		return stacks(itemManager, ids, quantities);
	}

	private static List<ItemStack> stacks(ItemManager itemManager, int[] ids, int[] quantities)
	{
		Map<Integer, Long> totals = new LinkedHashMap<>();
		for (int i = 0; i < ids.length; i++)
		{
			if (ids[i] <= 0 || quantities[i] <= 0)
			{
				continue;
			}
			ItemComposition composition = itemManager.getItemComposition(ids[i]);
			// A bank placeholder is a reminder of an item the player no longer has.
			if (composition.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			// Noted and unnoted copies of the same item count together.
			totals.merge(itemManager.canonicalize(ids[i]), (long) quantities[i], Long::sum);
		}

		List<ItemStack> stacks = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : totals.entrySet())
		{
			String name = itemManager.getItemComposition(entry.getKey()).getName();
			if (name != null && !name.equals("null"))
			{
				stacks.add(new ItemStack(entry.getKey(), name, entry.getValue()));
			}
		}
		return stacks;
	}
}
