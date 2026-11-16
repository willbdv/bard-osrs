package com.bardosrs.state;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.vars.AccountType;

/** Copies the local player's account out of the game client. Must run on the client thread. */
final class AccountReader
{
	private AccountReader()
	{
	}

	/** @param items supplies the player's items when they have agreed to share them, otherwise null */
	static AccountProfile read(Client client, AccountBuild configuredBuild, Supplier<ItemsSnapshot> items)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return AccountProfile.notLoggedIn();
		}

		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				levels.put(skill, client.getRealSkillLevel(skill));
				xp.put(skill, client.getSkillExperience(skill));
			}
		}

		Map<String, QuestState> quests = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			quests.put(quest.getName(), quest.getState(client));
		}

		AccountType type = client.getAccountType();
		boolean membersWorld = client.getWorldType().contains(WorldType.MEMBERS);
		return AccountProfile.ready(type == null ? AccountType.NORMAL : type, membersWorld, levels, xp,
			client.getVarpValue(VarPlayerID.QP), quests, configuredBuild, items == null ? null : items.get());
	}
}
