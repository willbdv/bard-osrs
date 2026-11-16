package com.bardosrs.goals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.FakeProfileStore;
import com.bardosrs.state.TestProfiles;
import com.google.gson.Gson;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GoalStoreTest
{
	private static final Gson GSON = new Gson();

	private final FakeProfileStore profiles = new FakeProfileStore();
	private final GoalStore store = new GoalStore(profiles, GSON);

	private static void assertRejected(Runnable action, String expectedFragment)
	{
		try
		{
			action.run();
			fail("expected a GoalException containing: " + expectedFragment);
		}
		catch (GoalException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
		}
	}

	private Goal addSkill(String title)
	{
		return store.add(Goal.Type.SKILL, title, "Crafting", 56);
	}

	@Test
	public void addsAndListsGoals()
	{
		Goal goal = addSkill("Colossal pouch");
		assertEquals(6, goal.id.length());
		assertFalse(goal.done);
		List<Goal> goals = store.list();
		assertEquals(1, goals.size());
		assertEquals("Colossal pouch", goals.get(0).title);
		assertEquals("Crafting", goals.get(0).target);
		assertEquals(56, goals.get(0).amount);
	}

	@Test
	public void titlesAreGeneratedWhenMissing()
	{
		assertEquals("Crafting level 56", store.add(Goal.Type.SKILL, "", "Crafting", 56).title);
		assertEquals("Complete Cook's Assistant", store.add(Goal.Type.QUEST, null, "Cook's Assistant", 0).title);
		assertEquals("Have 1,000 Soda ash", store.add(Goal.Type.ITEM, "  ", "Soda ash", 1000).title);
	}

	@Test
	public void goalsSurviveARestart()
	{
		Goal goal = addSkill("Colossal pouch");
		store.complete(goal.id);

		GoalStore afterRestart = new GoalStore(profiles, GSON);
		List<Goal> goals = afterRestart.list();
		assertEquals(1, goals.size());
		assertTrue(goals.get(0).done);
		assertEquals(goal.id, goals.get(0).id);
		assertTrue(goals.get(0).doneAt > 0);
	}

	@Test
	public void eachCharacterHasTheirOwnGoals()
	{
		addSkill("First character's goal");
		profiles.profile = "rsprofile.two";
		assertTrue(store.list().isEmpty());
		store.add(Goal.Type.CUSTOM, "Second character's goal", "", 0);
		profiles.profile = "rsprofile.one";
		assertEquals("First character's goal", store.list().get(0).title);
		assertEquals(1, store.list().size());
	}

	@Test
	public void nothingWorksWhileLoggedOut()
	{
		profiles.profile = null;
		assertFalse(store.available());
		assertTrue(store.list().isEmpty());
		assertRejected(() -> addSkill("x"), "log in first");
		assertRejected(() -> store.complete("x"), "log in first");
		assertRejected(() -> store.remove("x"), "log in first");
		assertTrue(store.applyProgress(AccountProfile.notLoggedIn()).isEmpty());
	}

	@Test
	public void completeAndRemoveByIdOrTitle()
	{
		Goal first = addSkill("Colossal pouch");
		Goal second = store.add(Goal.Type.CUSTOM, "Get the fire cape", "", 0);

		assertEquals(first.id, store.complete(first.id).id);
		assertTrue(store.list().get(0).done);
		assertEquals(second.id, store.remove("FIRE CAPE").id);
		assertEquals(1, store.list().size());
		assertEquals("already done, so unchanged", first.id, store.complete("colossal").id);
	}

	@Test
	public void ambiguousAndMissingGoalsExplainThemselves()
	{
		store.add(Goal.Type.CUSTOM, "Get the fire cape", "", 0);
		store.add(Goal.Type.CUSTOM, "Get the infernal cape", "", 0);
		assertRejected(() -> store.remove("cape"), "matches several goals");
		assertRejected(() -> store.remove("unicorn"), "No goal matches");
		assertRejected(() -> store.remove("  "), "Say which goal");
	}

	@Test
	public void anIdenticalOpenGoalIsNotAddedTwice()
	{
		Goal first = addSkill("Colossal pouch");
		Goal again = store.add(Goal.Type.SKILL, "A different title", "crafting", 56);
		assertEquals(first.id, again.id);
		assertEquals(1, store.list().size());

		Goal other = store.add(Goal.Type.SKILL, "", "Crafting", 60);
		assertNotEquals(first.id, other.id);
		store.add(Goal.Type.CUSTOM, "Same words", "", 0);
		store.add(Goal.Type.CUSTOM, "Other words", "", 0);
		assertEquals(4, store.list().size());
	}

	@Test
	public void goalsAreLimitedButFinishedOnesMakeRoom()
	{
		for (int i = 0; i < GoalStore.MAX_GOALS; i++)
		{
			store.add(Goal.Type.CUSTOM, "Goal " + i, "", 0);
		}
		assertRejected(() -> store.add(Goal.Type.CUSTOM, "One too many", "", 0), "already");

		store.complete("Goal 3");
		Goal fresh = store.add(Goal.Type.CUSTOM, "Fits now", "", 0);
		assertNotNull(fresh);
		assertEquals(GoalStore.MAX_GOALS, store.list().size());
		assertTrue("the finished goal made room", store.list().stream().noneMatch(g -> g.title.equals("Goal 3")));
	}

	@Test
	public void longTitlesAreRejected()
	{
		assertRejected(() -> store.add(Goal.Type.CUSTOM, new String(new char[GoalStore.MAX_TITLE_LENGTH + 1]).replace('\0', 'x'),
			"", 0), "too long");
	}

	@Test
	public void damagedSavedGoalsNeverBreakTheStore()
	{
		profiles.data.put("rsprofile.one|goals", "this is not json {{{");
		assertTrue(store.list().isEmpty());
		assertNotNull("still usable", store.add(Goal.Type.CUSTOM, "Fresh start", "", 0));

		FakeProfileStore other = new FakeProfileStore();
		other.data.put("rsprofile.one|goals", "[null,{\"id\":\"abc123\",\"type\":\"CUSTOM\",\"title\":\"Kept\"},{\"id\":\"x\"}]");
		List<Goal> kept = new GoalStore(other, GSON).list();
		assertEquals(1, kept.size());
		assertEquals("Kept", kept.get(0).title);
		assertEquals("", kept.get(0).target);
	}

	@Test
	public void listenersHearAboutEveryChange()
	{
		AtomicInteger calls = new AtomicInteger();
		Runnable listener = calls::incrementAndGet;
		store.addListener(listener);
		Goal goal = addSkill("Colossal pouch");
		store.complete(goal.id);
		store.remove(goal.id);
		assertEquals(3, calls.get());

		store.removeListener(listener);
		addSkill("Quiet");
		assertEquals(3, calls.get());
	}

	@Test
	public void reachedGoalsAreMarkedDoneOnceAndListedAfterOpenOnes()
	{
		// The sample account has Crafting 42, a finished Cook's Assistant and 14 molten glass.
		AccountProfile profile = TestProfiles.ironmanZerkerWithItems();
		store.add(Goal.Type.CUSTOM, "Manual goal", "", 0);
		store.add(Goal.Type.SKILL, "Reached already", "Crafting", 40);
		store.add(Goal.Type.QUEST, "", "Cook's Assistant", 0);
		store.add(Goal.Type.SKILL, "Still far away", "Crafting", 56);

		List<GoalStore.View> views = store.applyProgress(profile);

		assertEquals(4, views.size());
		assertEquals("Manual goal", views.get(0).goal.title);
		assertEquals("Still far away", views.get(1).goal.title);
		assertFalse(views.get(1).goal.done);
		assertTrue(views.get(2).goal.done);
		assertTrue(views.get(3).goal.done);
		assertEquals("saved, so it stays done", 2, store.list().stream().filter(g -> g.done).count());

		// Running it again changes nothing and does not rewrite the goals.
		AtomicInteger saves = new AtomicInteger();
		store.addListener(saves::incrementAndGet);
		store.applyProgress(profile);
		assertEquals(0, saves.get());
	}

	@Test
	public void progressWithoutAnAccountNeverCompletesAnything()
	{
		store.add(Goal.Type.SKILL, "", "Crafting", 40);
		List<GoalStore.View> views = store.applyProgress(AccountProfile.notLoggedIn());
		assertFalse(views.get(0).goal.done);
		assertFalse(views.get(0).progress.known);
	}
}
