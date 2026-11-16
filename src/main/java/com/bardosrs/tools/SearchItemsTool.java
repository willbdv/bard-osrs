package com.bardosrs.tools;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.AccountSource;
import com.bardosrs.state.ItemsSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Lets the model check what the player holds, from the items they chose to share. */
public class SearchItemsTool implements AgentTool
{
	private static final int MAX_QUERIES = 20;
	private static final int MAX_QUERY_LENGTH = 100;

	private final AccountSource account;

	public SearchItemsTool(AccountSource account)
	{
		this.account = account;
	}

	@Override
	public String name()
	{
		return "search_items";
	}

	@Override
	public String description()
	{
		return "Check what the player already holds. Takes a LIST of item names (or parts of names) and returns, for "
			+ "each, the matching items in their inventory, equipment and bank with quantities. The bank is as of "
			+ "the last time they opened it. Check all the items you care about in one call. Only works if the "
			+ "player shares their items.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject item = new JsonObject();
		item.addProperty("type", "string");
		JsonObject queries = new JsonObject();
		queries.addProperty("type", "array");
		queries.add("items", item);
		queries.addProperty("description", "Item names or parts of names, e.g. ['molten glass', 'glassblowing pipe'].");
		JsonObject properties = new JsonObject();
		properties.add("queries", queries);
		JsonArray required = new JsonArray();
		required.add("queries");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		AccountProfile profile = account.latest();
		if (!profile.isReady())
		{
			throw new ToolException(profile.unavailableReason());
		}
		ItemsSnapshot items = profile.items();
		if (items == null)
		{
			throw new ToolException("The player has not shared their items (bank, inventory and equipment are "
				+ "switched off in Bard's settings). Ask them instead.");
		}

		JsonArray queries = queries(args);
		if (queries.size() == 0)
		{
			throw new IllegalArgumentException("queries must be a non-empty list of item names.");
		}

		JsonArray results = new JsonArray();
		for (JsonElement element : queries)
		{
			String query = element.getAsString();
			JsonArray matches = new JsonArray();
			for (ItemsSnapshot.Match match : items.search(query))
			{
				JsonObject entry = new JsonObject();
				entry.addProperty("item", match.item);
				entry.addProperty("where", match.where.label);
				entry.addProperty("quantity", match.quantity);
				matches.add(entry);
			}
			JsonObject result = new JsonObject();
			result.addProperty("query", query);
			result.add("matches", matches);
			results.add(result);
		}

		JsonObject response = new JsonObject();
		response.add("results", results);
		if (items.bankKnown())
		{
			response.addProperty("bank_last_seen", items.bankAge());
		}
		else
		{
			response.addProperty("note", "The bank has not been opened yet, so only the inventory and equipment were "
				+ "searched. Ask the player to open their bank if it matters.");
		}
		return response.toString();
	}

	private static JsonArray queries(JsonObject args)
	{
		JsonArray cleaned = new JsonArray();
		JsonElement raw = args.get("queries");
		if (raw == null || raw.isJsonNull())
		{
			raw = args.get("query");
		}
		if (raw == null)
		{
			return cleaned;
		}
		if (raw.isJsonPrimitive())
		{
			addQuery(cleaned, raw.getAsString());
		}
		else if (raw.isJsonArray())
		{
			for (JsonElement element : raw.getAsJsonArray())
			{
				if (element.isJsonPrimitive())
				{
					addQuery(cleaned, element.getAsString());
				}
			}
		}
		return cleaned;
	}

	private static void addQuery(JsonArray queries, String query)
	{
		String trimmed = query.trim();
		if (!trimmed.isEmpty() && queries.size() < MAX_QUERIES)
		{
			queries.add(trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed);
		}
	}

	@Override
	public String summary(JsonObject args)
	{
		JsonArray queries = queries(args);
		if (queries.size() == 0)
		{
			return "item check";
		}
		String first = queries.get(0).getAsString();
		return "item check: " + first + (queries.size() > 1 ? " and " + (queries.size() - 1) + " more" : "");
	}
}
