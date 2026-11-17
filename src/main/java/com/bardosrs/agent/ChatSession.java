package com.bardosrs.agent;

import com.bardosrs.BardConfig;
import com.bardosrs.llm.LlmClient;
import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.AccountSource;
import com.bardosrs.tools.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * One running conversation: keeps the message history and drives the agent
 * loop for each question. The loop asks the model, runs any tools it requests,
 * sends the results back, and repeats until the model answers in text. UI
 * updates go out through {@link View}, whose methods may be called from any thread.
 */
@Singleton
public class ChatSession
{
	static final String SYSTEM_PROMPT = String.join("\n\n",
		"You are Bard, an assistant for Old School RuneScape (OSRS) players, running inside the RuneLite client. "
			+ "Answer concisely and accurately. If you are not sure about a game fact, say so instead of guessing. "
			+ "Details of the player's account may follow below; if they are missing, ask when their account type or "
			+ "stats matter to the answer.",

		"Style: be brief. Open with the answer in one or two sentences, then give only the steps and numbers that "
			+ "matter, normally under 150 words (more only if the player asks for depth). Do not repeat the "
			+ "player's account details back to them, do not greet or praise, and do not add suggestions they did "
			+ "not ask for. Reply to greetings in one short line. End with at most one short follow-up question, and "
			+ "only if you need the answer to help further.",

		"Tools: for any experience (xp) or level arithmetic, call the xp_calculator tool instead of estimating or "
			+ "working it out yourself. Look game facts up instead of trusting memory: use wiki_search to find the "
			+ "right OSRS Wiki page, then wiki_page to read it (pass a section name for tables such as drops, "
			+ "requirements or item sources). Use get_quests to see which quests the player has done before recommending "
			+ "quests, and search_items to check what they already hold before telling them what to gather or buy. "
			+ "Use list_goals to see what they are working towards, and edit_goal to add, complete or remove a goal, "
			+ "but only when they ask. Name the wiki page you used. If the wiki does not confirm something, "
			+ "say so. Be efficient with lookups: request several independent lookups together in one step, do not "
			+ "re-read a page you already have, and stop looking once you have enough to answer.",

		"Training and money-making advice:\n"
			+ "- Recommend the standard, widely used method for the player's level range, as the wiki's training "
			+ "guides describe it (pages like \"<Skill> training\", and \"Ironman Guide/<Skill>\" for ironmen), not "
			+ "just whatever unlocks next. Cover the whole range they asked about, with the usual points to switch "
			+ "method.\n"
			+ "- Judge methods by xp per hour first, then by what limits this player. For an ironman that is gathering "
			+ "time and supplies, so favour the most xp per unit of supplies and methods they can supply themselves. "
			+ "For an account that can trade it is gp, so weigh cost per xp and profit or loss per hour using "
			+ "prices from the wiki.\n"
			+ "- Always say what happens to the products (sold, reused, needed later, or just dropped) and whether "
			+ "that changes the choice. If a slower or costlier method has a real payoff, such as profit or useful "
			+ "items, mention it as an alternative with the trade-off.\n"
			+ "- Give one recommended route with xp per action, how many actions are needed (use xp_calculator) and "
			+ "the supplies it needs, then at most one alternative in a single line. Take xp rates, requirements and prices from the "
			+ "wiki, or say you could not confirm them.",

		"Staying accurate: if the player questions something you said, check the wiki again before answering. Keep "
			+ "your earlier answer if the wiki supports it, and change it only when the wiki shows you were wrong, "
			+ "saying what you found. Never invent a reason. Beware similarly named items (for example a quest item "
			+ "and an ordinary item with almost the same name): read the opening line of the page and make sure it is "
			+ "about the item you mean, and cite the page (and section) behind the key claims in one short line at the end.",

		"Your replies are shown in a narrow side panel: keep paragraphs short, use simple '-' lists and **bold** "
			+ "sparingly, and avoid tables, emoji and fancy punctuation (use straight quotes and plain hyphens).");

