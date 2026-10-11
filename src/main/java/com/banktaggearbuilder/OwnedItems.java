package com.banktaggearbuilder;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Tracks what the player owns in the bank, inventory and equipment, keyed by the
 * canonical (un-noted, base variation) item id. Updated on the client thread.
 */
@Singleton
public class OwnedItems
{
	private final ItemManager itemManager;

	private final Map<Integer, Integer> bank = new HashMap<>();
	private final Map<Integer, Integer> inventory = new HashMap<>();
	private final Map<Integer, Integer> equipment = new HashMap<>();

	/** True once the bank contents are known, from the live bank or a saved cache. */
	@Getter
	private volatile boolean bankSeen;

	/** True while the bank contents come from a saved cache rather than this session's bank. */
	@Getter
	private volatile boolean bankFromCache;

	/** When the cached bank was saved, epoch millis, or 0. */
	@Getter
	private volatile long cacheSavedAt;

	@Inject
	private OwnedItems(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/**
	 * Un-notes and un-placeholders an item, then maps it to its base variation so that
	 * charged, degraded, imbued and ornamented versions all compare equal.
	 */
	public static int canonical(ItemManager itemManager, int itemId)
	{
		return ItemVariationMapping.map(itemManager.canonicalize(itemId));
	}

	/**
	 * @return true if the container is one we track
	 */
	public boolean update(int containerId, ItemContainer container)
	{
		Map<Integer, Integer> target;
		switch (containerId)
		{
			case InventoryID.BANK:
				target = bank;
				bankSeen = true;
				bankFromCache = false;
				break;
			case InventoryID.INV:
				target = inventory;
				break;
			case InventoryID.WORN:
				target = equipment;
				break;
			default:
				return false;
		}

		target.clear();
		if (container == null)
		{
			return true;
		}
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			// Bank placeholders stand in for items the player no longer has
			if (itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			target.merge(canonical(itemManager, item.getId()), item.getQuantity(), Integer::sum);
		}
		return true;
	}

	public void clear()
	{
		bank.clear();
		inventory.clear();
		equipment.clear();
		bankSeen = false;
		bankFromCache = false;
		cacheSavedAt = 0;
	}

	/**
	 * Compact form of the bank for saving: {@code savedAt;id=qty,id=qty,...}
	 */
	public String serializeBank()
	{
		StringBuilder sb = new StringBuilder(bank.size() * 10 + 16);
		sb.append(System.currentTimeMillis()).append(';');
		boolean first = true;
		for (Map.Entry<Integer, Integer> e : bank.entrySet())
		{
			if (!first)
			{
				sb.append(',');
			}
			first = false;
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	/**
	 * Restores a bank saved by {@link #serializeBank()}. Does nothing if the live bank has
	 * already been seen this session.
	 *
	 * @return true if the cache was applied
	 */
	public boolean loadBank(String serialized)
	{
		if (bankSeen && !bankFromCache)
		{
			return false;
		}
		if (serialized == null || serialized.isEmpty())
		{
			return false;
		}
		int sep = serialized.indexOf(';');
		if (sep < 0)
		{
			return false;
		}
		Map<Integer, Integer> loaded = new HashMap<>();
		try
		{
			long savedAt = Long.parseLong(serialized.substring(0, sep));
			String body = serialized.substring(sep + 1);
			if (!body.isEmpty())
			{
				for (String entry : body.split(","))
				{
					int eq = entry.indexOf('=');
					loaded.put(Integer.parseInt(entry.substring(0, eq)), Integer.parseInt(entry.substring(eq + 1)));
				}
			}
			bank.clear();
			bank.putAll(loaded);
			cacheSavedAt = savedAt;
		}
		catch (RuntimeException e)
		{
			return false;
		}
		bankSeen = true;
		bankFromCache = true;
		return true;
	}

	public boolean owns(int canonicalId, boolean includeInventoryAndEquipment)
	{
		if (bank.containsKey(canonicalId))
		{
			return true;
		}
		return includeInventoryAndEquipment && (inventory.containsKey(canonicalId) || equipment.containsKey(canonicalId));
	}
}
