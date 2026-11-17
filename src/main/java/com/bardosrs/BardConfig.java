package com.bardosrs;

import com.bardosrs.state.AccountBuild;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BardConfig.GROUP)
public interface BardConfig extends Config
{
	String GROUP = "bardosrs";

	@ConfigSection(
		name = "Model",
		description = "The language model Bard talks to (bring your own)",
		position = 0
	)
	String modelSection = "model";

	@ConfigItem(
		keyName = "llmBaseUrl",
		name = "Endpoint",
		description = "OpenAI-compatible base URL. Default is a local Ollama server.",
		section = modelSection,
		position = 0
	)
	default String llmBaseUrl()
	{
		return "http://localhost:11434/v1";
	}

	@ConfigItem(
		keyName = "llmModel",
		name = "Model name",
		description = "Model id sent to the endpoint, e.g. qwen3:14b for Ollama",
		section = modelSection,
		position = 1
	)
	default String llmModel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "llmApiKey",
		name = "API key",
		description = "Only needed for hosted providers such as OpenRouter or OpenAI. Leave empty for local models.",
		section = modelSection,
		position = 2,
		secret = true
	)
	default String llmApiKey()
	{
		return "";
	}

	@ConfigSection(
		name = "Account",
		description = "What Bard may know about your account",
		position = 1
	)
	String accountSection = "account";

	@ConfigItem(
		keyName = "shareAccountData",
		name = "Share account data with the model",
		description = "Lets Bard read your account type, levels, xp and quests and send them to your model with each "
			+ "question, so advice fits your account. Only your own character is read, never other players.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite "
			+ "developers. It also sends your account data (account type, levels, xp and quests) with each question. "
			+ "Only turn it on if you trust the model endpoint you configured.",
		section = accountSection,
		position = 0
	)
	default boolean shareAccountData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareItems",
		name = "Also share bank, inventory and equipment",
		description = "Needs \"Share account data\" on. Lets Bard see what you hold, so it will not tell you to gather "
			+ "or buy what you already have. Your bank is remembered from the last time you opened it, and saved in "
			+ "your RuneLite settings for this character.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite "
			+ "developers. It also sends the items in your inventory, equipment and bank with each question. Only turn "
			+ "it on if you trust the model endpoint you configured.",
		section = accountSection,
		position = 1
	)
	default boolean shareItems()
	{
		return false;
	}

	@ConfigItem(
		keyName = "accountBuild",
		name = "Account build",
		description = "Bard guesses your build (zerker, 1 Defence pure, skiller...) from your levels. Choose one here "
			+ "to override the guess.",
		section = accountSection,
		position = 2
	)
	default AccountBuild accountBuild()
	{
		return AccountBuild.AUTO;
	}

	@ConfigSection(
		name = "Lookups",
		description = "Where Bard may look things up",
		position = 2
	)
	String lookupsSection = "lookups";

	@ConfigItem(
		keyName = "useWiki",
		name = "Look things up on the OSRS Wiki",
		description = "Lets Bard search and read pages on oldschool.runescape.wiki to check its answers. Only the search "
			+ "words and page titles are sent, never account data. Without it Bard answers from the model's own "
			+ "knowledge, which can be out of date.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite "
			+ "developers. It sends the search words and page titles Bard looks up to oldschool.runescape.wiki.",
		section = lookupsSection,
		position = 0
	)
	default boolean useWiki()
	{
		return false;
	}
}
