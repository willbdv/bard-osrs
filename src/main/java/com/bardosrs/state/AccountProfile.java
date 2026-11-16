package com.bardosrs.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;

/**
 * An immutable snapshot of the player's own account: type, build, levels and
 * quests. Only the local player is ever read, never other players. It also
 * knows how to describe itself to the model, including the advice constraints
 * the account type and build imply.
 */
public final class AccountProfile
{
	public enum Status
	{
		/** The player has switched account sharing off. */
		NOT_SHARED,
		NOT_LOGGED_IN,
		/** The game could not be read just now (still loading, or the read timed out). */
		UNAVAILABLE,
		READY
	}

	private static final AccountProfile NOT_SHARED = new AccountProfile(Status.NOT_SHARED);
	private static final AccountProfile NOT_LOGGED_IN = new AccountProfile(Status.NOT_LOGGED_IN);
	private static final AccountProfile UNAVAILABLE = new AccountProfile(Status.UNAVAILABLE);

	/** Hitpoints never goes below 10, so anything lower means the stats have not loaded yet. */
	private static final int MIN_LOADED_HITPOINTS = 10;
	private static final int MAX_MATCHES = 15;

	public final Status status;
	public final AccountType accountType;
	public final boolean membersWorld;
	public final int combatLevel;
	public final int totalLevel;
	public final int questPoints;
	/** What the levels suggest, and what is used: the player's setting if they overrode it. */
	public final AccountBuild detectedBuild;
	public final AccountBuild build;
	public final boolean buildOverridden;

	private final Map<Skill, Integer> levels;
	private final Map<Skill, Integer> xp;
	private final Map<String, QuestState> quests;
	/** Inventory, equipment and bank, or null when the player has not agreed to share items. */
	private final ItemsSnapshot items;

	private AccountProfile(Status status)
	{
		this.status = status;
		this.accountType = null;
		this.membersWorld = false;
		this.combatLevel = 0;
		this.totalLevel = 0;
		this.questPoints = 0;
		this.detectedBuild = null;
		this.build = null;
		this.buildOverridden = false;
		this.levels = Collections.emptyMap();
		this.xp = Collections.emptyMap();
		this.quests = Collections.emptyMap();
		this.items = null;
	}

	/** The same profile with the items taken out, for when the player switches item sharing off. */
	private AccountProfile(AccountProfile base)
	{
		this.status = base.status;
		this.accountType = base.accountType;
		this.membersWorld = base.membersWorld;
		this.combatLevel = base.combatLevel;
		this.totalLevel = base.totalLevel;
		this.questPoints = base.questPoints;
		this.detectedBuild = base.detectedBuild;
		this.build = base.build;
		this.buildOverridden = base.buildOverridden;
		this.levels = base.levels;
		this.xp = base.xp;
		this.quests = base.quests;
		this.items = null;
	}

	private AccountProfile(AccountType accountType, boolean membersWorld, Map<Skill, Integer> levels,
		Map<Skill, Integer> xp, int questPoints, Map<String, QuestState> quests, AccountBuild configuredBuild,
		ItemsSnapshot items)
	{
		this.status = Status.READY;
		this.items = items;
		this.accountType = accountType;
		this.membersWorld = membersWorld;
		this.levels = Collections.unmodifiableMap(copy(levels));
		this.xp = Collections.unmodifiableMap(copy(xp));
		this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
		this.questPoints = questPoints;

		this.combatLevel = Experience.getCombatLevel(level(Skill.ATTACK), level(Skill.STRENGTH), level(Skill.DEFENCE),
			level(Skill.HITPOINTS), level(Skill.MAGIC), level(Skill.RANGED), level(Skill.PRAYER));
		int total = 0;
		for (Map.Entry<Skill, Integer> entry : this.levels.entrySet())
		{
			if (entry.getKey() != Skill.OVERALL)
			{
				total += entry.getValue();
			}
		}
		this.totalLevel = total;

		this.detectedBuild = BuildDetector.detect(this.levels, combatLevel);
		this.buildOverridden = configuredBuild != null && configuredBuild != AccountBuild.AUTO;
		this.build = buildOverridden ? configuredBuild : detectedBuild;
	}

	private static Map<Skill, Integer> copy(Map<Skill, Integer> source)
	{
		Map<Skill, Integer> copy = new EnumMap<>(Skill.class);
		copy.putAll(source);
		return copy;
	}

