package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Wiki lookups are opt-in: with the setting off, nothing reaches the wiki and the model is not offered the tools. */
public class WikiOptInTest
{
	private final AtomicBoolean enabled = new AtomicBoolean(false);
	private FakeWiki fake;
	private WikiClient wiki;
	private ToolRegistry registry;

	@Before
	public void start() throws Exception
	{
		fake = new FakeWiki();
		wiki = fake.client(enabled::get);
		registry = new ToolRegistry(FakeWiki.GSON, wiki, new FakeAccountSource(),
			new GoalStore(new FakeProfileStore(), FakeWiki.GSON));
	}

	@After
	public void stop()
	{
		fake.close();
	}

	private List<String> offered()
	{
		List<String> names = new ArrayList<>();
		JsonArray specs = registry.specs();
		for (int i = 0; i < specs.size(); i++)
		{
			names.add(specs.get(i).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		}
		return names;
	}

	@Test
	public void offByDefaultTheClientNeverTouchesTheNetwork()
	{
		try
		{
			wiki.search("glassblowing");
			fail("expected a refusal");
		}
		catch (ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("turned off"));
			assertTrue(e.getMessage(), e.getMessage().contains("Look things up on the OSRS Wiki"));
		}
		try
		{
			wiki.page("Crafting", null);
			fail("expected a refusal");
		}
		catch (ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("turned off"));
		}
		assertTrue("no request may leave while the setting is off", fake.requests.isEmpty());
	}

	@Test
	public void turningItOnTakesEffectWithoutARestart()
	{
		enabled.set(true);
		assertFalse(wiki.search("glassblowing").isEmpty());
		assertFalse(fake.requests.isEmpty());
	}

	@Test
	public void theModelIsNotOfferedWikiToolsWhileOff()
	{
		List<String> off = offered();
		assertFalse(off.toString(), off.contains("wiki_search"));
		assertFalse(off.toString(), off.contains("wiki_page"));
		assertTrue(off.toString(), off.contains("xp_calculator"));
		assertEquals(5, off.size());

		enabled.set(true);
		List<String> on = offered();
		assertTrue(on.toString(), on.contains("wiki_search"));
		assertTrue(on.toString(), on.contains("wiki_page"));
		assertEquals(7, on.size());
	}

	@Test
	public void thePromptTellsTheModelWhatToDoWithoutTheWiki()
	{
		String notes = registry.unavailableNotes();
		assertTrue(notes, notes.contains("Look things up on the OSRS Wiki"));
		assertTrue(notes, notes.contains("your own knowledge"));

		enabled.set(true);
		assertEquals("", registry.unavailableNotes());
	}

	@Test
	public void aStaleWikiCallIsAnErrorNotARequest()
	{
		String result = registry.execute("wiki_page", "{\"title\":\"Crafting\"}");
		JsonObject json = FakeWiki.GSON.fromJson(result, JsonObject.class);
		assertTrue(result, json.get("error").getAsString().contains("turned off"));
		assertTrue(fake.requests.isEmpty());
	}
}
