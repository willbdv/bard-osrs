package com.bardosrs.ui;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalException;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.AccountSource;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/** The Goals tab: each goal with a progress bar, and buttons to tick it off or remove it. */
public class GoalsPanel extends JPanel
{
	private static final int TEXT_WIDTH = 150;
	private static final long CAPTURE_TIMEOUT_SECONDS = 3;
	private static final int MAX_CUSTOM_LENGTH = 120;
	/** How often a visible tab re-reads the game, so xp, quests and items update without switching tabs. */
	static final int LIVE_REFRESH_MILLIS = 2000;

	private final GoalStore goals;
	private final AccountSource account;
	private final Runnable changeListener = this::refresh;
	private final JPanel rows = new JPanel();
	private final JTextField customField = new JTextField();
	private final JButton addButton = new JButton("Add");
	private final JLabel status = new JLabel(" ");
	private final Timer liveTimer = new Timer(LIVE_REFRESH_MILLIS, e -> tick());
	private volatile boolean showing;
	/** What is on screen now. A refresh that finds nothing new leaves the rows alone, so buttons do not flicker. */
	private String renderedSignature;

	public GoalsPanel(GoalStore goals, AccountSource account)
	{
		this.goals = goals;
		this.account = account;

		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		JPanel addRow = new JPanel(new BorderLayout(4, 0));
		addRow.setOpaque(false);
		customField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		customField.setForeground(Color.WHITE);
		customField.setCaretColor(Color.WHITE);
		customField.setBorder(new EmptyBorder(4, 4, 4, 4));
		customField.setToolTipText("A goal you tick off yourself, e.g. Get the fire cape");
		customField.addActionListener(e -> addCustomGoal());
		addButton.addActionListener(e -> addCustomGoal());
		addRow.add(customField, BorderLayout.CENTER);
		addRow.add(addButton, BorderLayout.EAST);
		add(addRow, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(rows, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		add(scroll, BorderLayout.CENTER);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(status, BorderLayout.SOUTH);

		// Goals set by chat appear here without a manual refresh.
		goals.addListener(changeListener);

		// Progress follows the game while the tab is on screen. When the whole sidebar is hidden, ticks do nothing.
		liveTimer.setRepeats(true);
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing())
			{
				refresh();
			}
		});
	}

	/** Called when the tab is shown or hidden; only a visible tab reads the game. */
	public void setShowing(boolean showing)
	{
		this.showing = showing;
		if (showing)
		{
			refresh();
			liveTimer.start();
		}
		else
		{
			liveTimer.stop();
		}
	}

	public void dispose()
	{
		liveTimer.stop();
		goals.removeListener(changeListener);
	}

	/** One live-update beat: re-read the game, but only if the panel can actually be seen. */
	void tick()
	{
		if (isShowing())
		{
			refresh();
		}
	}

	/** Re-reads progress and redraws. Safe to call from any thread. */
	public void refresh()
	{
		if (!showing)
		{
			return;
		}
		if (!goals.available())
		{
			SwingUtilities.invokeLater(() -> renderMessage("Log in to see your goals."));
			return;
		}
		CompletableFuture<AccountProfile> capture;
		try
		{
			capture = account.capture();
		}
		catch (RuntimeException e)
		{
			capture = CompletableFuture.completedFuture(AccountProfile.unavailable());
		}
		// Progress is shown on screen only; nothing here is sent to the model.
		capture.orTimeout(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((profile, error) ->
		{
			AccountProfile read = error == null && profile != null ? profile : AccountProfile.unavailable();
			List<GoalStore.View> views = goals.applyProgress(read);
			SwingUtilities.invokeLater(() -> render(views));
		});
	}

	private void addCustomGoal()
	{
		String text = customField.getText().trim();
		if (text.isEmpty())
		{
			return;
		}
		if (text.length() > MAX_CUSTOM_LENGTH)
		{
			status.setText(html("Too long: at most " + MAX_CUSTOM_LENGTH + " characters."));
			return;
		}
		try
		{
			goals.add(Goal.Type.CUSTOM, text, "", 0);
			customField.setText("");
			status.setText(" ");
		}
		catch (GoalException e)
		{
			status.setText(html(e.getMessage()));
		}
	}

	private void render(List<GoalStore.View> views)
	{
		String signature = signature(views);
		if (Objects.equals(signature, renderedSignature))
		{
			return;
		}
		renderedSignature = signature;
		rows.removeAll();
		status.setText(" ");
		if (views.isEmpty())
		{
			rows.add(message("No goals yet. Ask Bard to set one, for example \"set a goal: 56 Crafting\", or add your "
				+ "own above."));
		}
		for (GoalStore.View view : views)
		{
			rows.add(row(view));
			rows.add(spacer());
		}
		rows.revalidate();
		rows.repaint();
	}

	/** Everything on a row that can change on screen, so an unchanged list is not rebuilt. */
	static String signature(List<GoalStore.View> views)
	{
		StringBuilder sb = new StringBuilder();
		for (GoalStore.View view : views)
		{
			sb.append(view.goal.id).append('|').append(view.goal.title).append('|').append(view.goal.done).append('|')
				.append(Math.round(view.progress.fraction * 100)).append('|').append(view.progress.text).append('\n');
		}
		return sb.toString();
	}

	private void renderMessage(String text)
	{
		String signature = "message:" + text;
		if (signature.equals(renderedSignature))
		{
			return;
		}
		renderedSignature = signature;
		rows.removeAll();
		rows.add(message(text));
		rows.revalidate();
		rows.repaint();
	}

	private JLabel message(String text)
	{
		JLabel label = new JLabel(html(text));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(6, 2, 6, 2));
		return label;
	}

	private JPanel row(GoalStore.View view)
	{
		Goal goal = view.goal;
		JPanel row = new JPanel(new BorderLayout(0, 4));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));

		JLabel title = new JLabel(html(goal.title));
		title.setForeground(goal.done ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		row.add(title, BorderLayout.NORTH);

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue((int) Math.round(view.progress.fraction * 100));
		bar.setForeground(goal.done ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorderPainted(false);
		bar.setPreferredSize(new Dimension(10, 6));
		JLabel detail = new JLabel(html(view.progress.text));
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel middle = new JPanel(new BorderLayout(0, 3));
		middle.setOpaque(false);
		middle.add(bar, BorderLayout.NORTH);
		middle.add(detail, BorderLayout.CENTER);
		row.add(middle, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		buttons.setOpaque(false);
		if (!goal.done)
		{
			JButton done = new JButton("Done");
			done.addActionListener(e -> change(() -> goals.complete(goal.id)));
			buttons.add(done);
		}
		JButton remove = new JButton("Remove");
		remove.addActionListener(e -> change(() -> goals.remove(goal.id)));
		buttons.add(remove);
		row.add(buttons, BorderLayout.SOUTH);
		return row;
	}

	private void change(Runnable change)
	{
		try
		{
			change.run();
		}
		catch (GoalException e)
		{
			status.setText(html(e.getMessage()));
		}
	}

	private static JPanel spacer()
	{
		JPanel spacer = new JPanel();
		spacer.setOpaque(false);
		spacer.setPreferredSize(new Dimension(1, 6));
		return spacer;
	}

	/** Wrapped, ASCII-safe text for the RuneScape font. */
	private String html(String text)
	{
		String safe = TextSanitizer.dropUndisplayable(TextSanitizer.sanitize(text), getFont());
		return "<html><body style='width:" + TEXT_WIDTH + "px'>"
			+ safe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
	}
}
