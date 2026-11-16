package com.bardosrs.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;
import org.junit.Test;

public class AccountProfileTest
{
	private static AccountProfile main(Object... overrides)
	{
		return TestProfiles.ready(AccountType.NORMAL, TestProfiles.levels(overrides), AccountBuild.AUTO);
	}

	// ---- combat level and totals ----

	@Test
	public void combatLevelUsesTheRightSkillsInTheRightSlots()
	{
		assertEquals(3, main().combatLevel);

		Object[] maxed = new Object[14];
		Skill[] combat = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.RANGED, Skill.PRAYER, Skill.MAGIC};
		for (int i = 0; i < combat.length; i++)
		{
			maxed[i * 2] = combat[i];
			maxed[i * 2 + 1] = 99;
		}
		assertEquals(126, main(maxed).combatLevel);

		// Prayer counts for half and sits beside Defence and Hitpoints in the formula; swapping slots would show here.
		assertEquals(15, main(Skill.PRAYER, 99).combatLevel);
		assertEquals(67, main(Skill.ATTACK, 99, Skill.STRENGTH, 99).combatLevel);
	}

	@Test
	public void totalLevelAddsEverySkillOnce()
	{
		AccountProfile profile = main(Skill.CRAFTING, 42, Skill.MINING, 30);
		int skills = 0;
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				skills++;
			}
		}
		// All skills at 1 except Hitpoints (10), Crafting (42) and Mining (30).
		assertEquals(skills + 9 + 41 + 29, profile.totalLevel);
	}

	@Test
	public void statsThatHaveNotLoadedYetMeanUnavailable()
	{
		Map<Skill, Integer> levels = TestProfiles.levels();
		levels.put(Skill.HITPOINTS, 1);
		AccountProfile profile = TestProfiles.ready(AccountType.NORMAL, levels, AccountBuild.AUTO);
		assertEquals(AccountProfile.Status.UNAVAILABLE, profile.status);
		assertFalse(profile.isReady());
	}

	// ---- build detection ----

	@Test
	public void detectsEachBuildFromLevels()
	{
		assertEquals(AccountBuild.LEVEL3_SKILLER, main().build);
		assertEquals(AccountBuild.TEN_HP, main(Skill.ATTACK, 60, Skill.STRENGTH, 60).build);
		assertEquals(AccountBuild.DEF1_PURE,
			main(Skill.ATTACK, 60, Skill.STRENGTH, 60, Skill.HITPOINTS, 60).build);
		assertEquals(AccountBuild.ZERKER,
			main(Skill.ATTACK, 70, Skill.STRENGTH, 70, Skill.DEFENCE, 45, Skill.HITPOINTS, 70).build);
		assertEquals(AccountBuild.MAIN,
			main(Skill.ATTACK, 80, Skill.STRENGTH, 80, Skill.DEFENCE, 70, Skill.HITPOINTS, 80).build);
	}

	@Test
	public void aZerkerNeedsRealOffence()
	{
		assertEquals(AccountBuild.MAIN,
			main(Skill.ATTACK, 50, Skill.STRENGTH, 50, Skill.DEFENCE, 45, Skill.HITPOINTS, 50).build);
	}

	@Test
	public void aWeakFighterWithHighSkillsIsASkiller()
	{
		Object[] overrides = new Object[2 * 17 + 8];
		int i = 0;
		for (Skill skill : new Skill[]{Skill.COOKING, Skill.WOODCUTTING, Skill.FLETCHING, Skill.FISHING, Skill.FIREMAKING,
			Skill.CRAFTING, Skill.SMITHING, Skill.MINING, Skill.HERBLORE, Skill.AGILITY, Skill.THIEVING, Skill.SLAYER,
			Skill.FARMING, Skill.RUNECRAFT, Skill.HUNTER, Skill.CONSTRUCTION, Skill.SAILING})
		{
			overrides[i++] = skill;
			overrides[i++] = 50;
		}
		overrides[i++] = Skill.HITPOINTS;
		overrides[i++] = 20;
		overrides[i++] = Skill.ATTACK;
		overrides[i++] = 5;
		overrides[i++] = Skill.STRENGTH;
		overrides[i++] = 5;
		overrides[i++] = Skill.DEFENCE;
		overrides[i] = 5;
		AccountProfile profile = main(overrides);
		assertTrue("combat " + profile.combatLevel, profile.combatLevel <= 25);
		assertEquals(AccountBuild.SKILLER, profile.build);
	}

	@Test
	public void thePlayersSettingOverridesTheGuess()
	{
		AccountProfile profile = TestProfiles.ready(AccountType.NORMAL, TestProfiles.levels(
			Skill.ATTACK, 80, Skill.STRENGTH, 80, Skill.DEFENCE, 70, Skill.HITPOINTS, 80), AccountBuild.ZERKER);
		assertEquals(AccountBuild.MAIN, profile.detectedBuild);
		assertEquals(AccountBuild.ZERKER, profile.build);
		assertTrue(profile.buildOverridden);
		assertTrue(profile.promptSection().contains("set by the player"));
	}

	// ---- panel summary ----

	@Test
	public void summariesForEveryState()
	{
		assertEquals("Account data: off (see Bard's settings)", AccountProfile.notShared().summary());
		assertEquals("Account: not logged in", AccountProfile.notLoggedIn().summary());
		assertEquals("Account: could not be read", AccountProfile.unavailable().summary());

		String ironman = TestProfiles.ironmanZerker().summary();
		assertTrue(ironman, ironman.startsWith("Ironman | Zerker (auto) | CB "));

		AccountProfile overridden = TestProfiles.ready(AccountType.HARDCORE_IRONMAN,
			TestProfiles.levels(Skill.HITPOINTS, 40), AccountBuild.MAIN);
		assertTrue(overridden.summary(), overridden.summary().startsWith("Hardcore ironman | Main | CB "));
	}

	// ---- what the model is told ----

	@Test
	public void theIronmanZerkerPromptCarriesTheRulesAndTheNumbers()
	{
		String text = TestProfiles.ironmanZerker().promptSection();
		assertTrue(text, text.contains("Account type: Ironman."));
		assertTrue(text, text.contains("never suggest buying from the Grand Exchange"));
		assertTrue(text, text.contains("Build: Zerker (45 Defence) (guessed from their levels"));
		assertTrue(text, text.contains("never recommend training Defence past 45"));
		assertTrue(text, text.contains("Crafting 42 (45,529 xp)"));
		assertTrue(text, text.contains("Attack 75 ("));
		assertTrue(text, text.contains("1 finished, 1 in progress, 2 not started"));
		assertTrue(text, text.contains("get_quests"));
		assertTrue(text, text.contains("on a members world"));
		assertFalse("the account name must never be sent", text.toLowerCase().contains("name:"));
	}

	@Test
	public void aRegularMainGetsNoBuildRulesAndMayTrade()
	{
		String text = main(Skill.ATTACK, 80, Skill.STRENGTH, 80, Skill.DEFENCE, 70, Skill.HITPOINTS, 80).promptSection();
		assertTrue(text, text.contains("Account type: Regular. Can trade and use the Grand Exchange"));
		assertTrue(text, text.contains("Build: Main / all-rounder"));
		assertFalse(text, text.contains("never recommend"));
	}

	@Test
	public void everyAccountTypeHasItsOwnConstraints()
	{
		String levels = "x";
		assertTrue(promptFor(AccountType.ULTIMATE_IRONMAN).contains("cannot use banks"));
		assertTrue(promptFor(AccountType.HARDCORE_IRONMAN).contains("only one life"));
		assertTrue(promptFor(AccountType.GROUP_IRONMAN).contains("shared with group members"));
		assertTrue(promptFor(AccountType.HARDCORE_GROUP_IRONMAN).contains("hardcore death risk"));
		assertTrue(promptFor(AccountType.IRONMAN).contains("cannot trade") || promptFor(AccountType.IRONMAN).contains("Cannot trade"));
	}

	private static String promptFor(AccountType type)
	{
		return TestProfiles.ready(type, TestProfiles.levels(Skill.HITPOINTS, 40), AccountBuild.AUTO).promptSection();
	}

	@Test
	public void buildRulesExistForEveryNonTrivialBuild()
	{
		for (AccountBuild build : AccountBuild.values())
		{
			boolean trivial = build == AccountBuild.AUTO || build == AccountBuild.MAIN;
			assertEquals(build.name(), trivial, build.rules().isEmpty());
		}
		assertEquals("Zerker", AccountBuild.ZERKER.shortLabel());
		assertEquals("Main", AccountBuild.MAIN.shortLabel());
		assertEquals("10 HP", AccountBuild.TEN_HP.shortLabel());
		assertEquals("Zerker (45 Defence)", AccountBuild.ZERKER.toString());
	}

	@Test
	public void accountsThatCannotBeReadSayWhyAndTellTheModelToAsk()
	{
		assertTrue(AccountProfile.notShared().promptSection().contains("switched off in Bard's settings"));
		assertTrue(AccountProfile.notLoggedIn().promptSection().contains("not logged in"));
		assertTrue(AccountProfile.unavailable().promptSection().contains("could not be read"));
		for (AccountProfile profile : new AccountProfile[]{
			AccountProfile.notShared(), AccountProfile.notLoggedIn(), AccountProfile.unavailable()})
		{
			assertTrue(profile.promptSection().contains("Ask about account type and stats"));
			assertFalse(profile.unavailableReason().isEmpty());
		}
	}

	@Test
	public void freeWorldsDoNotClaimMembership()
	{
		AccountProfile free = AccountProfile.ready(AccountType.NORMAL, false, TestProfiles.levels(Skill.HITPOINTS, 40),
			new java.util.EnumMap<>(Skill.class), 0, TestProfiles.quests(), AccountBuild.AUTO);
		assertTrue(free.promptSection().contains("free-to-play world (membership unknown)"));
	}

	// ---- quests ----

	@Test
	public void questLookups()
	{
		AccountProfile profile = main(Skill.HITPOINTS, 40);
		assertEquals(1, profile.questCount(QuestState.FINISHED));
		assertEquals("Dragon Slayer I", profile.questsWithState(QuestState.IN_PROGRESS).get(0));
		Map<String, QuestState> slayer = profile.findQuests("dragon SLAYER");
		assertEquals(2, slayer.size());
		assertEquals(QuestState.IN_PROGRESS, slayer.get("Dragon Slayer I"));
		assertTrue(profile.findQuests("no such quest").isEmpty());
	}

	@Test
	public void itemsAppearInThePromptAndSummaryOnlyWhenShared()
	{
		AccountProfile shared = TestProfiles.ironmanZerkerWithItems();
		assertTrue(shared.promptSection(), shared.promptSection().contains("Inventory (right now): Molten glass x14"));
		assertTrue(shared.summary(), shared.summary().endsWith("\nBank: 2 items (25m ago)"));

		AccountProfile without = TestProfiles.ironmanZerker();
		assertFalse(without.promptSection().contains("Inventory"));
		assertFalse(without.summary().contains("Bank"));
	}

	@Test
	public void switchingItemSharingOffRemovesThemFromAnExistingProfile()
	{
		AccountProfile stripped = TestProfiles.ironmanZerkerWithItems().withoutItems();
		assertEquals(null, stripped.items());
		assertFalse(stripped.promptSection().contains("Inventory"));
		assertFalse(stripped.promptSection().contains("Molten glass"));
		assertEquals(TestProfiles.ironmanZerkerWithItems().combatLevel, stripped.combatLevel);
		assertTrue(stripped.promptSection().contains("Account type: Ironman."));

		AccountProfile plain = TestProfiles.ironmanZerker();
		assertTrue("nothing to strip", plain.withoutItems() == plain);
		assertTrue(AccountProfile.notShared().withoutItems() == AccountProfile.notShared());
	}
}
