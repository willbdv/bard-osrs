package com.bardosrs.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bardosrs.BardConfig;
import com.bardosrs.llm.LlmClient;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.TestProfiles;
import com.bardosrs.tools.AgentTool;
import com.bardosrs.tools.ToolRegistry;
import com.bardosrs.tools.XpCalculatorTool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ChatSessionTest
{
	private static final Gson GSON = new Gson();

	private HttpServer server;
	private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());
	private volatile int status = 200;
	private volatile String responseBody = "";
	/** Responses served in order before falling back to status/responseBody. */
	private final Deque<Object[]> queued = new ArrayDeque<>();

	@Before
	public void startServer() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/chat/completions", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(GSON.fromJson(body, JsonObject.class));
			int code = status;
			String payload = responseBody;
			synchronized (queued)
			{
				Object[] next = queued.poll();
				if (next != null)
				{
					code = (Integer) next[0];
					payload = (String) next[1];
				}
			}
			exchange.sendResponseHeaders(code, 0);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(payload.getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
	}

	@After
	public void stopServer()
	{
		server.stop(0);
		executor.shutdownNow();
	}

	private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable ->
	{
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});

	private final FakeAccountSource account = new FakeAccountSource();
	private volatile boolean share;

	private ChatSession session(String model)
	{
		return session(model, new ToolRegistry(GSON, Arrays.<AgentTool>asList(new XpCalculatorTool())));
	}

	private ChatSession session(String model, ToolRegistry registry)
	{
		return session(model, registry, 3000);
	}

	private ChatSession session(String model, ToolRegistry registry, long captureTimeoutMillis)
	{
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
		BardConfig config = new BardConfig()
		{
			@Override
			public String llmBaseUrl()
			{
				return url;
			}

			@Override
			public String llmModel()
			{
				return model;
			}

			@Override
			public String llmApiKey()
			{
				return "";
			}

			@Override
			public boolean shareAccountData()
			{
				return share;
			}
		};
		return new ChatSession(new LlmClient(new OkHttpClient(), GSON), config, registry, account, executor,
			captureTimeoutMillis);
	}

	private static class RecordingView implements ChatSession.View
	{
		final List<String> events = Collections.synchronizedList(new ArrayList<>());
		final StringBuilder assistant = new StringBuilder();
		volatile CountDownLatch finished = new CountDownLatch(1);

		@Override
		public void onUserMessage(String text)
		{
			events.add("user:" + text);
		}

		@Override
		public void onAssistantStart()
		{
			events.add("start");
		}

		@Override
		public void onAssistantDelta(String text)
		{
			assistant.append(text);
		}

		@Override
		public void onToolUse(String description)
		{
			events.add("tool:" + description);
		}

		@Override
		public void onAssistantDone()
		{
			events.add("done");
		}

		@Override
		public void onNotice(String message)
		{
			events.add("notice:" + message);
		}

		@Override
		public void onAccountSummary(String summary)
		{
			events.add("account:" + summary);
		}

		@Override
		public void onBusyChanged(boolean busy)
		{
			events.add("busy:" + busy);
			if (!busy)
			{
				finished.countDown();
			}
		}

		void awaitFinished() throws InterruptedException
		{
			assertTrue("timed out", finished.await(10, TimeUnit.SECONDS));
			finished = new CountDownLatch(1);
		}
	}

	private static String sse(String text)
	{
		return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\ndata: [DONE]\n\n";
	}

	@Test
	public void keepsHistoryAcrossTurns() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		responseBody = sse("first");
		assertTrue(session.send("hello", view));
		view.awaitFinished();
		assertEquals("first", view.assistant.toString());

		responseBody = sse("second");
		assertTrue(session.send("again", view));
		view.awaitFinished();

		JsonArray messages = requests.get(1).getAsJsonArray("messages");
		assertEquals(4, messages.size());
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("hello", messages.get(1).getAsJsonObject().get("content").getAsString());
		assertEquals("first", messages.get(2).getAsJsonObject().get("content").getAsString());
		assertEquals("again", messages.get(3).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void failedRequestDropsTheUnansweredMessage() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		status = 500;
		responseBody = "{}";
		assertTrue(session.send("will fail", view));
		view.awaitFinished();
		assertTrue(view.events.toString(), view.events.stream().anyMatch(e -> e.startsWith("notice:")));

		status = 200;
		responseBody = sse("ok");
		assertTrue(session.send("retry", view));
		view.awaitFinished();

		JsonArray messages = requests.get(1).getAsJsonArray("messages");
		assertEquals(2, messages.size());
		assertEquals("retry", messages.get(1).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void refusesToSendWithoutAModel()
	{
		ChatSession session = session("");
		RecordingView view = new RecordingView();
		assertFalse(session.send("hello", view));
		assertTrue(view.events.toString(), view.events.get(0).startsWith("notice:"));
		assertTrue(requests.isEmpty());
	}

	@Test
	public void ignoresBlankInput()
	{
		ChatSession session = session("m");
		assertFalse(session.send("   ", new RecordingView()));
		assertTrue(requests.isEmpty());
	}

	private static String toolCallResponse(String id, String name, String arguments)
	{
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		JsonObject call = new JsonObject();
		call.addProperty("index", 0);
		call.addProperty("id", id);
		call.addProperty("type", "function");
		call.add("function", function);
		JsonArray calls = new JsonArray();
		calls.add(call);
		JsonObject delta = new JsonObject();
		delta.add("tool_calls", calls);
		JsonObject choice = new JsonObject();
		choice.add("delta", delta);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject chunk = new JsonObject();
		chunk.add("choices", choices);
		return "data: " + chunk + "\n\ndata: [DONE]\n\n";
	}

	private void queue(int code, String body)
	{
		synchronized (queued)
		{
			queued.add(new Object[]{code, body});
		}
	}

	@Test
	public void runsToolsAndFeedsTheResultsBack() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("call_1", "xp_calculator", "{\"from_level\":42,\"to_level\":56}"));
		queue(200, sse("About 138k xp"));
		assertTrue(session.send("how much xp 42 to 56?", view));
		view.awaitFinished();

		assertEquals("About 138k xp", view.assistant.toString());
		assertTrue(view.events.toString(), view.events.contains("tool:XP calculator: level 42 to level 56"));
		assertEquals(2, requests.size());

		JsonObject first = requests.get(0);
		assertEquals("xp_calculator",
			first.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		assertFalse(first.has("tool_choice"));

		JsonArray messages = requests.get(1).getAsJsonArray("messages");
		assertEquals(4, messages.size());
		JsonObject assistant = messages.get(2).getAsJsonObject();
		assertEquals("assistant", assistant.get("role").getAsString());
		assertEquals("call_1", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
		JsonObject toolMessage = messages.get(3).getAsJsonObject();
		assertEquals("tool", toolMessage.get("role").getAsString());
		assertEquals("call_1", toolMessage.get("tool_call_id").getAsString());
		assertTrue(toolMessage.get("content").getAsString(), toolMessage.get("content").getAsString().contains("\"xp_needed\":138511"));
	}

	@Test
	public void toolErrorsGoBackToTheModel() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("c1", "no_such_tool", "{}"));
		queue(200, sse("sorry"));
		assertTrue(session.send("hi", view));
		view.awaitFinished();

		JsonArray messages = requests.get(1).getAsJsonArray("messages");
		assertTrue(messages.get(3).getAsJsonObject().get("content").getAsString().contains("Unknown tool"));
		assertEquals("sorry", view.assistant.toString());
	}

	@Test
	public void forbidsToolsOnceTheRoundLimitIsSpent() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		for (int i = 0; i < ChatSession.MAX_TOOL_ROUNDS; i++)
		{
			queue(200, toolCallResponse("c" + i, "xp_calculator", "{\"from_level\":1,\"to_level\":2}"));
		}
		queue(200, sse("final answer"));
		assertTrue(session.send("loop", view));
		view.awaitFinished();

		assertEquals(ChatSession.MAX_TOOL_ROUNDS + 1, requests.size());
		assertFalse(requests.get(0).has("tool_choice"));
		JsonObject last = requests.get(ChatSession.MAX_TOOL_ROUNDS);
		assertEquals("none", last.get("tool_choice").getAsString());
		// The model is told to answer now, and only on that final request.
		JsonArray lastMessages = last.getAsJsonArray("messages");
		JsonObject nudge = lastMessages.get(lastMessages.size() - 1).getAsJsonObject();
		assertEquals("user", nudge.get("role").getAsString());
		assertEquals(ChatSession.BUDGET_SPENT_NOTICE, nudge.get("content").getAsString());
		JsonArray firstMessages = requests.get(0).getAsJsonArray("messages");
		assertEquals("loop", firstMessages.get(firstMessages.size() - 1).getAsJsonObject().get("content").getAsString());
		assertEquals("final answer", view.assistant.toString());
	}

	@Test
	public void aFailureAfterToolUseRollsBackTheWholeTurn() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("c1", "xp_calculator", "{\"from_level\":1,\"to_level\":2}"));
		queue(500, "{}");
		assertTrue(session.send("first", view));
		view.awaitFinished();
		assertTrue(view.events.toString(), view.events.stream().anyMatch(e -> e.startsWith("notice:")));

		responseBody = sse("ok");
		assertTrue(session.send("second", view));
		view.awaitFinished();

		JsonArray messages = requests.get(requests.size() - 1).getAsJsonArray("messages");
		assertEquals(2, messages.size());
		assertEquals("second", messages.get(1).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void laterQuestionsDoNotCarryEarlierToolMessages() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("c1", "xp_calculator", "{\"from_level\":1,\"to_level\":2}"));
		queue(200, sse("first answer"));
		assertTrue(session.send("one", view));
		view.awaitFinished();

		responseBody = sse("second answer");
		assertTrue(session.send("two", view));
		view.awaitFinished();

		JsonArray messages = requests.get(requests.size() - 1).getAsJsonArray("messages");
		assertEquals(4, messages.size());
		assertEquals("one", messages.get(1).getAsJsonObject().get("content").getAsString());
		assertEquals("first answer", messages.get(2).getAsJsonObject().get("content").getAsString());
		assertEquals("two", messages.get(3).getAsJsonObject().get("content").getAsString());
	}

	/** A tool that blocks until released, to prove stop() never waits on a running tool. */
	private static class BlockingTool implements AgentTool
	{
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public String name()
		{
			return "slow_tool";
		}

		@Override
		public String description()
		{
			return "blocks";
		}

		@Override
		public JsonObject parameters()
		{
			JsonObject schema = new JsonObject();
			schema.addProperty("type", "object");
			schema.add("properties", new JsonObject());
			return schema;
		}

		@Override
		public String run(JsonObject args)
		{
			started.countDown();
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			return "{}";
		}

		@Override
		public String summary(JsonObject args)
		{
			return "slow";
		}
	}

	@Test
	public void stopStaysResponsiveWhileAToolRunsAndDiscardsItsResult() throws Exception
	{
		BlockingTool slow = new BlockingTool();
		ChatSession session = session("m", new ToolRegistry(GSON, Arrays.<AgentTool>asList(slow)));
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("c1", "slow_tool", "{}"));
		queue(200, sse("should never be requested"));
		assertTrue(session.send("go", view));
		assertTrue("tool never started", slow.started.await(10, TimeUnit.SECONDS));

		long begin = System.nanoTime();
		session.stop(view);
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
		assertTrue("stop() blocked for " + millis + " ms", millis < 1000);
		assertTrue(view.events.toString(), view.events.contains("notice:Stopped."));

		slow.release.countDown();
		Thread.sleep(300);
		assertEquals("the stopped turn must not send another request", 1, requests.size());

		// The session is usable again and the abandoned turn left nothing behind.
		responseBody = sse("ok");
		assertTrue(session.send("again", view));
		view.awaitFinished();
		assertEquals(2, requests.get(requests.size() - 1).getAsJsonArray("messages").size());
	}

	private static String textResponse(String text)
	{
		JsonObject delta = new JsonObject();
		delta.addProperty("content", text);
		JsonObject choice = new JsonObject();
		choice.add("delta", delta);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject chunk = new JsonObject();
		chunk.add("choices", choices);
		return "data: " + chunk + "\n\ndata: [DONE]\n\n";
	}

	private static String parallelToolCallsResponse(int count)
	{
		JsonArray calls = new JsonArray();
		for (int i = 0; i < count; i++)
		{
			JsonObject function = new JsonObject();
			function.addProperty("name", "xp_calculator");
			function.addProperty("arguments", "{\"from_level\":1,\"to_level\":2}");
			JsonObject call = new JsonObject();
			call.addProperty("index", i);
			call.addProperty("id", "c" + i);
			call.addProperty("type", "function");
			call.add("function", function);
			calls.add(call);
		}
		JsonObject delta = new JsonObject();
		delta.add("tool_calls", calls);
		JsonObject choice = new JsonObject();
		choice.add("delta", delta);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject chunk = new JsonObject();
		chunk.add("choices", choices);
		return "data: " + chunk + "\n\ndata: [DONE]\n\n";
	}

	/** The failure seen in the wild: the budget is spent, tools are off, and the model prints its call syntax as text. */
	@Test
	public void leakedToolSyntaxAfterTheBudgetGivesAClearErrorAndNothingLeaksIntoTheReply() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		for (int i = 0; i < ChatSession.MAX_TOOL_ROUNDS; i++)
		{
			queue(200, toolCallResponse("c" + i, "xp_calculator", "{\"from_level\":1,\"to_level\":2}"));
		}
		queue(200, textResponse("<\uFF5CDSML\uFF5Cfunction_calls>\n<\uFF5CDSML\uFF5Cinvoke name=\"xp_calculator\">"));
		assertTrue(session.send("research this", view));
		view.awaitFinished();

		assertEquals("no tool-call markup may reach the panel", "", view.assistant.toString());
		assertTrue(view.events.toString(),
			view.events.stream().anyMatch(e -> e.startsWith("notice:") && e.contains("ran out of lookups")));

		// The abandoned turn is rolled back, so the next question starts clean.
		responseBody = sse("fine");
		assertTrue(session.send("again", view));
		view.awaitFinished();
		assertEquals(2, requests.get(requests.size() - 1).getAsJsonArray("messages").size());
	}

	@Test
	public void textBeforeLeakedMarkupIsKeptAsTheAnswer() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, textResponse("Best bet is glassblowing.<\uFF5CDSML\uFF5Cfunction_calls>"));
		assertTrue(session.send("what now?", view));
		view.awaitFinished();

		assertEquals("Best bet is glassblowing.", view.assistant.toString());
		assertTrue(view.events.toString(), view.events.contains("done"));
	}

	@Test
	public void manyParallelCallsSpendTheCallBudget() throws Exception
	{
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, parallelToolCallsResponse(ChatSession.MAX_TOOL_CALLS + 1));
		queue(200, sse("answer"));
		assertTrue(session.send("lots", view));
		view.awaitFinished();

		assertEquals(2, requests.size());
		JsonObject second = requests.get(1);
		assertEquals("none", second.get("tool_choice").getAsString());
		JsonArray messages = second.getAsJsonArray("messages");
		// system, user, assistant(tool_calls), 17 tool results, then the answer-now notice.
		assertEquals(3 + (ChatSession.MAX_TOOL_CALLS + 1) + 1, messages.size());
		assertEquals(ChatSession.BUDGET_SPENT_NOTICE,
			messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString());
		assertEquals("answer", view.assistant.toString());
	}

	private String systemPromptOf(int requestIndex)
	{
		return requests.get(requestIndex).getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
	}

	@Test
	public void aSharedAccountGoesIntoTheSystemPromptOfEveryRound() throws Exception
	{
		share = true;
		account.profile = TestProfiles.ironmanZerker();
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		queue(200, toolCallResponse("c1", "xp_calculator", "{\"from_level\":1,\"to_level\":2}"));
		queue(200, sse("ok"));
		assertTrue(session.send("what next?", view));
		view.awaitFinished();

		assertEquals(2, requests.size());
		for (int i = 0; i < 2; i++)
		{
			String system = systemPromptOf(i);
			assertTrue(system, system.contains("Account type: Ironman."));
			assertTrue(system, system.contains("Crafting 42 (45,529 xp)"));
			assertTrue(system, system.startsWith("You are Bard"));
		}
		assertEquals("one read per question, not per round", 1, account.captures.get());
		assertTrue(view.events.toString(),
			view.events.stream().anyMatch(e -> e.startsWith("account:Ironman | Zerker (auto) | CB ")));
	}

	@Test
	public void theAccountIsNotReadAtAllWhenSharingIsOff() throws Exception
	{
		share = false;
		account.profile = TestProfiles.ironmanZerker();
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		responseBody = sse("ok");
		assertTrue(session.send("hello", view));
		view.awaitFinished();

		assertEquals(0, account.captures.get());
		String system = systemPromptOf(0);
		assertTrue(system, system.contains("switched off in Bard's settings"));
		assertFalse("no account data may leave when sharing is off", system.contains("Crafting 42"));
		assertFalse(system, system.contains("Account type: Ironman"));
		assertTrue(view.events.toString(), view.events.contains("account:Account data: off (see Bard's settings)"));
	}

	@Test
	public void aFailedReadStillAnswersWithoutTheAccount() throws Exception
	{
		share = true;
		account.failRead = true;
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		responseBody = sse("ok");
		assertTrue(session.send("hello", view));
		view.awaitFinished();

		assertTrue(systemPromptOf(0), systemPromptOf(0).contains("could not be read"));
		assertEquals("ok", view.assistant.toString());
	}

	@Test
	public void aSlowReadTimesOutAndStillAnswers() throws Exception
	{
		share = true;
		account.pending = new CompletableFuture<>();
		ChatSession session = session("m", new ToolRegistry(GSON, Arrays.<AgentTool>asList(new XpCalculatorTool())), 200);
		RecordingView view = new RecordingView();

		responseBody = sse("ok");
		assertTrue(session.send("hello", view));
		view.awaitFinished();

		assertTrue(systemPromptOf(0), systemPromptOf(0).contains("could not be read"));
		assertEquals("ok", view.assistant.toString());
	}

	@Test
	public void stopWhileTheAccountIsBeingReadSendsNothing() throws Exception
	{
		share = true;
		CompletableFuture<com.bardosrs.state.AccountProfile> pending = new CompletableFuture<>();
		account.pending = pending;
		ChatSession session = session("m", new ToolRegistry(GSON, Arrays.<AgentTool>asList(new XpCalculatorTool())), 5000);
		RecordingView view = new RecordingView();

		assertTrue(session.send("hello", view));
		session.stop(view);
		view.awaitFinished();
		pending.complete(TestProfiles.ironmanZerker());
		Thread.sleep(300);
		assertEquals("a stopped question must not reach the model", 0, requests.size());

		// The session recovers and the abandoned message is gone from history.
		account.pending = null;
		responseBody = sse("ok");
		assertTrue(session.send("again", view));
		view.awaitFinished();
		assertEquals(2, requests.get(0).getAsJsonArray("messages").size());
	}

	@Test
	public void everyQuestionReadsTheAccountAgain() throws Exception
	{
		share = true;
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		account.profile = TestProfiles.ironmanZerker();
		responseBody = sse("one");
		assertTrue(session.send("first", view));
		view.awaitFinished();

		account.profile = TestProfiles.ready(net.runelite.api.vars.AccountType.HARDCORE_IRONMAN,
			TestProfiles.levels(net.runelite.api.Skill.HITPOINTS, 40), com.bardosrs.state.AccountBuild.AUTO);
		responseBody = sse("two");
		assertTrue(session.send("second", view));
		view.awaitFinished();

		assertEquals(2, account.captures.get());
		assertTrue(systemPromptOf(0), systemPromptOf(0).contains("Account type: Ironman."));
		assertTrue(systemPromptOf(1), systemPromptOf(1).contains("Account type: Hardcore ironman."));
	}

	@Test
	public void sharedItemsGoIntoThePromptButNotWhenTheProfileHasNone() throws Exception
	{
		share = true;
		ChatSession session = session("m");
		RecordingView view = new RecordingView();

		account.profile = TestProfiles.ironmanZerkerWithItems();
		responseBody = sse("one");
		assertTrue(session.send("with items", view));
		view.awaitFinished();

		account.profile = TestProfiles.ironmanZerker();
		responseBody = sse("two");
		assertTrue(session.send("without items", view));
		view.awaitFinished();

		assertTrue(systemPromptOf(0), systemPromptOf(0).contains("Inventory (right now): Molten glass x14"));
		assertTrue(systemPromptOf(0), systemPromptOf(0).contains("search_items"));
		assertFalse(systemPromptOf(1), systemPromptOf(1).contains("Inventory"));
		assertFalse(systemPromptOf(1), systemPromptOf(1).contains("Molten glass"));
		assertTrue(view.events.toString(),
			view.events.stream().anyMatch(e -> e.contains("Bank: 2 items (25m ago)")));
	}
}
