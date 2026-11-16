package com.bardosrs.goals;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.ProfileStore;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The player's goals, saved per character in RuneLite's settings. Goals are small and change rarely, so every
 * change is saved straight away. Each goal carries an update time so a later dashboard sync can merge edits.
 */
@Slf4j
@Singleton
public class GoalStore
{
	static final int MAX_GOALS = 30;
	static final int MAX_TITLE_LENGTH = 120;
	private static final String KEY = "goals";
	private static final int MAX_AMBIGUOUS_LISTED = 5;

	/** A goal together with how far along it is. */
	public static final class View
	{
		public final Goal goal;
		public final GoalProgress progress;

		View(Goal goal, GoalProgress progress)
		{
			this.goal = goal;
			this.progress = progress;
		}
	}

	private final ProfileStore store;
	private final Gson gson;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private String loadedKey;
	private List<Goal> goals = new ArrayList<>();

	@Inject
	public GoalStore(ProfileStore store, Gson gson)
	{
		this.store = store;
		this.gson = gson;
	}

	/** Called after any change, from whichever thread made it. */
	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	/** Goals belong to a character, so there are none to show until someone is logged in. */
	public synchronized boolean available()
	{
		return store.profileKey() != null;
	}

	public synchronized List<Goal> list()
	{
		return available() && load() ? Collections.unmodifiableList(new ArrayList<>(goals)) : Collections.emptyList();
	}

	public synchronized Goal add(Goal.Type type, String title, String target, long amount)
	{
		requireLoggedIn();
		String cleanTitle = title == null ? "" : title.trim();
		if (cleanTitle.isEmpty())
		{
			cleanTitle = defaultTitle(type, target, amount);
		}
		if (cleanTitle.length() > MAX_TITLE_LENGTH)
		{
			throw new GoalException("The goal title is too long (at most " + MAX_TITLE_LENGTH + " characters).");
		}
		for (Goal existing : goals)
		{
			if (!existing.done && existing.type == type && existing.amount == amount
				&& existing.target.equalsIgnoreCase(target) && (type != Goal.Type.CUSTOM
				|| existing.title.equalsIgnoreCase(cleanTitle)))
			{
				return existing;
			}
		}
		long activeGoals = goals.stream().filter(g -> !g.done).count();
		if (goals.size() >= MAX_GOALS && activeGoals >= MAX_GOALS)
		{
			throw new GoalException("There are already " + MAX_GOALS + " goals. Remove one first.");
		}
		if (goals.size() >= MAX_GOALS)
		{
			goals.removeIf(g -> g.done);
		}

		long now = System.currentTimeMillis();
		Goal goal = new Goal(newId(), type, cleanTitle, target == null ? "" : target, amount, now, now, false, 0);
		goals.add(goal);
		save();
		return goal;
	}

	/** Marks a goal done, finding it by id or by part of its title. */
	public synchronized Goal complete(String idOrTitle)
	{
		requireLoggedIn();
		Goal found = find(idOrTitle);
		if (found.done)
		{
			return found;
		}
		Goal done = found.asDone(System.currentTimeMillis());
		goals.set(goals.indexOf(found), done);
		save();
		return done;
	}

	public synchronized Goal remove(String idOrTitle)
	{
		requireLoggedIn();
		Goal found = find(idOrTitle);
		goals.remove(found);
		save();
		return found;
	}

	/** Works out progress for every goal, and marks goals the account has already reached as done. */
	public synchronized List<View> applyProgress(AccountProfile profile)
	{
		if (!available() || !load())
		{
			return Collections.emptyList();
		}
		boolean changed = false;
		long now = System.currentTimeMillis();
		for (int i = 0; i < goals.size(); i++)
		{
			Goal goal = goals.get(i);
			GoalProgress progress = GoalProgress.of(goal, profile);
			if (!goal.done && progress.known && progress.reached)
			{
				goals.set(i, goal.asDone(now));
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}

		List<View> views = new ArrayList<>();
		for (Goal goal : goals)
		{
			views.add(new View(goal, GoalProgress.of(goal, profile)));
		}
		// Open goals first in the order they were set, then finished ones, newest first.
		views.sort(Comparator.<View, Boolean>comparing(v -> v.goal.done)
			.thenComparing(v -> v.goal.done ? -v.goal.doneAt : v.goal.createdAt));
		return views;
	}

	private Goal find(String idOrTitle)
	{
		String wanted = idOrTitle == null ? "" : idOrTitle.trim();
		if (wanted.isEmpty())
		{
			throw new GoalException("Say which goal, by its id or part of its title.");
		}
		for (Goal goal : goals)
		{
			if (goal.id.equalsIgnoreCase(wanted))
			{
				return goal;
			}
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<Goal> matches = new ArrayList<>();
		for (Goal goal : goals)
		{
			if (goal.title.toLowerCase(Locale.ROOT).contains(needle))
			{
				matches.add(goal);
			}
		}
		if (matches.size() == 1)
		{
			return matches.get(0);
		}
		if (matches.isEmpty())
		{
			throw new GoalException("No goal matches '" + wanted + "'. Use list_goals to see the ids.");
		}
		StringBuilder options = new StringBuilder();
		for (Goal match : matches.subList(0, Math.min(MAX_AMBIGUOUS_LISTED, matches.size())))
		{
			options.append(options.length() == 0 ? "" : "; ").append(match.id).append(" = ").append(match.title);
		}
		throw new GoalException("'" + wanted + "' matches several goals: " + options + ". Use the id.");
	}

	static String defaultTitle(Goal.Type type, String target, long amount)
	{
		switch (type)
		{
			case SKILL:
				return target + " level " + amount;
			case QUEST:
				return "Complete " + target;
			case ITEM:
				return "Have " + String.format(Locale.ROOT, "%,d", amount) + " " + target;
			default:
				return "Goal";
		}
	}

	private void requireLoggedIn()
	{
		if (!available())
		{
			throw new GoalException("Goals are saved per character, so log in first.");
		}
		load();
	}

	/** Loads the current character's goals if they are not loaded yet. Returns false if nobody is logged in. */
	private boolean load()
	{
		String key = store.profileKey();
		if (key == null)
		{
			return false;
		}
		if (key.equals(loadedKey))
		{
			return true;
		}
		loadedKey = key;
		goals = new ArrayList<>();
		try
		{
			String saved = store.read(key, KEY);
			Goal[] parsed = saved == null || saved.isEmpty() ? null : gson.fromJson(saved, Goal[].class);
			if (parsed != null)
			{
				for (Goal goal : parsed)
				{
					if (goal != null && goal.id != null && goal.type != null && goal.title != null)
					{
						goals.add(new Goal(goal.id, goal.type, goal.title, goal.target == null ? "" : goal.target,
							goal.amount, goal.createdAt, goal.updatedAt, goal.done, goal.doneAt));
					}
				}
			}
		}
		// Damaged settings must never stop goals working; start from an empty list.
		catch (RuntimeException e)
		{
			log.debug("Could not read the saved goals", e);
		}
		return true;
	}

	private void save()
	{
		try
		{
			store.write(loadedKey, KEY, gson.toJson(goals));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not save goals", e);
		}
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}

	private static String newId()
	{
		return UUID.randomUUID().toString().substring(0, 6);
	}
}
