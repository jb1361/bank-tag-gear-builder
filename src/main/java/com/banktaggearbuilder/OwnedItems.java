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

	/** Exact (un-noted) item id -> quantity, per container. */
	private final Map<Integer, Integer> bank = new HashMap<>();
	private final Map<Integer, Integer> inventory = new HashMap<>();
	private final Map<Integer, Integer> equipment = new HashMap<>();
	/** Base-variation id -> quantity, derived from the maps above. */
	private final Map<Integer, Integer> bankVariants = new HashMap<>();
	private final Map<Integer, Integer> inventoryVariants = new HashMap<>();
	private final Map<Integer, Integer> equipmentVariants = new HashMap<>();

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
		Map<Integer, Integer> variants;
		switch (containerId)
		{
			case InventoryID.BANK:
				target = bank;
				variants = bankVariants;
				bankSeen = true;
				bankFromCache = false;
				break;
			case InventoryID.INV:
				target = inventory;
				variants = inventoryVariants;
				break;
			case InventoryID.WORN:
				target = equipment;
				variants = equipmentVariants;
				break;
			default:
				return false;
		}

		target.clear();
		if (container != null)
		{
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
				target.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		rebuildVariants(target, variants);
		return true;
	}

	private static void rebuildVariants(Map<Integer, Integer> exact, Map<Integer, Integer> variants)
	{
		variants.clear();
		for (Map.Entry<Integer, Integer> e : exact.entrySet())
		{
			variants.merge(ItemVariationMapping.map(e.getKey()), e.getValue(), Integer::sum);
		}
	}

	public void clear()
	{
		bank.clear();
		inventory.clear();
		equipment.clear();
		bankVariants.clear();
		inventoryVariants.clear();
		equipmentVariants.clear();
		bankSeen = false;
		bankFromCache = false;
		cacheSavedAt = 0;
	}

	/**
	 * Whether this exact item id (a specific charge or dose) is owned.
	 */
	public boolean ownsExact(int exactId, boolean includeInventoryAndEquipment)
	{
		if (bank.containsKey(exactId))
		{
			return true;
		}
		return includeInventoryAndEquipment && (inventory.containsKey(exactId) || equipment.containsKey(exactId));
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
			rebuildVariants(bank, bankVariants);
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

	/**
	 * Whether any variant of this base-variation id is owned.
	 */
	public boolean owns(int canonicalId, boolean includeInventoryAndEquipment)
	{
		if (bankVariants.containsKey(canonicalId))
		{
			return true;
		}
		return includeInventoryAndEquipment && (inventoryVariants.containsKey(canonicalId) || equipmentVariants.containsKey(canonicalId));
	}
}
