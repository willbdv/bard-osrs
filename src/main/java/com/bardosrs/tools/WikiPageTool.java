package com.bardosrs.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Reads an Old School RuneScape Wiki page, or one section of it. */
public class WikiPageTool implements AgentTool
{
	private final WikiClient wiki;

	public WikiPageTool(WikiClient wiki)
	{
		this.wiki = wiki;
	}

	@Override
	public String name()
	{
		return "wiki_page";
	}

	@Override
	public boolean available()
	{
		return wiki.isEnabled();
	}

	@Override
	public String unavailableNote()
	{
		return "Wiki lookups are switched off, so you cannot read the OSRS Wiki. Answer from your own knowledge and say "
			+ "plainly when you are unsure or a detail may be out of date. Do not explain how to turn lookups on: the "
			+ "panel already tells the player.";
	}

	@Override
	public String unavailableHint()
	{
		return "Wiki lookups are off. Turn on \"Look things up on the OSRS Wiki\" in Bard's settings for answers "
			+ "checked against the wiki.";
	}

	@Override
	public String description()
	{
		return "Read an Old School RuneScape Wiki page (item, monster, quest, skill, guide) as text with tables kept "
			+ "as 'a | b' rows. Long pages are cut short: the result lists every section name, so call again with "
			+ "'section' to read one in full (for example 'Drops', 'Requirements', 'Strategy', 'Item sources').";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject title = new JsonObject();
		title.addProperty("type", "string");
		title.addProperty("description", "Exact page title, e.g. 'Dragon scimitar'. Use wiki_search if unsure.");
		JsonObject section = new JsonObject();
		section.addProperty("type", "string");
		section.addProperty("description", "Optional section name to read in full.");
		JsonObject properties = new JsonObject();
		properties.add("title", title);
		properties.add("section", section);
		JsonArray required = new JsonArray();
		required.add("title");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		String title = WikiSearchTool.text(args, "title");
		if (title.isEmpty())
		{
			throw new IllegalArgumentException("title is required.");
		}
		String section = WikiSearchTool.text(args, "section");

		WikiClient.Page page = wiki.page(title, section.isEmpty() ? null : section);
		JsonObject result = new JsonObject();
		result.addProperty("title", page.title);
		result.addProperty("url", page.url);
		if (page.redirectedFrom != null)
		{
			result.addProperty("redirected_from", page.redirectedFrom);
		}
		if (page.section != null)
		{
			result.addProperty("section", page.section);
		}
		JsonArray sections = new JsonArray();
		for (String name : page.sections)
		{
			sections.add(name);
		}
		result.add("sections", sections);
		result.addProperty("text", page.text);
		if (page.truncated)
		{
			result.addProperty("truncated", true);
			result.addProperty("note", "The text was cut short. Call wiki_page again with a section name to read more.");
		}
		return result.toString();
	}

	@Override
	public String summary(JsonObject args)
	{
		String section = WikiSearchTool.text(args, "section");
		return "wiki page: " + WikiSearchTool.text(args, "title") + (section.isEmpty() ? "" : " (" + section + ")");
	}
}
