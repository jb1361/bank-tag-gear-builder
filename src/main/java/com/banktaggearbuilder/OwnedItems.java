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

	/** True once the bank has been seen this session. */
	@Getter
	private volatile boolean bankSeen;

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
