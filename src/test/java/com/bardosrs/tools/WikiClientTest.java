package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WikiClientTest
{
	private FakeWiki fake;
	private WikiClient wiki;

	@Before
	public void start() throws Exception
	{
		fake = new FakeWiki();
		wiki = fake.client();
	}

	@After
	public void stop()
	{
		fake.close();
	}

	private static void assertToolError(Runnable action, String expectedFragment)
	{
		try
		{
			action.run();
			fail("expected a ToolException containing: " + expectedFragment);
		}
		catch (ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
		}
	}

	@Test
	public void searchReturnsCleanSnippetsAndUrls()
	{
		List<WikiClient.SearchHit> hits = wiki.search("glassblowing");
		assertEquals(2, hits.size());
		assertEquals("Glassblowing pipe", hits.get(0).title);
		assertEquals("A glassblowing pipe is a tool", hits.get(0).snippet);
		assertTrue(hits.get(0).url, hits.get(0).url.endsWith("/w/Glassblowing_pipe"));
		assertEquals("Use the glassblowing pipe & molten glass", hits.get(1).snippet);

		assertEquals("query", fake.requests.get(0).get("action"));
		assertEquals("glassblowing", fake.requests.get(0).get("srsearch"));
		assertEquals("0", fake.requests.get(0).get("srnamespace"));
	}

	@Test
	public void searchWithNoMatchesIsEmpty()
	{
		assertTrue(wiki.search("nothing").isEmpty());
	}

	@Test
	public void pageWithoutASectionReturnsTheStartAndTheSectionList()
	{
		WikiClient.Page page = wiki.page("Crafting", null);
		assertEquals("Crafting", page.title);
		assertEquals(2, page.sections.size());
		assertEquals("Spinning", page.sections.get(0));
		assertNull(page.section);
		assertFalse(page.truncated);
		assertTrue(page.text, page.text.startsWith("Crafting intro."));
		assertTrue(page.text, page.text.contains("## Glass"));
		assertEquals(1, fake.requests.size());
		assertEquals("text|sections", fake.requests.get(0).get("prop"));
		assertEquals("1", fake.requests.get(0).get("redirects"));
	}

	@Test
	public void longPagesAreTruncatedAtALineBreak()
	{
		WikiClient.Page page = wiki.page("Big page", null);
		assertTrue(page.truncated);
		assertTrue(page.text.length() <= WikiClient.PAGE_CHAR_LIMIT);
		assertTrue(page.text.length() > WikiClient.PAGE_CHAR_LIMIT / 2);
	}

	@Test
	public void sectionNamesMatchLeniently()
	{
		WikiClient.Page page = wiki.page("Crafting", "glass");
		assertEquals("Glass", page.section);
		assertTrue(page.text, page.text.contains("Fishbowl | 42.5"));
		assertFalse(page.text, page.text.contains("Spin things."));
		assertTrue(page.url, page.url.endsWith("/w/Crafting#Glass"));
		// One call for the section list, one for the section itself.
		assertEquals(2, fake.requests.size());
		assertEquals("sections", fake.requests.get(0).get("prop"));
		assertEquals("2", fake.requests.get(1).get("section"));
	}

	@Test
	public void aRedirectThatPointsAtASectionOpensThatSection()
	{
		WikiClient.Page page = wiki.page("Glassblowing", null);
		assertEquals("Crafting", page.title);
		assertEquals("Glassblowing", page.redirectedFrom);
		assertEquals("Glass", page.section);
		assertTrue(page.text, page.text.contains("Glassblowing makes items."));
		assertFalse(page.text, page.text.contains("Crafting intro."));
	}

	@Test
	public void aSelfRedirectWithoutAFragmentReturnsTheWholePage()
	{
		WikiClient.Page page = wiki.page("Cooking", null);
		assertNull(page.section);
		assertEquals("Cooking", page.redirectedFrom);
	}

	@Test
	public void anUnknownSectionListsTheRealOnes()
	{
		assertToolError(() -> wiki.page("Crafting", "Dragons"), "Spinning, Glass");
	}

	@Test
	public void aMissingPagePointsToSearch()
	{
		assertToolError(() -> wiki.page("Missing page", null), "wiki_search");
	}

	@Test
	public void otherWikiErrorsAreReported()
	{
		assertToolError(() -> wiki.page("Broken", null), "Something went wrong");
		assertToolError(() -> wiki.page("Server error", null), "HTTP 500");
	}

	@Test
	public void identicalRequestsAreServedFromTheCache()
	{
		wiki.search("glassblowing");
		wiki.search("glassblowing");
		assertEquals(1, fake.requests.size());
		wiki.search("something else");
		assertEquals(2, fake.requests.size());
	}

	@Test
	public void unreachableWikiGivesAReadableError()
	{
		fake.close();
		assertToolError(() -> wiki.search("anything"), "Could not reach the OSRS Wiki");
	}
}
