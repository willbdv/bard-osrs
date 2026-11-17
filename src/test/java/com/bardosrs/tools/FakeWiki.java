package com.bardosrs.tools;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import com.google.gson.Gson;

/** A tiny stand-in for the MediaWiki API, shaped like the real OSRS Wiki responses. */
class FakeWiki implements AutoCloseable
{
	static final Gson GSON = new Gson();

	static final String SEARCH_JSON = "{\"batchcomplete\":true,\"query\":{\"searchinfo\":{\"totalhits\":2},\"search\":["
		+ "{\"ns\":0,\"title\":\"Glassblowing pipe\",\"pageid\":1,\"snippet\":\"A <span class=\\\"searchmatch\\\">glassblowing</span> pipe is a tool\"},"
		+ "{\"ns\":0,\"title\":\"Crafting\",\"pageid\":2,\"snippet\":\"Use the glassblowing pipe &amp; molten glass\"}]}}";

	static final String EMPTY_SEARCH_JSON = "{\"batchcomplete\":true,\"query\":{\"searchinfo\":{\"totalhits\":0},\"search\":[]}}";

	static final String SECTIONS_JSON = "[{\"toclevel\":1,\"level\":\"2\",\"line\":\"Spinning\",\"number\":\"1\",\"index\":\"1\",\"anchor\":\"Spinning\"},"
		+ "{\"toclevel\":1,\"level\":\"2\",\"line\":\"Glass\",\"number\":\"2\",\"index\":\"2\",\"anchor\":\"Glass\"}]";

	static final String FULL_HTML = "<p>Crafting intro.</p><h2>Spinning</h2><p>Spin things.</p><h2>Glass</h2><p>Glassblowing makes items.</p>";
	static final String SECTION_1_HTML = "<h2>Spinning</h2><p>Spin things.</p>";
	static final String SECTION_2_HTML = "<h2>Glass</h2><p>Glassblowing makes items.</p>"
		+ "<table><tr><th>Item</th><th>XP</th></tr><tr><td>Fishbowl</td><td>42.5</td></tr></table>";

	final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
	private final HttpServer server;

	FakeWiki() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api.php", exchange ->
		{
			Map<String, String> params = new HashMap<>();
			String query = exchange.getRequestURI().getRawQuery();
			if (query != null)
			{
				for (String pair : query.split("&"))
				{
					int eq = pair.indexOf('=');
					params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
				}
			}
			requests.add(params);

			int status = 200;
			String body;
			if ("search".equals(params.get("list")))
			{
				body = "nothing".equals(params.get("srsearch")) ? EMPTY_SEARCH_JSON : SEARCH_JSON;
			}
			else
			{
				String page = params.getOrDefault("page", "").replace('_', ' ');
				if (page.equals("Server error"))
				{
					status = 500;
					body = "{}";
				}
				else
				{
					body = parse(page, params);
				}
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(bytes);
			}
		});
		server.start();
	}

	private static String parse(String page, Map<String, String> params)
	{
		if (page.equals("Missing page"))
		{
			return "{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}";
		}
		if (page.equals("Broken"))
		{
			return "{\"error\":{\"code\":\"internal_api_error\",\"info\":\"Something went wrong\"}}";
		}

		String title = page.equals("Glassblowing") ? "Crafting" : page;
		StringBuilder parse = new StringBuilder("{\"parse\":{\"title\":\"" + title + "\",\"pageid\":2");
		if (page.equals("Glassblowing"))
		{
			parse.append(",\"redirects\":[{\"from\":\"Glassblowing\",\"to\":\"Crafting\",\"tofragment\":\"Glass\"}]");
		}
		if (page.equals("Cooking"))
		{
			parse.append(",\"redirects\":[{\"from\":\"Cooking\",\"to\":\"Cooking\"}]");
		}
		String prop = params.getOrDefault("prop", "");
		if (prop.contains("sections"))
		{
			parse.append(",\"sections\":").append(SECTIONS_JSON);
		}
		if (prop.contains("text"))
		{
			String html;
			String section = params.get("section");
			if ("1".equals(section))
			{
				html = SECTION_1_HTML;
			}
			else if ("2".equals(section))
			{
				html = SECTION_2_HTML;
			}
			else if (page.equals("Big page"))
			{
				StringBuilder big = new StringBuilder();
				for (int i = 0; i < 400; i++)
				{
					big.append("<p>").append("word ".repeat(30)).append("</p>");
				}
				html = big.toString();
			}
			else
			{
				html = FULL_HTML;
			}
			parse.append(",\"text\":").append(GSON.toJson(html));
		}
		return parse.append("}}").toString();
	}

	WikiClient client()
	{
		return new WikiClient(new OkHttpClient(), GSON, "http://127.0.0.1:" + server.getAddress().getPort());
	}

	/** A client whose opt-in gate the test controls, like the player's "Look things up on the OSRS Wiki" setting. */
	WikiClient client(java.util.function.BooleanSupplier enabled)
	{
		return new WikiClient(new OkHttpClient(), GSON, "http://127.0.0.1:" + server.getAddress().getPort(), enabled);
	}

	@Override
	public void close()
	{
		server.stop(0);
	}
}
