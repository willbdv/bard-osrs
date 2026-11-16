package com.bardosrs.goals;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.ItemsSnapshot;
import com.bardosrs.tools.XpTable;
import java.util.Locale;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/** How far along a goal is, worked out from the player's account. */
public final class GoalProgress
{
	/** False when progress cannot be told (custom goals, or the account is not available). */
	public final boolean known;
	public final boolean reached;
	/** 0 to 1, for a progress bar. */
	public final double fraction;
	public final String text;

	private GoalProgress(boolean known, boolean reached, double fraction, String text)
	{
		this.known = known;
		this.reached = reached;
		this.fraction = Math.max(0, Math.min(1, fraction));
		this.text = text;
	}

	public static GoalProgress of(Goal goal, AccountProfile profile)
	{
		if (goal.done)
		{
			return new GoalProgress(true, true, 1, "Done");
		}
		if (goal.type == Goal.Type.CUSTOM)
		{
			return new GoalProgress(false, false, 0, "Tick it off when you finish");
		}
		if (!profile.isReady())
		{
			return new GoalProgress(false, false, 0, unavailable(profile));
		}
		switch (goal.type)
		{
			case SKILL:
				return skill(goal, profile);
			case QUEST:
				return quest(goal, profile);
			default:
				return item(goal, profile);
		}
	}

	private static String unavailable(AccountProfile profile)
	{
		switch (profile.status)
		{
			case NOT_LOGGED_IN:
				return "Log in to see progress";
			case NOT_SHARED:
				return "Progress hidden: account data is not shared";
			default:
				return "Progress unavailable right now";
		}
	}

	private static GoalProgress skill(Goal goal, AccountProfile profile)
	{
		Skill skill;
		try
		{
			skill = GoalTargets.skill(goal.target);
		}
		catch (GoalException e)
		{
			return new GoalProgress(false, false, 0, "Unknown skill");
		}
		long targetXp = XpTable.xpForLevel((int) goal.amount);
		long currentXp = profile.xp(skill);
		if (currentXp >= targetXp)
		{
			return new GoalProgress(true, true, 1, "Reached level " + goal.amount);
		}
		return new GoalProgress(true, false, targetXp == 0 ? 0 : (double) currentXp / targetXp,
			skill.getName() + " " + profile.level(skill) + " -> " + goal.amount + " ("
				+ String.format(Locale.ROOT, "%,d", targetXp - currentXp) + " xp to go)");
	}

	private static GoalProgress quest(Goal goal, AccountProfile profile)
	{
		QuestState state = profile.questState(goal.target);
		if (state == null)
		{
			return new GoalProgress(false, false, 0, "Quest not recognised");
		}
		switch (state)
		{
			case FINISHED:
				return new GoalProgress(true, true, 1, "Finished");
			case IN_PROGRESS:
				return new GoalProgress(true, false, 0.5, "In progress");
			default:
				return new GoalProgress(true, false, 0, "Not started");
		}
	}

	private static GoalProgress item(Goal goal, AccountProfile profile)
	{
		ItemsSnapshot items = profile.items();
		if (items == null)
		{
			return new GoalProgress(false, false, 0, "Turn on item sharing to track this");
		}
		long have = items.countOf(goal.target);
		boolean reached = have >= goal.amount;
		return new GoalProgress(true, reached, goal.amount == 0 ? 0 : (double) have / goal.amount,
			reached ? "Have " + String.format(Locale.ROOT, "%,d", have)
				: String.format(Locale.ROOT, "%,d / %,d", have, goal.amount));
	}
}
