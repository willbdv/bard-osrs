package com.bardosrs.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** An in-memory stand-in for RuneLite's per-character settings. */
public class FakeProfileStore implements ProfileStore
{
	public volatile String profile = "rsprofile.one";
	public final Map<String, String> data = Collections.synchronizedMap(new HashMap<>());

	@Override
	public String profileKey()
	{
		return profile;
	}

	@Override
	public String read(String profileKey, String key)
	{
		return data.get(profileKey + "|" + key);
	}

	@Override
	public void write(String profileKey, String key, String value)
	{
		data.put(profileKey + "|" + key, value);
	}
}
