package com.bardosrs.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LlmClientTest
{
	private static final Gson GSON = new Gson();

	private HttpServer server;
	private final AtomicReference<String> requestPath = new AtomicReference<>();
	private final AtomicReference<String> requestAuth = new AtomicReference<>();
	private final AtomicReference<String> requestBody = new AtomicReference<>();

	@Before
	public void startServer() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.start();
	}

	@After
	public void stopServer()
	{
		server.stop(0);
	}

	private String baseUrl()
	{
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	/** Serves the given raw SSE text (or an error status) for POST /v1/chat/completions. */
	private void serve(int status, String body)
	{
		server.createContext("/v1/chat/completions", exchange ->
		{
			requestPath.set(exchange.getRequestURI().getPath());
			requestAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
			exchange.sendResponseHeaders(status, 0);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		});
	}

	private static class Recorder implements LlmClient.Listener
	{
		final StringBuilder text = new StringBuilder();
		final CountDownLatch done = new CountDownLatch(1);
		volatile boolean completed;
		volatile LlmClient.Reply reply;
		volatile String error;

		@Override
		public void onDelta(String piece)
		{
			text.append(piece);
		}

		@Override
		public void onComplete(LlmClient.Reply finished)
		{
			reply = finished;
			completed = true;
			done.countDown();
		}

		@Override
		public void onError(String message)
		{
			error = message;
			done.countDown();
		}

		void await() throws InterruptedException
		{
			assertTrue("timed out waiting for the stream", done.await(10, TimeUnit.SECONDS));
		}
	}

	private static List<JsonObject> userMessage(String text)
	{
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.addProperty("content", text);
		return Collections.singletonList(message);
	}

	private Recorder run(String apiKey, String model) throws InterruptedException
	{
		LlmClient client = new LlmClient(new OkHttpClient(), GSON);
		Recorder recorder = new Recorder();
		client.stream(new LlmClient.Settings(baseUrl(), apiKey, model), userMessage("hi"), null, null, recorder);
		recorder.await();
		return recorder;
	}

	@Test
	public void urlAcceptsCommonBaseUrlForms()
	{
		assertEquals("https://api.deepseek.com/chat/completions",
			LlmClient.chatCompletionsUrl("https://api.deepseek.com"));
		assertEquals("http://localhost:11434/v1/chat/completions",
			LlmClient.chatCompletionsUrl("http://localhost:11434/v1/"));
		assertEquals("https://x.test/v1/chat/completions",
			LlmClient.chatCompletionsUrl(" https://x.test/v1/chat/completions "));
	}

	@Test
	public void streamsTextAndSendsAuthAndModel() throws Exception
	{
		serve(200,
			"data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
				+ "data:{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
				+ "data: [DONE]\n\n");

		Recorder recorder = run("secret-key", "test-model");

		assertTrue(recorder.completed);
		assertEquals("Hello", recorder.text.toString());
		assertEquals("/v1/chat/completions", requestPath.get());
		assertEquals("Bearer secret-key", requestAuth.get());
		JsonObject body = GSON.fromJson(requestBody.get(), JsonObject.class);
		assertEquals("test-model", body.get("model").getAsString());
		assertTrue(body.get("stream").getAsBoolean());
		assertFalse(body.has("tools"));
		assertFalse(body.has("tool_choice"));
		assertEquals("hi", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void omitsAuthHeaderWhenNoKey() throws Exception
	{
		serve(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
		run("", "m");
		assertNull(requestAuth.get());
	}

	@Test
	public void skipsMalformedChunks() throws Exception
	{
		serve(200,
			"data: {not json}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"fine\"}}]}\n\n"
				+ "data: [DONE]\n\n");
		Recorder recorder = run("", "m");
		assertTrue(recorder.completed);
		assertEquals("fine", recorder.text.toString());
	}

	@Test
	public void reportsHttpErrorsWithHint() throws Exception
	{
		serve(401, "{\"error\":{\"message\":\"bad key\"}}");
		Recorder recorder = run("nope", "m");
		assertNotNull(recorder.error);
		assertTrue(recorder.error, recorder.error.contains("401"));
		assertTrue(recorder.error, recorder.error.contains("API key"));
		assertTrue(recorder.error, recorder.error.contains("bad key"));
	}

	@Test
	public void reportsErrorsSentInsideTheStream() throws Exception
	{
		serve(200, "data: {\"error\":{\"message\":\"model overloaded\"}}\n\n");
		Recorder recorder = run("", "m");
		assertEquals("model overloaded", recorder.error);
	}

	@Test
	public void reportsEmptyReply() throws Exception
	{
		serve(200, "data: [DONE]\n\n");
		Recorder recorder = run("", "m");
		assertNotNull(recorder.error);
		assertTrue(recorder.error, recorder.error.contains("empty reply"));
	}

	@Test
	public void rejectsInvalidEndpointWithoutNetwork()
	{
		LlmClient client = new LlmClient(new OkHttpClient(), GSON);
		Recorder recorder = new Recorder();
		client.stream(new LlmClient.Settings("not a url", "", "m"), userMessage("hi"), null, null, recorder);
		assertNotNull(recorder.error);
		assertTrue(recorder.error, recorder.error.contains("not a valid"));
	}

	private static String toolChunk(int index, String id, String name, String arguments)
	{
		JsonObject function = new JsonObject();
		if (name != null)
		{
			function.addProperty("name", name);
		}
		function.addProperty("arguments", arguments);
		JsonObject call = new JsonObject();
		call.addProperty("index", index);
		if (id != null)
		{
			call.addProperty("id", id);
		}
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
		return "data: " + chunk + "\n\n";
	}

	@Test
	public void assemblesToolCallsFromFragmentedChunks() throws Exception
	{
		serve(200,
			toolChunk(0, "call_a", "xp_calculator", "")
				+ toolChunk(1, "call_b", "xp_calculator", "{\"to_level\":")
				+ toolChunk(0, null, null, "{\"from_level\":42,")
				+ toolChunk(0, null, null, "\"to_level\":56}")
				+ toolChunk(1, null, null, "10}")
				+ "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertTrue(recorder.error, recorder.completed);
		assertEquals("", recorder.reply.text);
		assertEquals(2, recorder.reply.toolCalls.size());
		assertEquals("call_a", recorder.reply.toolCalls.get(0).id);
		assertEquals("xp_calculator", recorder.reply.toolCalls.get(0).name);
		assertEquals("{\"from_level\":42,\"to_level\":56}", recorder.reply.toolCalls.get(0).arguments);
		assertEquals("{\"to_level\":10}", recorder.reply.toolCalls.get(1).arguments);
	}

	@Test
	public void toolCallsWithoutAnIdGetOne() throws Exception
	{
		serve(200, toolChunk(0, null, "xp_calculator", "{}") + "data: [DONE]\n\n");
		Recorder recorder = run("", "m");
		assertEquals("call_0", recorder.reply.toolCalls.get(0).id);
	}

	@Test
	public void keepsReplyTextAlongsideToolCalls() throws Exception
	{
		serve(200,
			"data: {\"choices\":[{\"delta\":{\"content\":\"Let me check.\"}}]}\n\n"
				+ toolChunk(0, "c", "xp_calculator", "{}")
				+ "data: [DONE]\n\n");
		Recorder recorder = run("", "m");
		assertEquals("Let me check.", recorder.text.toString());
		assertEquals("Let me check.", recorder.reply.text);
		assertEquals(1, recorder.reply.toolCalls.size());
	}

	@Test
	public void capturesReasoningContentWithoutShowingIt() throws Exception
	{
		serve(200,
			"data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm \"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"ok\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"}}]}\n\n"
				+ "data: [DONE]\n\n");
		Recorder recorder = run("", "m");
		assertEquals("Answer", recorder.text.toString());
		assertEquals("hmm ok", recorder.reply.reasoningContent);
	}

	@Test
	public void sendsToolsAndToolChoice() throws Exception
	{
		serve(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
		JsonArray tools = new JsonArray();
		JsonObject tool = new JsonObject();
		tool.addProperty("type", "function");
		tools.add(tool);

		LlmClient client = new LlmClient(new OkHttpClient(), GSON);
		Recorder recorder = new Recorder();
		client.stream(new LlmClient.Settings(baseUrl(), "", "m"), userMessage("hi"), tools, "none", recorder);
		recorder.await();

		JsonObject body = GSON.fromJson(requestBody.get(), JsonObject.class);
		assertEquals(1, body.getAsJsonArray("tools").size());
		assertEquals("none", body.get("tool_choice").getAsString());
	}

	private static String textChunk(String text)
	{
		JsonObject delta = new JsonObject();
		delta.addProperty("content", text);
		JsonObject choice = new JsonObject();
		choice.add("delta", delta);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject chunk = new JsonObject();
		chunk.add("choices", choices);
		return "data: " + chunk + "\n\n";
	}

	private static final String BAR = "\uFF5C";

	@Test
	public void leakedToolMarkupIsNeverShownAndCutsTheReply() throws Exception
	{
		serve(200,
			textChunk("Here is the plan. ")
				+ textChunk("<" + BAR + "DSML" + BAR + "function_calls>\n<" + BAR + "DSML" + BAR + "invoke name=\"xp_calculator\">")
				+ "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertTrue(recorder.error, recorder.completed);
		assertEquals("Here is the plan. ", recorder.text.toString());
		assertEquals("Here is the plan. ", recorder.reply.text);
		assertTrue(recorder.reply.leakedToolMarkup);
	}

	@Test
	public void aMarkerSplitAcrossChunksIsStillCaught() throws Exception
	{
		serve(200,
			textChunk("Answer text")
				+ textChunk("<")
				+ textChunk(BAR)
				+ textChunk("DSML" + BAR + "invoke>")
				+ "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertEquals("Answer text", recorder.text.toString());
		assertTrue(recorder.reply.leakedToolMarkup);
	}

	@Test
	public void leakedMarkupWithNoVisibleTextCompletesInsteadOfClaimingAnEmptyReply() throws Exception
	{
		serve(200, textChunk("<" + BAR + "DSML" + BAR + "function_calls>") + "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertEquals(null, recorder.error);
		assertTrue(recorder.completed);
		assertEquals("", recorder.reply.text);
		assertTrue(recorder.reply.leakedToolMarkup);
	}

	@Test
	public void ordinaryAngleBracketsAreShownInFull() throws Exception
	{
		serve(200,
			textChunk("if a <") + textChunk(" b then c<") + textChunk("d and x <") + textChunk("3 done")
				+ "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertEquals("if a < b then c<d and x <3 done", recorder.text.toString());
		assertFalse(recorder.reply.leakedToolMarkup);
	}

	@Test
	public void aTrailingAngleBracketIsFlushedWhenTheStreamEnds() throws Exception
	{
		serve(200, textChunk("x <") + "data: [DONE]\n\n");

		Recorder recorder = run("", "m");

		assertEquals("x <", recorder.text.toString());
		assertEquals("x <", recorder.reply.text);
		assertFalse(recorder.reply.leakedToolMarkup);
	}
}
