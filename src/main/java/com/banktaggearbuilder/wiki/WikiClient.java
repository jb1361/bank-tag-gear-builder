package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.ContentPage;
import com.banktaggearbuilder.model.GearSetup;
import com.banktaggearbuilder.model.StrategyPage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.util.Filepath;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Talks to the OSRS Wiki MediaWiki API, with an on-disk cache keyed by the game cache
 * revision so the wiki is only asked again after a game update (or on an explicit refresh).
 * Callbacks run on a background thread, never the client thread.
 */
@Slf4j
@Singleton
public class WikiClient
{
	private static final HttpUrl API = HttpUrl.parse("https://oldschool.runescape.wiki/api.php");
	private static final String EQUIPMENT_TEMPLATE = "Template:Recommended equipment";
	private static final String COMBAT_ACHIEVEMENTS_CATEGORY = "Category:Combat Achievements";
	private static final String CONTENT_FILE = "content.json";
	private static final String STRATEGIES_SUFFIX = "/Strategies";
	private static final String PAGES_DIR = "pages";

	/** On-disk form of the content list. */
	@Data
	private static class CachedContent
	{
		private int revision;
		private long fetchedAt;
		private List<String> titles = new ArrayList<>();
	}

	/** On-disk form of one parsed strategy page. */
	@Data
	private static class CachedPage
	{
		private int revision;
		private String title;
		private String displayOverride;
		private long revId;
		private long fetchedAt;
		private List<GearSetup> setups = new ArrayList<>();
	}

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Client client;
	private final ScheduledExecutorService executor;
	private final WikiTextParser parser = new WikiTextParser();

	/** Directory for the cache, or null when none is available (then every call goes to the wiki). */
	private volatile Filepath cacheDir;
	private volatile long syncedAt;

