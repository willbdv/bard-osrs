package com.bardosrs.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Finds Old School RuneScape Wiki pages by keyword. */
public class WikiSearchTool implements AgentTool
{
	private static final int MAX_QUERY_LENGTH = 200;

	private final WikiClient wiki;

	public WikiSearchTool(WikiClient wiki)
	{
		this.wiki = wiki;
	}

	@Override
	public String name()
	{
		return "wiki_search";
	}

	@Override
	public boolean available()
	{
		return wiki.isEnabled();
	}

	@Override
	public String description()
	{
		return "Search the Old School RuneScape Wiki for pages. Returns page titles with short snippets only; "
			+ "call wiki_page with a title to read a page.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject query = new JsonObject();
		query.addProperty("type", "string");
		query.addProperty("description", "Keywords, e.g. 'Vorkath strategy' or 'dragon scimitar'.");
		JsonObject properties = new JsonObject();
		properties.add("query", query);
		JsonArray required = new JsonArray();
		required.add("query");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		String query = text(args, "query");
		if (query.isEmpty())
		{
			throw new IllegalArgumentException("query is required.");
		}
		if (query.length() > MAX_QUERY_LENGTH)
		{
			throw new IllegalArgumentException("query must be at most " + MAX_QUERY_LENGTH + " characters.");
		}

		List<WikiClient.SearchHit> hits = wiki.search(query);
		JsonArray results = new JsonArray();
		for (WikiClient.SearchHit hit : hits)
		{
			JsonObject entry = new JsonObject();
			entry.addProperty("title", hit.title);
			entry.addProperty("snippet", hit.snippet);
			entry.addProperty("url", hit.url);
			results.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("query", query);
		result.add("results", results);
		if (hits.isEmpty())
		{
			result.addProperty("note", "No pages matched. Try different or fewer keywords.");
		}
		return result.toString();
	}

	@Override
	public String summary(JsonObject args)
	{
		return "wiki search: " + text(args, "query");
	}

	static String text(JsonObject args, String key)
	{
		JsonElement value = args.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
	}
}
