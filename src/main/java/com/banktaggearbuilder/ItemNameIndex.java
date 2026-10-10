package com.banktaggearbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Maps in-game item names to item ids. Built once per session on the client thread, a
 * chunk per frame so the client does not stall. Notes and placeholders are skipped.
 */
@Slf4j
@Singleton
public class ItemNameIndex
{
	private static final int CHUNK = 2500;

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;

	private final Map<String, List<Integer>> byName = new HashMap<>();
	private volatile boolean ready;
	private boolean building;
	private int nextId;

	@Inject
	private ItemNameIndex(Client client, ClientThread clientThread, ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
	}

	public boolean isReady()
	{
		return ready;
	}

	/**
	 * Starts building if not already built or building. Safe to call from any thread.
	 * {@code onReady} runs on the client thread once the index is complete.
	 */
	public void ensureBuilt(Runnable onReady)
	{
		clientThread.invoke(() ->
		{
			if (ready)
			{
				onReady.run();
				return;
			}
			if (building)
			{
				return;
			}
			building = true;
			nextId = 0;
			clientThread.invoke(() -> step(onReady));
		});
	}

	private boolean step(Runnable onReady)
	{
		int count = client.getItemCount();
		int end = Math.min(count, nextId + CHUNK);
		for (int id = nextId; id < end; id++)
		{
			ItemComposition c = itemManager.getItemComposition(id);
			String name = c.getName();
			if (name == null || name.equalsIgnoreCase("null") || c.getNote() != -1 || c.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
		}
		nextId = end;
		if (nextId >= count)
		{
			ready = true;
			building = false;
			log.debug("Item name index built: {} names", byName.size());
			onReady.run();
			return true;
		}
		return false;
	}

	/**
	 * Returns every item id with exactly this name (case-insensitive), or an empty list.
	 */
	public List<Integer> lookup(String name)
	{
		List<Integer> ids = byName.get(name.toLowerCase(Locale.ROOT));
		return ids == null ? Collections.emptyList() : ids;
	}
}