	private static final int MAX_HISTORY_MESSAGES = 30;
	/** Rounds of tool calls, and total calls, allowed per question before the model must answer. */
	static final int MAX_TOOL_ROUNDS = 8;
	static final int MAX_TOOL_CALLS = 16;
	/** How long to wait for the game thread to hand over the account before answering without it. */
	private static final long CAPTURE_TIMEOUT_SECONDS = 3;

	/** Sent (not stored) once the budget is spent, so the model answers instead of asking for more tools. */
	static final String BUDGET_SPENT_NOTICE =
		"You have used all the tool lookups available for this question. Do not call any more tools. "
			+ "Answer now using what you have already gathered, and say plainly what you could not confirm.";

	public interface View
	{
		void onUserMessage(String text);

		void onAssistantStart();

		void onAssistantDelta(String text);

		/** The model called a tool; {@code description} is a short human-readable summary. */
		void onToolUse(String description);

		void onAssistantDone();

		/** Errors and other one-line status messages. */
		void onNotice(String message);

		void onBusyChanged(boolean busy);

		/** A one-line description of the account being used for this question, shown in the panel. */
		void onAccountSummary(String summary);
	}

	private final LlmClient llm;
	private final BardConfig config;
	private final ToolRegistry tools;
	private final AccountSource account;
	private final Executor executor;
	private final long captureTimeoutMillis;

	private final List<JsonObject> history = new ArrayList<>();
	private LlmClient.Cancellable inFlight;
	private boolean busy;
	private int generation;
	/** Where the current question starts in {@code history}, so a failure can roll the whole turn back. */
	private int turnStart;
	private int toolRounds;
	private int toolCallCount;
	/** The account as read when the current question was asked; the same snapshot is used for every round. */
	private AccountProfile turnProfile = AccountProfile.notShared();

	@Inject
	ChatSession(LlmClient llm, BardConfig config, ToolRegistry tools, AccountSource account,
		ScheduledExecutorService executor)
	{
		this(llm, config, tools, account, executor, TimeUnit.SECONDS.toMillis(CAPTURE_TIMEOUT_SECONDS));
	}

	ChatSession(LlmClient llm, BardConfig config, ToolRegistry tools, AccountSource account, Executor executor,
		long captureTimeoutMillis)
	{
		this.llm = llm;
		this.config = config;
		this.tools = tools;
		this.account = account;
		this.executor = executor;
		this.captureTimeoutMillis = captureTimeoutMillis;
	}

	/** Returns false if the message was not sent (empty, busy, or not configured). */
	public synchronized boolean send(String text, View view)
	{
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty() || busy)
		{
			return false;
		}
		LlmClient.Settings settings = new LlmClient.Settings(
			config.llmBaseUrl(), config.llmApiKey(), config.llmModel());
		if (!settings.isConfigured())
		{
			view.onNotice("Set the endpoint and model name in Bard's settings first (RuneLite settings > Bard).");
			return false;
		}

