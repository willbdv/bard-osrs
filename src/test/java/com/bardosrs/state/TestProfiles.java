package com.bardosrs.state;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;

/** Builds account profiles from plain data, the way the reader would from the game client. */
public final class TestProfiles
{
	private TestProfiles()
	{
	}

	/** Every skill at level 1 (Hitpoints 10), then the given overrides as Skill, level pairs. */
	public static Map<Skill, Integer> levels(Object... overrides)
	{
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				levels.put(skill, skill == Skill.HITPOINTS ? 10 : 1);
			}
		}
		for (int i = 0; i < overrides.length; i += 2)
		{
			levels.put((Skill) overrides[i], (Integer) overrides[i + 1]);
		}
		return levels;
	}

	public static Map<String, QuestState> quests()
	{
		Map<String, QuestState> quests = new LinkedHashMap<>();
		quests.put("Cook's Assistant", QuestState.FINISHED);
		quests.put("Dragon Slayer I", QuestState.IN_PROGRESS);
		quests.put("Dragon Slayer II", QuestState.NOT_STARTED);
		quests.put("Monkey Madness I", QuestState.NOT_STARTED);
		return quests;
	}

	public static AccountProfile ready(AccountType type, Map<Skill, Integer> levels, AccountBuild configured)
	{
		Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		for (Map.Entry<Skill, Integer> entry : levels.entrySet())
		{
			xp.put(entry.getKey(), Experience.getXpForLevel(entry.getValue()));
		}
		return AccountProfile.ready(type, true, levels, xp, 5, quests(), configured);
	}

	public static AccountProfile ironmanZerker()
	{
		return ready(AccountType.IRONMAN, levels(
			Skill.ATTACK, 75, Skill.STRENGTH, 80, Skill.DEFENCE, 45, Skill.HITPOINTS, 78, Skill.RANGED, 60,
			Skill.PRAYER, 52, Skill.MAGIC, 55, Skill.CRAFTING, 42), AccountBuild.AUTO);
	}

	public static ItemsSnapshot sampleItems()
	{
		return ItemsSnapshot.of(
			java.util.Arrays.asList(new ItemStack(1, "Molten glass", 14), new ItemStack(2, "Glassblowing pipe", 1)),
			java.util.Collections.singletonList(new ItemStack(3, "Dragon scimitar", 1)),
			java.util.Arrays.asList(new ItemStack(995, "Coins", 2_345_678), new ItemStack(4, "Soda ash", 300)),
			25);
	}

	public static AccountProfile ironmanZerkerWithItems()
	{
		Map<Skill, Integer> levels = levels(Skill.ATTACK, 75, Skill.STRENGTH, 80, Skill.DEFENCE, 45,
			Skill.HITPOINTS, 78, Skill.CRAFTING, 42);
		Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		for (Map.Entry<Skill, Integer> entry : levels.entrySet())
		{
			xp.put(entry.getKey(), Experience.getXpForLevel(entry.getValue()));
		}
		return AccountProfile.ready(AccountType.IRONMAN, true, levels, xp, 5, quests(), AccountBuild.AUTO, sampleItems());
	}
}
