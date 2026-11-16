package com.bardosrs.state;

import com.bardosrs.BardConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Keeps Bard's per-character data in RuneLite's settings, so it needs no file access of its own. */
@Singleton
class ConfigProfileStore implements ProfileStore
{
	private final ConfigManager configManager;

	@Inject
	ConfigProfileStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	@Override
	public String profileKey()
	{
		return configManager.getRSProfileKey();
	}

	@Override
	public String read(String profileKey, String key)
	{
		return configManager.getConfiguration(BardConfig.GROUP, profileKey, key);
	}

	@Override
	public void write(String profileKey, String key, String value)
	{
		configManager.setConfiguration(BardConfig.GROUP, profileKey, key, value);
	}
}