		view.onUserMessage(trimmed);
		trimHistory();
		turnStart = history.size();
		history.add(message("user", trimmed));
		toolRounds = 0;
		toolCallCount = 0;
		busy = true;
		int gen = ++generation;
		view.onBusyChanged(true);
		view.onAssistantStart();
		startTurn(settings, gen, view);
		return true;
	}

	public synchronized void stop(View view)
	{
		if (!busy)
		{
			return;
		}
		generation++;
		cancelInFlight();
		rollBackTurn();
		busy = false;
		view.onBusyChanged(false);
		view.onNotice("Stopped.");
	}

	public synchronized void clear(View view)
	{
		generation++;
		cancelInFlight();
		history.clear();
		busy = false;
		view.onBusyChanged(false);
	}

	public synchronized void shutDown()
	{
		generation++;
		cancelInFlight();
		history.clear();
		busy = false;
	}

	/** Reads the account if the player allows it, then starts the model rounds. Callers hold the lock. */
	private void startTurn(LlmClient.Settings settings, int gen, View view)
	{
		if (!config.shareAccountData())
		{
			beginTurn(gen, settings, view, AccountProfile.notShared());
			return;
		}
		CompletableFuture<AccountProfile> capture;
		try
		{
			capture = account.capture();
		}
		catch (RuntimeException e)
		{
			beginTurn(gen, settings, view, AccountProfile.unavailable());
			return;
		}
		// The game thread answers asynchronously; carry on without the account rather than hang if it never does.
		capture.orTimeout(captureTimeoutMillis, TimeUnit.MILLISECONDS)
			.whenCompleteAsync((profile, error) -> beginTurn(gen, settings, view,
				error == null && profile != null ? profile : AccountProfile.unavailable()), executor);
	}

	private synchronized void beginTurn(int gen, LlmClient.Settings settings, View view, AccountProfile profile)
	{
		if (gen != generation)
		{
			return;
		}
		turnProfile = profile;
		view.onAccountSummary(profile.summary());
		requestModel(settings, gen, view);
	}

	static String systemPrompt(AccountProfile profile)
	{
		return systemPrompt(profile, "");
	}

	/** {@code toolNotes} says what to do without tools the player has switched off, and is empty when none are. */
	static String systemPrompt(AccountProfile profile, String toolNotes)
	{
		String prompt = SYSTEM_PROMPT + "\n\n" + profile.promptSection();
		return toolNotes == null || toolNotes.isEmpty() ? prompt : prompt + "\n\n" + toolNotes;
	}

	/** Sends the history to the model. Callers hold the lock. */
	private void requestModel(LlmClient.Settings settings, int gen, View view)
	{
		List<JsonObject> request = new ArrayList<>();
		request.add(message("system", systemPrompt(turnProfile, tools.unavailableNotes())));
		request.addAll(history);

		// Once the tool budget is spent, keep the tools listed but forbid calling them and ask for the answer.
		boolean spent = toolBudgetSpent();
		if (spent)
		{
			request.add(message("user", BUDGET_SPENT_NOTICE));
		}
		String toolChoice = spent ? "none" : null;
		LlmClient.Cancellable handle = llm.stream(settings, request, tools.specs(), toolChoice,
			new LlmClient.Listener()
			{
				@Override
				public void onDelta(String piece)
				{
					handleDelta(gen, view, piece);
				}

				@Override
				public void onComplete(LlmClient.Reply reply)
				{
					handleReply(gen, settings, view, reply);
				}

				@Override
				public void onError(String errorMessage)
				{
					handleError(gen, view, errorMessage);
				}
			});
		// The listener can already have fired (e.g. an invalid endpoint), so
		// only keep the handle while this request is still the live one.
		if (busy && gen == generation)
		{
			inFlight = handle;
		}
	}

	private synchronized void handleDelta(int gen, View view, String piece)
	{
		if (gen != generation)
		{
			return;
		}
		view.onAssistantDelta(piece);
	}

	private synchronized void handleReply(int gen, LlmClient.Settings settings, View view, LlmClient.Reply reply)
	{
		if (gen != generation)
		{
			return;
		}
		inFlight = null;

		if (!reply.toolCalls.isEmpty())
		{
			if (toolBudgetSpent())
			{
				handleError(gen, view, "The model kept asking for tools instead of answering. Try rephrasing.");
				return;
			}
			toolRounds++;
			toolCallCount += reply.toolCalls.size();
			history.add(assistantToolCallMessage(reply));
			List<LlmClient.ToolCall> calls = reply.toolCalls;
			try
			{
				// Tools can block on the network, so they run off this lock: stop() must stay responsive.
				executor.execute(() -> runTools(gen, settings, view, calls));
			}
			catch (RejectedExecutionException e)
			{
				handleError(gen, view, "Bard could not run its tools. Try again.");
			}
			return;
		}

		if (reply.leakedToolMarkup && reply.text.trim().isEmpty())
		{
			handleError(gen, view, "Bard ran out of lookups before it could answer. Ask again, or narrow the question.");
			return;
		}

		compactTurn();
		history.add(message("assistant", reply.text));
		finish(view);
		view.onAssistantDone();
	}

	/** Runs the model's tool calls on a worker thread, then sends the results back, unless the turn was stopped. */
	private void runTools(int gen, LlmClient.Settings settings, View view, List<LlmClient.ToolCall> calls)
	{
		try
		{
			List<JsonObject> results = new ArrayList<>();
			for (LlmClient.ToolCall call : calls)
			{
				if (!isCurrent(gen))
				{
					return;
				}
				view.onToolUse(tools.summary(call.name, call.arguments));
				results.add(toolResultMessage(call.id, tools.execute(call.name, call.arguments)));
			}
			synchronized (this)
			{
				if (gen != generation)
				{
					return;
				}
				history.addAll(results);
				requestModel(settings, gen, view);
			}
		}
		catch (RuntimeException e)
		{
			handleError(gen, view, "A tool failed unexpectedly.");
		}
	}

	private boolean toolBudgetSpent()
	{
		return toolRounds >= MAX_TOOL_ROUNDS || toolCallCount >= MAX_TOOL_CALLS;
	}

	private synchronized boolean isCurrent(int gen)
	{
		return gen == generation;
	}

	private synchronized void handleError(int gen, View view, String errorMessage)
	{
		if (gen != generation)
		{
			return;
		}
		rollBackTurn();
		finish(view);
		view.onNotice(errorMessage);
	}

	private void finish(View view)
	{
		busy = false;
		inFlight = null;
		view.onBusyChanged(false);
	}

	private void cancelInFlight()
	{
		if (inFlight != null)
		{
			inFlight.cancel();
			inFlight = null;
		}
	}

	/**
	 * Once a question is answered, drop its tool-call and tool-result messages. They can be large and the answer
	 * already carries what mattered, so later questions stay cheap.
	 */
	private void compactTurn()
	{
		for (int i = history.size() - 1; i > turnStart; i--)
		{
			JsonObject entry = history.get(i);
			if ("tool".equals(entry.get("role").getAsString()) || entry.has("tool_calls"))
			{
				history.remove(i);
			}
		}
	}

	/** After a failed or stopped request, forget the whole unfinished turn so a retry starts clean. */
	private void rollBackTurn()
	{
		while (history.size() > turnStart)
		{
			history.remove(history.size() - 1);
		}
	}

	private void trimHistory()
	{
		while (history.size() > MAX_HISTORY_MESSAGES)
		{
			history.remove(0);
		}
		// Never start the history mid-turn with an assistant or tool message.
		while (!history.isEmpty() && !"user".equals(history.get(0).get("role").getAsString()))
		{
			history.remove(0);
		}
	}

	static JsonObject message(String role, String content)
	{
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private static JsonObject assistantToolCallMessage(LlmClient.Reply reply)
	{
		JsonObject message = message("assistant", reply.text);
		JsonArray calls = new JsonArray();
		for (LlmClient.ToolCall call : reply.toolCalls)
		{
			JsonObject function = new JsonObject();
			function.addProperty("name", call.name);
			function.addProperty("arguments", call.arguments);
			JsonObject entry = new JsonObject();
			entry.addProperty("id", call.id);
			entry.addProperty("type", "function");
			entry.add("function", function);
			calls.add(entry);
		}
		message.add("tool_calls", calls);
		// Providers that stream reasoning separately require it echoed back alongside tool calls.
		if (reply.reasoningContent != null && !reply.reasoningContent.isEmpty())
		{
			message.addProperty("reasoning_content", reply.reasoningContent);
		}
		return message;
	}

	private static JsonObject toolResultMessage(String callId, String content)
	{
		JsonObject message = message("tool", content);
		message.addProperty("tool_call_id", callId);
		return message;
	}
}
