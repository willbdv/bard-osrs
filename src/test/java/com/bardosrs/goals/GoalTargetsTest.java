package com.bardosrs.goals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;

public class GoalTargetsTest
{
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

	@Test
	public void skillsResolveByNameAndCommonShortForms()
	{
		assertEquals(Skill.CRAFTING, GoalTargets.skill("Crafting"));
		assertEquals(Skill.CRAFTING, GoalTargets.skill("  crafting "));
		assertEquals(Skill.RUNECRAFT, GoalTargets.skill("Runecraft"));
		assertEquals(Skill.RUNECRAFT, GoalTargets.skill("rc"));
		assertEquals(Skill.HITPOINTS, GoalTargets.skill("hp"));
		assertEquals(Skill.RANGED, GoalTargets.skill("range"));
		assertEquals(Skill.CONSTRUCTION, GoalTargets.skill("con"));
	}

	@Test
	public void notASkillIsRejected()
	{
		assertRejected(() -> GoalTargets.skill("basketweaving"), "not a skill name");
		assertRejected(() -> GoalTargets.skill(""), "not a skill name");
		assertRejected(() -> GoalTargets.skill("overall"), "not a skill name");
		assertRejected(() -> GoalTargets.skill(null), "not a skill name");
	}

	@Test
	public void levelsMustBeReachable()
	{
		assertEquals(56, GoalTargets.level(56));
		assertEquals(126, GoalTargets.level(126));
		assertRejected(() -> GoalTargets.level(1), "between 2 and 126");
		assertRejected(() -> GoalTargets.level(127), "between 2 and 126");
		assertRejected(() -> GoalTargets.level(0), "between 2 and 126");
	}

	@Test
	public void questsResolveByExactNameIgnoringCase()
	{
		String name = Quest.values()[0].getName();
		assertEquals(name, GoalTargets.quest(name.toUpperCase()));
		assertEquals(name, GoalTargets.quest("  " + name.toLowerCase() + " "));
	}

	@Test
	public void aUniquePartialQuestNameIsAccepted()
	{
		// Pick a quest whose name is long enough to be unique when truncated by one character at the end.
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			String partial = name.substring(0, name.length() - 1).toLowerCase();
			long matches = java.util.Arrays.stream(Quest.values())
				.filter(q -> q.getName().toLowerCase().contains(partial)).count();
			if (matches == 1 && name.length() > 8)
			{
				assertEquals(name, GoalTargets.quest(partial));
				return;
			}
		}
		fail("no quest with a unique partial name found");
	}

	@Test
	public void ambiguousAndUnknownQuestsExplainThemselves()
	{
		assertRejected(() -> GoalTargets.quest("the"), "matches several quests");
		assertRejected(() -> GoalTargets.quest("qqqzzzxxx"), "No quest matches");
		assertRejected(() -> GoalTargets.quest("  "), "quest name is required");
		assertRejected(() -> GoalTargets.quest(null), "quest name is required");
	}
}
