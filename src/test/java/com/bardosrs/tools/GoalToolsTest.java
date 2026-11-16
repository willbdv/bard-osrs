package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalException;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import com.bardosrs.state.TestProfiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class GoalToolsTest
{
	private static final Gson GSON = new Gson();

	private final FakeProfileStore profiles = new FakeProfileStore();
	private final GoalStore goals = new GoalStore(profiles, GSON);
	private final FakeAccountSource account = new FakeAccountSource();
	private final EditGoalTool edit = new EditGoalTool(goals);
	private final ListGoalsTool list = new ListGoalsTool(goals, account);

	private static JsonObject args(String json)
	{
		return GSON.fromJson(json, JsonObject.class);
	}

	private JsonObject edit(String json)
	{
		return GSON.fromJson(edit.run(args(json)), JsonObject.class);
	}

	private JsonObject list()
	{
		return GSON.fromJson(list.run(new JsonObject()), JsonObject.class);
	}

	private static void assertRejected(Runnable action, String expectedFragment)
	{
		try
		{
			action.run();
			fail("expected a rejection containing: " + expectedFragment);
		}
		catch (IllegalArgumentException | ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
		}
	}

	// ---- edit_goal ----

	@Test
	public void addsASkillGoal()
	{
		JsonObject result = edit("{\"action\":\"add\",\"type\":\"skill\",\"skill\":\"crafting\",\"level\":56,"
			+ "\"title\":\"Colossal pouch\"}");
		assertEquals("added", result.get("result").getAsString());
		assertEquals("Colossal pouch", result.getAsJsonObject("goal").get("title").getAsString());

		Goal stored = goals.list().get(0);
		assertEquals(Goal.Type.SKILL, stored.type);
		assertEquals("Crafting", stored.target);
		assertEquals(56, stored.amount);
	}

	@Test
	public void theTypeIsWorkedOutFromTheFieldsGiven()
	{
		edit("{\"action\":\"add\",\"skill\":\"Mining\",\"level\":70}");
		edit("{\"action\":\"add\",\"item\":\"Soda ash\",\"quantity\":500}");
		edit("{\"action\":\"add\",\"title\":\"Get the fire cape\"}");
		assertEquals(Goal.Type.SKILL, goals.list().get(0).type);
		assertEquals("Mining level 70", goals.list().get(0).title);
		assertEquals(Goal.Type.ITEM, goals.list().get(1).type);
		assertEquals(500, goals.list().get(1).amount);
		assertEquals(Goal.Type.CUSTOM, goals.list().get(2).type);
	}

	@Test
	public void questsMustBeRealQuestNames()
	{
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"quest\",\"quest\":\"qqqzzzxxx\"}"), "No quest matches");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"quest\",\"quest\":\"the\"}"), "matches several quests");
		assertTrue(goals.list().isEmpty());
	}

	@Test
	public void itemGoalsDefaultToOneAndRejectNonsense()
	{
		edit("{\"action\":\"add\",\"type\":\"item\",\"item\":\"Fire cape\"}");
		assertEquals(1, goals.list().get(0).amount);
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"item\"}"), "item is required");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"item\",\"item\":\"X\",\"quantity\":0}"), "quantity must be");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"item\",\"item\":\"X\",\"quantity\":\"lots\"}"), "must be a number");
	}

	@Test
	public void skillGoalsRejectBadSkillsAndLevels()
	{
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"skill\",\"skill\":\"cooking\"}"), "level must be");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"skill\",\"skill\":\"cooking\",\"level\":127}"), "level must be");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"skill\",\"skill\":\"nonsense\",\"level\":50}"), "not a skill name");
	}

	@Test
	public void customGoalsNeedATitle()
	{
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"custom\"}"), "title is required");
		assertRejected(() -> edit("{\"action\":\"add\"}"), "title is required");
	}

	@Test
	public void addingTheSameGoalTwiceSaysSo()
	{
		edit("{\"action\":\"add\",\"skill\":\"Crafting\",\"level\":56}");
		JsonObject again = edit("{\"action\":\"add\",\"skill\":\"crafting\",\"level\":56}");
		assertEquals("already_set", again.get("result").getAsString());
		assertEquals(1, goals.list().size());
	}

	@Test
	public void completesAndRemovesByIdOrTitle()
	{
		String id = edit("{\"action\":\"add\",\"title\":\"Get the fire cape\"}").getAsJsonObject("goal").get("id").getAsString();
		edit("{\"action\":\"add\",\"skill\":\"Crafting\",\"level\":56,\"title\":\"Colossal pouch\"}");

		JsonObject done = edit("{\"action\":\"complete\",\"id\":\"" + id + "\"}");
		assertEquals("completed", done.get("result").getAsString());
		assertTrue(done.getAsJsonObject("goal").get("done").getAsBoolean());

		JsonObject removed = edit("{\"action\":\"remove\",\"id\":\"colossal\"}");
		assertEquals("removed", removed.get("result").getAsString());
		assertEquals(1, goals.list().size());
		assertRejected(() -> edit("{\"action\":\"remove\",\"id\":\"zzz\"}"), "No goal matches");
		assertRejected(() -> edit("{\"action\":\"complete\"}"), "Say which goal");
	}

	@Test
	public void anUnknownActionOrTypeIsRejected()
	{
		assertRejected(() -> edit("{\"action\":\"explode\"}"), "action must be");
		assertRejected(() -> edit("{\"action\":\"add\",\"type\":\"banana\",\"title\":\"x\"}"), "type must be");
		assertRejected(() -> edit("{}"), "action must be");
	}

	@Test
	public void editingWhileLoggedOutExplainsWhy()
	{
		profiles.profile = null;
		try
		{
			edit("{\"action\":\"add\",\"title\":\"x\"}");
			fail("expected a GoalException");
		}
		catch (GoalException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("log in first"));
		}
	}

	@Test
	public void editSummariesDescribeTheCall()
	{
		assertEquals("goal add: Colossal pouch", edit.summary(args("{\"action\":\"add\",\"title\":\"Colossal pouch\"}")));
		assertEquals("goal add: Crafting", edit.summary(args("{\"action\":\"add\",\"skill\":\"Crafting\",\"level\":56}")));
		assertEquals("goal remove: abc123", edit.summary(args("{\"action\":\"remove\",\"id\":\"abc123\"}")));
		assertEquals("goal change", edit.summary(args("{}")));
	}

	// ---- list_goals ----

	@Test
	public void listsGoalsWithProgress()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		goals.add(Goal.Type.SKILL, "Colossal pouch", "Crafting", 56);
		goals.add(Goal.Type.ITEM, "", "Soda ash", 1000);

		JsonObject result = list();

		assertEquals(2, result.getAsJsonArray("goals").size());
		JsonObject skill = result.getAsJsonArray("goals").get(0).getAsJsonObject();
		assertEquals("Colossal pouch", skill.get("title").getAsString());
		assertEquals("skill", skill.get("type").getAsString());
		assertFalse(skill.get("done").getAsBoolean());
		assertEquals("Crafting 42 -> 56 (138,511 xp to go)", skill.get("progress").getAsString());
		assertEquals(25, skill.get("percent").getAsInt());
		assertEquals("300 / 1,000", result.getAsJsonArray("goals").get(1).getAsJsonObject().get("progress").getAsString());
		assertFalse(result.has("note"));
	}

	@Test
	public void listingMarksReachedGoalsDone()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		goals.add(Goal.Type.SKILL, "Easy one", "Crafting", 30);
		JsonObject entry = list().getAsJsonArray("goals").get(0).getAsJsonObject();
		assertTrue(entry.get("done").getAsBoolean());
		assertTrue(goals.list().get(0).done);
	}

	@Test
	public void listingWithoutAccountDataStillShowsTheGoals()
	{
		account.profile = AccountProfile.notShared();
		goals.add(Goal.Type.SKILL, "Colossal pouch", "Crafting", 56);
		JsonObject entry = list().getAsJsonArray("goals").get(0).getAsJsonObject();
		assertEquals("Progress hidden: account data is not shared", entry.get("progress").getAsString());
		assertFalse(entry.has("percent"));
	}

	@Test
	public void anEmptyListSaysSo()
	{
		account.profile = TestProfiles.ironmanZerker();
		JsonObject result = list();
		assertEquals(0, result.getAsJsonArray("goals").size());
		assertEquals("The player has no goals yet.", result.get("note").getAsString());
	}

	@Test
	public void listingWhileLoggedOutIsRefused()
	{
		profiles.profile = null;
		try
		{
			list();
			fail("expected a ToolException");
		}
		catch (ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("logged in"));
		}
		assertEquals("goals overview", list.summary(new JsonObject()));
	}
}