	@Inject
	private WikiClient(OkHttpClient okHttpClient, Gson gson, Client client, ScheduledExecutorService executor)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.client = client;
		this.executor = executor;
	}

	public void setCacheDirectory(Filepath dir)
	{
		this.cacheDir = dir;
	}

	/** The game cache revision, which changes with every game update. */
	private int gameRevision()
	{
		return client.getRevision();
	}

	// ---- Content list ----

	/**
	 * Lists every main-namespace page that uses the Recommended equipment template, except
	 * Combat Achievements task pages. Served from the cache unless the game has updated since
	 * it was written, or {@code force} is set.
	 */
	public void listContent(boolean force, Consumer<List<ContentPage>> onSuccess, Consumer<String> onError)
	{
		executor.execute(() ->
		{
			Filepath file = cacheFile(CONTENT_FILE);
			if (!force && file != null)
			{
				CachedContent cached = readJson(file, CachedContent.class);
				if (cached != null && isCurrent(cached.getRevision(), cached.getFetchedAt()))
				{
					List<ContentPage> pages = new ArrayList<>();
					for (String title : cached.getTitles())
					{
						pages.add(new ContentPage(title));
					}
					// Filtered again so lists cached before a rule change still follow it
					pages = disambiguate(dropDuplicateStrategies(pages));
					log.debug("Content list served from cache (revision {})", cached.getRevision());
					onSuccess.accept(pages);
					return;
				}
			}
			listContentPage(null, new LinkedHashMap<>(), pages ->
			{
				if (file != null)
				{
					CachedContent cached = new CachedContent();
					cached.setRevision(gameRevision());
					cached.setFetchedAt(System.currentTimeMillis());
					for (ContentPage p : pages)
					{
						cached.getTitles().add(p.getTitle());
					}
					writeJson(file, cached);
				}
				onSuccess.accept(pages);
			}, onError);
		});
	}

	/**
	 * Lists pages using the template together with whether each is a Combat Achievements
	 * task page; those are left out since their gear tables describe a single task, not
	 * the content. {@code continuation} carries the API's continue parameters between calls.
	 */
	private void listContentPage(Map<String, String> continuation, Map<String, Boolean> acc, Consumer<List<ContentPage>> onSuccess, Consumer<String> onError)
	{
		HttpUrl.Builder url = API.newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("generator", "embeddedin")
			.addQueryParameter("geititle", EQUIPMENT_TEMPLATE)
			.addQueryParameter("geinamespace", "0")
			.addQueryParameter("geilimit", "500")
			.addQueryParameter("prop", "categories")
			.addQueryParameter("clcategories", COMBAT_ACHIEVEMENTS_CATEGORY)
			.addQueryParameter("cllimit", "500")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2");
		if (continuation != null)
		{
			for (Map.Entry<String, String> e : continuation.entrySet())
			{
				url.addQueryParameter(e.getKey(), e.getValue());
			}
		}

		get(url.build(), onError, json ->
		{
			JsonObject query = json.getAsJsonObject("query");
			JsonArray pages = query == null ? new JsonArray() : query.getAsJsonArray("pages");
			for (JsonElement e : pages)
			{
				JsonObject page = e.getAsJsonObject();
				String title = page.get("title").getAsString();
				boolean combatAchievement = page.has("categories") && page.getAsJsonArray("categories").size() > 0;
				acc.merge(title, combatAchievement, Boolean::logicalOr);
			}
			JsonObject cont = json.getAsJsonObject("continue");
			if (cont != null)
			{
				Map<String, String> next = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : cont.entrySet())
				{
					next.put(e.getKey(), e.getValue().getAsString());
				}
				listContentPage(next, acc, onSuccess, onError);
				return;
			}
			List<ContentPage> result = new ArrayList<>();
			for (Map.Entry<String, Boolean> e : acc.entrySet())
			{
				if (!e.getValue())
				{
					result.add(new ContentPage(e.getKey()));
				}
			}
			result = disambiguate(dropDuplicateStrategies(result));
			result.sort(Comparator.comparing(p -> p.getDisplayName().toLowerCase()));
			onSuccess.accept(result);
		});
	}

	/**
	 * Drops a "/Strategies" page when another page has the same short name, e.g.
	 * "Chambers of Xeric/Challenge Mode/Strategies" beside "Chambers of Xeric/Challenge Mode".
	 */
	private static List<ContentPage> dropDuplicateStrategies(List<ContentPage> pages)
	{
		Set<String> plainNames = new HashSet<>();
		for (ContentPage p : pages)
		{
			if (!p.getTitle().endsWith(STRATEGIES_SUFFIX))
			{
				plainNames.add(ContentPage.shortName(p.getTitle()));
			}
		}
		List<ContentPage> out = new ArrayList<>(pages.size());
		for (ContentPage p : pages)
		{
			if (!p.getTitle().endsWith(STRATEGIES_SUFFIX) || !plainNames.contains(ContentPage.shortName(p.getTitle())))
			{
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * Pages whose short names still collide are shown with their full page path.
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

	// ---- Strategy pages ----

	/**
	 * Fetches and parses a strategy page. Served from the cache unless the game has updated
	 * since the page was written, or {@code force} is set.
	 */
	public void fetchStrategy(ContentPage page, boolean force, Consumer<StrategyPage> onSuccess, Consumer<String> onError)
	{
		executor.execute(() ->
		{
			Filepath file = cacheFile(PAGES_DIR + "/" + fileNameFor(page.getTitle()));
			if (!force && file != null)
			{
				CachedPage cached = readJson(file, CachedPage.class);
				if (cached != null && isCurrent(cached.getRevision(), cached.getFetchedAt()) && cached.getSetups() != null && !cached.getSetups().isEmpty())
				{
					log.debug("{} served from cache (revision {})", page.getTitle(), cached.getRevision());
					onSuccess.accept(new StrategyPage(page, cached.getRevId(), cached.getFetchedAt(), cached.getSetups()));
					return;
				}
			}
			fetchStrategyFromWiki(page, sp ->
			{
				if (file != null)
				{
					CachedPage cached = new CachedPage();
					cached.setRevision(gameRevision());
					cached.setTitle(page.getTitle());
					cached.setDisplayOverride(page.getDisplayOverride());
					cached.setRevId(sp.getRevId());
					cached.setFetchedAt(sp.getFetchedAt());
					cached.setSetups(sp.getSetups());
					writeJson(file, cached);
				}
				onSuccess.accept(sp);
			}, onError);
		});
	}

	private void fetchStrategyFromWiki(ContentPage page, Consumer<StrategyPage> onSuccess, Consumer<String> onError)
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

	// ---- Cache files ----

	/**
	 * Cache entries written before this time are fetched again, so a forced sync also
	 * refreshes pages not opened until a later session.
	 */
	public void setSyncedAt(long syncedAt)
	{
		this.syncedAt = syncedAt;
	}

	/**
	 * A cache entry is current when it was written for the game revision now running and
	 * after the last forced sync.
	 */
	private boolean isCurrent(int cachedRevision, long fetchedAt)
	{
		if (fetchedAt < syncedAt)
		{
			return false;
		}
		int now = gameRevision();
		// Before the game cache is known, keep whatever is cached rather than pounding the wiki
		return now <= 0 || cachedRevision == now;
	}

	private Filepath cacheFile(String relative)
	{
		Filepath dir = cacheDir;
		if (dir == null)
		{
			return null;
		}
		String[] parts = relative.split("/");
		Filepath f = dir;
		for (String part : parts)
		{
			f = f.joinSegment(part);
		}
		return f;
	}

	/** Title turned into a safe file name, with a hash so similar titles never collide. */
	private static String fileNameFor(String title)
	{
		String safe = title.replaceAll("[^A-Za-z0-9._-]", "_");
		if (safe.length() > 80)
		{
			safe = safe.substring(0, 80);
		}
		return safe + "-" + Integer.toHexString(title.hashCode()) + ".json";
	}

	private <T> T readJson(Filepath file, Class<T> type)
	{
		try
		{
			if (!file.isFile())
			{
				return null;
			}
			try (Reader reader = file.openBufferedReader())
			{
				return gson.fromJson(reader, type);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not read cache file {}", file, e);
			return null;
		}
	}

	private void writeJson(Filepath file, Object value)
	{
		try
		{
			Filepath parent = file.getParent();
			if (parent != null)
			{
				parent.createDirectories();
			}
			file.write(gson.toJson(value));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not write cache file {}", file, e);
		}
	}

	// ---- HTTP ----

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
