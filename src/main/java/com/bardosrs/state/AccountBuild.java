package com.bardosrs.state;

/**
 * The kind of account the player is building. Builds are choices about which
 * skills to avoid, so stats alone cannot prove them; Bard guesses from the
 * levels and the player can override the guess in settings.
 */
public enum AccountBuild
{
	AUTO("Auto-detect", "Auto", ""),
	MAIN("Main / all-rounder", "Main", ""),
	ZERKER("Zerker (45 Defence)", "Zerker",
		"Defence is kept at 45, so never recommend training Defence past 45. Avoid quests, lamps and methods that "
			+ "would push Defence above 45 (check xp rewards first). Attack and Strength are the focus."),
	DEF1_PURE("1 Defence pure", "1 Defence pure",
		"Defence must stay at level 1. Never recommend quests, lamps, minigames or combat styles that give Defence "
			+ "xp (check each quest's xp rewards first), and avoid content that needs Defence."),
	TEN_HP("10 Hitpoints", "10 HP",
		"Hitpoints must stay at 10, so the player gains no Hitpoints xp, which means no combat xp of any kind. "
			+ "Avoid fighting, and any quest or activity that rewards Hitpoints or combat xp (check xp rewards first)."),
	LEVEL3_SKILLER("Level 3 skiller", "Level 3 skiller",
		"Combat level 3: every combat skill at 1 and Hitpoints 10, so the player never fights or gains combat xp. "
			+ "Suggest only non-combat methods, and avoid quests and activities that need or reward combat xp "
			+ "(check xp rewards first)."),
	SKILLER("Skiller", "Skiller",
		"The player avoids training combat and focuses on non-combat skills. Prefer non-combat methods, avoid "
			+ "quests or activities that need heavy combat, and check xp rewards before recommending combat xp.");

	private final String label;
	private final String shortLabel;
	private final String rules;

	AccountBuild(String label, String shortLabel, String rules)
	{
		this.label = label;
		this.shortLabel = shortLabel;
		this.rules = rules;
	}

	public String label()
	{
		return label;
	}

	/** Advice constraints this build implies, or an empty string if it adds none. */
	public String rules()
	{
		return rules;
	}

	/** A compact name for the panel line, e.g. "Zerker" or "10 HP". */
	public String shortLabel()
	{
		return shortLabel;
	}

	/** Shown in RuneLite's settings dropdown. */
	@Override
	public String toString()
	{
		return label;
	}
}
