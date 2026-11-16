package com.bardosrs.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/** Guesses the account build from real skill levels. A guess only: the player can override it in settings. */
final class BuildDetector
{
	/** Combat level at or below which a high non-combat total suggests a skiller. */
	private static final int SKILLER_MAX_COMBAT = 25;
	private static final int SKILLER_MIN_NON_COMBAT_TOTAL = 700;
	private static final int PURE_MIN_OFFENCE = 40;
	private static final int ZERKER_MIN_OFFENCE = 60;
	private static final int ZERKER_DEFENCE = 45;

	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.RANGED, Skill.PRAYER, Skill.MAGIC);

	private BuildDetector()
	{
	}

	static AccountBuild detect(Map<Skill, Integer> levels, int combatLevel)
	{
		int attack = level(levels, Skill.ATTACK);
		int strength = level(levels, Skill.STRENGTH);
		int defence = level(levels, Skill.DEFENCE);
		int hitpoints = level(levels, Skill.HITPOINTS);
		int ranged = level(levels, Skill.RANGED);
		int magic = level(levels, Skill.MAGIC);

		if (combatLevel == 3)
		{
			return AccountBuild.LEVEL3_SKILLER;
		}
		if (hitpoints == 10)
		{
			return AccountBuild.TEN_HP;
		}
		int bestOffence = Math.max(Math.max(attack, strength), Math.max(ranged, magic));
		if (defence == 1 && bestOffence >= PURE_MIN_OFFENCE)
		{
			return AccountBuild.DEF1_PURE;
		}
		if (defence == ZERKER_DEFENCE && Math.max(attack, strength) >= ZERKER_MIN_OFFENCE)
		{
			return AccountBuild.ZERKER;
		}
		if (combatLevel <= SKILLER_MAX_COMBAT && nonCombatTotal(levels) >= SKILLER_MIN_NON_COMBAT_TOTAL)
		{
			return AccountBuild.SKILLER;
		}
		return AccountBuild.MAIN;
	}

	private static int nonCombatTotal(Map<Skill, Integer> levels)
	{
		int total = 0;
		for (Map.Entry<Skill, Integer> entry : levels.entrySet())
		{
			if (!isCombat(entry.getKey()))
			{
				total += entry.getValue();
			}
		}
		return total;
	}

	private static boolean isCombat(Skill skill)
	{
		return COMBAT_SKILLS.contains(skill) || skill == Skill.OVERALL;
	}

	private static int level(Map<Skill, Integer> levels, Skill skill)
	{
		Integer level = levels.get(skill);
		return level == null ? 1 : level;
	}
}
