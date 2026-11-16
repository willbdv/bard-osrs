package com.bardosrs.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reads the Old School RuneScape Wiki through its public MediaWiki API. Calls
 * block, so they must run off the client and Swing threads. Responses are
 * cached briefly to keep request volume polite.
 *
 * Wiki text is available under CC BY-NC-SA 3.0; results carry the page URL so
 * answers can credit the page they came from.
 */
@Slf4j
@Singleton
public class WikiClient
{
	static final String DEFAULT_BASE_URL = "https://oldschool.runescape.wiki";

	static final int SEARCH_LIMIT = 6;
	static final int PAGE_CHAR_LIMIT = 6000;
	static final int SECTION_CHAR_LIMIT = 8000;
	private static final int MAX_RESPONSE_BYTES = 3_000_000;
	private static final int MAX_LISTED_SECTIONS = 40;
	private static final int CACHE_ENTRIES = 40;
	private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(15);

	public static final class SearchHit
	{
		public final String title;
		public final String snippet;
		public final String url;

		SearchHit(String title, String snippet, String url)
		{
			this.title = title;
			this.snippet = snippet;
			this.url = url;
		}
	}

	public static final class Page
	{
		public final String title;
		public final String url;
		/** The section whose text is returned, or null for the start of the page. */
		public final String section;
		public final String redirectedFrom;
		public final List<String> sections;
		public final String text;
		public final boolean truncated;

		Page(String title, String url, String section, String redirectedFrom, List<String> sections, String text,
			boolean truncated)
		{
			this.title = title;
			this.url = url;
			this.section = section;
			this.redirectedFrom = redirectedFrom;
			this.sections = sections;
			this.text = text;
			this.truncated = truncated;
		}
	}

	private static final class SectionRef
	{
		final String index;
		final String line;
		final String anchor;

		SectionRef(String index, String line, String anchor)
		{
			this.index = index;
			this.line = line;
			this.anchor = anchor;
		}
	}

	private static final class CacheEntry
	{
		final long time = System.currentTimeMillis();
		final JsonObject json;

		CacheEntry(JsonObject json)
		{
			this.json = json;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final String baseUrl;
	private final Map<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest)
		{
			return size() > CACHE_ENTRIES;
		}
	};

	@Inject
	public WikiClient(OkHttpClient okHttpClient, Gson gson)
	{
		this(okHttpClient, gson, DEFAULT_BASE_URL);
	}

	WikiClient(OkHttpClient okHttpClient, Gson gson, String baseUrl)
	{
		this.http = okHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
		this.gson = gson;
		this.baseUrl = baseUrl;
	}

	public List<SearchHit> search(String query)
	{
		JsonObject json = get(api()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "search")
			.addQueryParameter("srsearch", query)
			.addQueryParameter("srnamespace", "0")
			.addQueryParameter("srlimit", String.valueOf(SEARCH_LIMIT))
			.build());

