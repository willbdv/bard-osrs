package com.bardosrs.goals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/** Turns what a player or model typed into a real skill or quest, or explains what was wrong. */
public final class GoalTargets
{
	/** The highest level a skill can show, counting virtual levels. */
	public static final int MAX_LEVEL = 126;
	private static final int MAX_SUGGESTIONS = 5;

	private GoalTargets()
	{
	}

	public static Skill skill(String input)
	{
		String wanted = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL && (skill.getName().toLowerCase(Locale.ROOT).equals(wanted)
				|| skill.name().toLowerCase(Locale.ROOT).equals(wanted)))
			{
				return skill;
			}
		}
		// Players say "rc" or "hp"; map the few common short forms.
		switch (wanted)
		{
			case "hp":
				return Skill.HITPOINTS;
			case "rc":
				return Skill.RUNECRAFT;
			case "range":
			case "ranging":
				return Skill.RANGED;
			case "agil":
				return Skill.AGILITY;
			case "herb":
				return Skill.HERBLORE;
			case "con":
			case "cons":
				return Skill.CONSTRUCTION;
			case "wc":
				return Skill.WOODCUTTING;
			default:
				throw new GoalException("'" + (input == null ? "" : input.trim()) + "' is not a skill name.");
		}
	}

	public static int level(long level)
	{
		if (level < 2 || level > MAX_LEVEL)
		{
			throw new GoalException("The level must be between 2 and " + MAX_LEVEL + ".");
		}
		return (int) level;
	}

	/** The official quest name for what was typed: exact match first, then a single partial match. */
	public static String quest(String input)
	{
		String wanted = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
		if (wanted.isEmpty())
		{
			throw new GoalException("A quest name is required.");
		}
		List<String> partial = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.equals(wanted))
			{
				return name;
			}
			if (lower.contains(wanted))
			{
				partial.add(name);
			}
		}
		if (partial.size() == 1)
		{
			return partial.get(0);
		}
		if (partial.isEmpty())
		{
			throw new GoalException("No quest matches '" + input.trim() + "'. Check the spelling, or search the wiki "
				+ "for the exact quest name.");
		}
		throw new GoalException("'" + input.trim() + "' matches several quests: "
			+ String.join(", ", partial.subList(0, Math.min(MAX_SUGGESTIONS, partial.size())))
			+ (partial.size() > MAX_SUGGESTIONS ? ", ..." : "") + ". Use the full name.");
	}
}
