package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.ContentPage;
import com.banktaggearbuilder.model.GearSetup;
import com.banktaggearbuilder.model.StrategyPage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Talks to the OSRS Wiki MediaWiki API. All callbacks run on the OkHttp thread pool.
 */
@Slf4j
@Singleton
public class WikiClient
{
	private static final HttpUrl API = HttpUrl.parse("https://oldschool.runescape.wiki/api.php");
	private static final String EQUIPMENT_TEMPLATE = "Template:Recommended equipment";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final WikiTextParser parser = new WikiTextParser();

	@Inject
	private WikiClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	/**
	 * Lists every main-namespace page that uses the Recommended equipment template.
	 */
	public void listContent(Consumer<List<ContentPage>> onSuccess, Consumer<String> onError)
	{
		listContentPage(null, new ArrayList<>(), onSuccess, onError);
	}

	private void listContentPage(String continueToken, List<ContentPage> acc, Consumer<List<ContentPage>> onSuccess, Consumer<String> onError)
	{
		HttpUrl.Builder url = API.newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "embeddedin")
			.addQueryParameter("eititle", EQUIPMENT_TEMPLATE)
			.addQueryParameter("einamespace", "0")
			.addQueryParameter("eilimit", "500")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2");
		if (continueToken != null)
		{
			url.addQueryParameter("eicontinue", continueToken);
		}

		get(url.build(), onError, json ->
		{
			JsonArray pages = json.getAsJsonObject("query").getAsJsonArray("embeddedin");
			for (JsonElement e : pages)
			{
				acc.add(new ContentPage(e.getAsJsonObject().get("title").getAsString()));
			}
			JsonObject cont = json.getAsJsonObject("continue");
			if (cont != null && cont.has("eicontinue"))
			{
				listContentPage(cont.get("eicontinue").getAsString(), acc, onSuccess, onError);
				return;
			}
			List<ContentPage> result = disambiguate(acc);
			result.sort(Comparator.comparing(p -> p.getDisplayName().toLowerCase()));
			onSuccess.accept(result);
		});
	}

	/**
	 * Pages whose short names collide ("Chambers of Xeric/Challenge Mode" and
	 * "Chambers of Xeric/Challenge Mode/Strategies") are shown with their full page path.
	 */
	private static List<ContentPage> disambiguate(List<ContentPage> pages)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (ContentPage p : pages)
		{
			counts.merge(p.getDisplayName(), 1, Integer::sum);
		}
		List<ContentPage> out = new ArrayList<>(pages.size());
		for (ContentPage p : pages)
		{
			if (counts.get(p.getDisplayName()) > 1)
			{
				out.add(new ContentPage(p.getTitle(), p.getTitle().replace("/", " / ")));
			}
			else
			{
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * Fetches and parses a strategy page.
	 */
	public void fetchStrategy(ContentPage page, Consumer<StrategyPage> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = API.newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", page.getTitle())
			.addQueryParameter("prop", "wikitext|revid")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.build();

		get(url, onError, json ->
		{
			JsonObject parse = json.getAsJsonObject("parse");
			if (parse == null)
			{
				onError.accept("Wiki page not found: " + page.getTitle());
				return;
			}
			String wikitext = parse.get("wikitext").getAsString();
			long revId = parse.has("revid") ? parse.get("revid").getAsLong() : 0;
			List<GearSetup> setups = parser.parse(wikitext);
			if (setups.isEmpty())
			{
				onError.accept("No recommended equipment table found on " + page.getTitle());
				return;
			}
			onSuccess.accept(new StrategyPage(page, revId, System.currentTimeMillis(), setups));
		});
	}

	private void get(HttpUrl url, Consumer<String> onError, Consumer<JsonObject> onJson)
	{
		Request request = new Request.Builder().url(url).build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Wiki request failed: {}", url, e);
				onError.accept("Could not reach the OSRS Wiki: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept("Wiki returned HTTP " + r.code());
						return;
					}
					JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
					if (json.has("error"))
					{
						onError.accept("Wiki error: " + json.getAsJsonObject("error").get("info").getAsString());
						return;
					}
					onJson.accept(json);
				}
				catch (Exception e)
				{
					log.debug("Failed to parse wiki response from {}", url, e);
					onError.accept("Could not read the wiki response: " + e.getMessage());
				}
			}
		});
	}
}
