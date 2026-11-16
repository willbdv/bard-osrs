package com.bardosrs;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.AccountSource;
import com.bardosrs.tools.ToolRegistry;
import com.bardosrs.tools.WikiClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;

/**
 * Dev tool: runs wiki tools against the live OSRS Wiki and prints what the model would see.
 * gradlew wikiLive -PliveCalls='wiki_search={"query":"glassblowing"}' (several calls separated by '||')
 */
public class WikiLive
{
	private static final int PRINT_LIMIT = Integer.getInteger("wikiLive.max", 5000);

	public static void main(String[] args) throws Exception
	{
		Gson gson = new Gson();
		// Identify ourselves politely; the wiki throttles anonymous default user agents.
		OkHttpClient http = new OkHttpClient.Builder()
			.addNetworkInterceptor(chain -> chain.proceed(chain.request().newBuilder()
				.header("User-Agent", "bard-osrs-dev/0.1 (development prototype)").build()))
			.build();
		AccountSource noAccount = new AccountSource()
		{
			@Override
			public java.util.concurrent.CompletableFuture<AccountProfile> capture()
			{
				return java.util.concurrent.CompletableFuture.completedFuture(AccountProfile.notShared());
			}

			@Override
			public AccountProfile latest()
			{
				return AccountProfile.notShared();
			}
		};
		ToolRegistry registry = new ToolRegistry(gson, new WikiClient(http, gson), noAccount,
			new com.bardosrs.goals.GoalStore(new com.bardosrs.state.ProfileStore()
			{
				@Override
				public String profileKey()
				{
					return null;
				}

				@Override
				public String read(String profileKey, String key)
				{
					return null;
				}

				@Override
				public void write(String profileKey, String key, String value)
				{
				}
			}, gson));

		for (String call : String.join(" ", args).split("\\|\\|"))
		{
			int eq = call.indexOf('=');
			String name = call.substring(0, eq).trim();
			String json = call.substring(eq + 1).trim();
			String result = registry.execute(name, json);
			System.out.println("=== " + name + " " + json + "  (" + result.length() + " chars)");
			// Print the page text itself when there is one; the JSON wrapper only adds noise here.
			JsonObject parsed = gson.fromJson(result, JsonObject.class);
			String shown = parsed.has("text") ? parsed.get("text").getAsString() : result;
			System.out.println(shown.length() > PRINT_LIMIT ? shown.substring(0, PRINT_LIMIT) + "\n...[cut for display]" : shown);
			Thread.sleep(1200);
		}
		System.exit(0);
	}
}