	public static AccountProfile notShared()
	{
		return NOT_SHARED;
	}

	public static AccountProfile notLoggedIn()
	{
		return NOT_LOGGED_IN;
	}

	public static AccountProfile unavailable()
	{
		return UNAVAILABLE;
	}

	/** Builds a profile with no items; see the overload with items. */
	public static AccountProfile ready(AccountType accountType, boolean membersWorld, Map<Skill, Integer> levels,
		Map<Skill, Integer> xp, int questPoints, Map<String, QuestState> quests, AccountBuild configuredBuild)
	{
		return ready(accountType, membersWorld, levels, xp, questPoints, quests, configuredBuild, null);
	}

	/** Builds a profile from what the game client reported; returns "unavailable" if the stats have not loaded. */
	public static AccountProfile ready(AccountType accountType, boolean membersWorld, Map<Skill, Integer> levels,
		Map<Skill, Integer> xp, int questPoints, Map<String, QuestState> quests, AccountBuild configuredBuild,
		ItemsSnapshot items)
	{
		Integer hitpoints = levels.get(Skill.HITPOINTS);
		if (hitpoints == null || hitpoints < MIN_LOADED_HITPOINTS)
		{
			return UNAVAILABLE;
		}
		return new AccountProfile(accountType == null ? AccountType.NORMAL : accountType, membersWorld, levels, xp,
			questPoints, quests, configuredBuild, items);
	}

	/** The player's items, or null if they have not agreed to share them. */
	public ItemsSnapshot items()
	{
		return items;
	}

	public AccountProfile withoutItems()
	{
		return items == null ? this : new AccountProfile(this);
	}

	public boolean isReady()
	{
		return status == Status.READY;
	}

	public int level(Skill skill)
	{
		Integer level = levels.get(skill);
		return level == null ? 1 : level;
	}

	public int xp(Skill skill)
	{
		Integer value = xp.get(skill);
		return value == null ? 0 : value;
	}

	public int questCount(QuestState state)
	{
		int count = 0;
		for (QuestState value : quests.values())
		{
			if (value == state)
			{
				count++;
			}
		}
		return count;
	}

