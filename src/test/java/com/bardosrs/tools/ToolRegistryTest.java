package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class ToolRegistryTest
{
	private static final Gson GSON = new Gson();
	private final ToolRegistry registry = new ToolRegistry(GSON, new WikiClient(new OkHttpClient(), GSON),
		new FakeAccountSource(), new GoalStore(new FakeProfileStore(), GSON));

	@Test
	public void specsUseTheOpenAiToolFormat()
	{
		JsonArray specs = registry.specs();
		assertEquals(7, specs.size());
		assertEquals("list_goals", specs.get(5).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertEquals("edit_goal", specs.get(6).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertEquals("search_items", specs.get(4).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertEquals("get_quests", specs.get(3).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertEquals("wiki_search", specs.get(1).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertEquals("wiki_page", specs.get(2).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		JsonObject spec = specs.get(0).getAsJsonObject();
		assertEquals("function", spec.get("type").getAsString());
		JsonObject function = spec.getAsJsonObject("function");
		assertEquals("xp_calculator", function.get("name").getAsString());
		assertTrue(function.get("description").getAsString().length() > 20);
		JsonObject parameters = function.getAsJsonObject("parameters");
		assertEquals("object", parameters.get("type").getAsString());
		assertEquals("to_level", parameters.getAsJsonArray("required").get(0).getAsString());
		assertTrue(parameters.getAsJsonObject("properties").has("xp_per_action"));
	}

	@Test
	public void executesAKnownTool()
	{
		String result = registry.execute("xp_calculator", "{\"from_level\":1,\"to_level\":2}");
		assertEquals(83, GSON.fromJson(result, JsonObject.class).get("xp_needed").getAsLong());
	}

	@Test
	public void failuresComeBackAsErrorJson()
	{
		assertTrue(registry.execute("nope", "{}").contains("Unknown tool"));
		assertTrue(registry.execute("xp_calculator", "not json").contains("not valid JSON"));
		assertTrue(registry.execute("xp_calculator", "{}").contains("to_level is required"));
		assertTrue(registry.execute("xp_calculator", "").contains("to_level is required"));
	}

	@Test
	public void summaryFallsBackToTheNameWhenArgumentsAreBroken()
	{
		assertEquals("xp_calculator", registry.summary("xp_calculator", "not json"));
		assertEquals("mystery", registry.summary("mystery", "{}"));
	}
}
