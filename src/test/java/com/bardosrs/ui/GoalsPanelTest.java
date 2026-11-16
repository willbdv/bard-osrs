package com.bardosrs.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountBuild;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import com.bardosrs.state.TestProfiles;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Test;

public class GoalsPanelTest
{
	private final FakeProfileStore profiles = new FakeProfileStore();
	private final GoalStore goals = new GoalStore(profiles, new Gson());
	private final FakeAccountSource account = new FakeAccountSource();
	private final GoalsPanel panel = new GoalsPanel(goals, account);

	@After
	public void tearDown()
	{
		panel.dispose();
	}

	private void crafting(int level)
	{
		account.profile = TestProfiles.ready(AccountType.NORMAL, TestProfiles.levels(Skill.CRAFTING, level),
			AccountBuild.AUTO);
	}

	/** The panel draws on the Swing thread; wait for anything it has queued. */
	private static void flush() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	private static String text(Component root)
	{
		List<String> labels = new ArrayList<>();
		collect(root, labels);
		return String.join("\n", labels);
	}

	private static void collect(Component component, List<String> labels)
	{
		if (component instanceof JLabel)
		{
			labels.add(((JLabel) component).getText());
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collect(child, labels);
			}
		}
	}

	@Test
	public void progressFollowsTheGameWithoutSwitchingTabs() throws Exception
	{
		crafting(42);
		goals.add(Goal.Type.SKILL, "56 Crafting", "Crafting", 56);
		panel.setShowing(true);
		flush();
		assertTrue(text(panel), text(panel).contains("Crafting 42 -&gt; 56") || text(panel).contains("Crafting 42 -> 56"));

		// The player trains: the next beat of the live refresh shows it.
		crafting(50);
		panel.refresh();
		flush();
		assertTrue(text(panel), text(panel).contains("Crafting 50"));
		assertFalse(text(panel), text(panel).contains("Crafting 42"));
	}

	@Test
	public void anUnchangedListIsNotRebuilt() throws Exception
	{
		crafting(42);
		goals.add(Goal.Type.SKILL, "56 Crafting", "Crafting", 56);
		panel.setShowing(true);
		flush();
		Component firstRow = firstRow();
		assertTrue("the test must find a real goal row", firstRow != null && firstRow != panel);

		panel.refresh();
		flush();
		assertSame("the row must survive a refresh that found nothing new, or clicks on it get lost",
			firstRow, firstRow());

		crafting(43);
		panel.refresh();
		flush();
		assertNotSame(firstRow, firstRow());
	}

	@Test
	public void aHiddenPanelDoesNotReadTheGame() throws Exception
	{
		crafting(42);
		goals.add(Goal.Type.SKILL, "56 Crafting", "Crafting", 56);
		// Never attached to a window, so it cannot be seen.
		int before = account.captures.get();
		panel.tick();
		assertEquals(before, account.captures.get());

		panel.setShowing(false);
		panel.refresh();
		assertEquals(before, account.captures.get());
	}

	@Test
	public void signatureChangesOnlyWhenWhatIsShownChanges()
	{
		crafting(42);
		goals.add(Goal.Type.SKILL, "56 Crafting", "Crafting", 56);
		String first = GoalsPanel.signature(goals.applyProgress(account.profile));
		assertEquals(first, GoalsPanel.signature(goals.applyProgress(account.profile)));

		crafting(44);
		assertFalse(first.equals(GoalsPanel.signature(goals.applyProgress(account.profile))));
	}

	private Component firstRow()
	{
		return findRow(panel);
	}

	/** The first goal row: the card-coloured panel that holds a progress bar (not the list around it). */
	private static Component findRow(Component component)
	{
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				if (child instanceof javax.swing.JPanel && child.isOpaque()
					&& ColorScheme.DARKER_GRAY_COLOR.equals(child.getBackground()) && containsBar(child))
				{
					return child;
				}
				Component found = findRow(child);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static boolean containsBar(Component component)
	{
		if (component instanceof javax.swing.JProgressBar)
		{
			return true;
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				if (containsBar(child))
				{
					return true;
				}
			}
		}
		return false;
	}
}