	public List<String> questsWithState(QuestState state)
	{
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, QuestState> entry : quests.entrySet())
		{
			if (entry.getValue() == state)
			{
				names.add(entry.getKey());
			}
		}
		return names;
	}

	/** The state of the quest with exactly this name (ignoring case), or null if there is no such quest. */
	public QuestState questState(String name)
	{
		for (Map.Entry<String, QuestState> entry : quests.entrySet())
		{
			if (entry.getKey().equalsIgnoreCase(name.trim()))
			{
				return entry.getValue();
			}
		}
		return null;
	}

	/** Quests whose name contains the given text, case-insensitively, up to a small cap. */
	public Map<String, QuestState> findQuests(String partialName)
	{
		String needle = partialName.toLowerCase(Locale.ROOT).trim();
		Map<String, QuestState> matches = new LinkedHashMap<>();
		for (Map.Entry<String, QuestState> entry : quests.entrySet())
		{
			if (entry.getKey().toLowerCase(Locale.ROOT).contains(needle))
			{
				matches.put(entry.getKey(), entry.getValue());
				if (matches.size() >= MAX_MATCHES)
				{
					break;
				}
			}
		}
		return matches;
	}

	/** Why the account cannot be read, in words for the model; empty when it can. */
	public String unavailableReason()
	{
		switch (status)
		{
			case NOT_SHARED:
				return "The player has not shared their account data (it is switched off in Bard's settings), so it "
					+ "cannot be read. Ask them instead.";
			case NOT_LOGGED_IN:
				return "The player is not logged in, so their account cannot be read. Ask them instead.";
			case UNAVAILABLE:
				return "The player's account could not be read just now (the game may still be loading). Ask them "
					+ "instead, or try again shortly.";
			default:
				return "";
		}
	}

	public String modeLabel()
	{
		if (accountType == null)
		{
			return "Unknown";
		}
		switch (accountType)
		{
			case IRONMAN:
				return "Ironman";
			case ULTIMATE_IRONMAN:
				return "Ultimate ironman";
			case HARDCORE_IRONMAN:
				return "Hardcore ironman";
			case GROUP_IRONMAN:
				return "Group ironman";
			case HARDCORE_GROUP_IRONMAN:
				return "Hardcore group ironman";
			default:
				return "Regular";
		}
	}

	private static String modeRules(AccountType type)
	{
		switch (type)
		{
			case IRONMAN:
				return "Cannot trade with players or use the Grand Exchange: every item must be gathered, made, bought "
					+ "from NPC shops or earned. Recommend self-sufficient methods, and never suggest buying from the "
					+ "Grand Exchange or trading.";
			case ULTIMATE_IRONMAN:
				return "All ironman restrictions (no trading, no Grand Exchange), and also cannot use banks, so "
					+ "storage is limited to the inventory, equipment and a few special storage items. Favour "
					+ "methods that fit inventory limits, and never suggest buying from the Grand Exchange or trading.";
			case HARDCORE_IRONMAN:
				return "All ironman restrictions (no trading, no Grand Exchange), and only one life: dying in most "
					+ "situations permanently ends hardcore status. Favour safe methods and warn clearly about risk. "
					+ "Never suggest buying from the Grand Exchange or trading.";
			case GROUP_IRONMAN:
				return "Ironman restrictions apply (no Grand Exchange, no trading outside the group), but items can be "
					+ "shared with group members. Never suggest buying from the Grand Exchange.";
			case HARDCORE_GROUP_IRONMAN:
				return "Group ironman restrictions (no Grand Exchange, no trading outside the group) plus hardcore "
					+ "death risk. Favour safe methods and warn clearly about risk. Never suggest buying from the "
					+ "Grand Exchange.";
			default:
				return "Can trade and use the Grand Exchange, so cost, profit and buying supplies are valid "
					+ "considerations.";
		}
	}

	/** A short line for the chat panel, e.g. "Ironman | Zerker (auto) | CB 87". */
	public String summary()
	{
		switch (status)
		{
			case NOT_SHARED:
				return "Account data: off (see Bard's settings)";
			case NOT_LOGGED_IN:
				return "Account: not logged in";
			case UNAVAILABLE:
				return "Account: could not be read";
			default:
				String line = modeLabel() + " | " + build.shortLabel() + (buildOverridden ? "" : " (auto)") + " | CB "
					+ combatLevel;
				return items == null ? line : line + "\n" + items.summaryLine();
		}
	}

	/** The account section of the system prompt. */
	public String promptSection()
	{
		if (!isReady())
		{
			return "Player account: " + unavailableReason() + " Ask about account type and stats when they matter.";
		}

		StringBuilder text = new StringBuilder();
		text.append("Player account, read from their game client when they asked this question. Use it to tailor "
			+ "your advice, and do not ask for details you already have:\n");
		text.append("- Account type: ").append(modeLabel()).append(". ").append(modeRules(accountType)).append('\n');
		text.append("- Build: ").append(build.label())
			.append(buildOverridden
				? " (set by the player)."
				: " (guessed from their levels; the player can change it in Bard's settings).");
		if (!build.rules().isEmpty())
		{
			text.append(' ').append(build.rules());
		}
		text.append('\n');
		text.append("- Combat level ").append(combatLevel).append(", total level ").append(totalLevel).append(", ")
			.append(questPoints).append(" quest points, ")
			.append(membersWorld ? "on a members world (so a member)." : "on a free-to-play world (membership unknown).")
			.append('\n');
		text.append("- Levels (xp): ").append(skillLine()).append(".\n");
		text.append("- Quests: ").append(questCount(QuestState.FINISHED)).append(" finished, ")
			.append(questCount(QuestState.IN_PROGRESS)).append(" in progress, ")
			.append(questCount(QuestState.NOT_STARTED))
			.append(" not started. Use the get_quests tool to see which, or to check one quest.\n");
		if (items != null)
		{
			text.append(items.promptSection()).append('\n');
		}
		text.append("The account type and build rules above override a method that would otherwise be best.");
		return text.toString();
	}

	private String skillLine()
	{
		StringBuilder line = new StringBuilder();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL || !levels.containsKey(skill))
			{
				continue;
			}
			if (line.length() > 0)
			{
				line.append(", ");
			}
			line.append(skill.getName()).append(' ').append(level(skill)).append(" (")
				.append(String.format(Locale.ROOT, "%,d", xp(skill))).append(" xp)");
		}
		return line.toString();
	}
}