		List<SearchHit> hits = new ArrayList<>();
		JsonObject queryObject = object(json, "query");
		JsonArray results = queryObject == null ? null : array(queryObject, "search");
		if (results == null)
		{
			return hits;
		}
		for (JsonElement element : results)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject hit = element.getAsJsonObject();
			String title = string(hit, "title");
			if (title != null)
			{
				hits.add(new SearchHit(title, HtmlText.toText(string(hit, "snippet")), pageUrl(title, null)));
			}
		}
		return hits;
	}

	/**
	 * Reads a page. With no {@code section} it returns the start of the page plus a list of its sections; with a
	 * section name it returns just that section. A redirect that points at a section (for example "Glassblowing"
	 * redirecting to a section of "Crafting") opens that section.
	 */
	public Page page(String title, String section)
	{
		boolean explicit = section != null && !section.trim().isEmpty();
		JsonObject meta = parse(title, explicit ? "sections" : "text|sections", null);

		String resolvedTitle = string(meta, "title");
		if (resolvedTitle == null)
		{
			resolvedTitle = title;
		}
		List<SectionRef> sections = sectionsOf(meta);
		String redirectedFrom = null;
		String redirectFragment = null;
		JsonArray redirects = array(meta, "redirects");
		if (redirects != null && redirects.size() > 0 && redirects.get(0).isJsonObject())
		{
			redirectedFrom = string(redirects.get(0).getAsJsonObject(), "from");
			redirectFragment = string(redirects.get(0).getAsJsonObject(), "tofragment");
		}

		String wanted = explicit ? section : redirectFragment;
		SectionRef match = wanted == null ? null : findSection(sections, wanted);
		if (explicit && match == null)
		{
			throw new ToolException("The page '" + resolvedTitle + "' has no section named '" + section.trim()
				+ "'. Its sections are: " + String.join(", ", sectionNames(sections)) + ".");
		}

		if (match != null)
		{
			JsonObject sectionJson = parse(resolvedTitle, "text", match.index);
			String text = HtmlText.toText(string(sectionJson, "text"));
			String cut = HtmlText.truncate(text, SECTION_CHAR_LIMIT);
			return new Page(resolvedTitle, pageUrl(resolvedTitle, match.anchor), match.line, redirectedFrom,
				sectionNames(sections), cut.isEmpty() ? "(This section is empty.)" : cut, cut.length() < text.length());
		}

		String text = HtmlText.toText(string(meta, "text"));
		String cut = HtmlText.truncate(text, PAGE_CHAR_LIMIT);
		return new Page(resolvedTitle, pageUrl(resolvedTitle, null), null, redirectedFrom, sectionNames(sections),
			cut, cut.length() < text.length());
	}

	private JsonObject parse(String title, String prop, String sectionIndex)
	{
		HttpUrl.Builder url = api()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", title)
			.addQueryParameter("prop", prop)
			.addQueryParameter("redirects", "1")
			.addQueryParameter("disableeditsection", "1")
			.addQueryParameter("disablelimitreport", "1");
		if (sectionIndex != null)
		{
			url.addQueryParameter("section", sectionIndex);
		}
		JsonObject json = get(url.build());
		JsonObject parsed = object(json, "parse");
		if (parsed == null)
		{
			throw new ToolException("No wiki page named '" + title + "'. Use wiki_search to find the right title.");
		}
		return parsed;
	}

	private HttpUrl.Builder api()
	{
		HttpUrl url = HttpUrl.parse(baseUrl + "/api.php");
		if (url == null)
		{
			throw new ToolException("The wiki address is invalid.");
		}
		return url.newBuilder()
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2");
	}

	private JsonObject get(HttpUrl url)
	{
		String key = url.toString();
		synchronized (cache)
		{
			CacheEntry cached = cache.get(key);
			if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL_MS)
			{
				return cached.json;
			}
		}

		Request request = new Request.Builder().url(url).header("Accept", "application/json").build();
		JsonObject json;
		try (Response response = http.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new ToolException("The OSRS Wiki returned HTTP " + response.code() + ". Try again shortly.");
			}
			json = gson.fromJson(response.peekBody(MAX_RESPONSE_BYTES).string(), JsonObject.class);
		}
		catch (IOException e)
		{
			throw new ToolException("Could not reach the OSRS Wiki: " + e.getMessage());
		}
		catch (JsonParseException e)
		{
			throw new ToolException("The OSRS Wiki sent a response that could not be read.");
		}
		if (json == null)
		{
			throw new ToolException("The OSRS Wiki sent an empty response.");
		}

		JsonObject error = object(json, "error");
		if (error != null && !"missingtitle".equals(string(error, "code")))
		{
			throw new ToolException("The OSRS Wiki reported an error: " + string(error, "info"));
		}
		synchronized (cache)
		{
			cache.put(key, new CacheEntry(json));
		}
		return json;
	}

	private static List<SectionRef> sectionsOf(JsonObject meta)
	{
		List<SectionRef> sections = new ArrayList<>();
		JsonArray array = array(meta, "sections");
		if (array == null)
		{
			return sections;
		}
		for (JsonElement element : array)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject section = element.getAsJsonObject();
			String index = string(section, "index");
			String line = string(section, "line");
			if (index != null && !index.isEmpty() && line != null)
			{
				String anchor = string(section, "anchor");
				sections.add(new SectionRef(index, HtmlText.toText(line), anchor == null ? "" : anchor));
			}
		}
		return sections;
	}

	private static List<String> sectionNames(List<SectionRef> sections)
	{
		List<String> names = new ArrayList<>();
		for (SectionRef section : sections)
		{
			if (names.size() >= MAX_LISTED_SECTIONS)
			{
				break;
			}
			names.add(section.line);
		}
		return names;
	}

	/** Exact name first, then anchor, then prefix, then substring; all case-insensitive. */
	private static SectionRef findSection(List<SectionRef> sections, String wanted)
	{
		String target = normalise(wanted);
		for (int pass = 0; pass < 4; pass++)
		{
			for (SectionRef section : sections)
			{
				String line = normalise(section.line);
				String anchor = normalise(section.anchor);
				boolean hit;
				switch (pass)
				{
					case 0:
						hit = line.equals(target);
						break;
					case 1:
						hit = anchor.equals(target);
						break;
					case 2:
						hit = line.startsWith(target);
						break;
					default:
						hit = line.contains(target);
				}
				if (hit)
				{
					return section;
				}
			}
		}
		return null;
	}

	private static String normalise(String text)
	{
		return text.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
	}

	String pageUrl(String title, String anchor)
	{
		HttpUrl base = HttpUrl.parse(baseUrl);
		if (base == null)
		{
			return baseUrl;
		}
		HttpUrl.Builder url = base.newBuilder().addPathSegment("w").addPathSegment(title.replace(' ', '_'));
		if (anchor != null && !anchor.isEmpty())
		{
			url.fragment(anchor);
		}
		return url.build().toString();
	}

	private static JsonObject object(JsonObject parent, String key)
	{
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject parent, String key)
	{
		JsonElement element = parent.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String string(JsonObject parent, String key)
	{
		JsonElement element = parent.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
