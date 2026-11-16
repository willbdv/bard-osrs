package com.bardosrs.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Streaming client for any OpenAI-compatible chat completions endpoint
 * (Ollama, LM Studio, OpenRouter, OpenAI, DeepSeek, ...).
 *
 * Requests run on the OkHttp thread pool and listener callbacks arrive on an
 * OkHttp thread, so UI code must hop onto the Swing thread itself.
 */
@Slf4j
@Singleton
public class LlmClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final int ERROR_BODY_LIMIT = 400;

	public interface Listener
	{
		/** A fragment of the visible reply text. */
		void onDelta(String text);

		/** The stream finished: the full reply, possibly with tool calls the caller must run. */
		void onComplete(Reply reply);

		void onError(String message);
	}

	/** One function call the model asked for. {@code arguments} is the raw JSON string it produced. */
	public static final class ToolCall
	{
		public final String id;
		public final String name;
		public final String arguments;

		ToolCall(String id, String name, String arguments)
		{
			this.id = id;
			this.name = name;
			this.arguments = arguments;
		}
	}

	/** A finished model reply. */
	public static final class Reply
	{
		public final String text;
		public final List<ToolCall> toolCalls;
		/** Reasoning text some providers stream separately; they require it echoed back with tool calls. */
		public final String reasoningContent;
		/** True if the model printed tool-call syntax as text; the text field already has it cut off. */
		public final boolean leakedToolMarkup;

		Reply(String text, List<ToolCall> toolCalls, String reasoningContent, boolean leakedToolMarkup)
		{
			this.text = text;
			this.toolCalls = Collections.unmodifiableList(toolCalls);
			this.reasoningContent = reasoningContent;
			this.leakedToolMarkup = leakedToolMarkup;
		}
	}

	@FunctionalInterface
	public interface Cancellable
	{
		void cancel();
	}

	/** Connection settings, captured per request from the plugin config. */
	public static final class Settings
	{
		final String baseUrl;
		final String apiKey;
		final String model;

		public Settings(String baseUrl, String apiKey, String model)
		{
			this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
			this.apiKey = apiKey == null ? "" : apiKey.trim();
			this.model = model == null ? "" : model.trim();
		}

		public boolean isConfigured()
		{
			return !baseUrl.isEmpty() && !model.isEmpty();
		}
	}

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	public LlmClient(OkHttpClient okHttpClient, Gson gson)
	{
		// Models can take a while to produce the first token, so allow more
		// than RuneLite's default read timeout.
		this.http = okHttpClient.newBuilder()
			.readTimeout(2, TimeUnit.MINUTES)
			.callTimeout(5, TimeUnit.MINUTES)
			.build();
		this.gson = gson;
	}

	/** Accepts "https://host", "https://host/v1" and a full ".../chat/completions" URL. */
	static String chatCompletionsUrl(String baseUrl)
	{
		String url = baseUrl == null ? "" : baseUrl.trim();
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		String suffix = "/chat/completions";
		if (url.endsWith(suffix))
		{
			url = url.substring(0, url.length() - suffix.length());
		}
		return url + suffix;
	}

	/**
	 * @param tools      OpenAI-style tool specs, or null for a plain chat request
	 * @param toolChoice "none" to forbid tool calls on this request, or null for the provider default
	 */
	public Cancellable stream(Settings settings, List<JsonObject> messages, JsonArray tools, String toolChoice,
		Listener listener)
	{
		HttpUrl url = HttpUrl.parse(chatCompletionsUrl(settings.baseUrl));
		if (url == null)
		{
			listener.onError("The endpoint is not a valid http(s) URL: " + settings.baseUrl);
			return () -> { };
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", settings.model);
		body.addProperty("stream", true);
		JsonArray array = new JsonArray();
		for (JsonObject message : messages)
		{
			array.add(message);
		}
		body.add("messages", array);
		if (tools != null && tools.size() > 0)
		{
			body.add("tools", tools);
			if (toolChoice != null)
			{
				body.addProperty("tool_choice", toolChoice);
			}
		}

		Request.Builder request = new Request.Builder()
			.url(url)
			.header("Accept", "text/event-stream")
			.post(RequestBody.create(JSON, gson.toJson(body)));
		if (!settings.apiKey.isEmpty())
		{
			request.header("Authorization", "Bearer " + settings.apiKey);
		}

		Call call = http.newCall(request.build());
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (!call.isCanceled())
				{
					listener.onError("Could not reach the model endpoint: " + e.getMessage());
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						listener.onError(describeHttpError(r));
						return;
					}
					ResponseBody responseBody = r.body();
					if (responseBody == null)
					{
						listener.onError("The model endpoint sent an empty response.");
						return;
					}
					readStream(responseBody.source(), call, listener);
				}
				catch (IOException e)
				{
					if (!call.isCanceled())
					{
						listener.onError("Connection lost while reading the reply: " + e.getMessage());
					}
				}
			}
		});
		return call::cancel;
	}

	/** Accumulates one tool call whose name and arguments arrive in fragments. */
	private static final class CallBuilder
	{
		String id = "";
		final StringBuilder name = new StringBuilder();
		final StringBuilder arguments = new StringBuilder();
	}

	private void readStream(BufferedSource source, Call call, Listener listener) throws IOException
	{
		StringBuilder text = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		Map<Integer, CallBuilder> calls = new TreeMap<>();
		// Text before this index has been shown; leakStart is where leaked tool-call markup began, if it did.
		int forwarded = 0;
		int leakStart = -1;

		String line;
		while (!call.isCanceled() && (line = source.readUtf8Line()) != null)
		{
			line = line.trim();
			if (!line.startsWith("data:"))
			{
				continue;
			}
			String data = line.substring("data:".length()).trim();
			if (data.isEmpty())
			{
				continue;
			}
			if (data.equals("[DONE]"))
			{
				break;
			}

			JsonObject chunk;
			try
			{
				chunk = gson.fromJson(data, JsonObject.class);
			}
			catch (JsonParseException e)
			{
				log.debug("Skipping malformed stream chunk");
				continue;
			}
			if (chunk == null)
			{
				continue;
			}
			if (chunk.has("error"))
			{
				listener.onError(describeError(chunk.get("error")));
				return;
			}

			JsonObject delta = firstDelta(chunk);
			if (delta == null)
			{
				continue;
			}
			String piece = stringField(delta, "content");
			if (piece != null && !piece.isEmpty())
			{
				text.append(piece);
				if (leakStart < 0)
				{
					leakStart = LeakedMarkup.find(text, forwarded);
					int safeEnd = leakStart >= 0 ? leakStart : text.length() - LeakedMarkup.pendingPrefixLength(text);
					if (safeEnd > forwarded)
					{
						listener.onDelta(text.substring(forwarded, safeEnd));
						forwarded = safeEnd;
					}
				}
			}
			String thought = stringField(delta, "reasoning_content");
			if (thought != null)
			{
				reasoning.append(thought);
			}
			collectToolCalls(delta, calls);
		}

		if (call.isCanceled())
		{
			return;
		}

		List<ToolCall> toolCalls = new ArrayList<>();
		for (Map.Entry<Integer, CallBuilder> entry : calls.entrySet())
		{
			CallBuilder builder = entry.getValue();
			if (builder.name.length() == 0)
			{
				continue;
			}
			String id = builder.id.isEmpty() ? "call_" + entry.getKey() : builder.id;
			toolCalls.add(new ToolCall(id, builder.name.toString(), builder.arguments.toString()));
		}

		boolean leaked = leakStart >= 0;
		if (!leaked && forwarded < text.length())
		{
			listener.onDelta(text.substring(forwarded));
		}
		String visible = leaked ? text.substring(0, leakStart) : text.toString();
		if (visible.isEmpty() && toolCalls.isEmpty() && !leaked)
		{
			listener.onError("The model returned an empty reply. Check the model name in Bard's settings.");
			return;
		}
		listener.onComplete(new Reply(visible, toolCalls, reasoning.toString(), leaked));
	}

	private static void collectToolCalls(JsonObject delta, Map<Integer, CallBuilder> calls)
	{
		JsonElement toolCalls = delta.get("tool_calls");
		if (toolCalls == null || !toolCalls.isJsonArray())
		{
			return;
		}
		JsonArray array = toolCalls.getAsJsonArray();
		for (int i = 0; i < array.size(); i++)
		{
			if (!array.get(i).isJsonObject())
			{
				continue;
			}
			JsonObject fragment = array.get(i).getAsJsonObject();
			// Some providers omit "index"; the position in the array stands in for it.
			int index = fragment.has("index") && fragment.get("index").isJsonPrimitive()
				? fragment.get("index").getAsInt() : i;
			CallBuilder builder = calls.computeIfAbsent(index, k -> new CallBuilder());

			String id = stringField(fragment, "id");
			if (id != null && !id.isEmpty())
			{
				builder.id = id;
			}
			JsonElement function = fragment.get("function");
			if (function != null && function.isJsonObject())
			{
				String name = stringField(function.getAsJsonObject(), "name");
				if (name != null)
				{
					builder.name.append(name);
				}
				String arguments = stringField(function.getAsJsonObject(), "arguments");
				if (arguments != null)
				{
					builder.arguments.append(arguments);
				}
			}
		}
	}

	private static JsonObject firstDelta(JsonObject chunk)
	{
		JsonElement choices = chunk.get("choices");
		if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().size() == 0)
		{
			return null;
		}
		JsonElement first = choices.getAsJsonArray().get(0);
		if (!first.isJsonObject())
		{
			return null;
		}
		JsonElement delta = first.getAsJsonObject().get("delta");
		return delta != null && delta.isJsonObject() ? delta.getAsJsonObject() : null;
	}

	private static String stringField(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	/** The text fragment of one streamed chunk, or null if the chunk carries none. */
	static String contentDelta(JsonObject chunk)
	{
		JsonObject delta = firstDelta(chunk);
		return delta == null ? null : stringField(delta, "content");
	}

	private static String describeHttpError(Response response) throws IOException
	{
		String hint;
		switch (response.code())
		{
			case 401:
			case 403:
				hint = " (check the API key)";
				break;
			case 404:
				hint = " (check the endpoint and model name)";
				break;
			case 429:
				hint = " (rate limited or out of credit)";
				break;
			default:
				hint = "";
		}
		ResponseBody body = response.body();
		String text = body == null ? "" : body.string().trim();
		if (text.length() > ERROR_BODY_LIMIT)
		{
			text = text.substring(0, ERROR_BODY_LIMIT) + "...";
		}
		return "The model endpoint returned HTTP " + response.code() + hint + (text.isEmpty() ? "" : ": " + text);
	}

	private static String describeError(JsonElement error)
	{
		if (error.isJsonObject())
		{
			JsonElement message = error.getAsJsonObject().get("message");
			if (message != null && message.isJsonPrimitive())
			{
				return message.getAsString();
			}
		}
		return error.toString();
	}
}
