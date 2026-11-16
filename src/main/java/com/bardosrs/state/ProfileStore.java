package com.bardosrs.state;

import com.google.inject.ImplementedBy;

/** Per-character storage for Bard's saved data (bank snapshot, goals). The real one is RuneLite's settings. */
@ImplementedBy(ConfigProfileStore.class)
public interface ProfileStore
{
	/** The current character's settings key, or null when nobody is logged in. */
	String profileKey();

	String read(String profileKey, String key);

	void write(String profileKey, String key, String value);
}
