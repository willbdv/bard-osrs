package com.bardosrs.goals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.TestProfiles;
import org.junit.Test;

public class GoalProgressTest
{
	private static Goal goal(Goal.Type type, String target, long amount)
	{
		return new Goal("id0001", type, "t", target, amount, 1, 1, false, 0);
	}

	private final AccountProfile profile = TestProfiles.ironmanZerkerWithItems();

	@Test
	public void aSkillGoalShowsXpToGo()
	{
		GoalProgress progress = GoalProgress.of(goal(Goal.Type.SKILL, "Crafting", 56), profile);
		assertTrue(progress.known);
		assertFalse(progress.reached);
		assertEquals("Crafting 42 -> 56 (138,511 xp to go)", progress.text);
		assertEquals(45529.0 / 184040.0, progress.fraction, 0.0001);
	}

	@Test
	public void aSkillGoalIsReachedByXpNotJustLevel()
	{
		GoalProgress reached = GoalProgress.of(goal(Goal.Type.SKILL, "Crafting", 42), profile);
		assertTrue(reached.reached);
		assertEquals(1.0, reached.fraction, 0.0);
		assertEquals("Reached level 42", reached.text);
		assertTrue(GoalProgress.of(goal(Goal.Type.SKILL, "Crafting", 10), profile).reached);
	}

	@Test
	public void anUnknownSkillIsNotKnown()
	{
		GoalProgress progress = GoalProgress.of(goal(Goal.Type.SKILL, "Basketweaving", 50), profile);
		assertFalse(progress.known);
		assertEquals("Unknown skill", progress.text);
	}

	@Test
	public void questGoalsFollowQuestState()
	{
		GoalProgress finished = GoalProgress.of(goal(Goal.Type.QUEST, "Cook's Assistant", 0), profile);
		assertTrue(finished.reached);
		assertEquals("Finished", finished.text);

		GoalProgress started = GoalProgress.of(goal(Goal.Type.QUEST, "dragon slayer i", 0), profile);
		assertFalse(started.reached);
		assertEquals(0.5, started.fraction, 0.0);
		assertEquals("In progress", started.text);

		GoalProgress notStarted = GoalProgress.of(goal(Goal.Type.QUEST, "Monkey Madness I", 0), profile);
		assertEquals("Not started", notStarted.text);
		assertEquals(0.0, notStarted.fraction, 0.0);

		assertFalse(GoalProgress.of(goal(Goal.Type.QUEST, "Not A Quest", 0), profile).known);
	}

	@Test
	public void itemGoalsCountEverywhereTheItemIsHeld()
	{
		GoalProgress partway = GoalProgress.of(goal(Goal.Type.ITEM, "Soda ash", 1000), profile);
		assertEquals("300 / 1,000", partway.text);
		assertEquals(0.3, partway.fraction, 0.0001);

		GoalProgress done = GoalProgress.of(goal(Goal.Type.ITEM, "molten glass", 14), profile);
		assertTrue(done.reached);
		assertEquals("Have 14", done.text);

		GoalProgress none = GoalProgress.of(goal(Goal.Type.ITEM, "Dragon bones", 5), profile);
		assertEquals("0 / 5", none.text);
		assertFalse(none.reached);
	}

	@Test
	public void itemGoalsNeedItemSharing()
	{
		GoalProgress progress = GoalProgress.of(goal(Goal.Type.ITEM, "Soda ash", 10), TestProfiles.ironmanZerker());
		assertFalse(progress.known);
		assertEquals("Turn on item sharing to track this", progress.text);
	}

	@Test
	public void customGoalsAreTickedOffByHand()
	{
		GoalProgress progress = GoalProgress.of(goal(Goal.Type.CUSTOM, "", 0), profile);
		assertFalse(progress.known);
		assertFalse(progress.reached);
	}

	@Test
	public void doneGoalsAreDoneWhateverTheAccountSays()
	{
		Goal done = new Goal("id0001", Goal.Type.SKILL, "t", "Crafting", 99, 1, 1, true, 5);
		GoalProgress progress = GoalProgress.of(done, AccountProfile.notLoggedIn());
		assertTrue(progress.reached);
		assertEquals("Done", progress.text);
	}

	@Test
	public void withoutAnAccountProgressIsUnknownAndSaysWhy()
	{
		Goal skill = goal(Goal.Type.SKILL, "Crafting", 56);
		assertEquals("Log in to see progress", GoalProgress.of(skill, AccountProfile.notLoggedIn()).text);
		assertEquals("Progress unavailable right now", GoalProgress.of(skill, AccountProfile.unavailable()).text);
		assertEquals("Progress hidden: account data is not shared", GoalProgress.of(skill, AccountProfile.notShared()).text);
		assertFalse(GoalProgress.of(skill, AccountProfile.notLoggedIn()).known);
	}
}
