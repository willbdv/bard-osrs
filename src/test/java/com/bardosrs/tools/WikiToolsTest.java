package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WikiToolsTest
{
	private FakeWiki fake;
	private WikiSearchTool search;
	private WikiPageTool page;

	@Before
	public void start() throws Exception
	{
		fake = new FakeWiki();
		WikiClient wiki = fake.client();
		search = new WikiSearchTool(wiki);
		page = new WikiPageTool(wiki);
	}

	@After
	public void stop()
	{
		fake.close();
	}

	private static JsonObject args(String json)
	{
		return FakeWiki.GSON.fromJson(json, JsonObject.class);
	}

	private static JsonObject parse(String json)
	{
		return FakeWiki.GSON.fromJson(json, JsonObject.class);
	}

	@Test
	public void searchResultsCarryTitleSnippetAndUrl()
	{
		JsonObject result = parse(search.run(args("{\"query\":\"glassblowing\"}")));
		assertEquals(2, result.getAsJsonArray("results").size());
		JsonObject first = result.getAsJsonArray("results").get(0).getAsJsonObject();
		assertEquals("Glassblowing pipe", first.get("title").getAsString());
		assertTrue(first.get("url").getAsString().contains("/w/Glassblowing_pipe"));
		assertFalse(result.has("note"));
	}

	@Test
	public void searchWithNoHitsSaysSo()
	{
		JsonObject result = parse(search.run(args("{\"query\":\"nothing\"}")));
		assertEquals(0, result.getAsJsonArray("results").size());
		assertTrue(result.has("note"));
	}

	@Test
	public void searchRejectsBlankAndOversizedQueries()
	{
		for (String json : new String[]{"{}", "{\"query\":\"   \"}", "{\"query\":\"" + "x".repeat(300) + "\"}"})
		{
			try
			{
				search.run(args(json));
				throw new AssertionError("expected rejection for " + json);
			}
			catch (IllegalArgumentException e)
			{
				assertTrue(e.getMessage(), e.getMessage().contains("query"));
			}
		}
	}

	@Test
	public void pageResultIncludesSectionsAndText()
	{
		JsonObject result = parse(page.run(args("{\"title\":\"Crafting\"}")));
		assertEquals("Crafting", result.get("title").getAsString());
		assertTrue(result.get("url").getAsString().endsWith("/w/Crafting"));
		assertEquals(2, result.getAsJsonArray("sections").size());
		assertTrue(result.get("text").getAsString().contains("Crafting intro."));
		assertFalse(result.has("truncated"));
	}

	@Test
	public void truncatedPagesTellTheModelHowToReadMore()
	{
		JsonObject result = parse(page.run(args("{\"title\":\"Big page\"}")));
		assertTrue(result.get("truncated").getAsBoolean());
		assertTrue(result.get("note").getAsString().contains("section"));
	}

	@Test
	public void pageSectionAndRedirectAreReported()
	{
		JsonObject result = parse(page.run(args("{\"title\":\"Glassblowing\"}")));
		assertEquals("Glass", result.get("section").getAsString());
		assertEquals("Glassblowing", result.get("redirected_from").getAsString());
	}

	@Test
	public void pageRequiresATitle()
	{
		try
		{
			page.run(args("{\"section\":\"Drops\"}"));
			throw new AssertionError("expected rejection");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("title"));
		}
	}

	@Test
	public void summariesDescribeTheCall()
	{
		assertEquals("wiki search: dragon scimitar", search.summary(args("{\"query\":\"dragon scimitar\"}")));
		assertEquals("wiki page: Crafting", page.summary(args("{\"title\":\"Crafting\"}")));
		assertEquals("wiki page: Crafting (Glass)", page.summary(args("{\"title\":\"Crafting\",\"section\":\"Glass\"}")));
	}

	@Test
	public void registryTurnsWikiFailuresIntoErrorJson()
	{
		ToolRegistry registry = new ToolRegistry(FakeWiki.GSON, java.util.Arrays.<AgentTool>asList(page));
		String result = registry.execute("wiki_page", "{\"title\":\"Missing page\"}");
		assertTrue(result, result.contains("wiki_search"));
		assertTrue(result, result.startsWith("{\"error\""));
	}
}
