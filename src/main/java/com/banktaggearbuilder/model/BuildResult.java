package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.Value;

/**
 * The outcome of matching a {@link GearSetup} against what the player owns.
 */
@Data
public class BuildResult
{
	@Value
	public static class SlotPick
	{
		/** Style of the component setup this pick belongs to; empty for a single setup. */
		String group;
		EquipmentSlot slot;
		int rank;
		String name;
		/** The item id to tag. Unless exactVariant is set, every variant of this item matches. */
		int itemId;
		/** Tag exactly this id (used for charged items such as Amulet of glory(6)). */
		boolean exactVariant;
	}

	@Value
	public static class MissingSlot
	{
		String group;
		EquipmentSlot slot;
		/** Cheapest recommended item, or null if no name on the list could be resolved. */
		String suggestionName;
		int suggestionItemId;
		long suggestionPrice;
	}

	/** Group names in the order they first appear among picks and missing slots. */
	public List<String> getGroups()
	{
		Set<String> groups = new LinkedHashSet<>();
		for (SlotPick pick : picks)
		{
			groups.add(pick.getGroup());
		}
		for (MissingSlot miss : missing)
		{
			groups.add(miss.getGroup());
		}
		return new ArrayList<>(groups);
	}

	@Value
	public static class InventoryPick
	{
		String name;
		int itemId;
		/** Tag exactly this item id rather than all of its variants (used for 4-dose potions). */
		boolean exactVariant;
	}

	private final List<SlotPick> picks = new ArrayList<>();
	private final List<MissingSlot> missing = new ArrayList<>();
	private final List<InventoryPick> inventory = new ArrayList<>();
	/** Runes for the rune pouch, kept apart from the inventory items. */
	private final List<InventoryPick> runePouch = new ArrayList<>();
	private final List<String> unownedInventory = new ArrayList<>();
	private final Set<String> unresolvedNames = new LinkedHashSet<>();

	/**
	 * Item ids to include in the tag, in equipment-slot order followed by inventory order.
	 */
	public List<Integer> getTagItemIds()
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (SlotPick pick : picks)
		{
			ids.add(pick.getItemId());
		}
		for (InventoryPick pick : inventory)
		{
			ids.add(pick.getItemId());
		}
		for (InventoryPick pick : runePouch)
		{
			ids.add(pick.getItemId());
		}
		return new ArrayList<>(ids);
	}

	/** Item ids that must be tagged exactly, without variant matching. */
	public Set<Integer> getExactItemIds()
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (SlotPick pick : picks)
		{
			if (pick.isExactVariant())
			{
				ids.add(pick.getItemId());
			}
		}
		for (InventoryPick pick : inventory)
		{
			if (pick.isExactVariant())
			{
				ids.add(pick.getItemId());
			}
		}
		return ids;
	}

	/** The item used as the tag tab icon: the best weapon, else the first picked item. */
	public int getIconItemId()
	{
		for (SlotPick pick : picks)
		{
			if (pick.getSlot() == EquipmentSlot.WEAPON || pick.getSlot() == EquipmentSlot.TWO_HANDED)
			{
				return pick.getItemId();
			}
		}
		if (!picks.isEmpty())
		{
			return picks.get(0).getItemId();
		}
		if (!inventory.isEmpty())
		{
			return inventory.get(0).getItemId();
		}
		return -1;
	}
}
