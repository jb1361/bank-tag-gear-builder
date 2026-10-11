package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.EquipmentSlot;
import com.banktaggearbuilder.model.GearSetup;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;

/**
 * Picks the best-ranked owned item for each slot of a setup. Must run on the client thread
 * because it reads item compositions and prices.
 */
@Singleton
public class GearMatcher
{
	/** Trailing dose marker on potion names, e.g. "Prayer potion(3)" or "Super restore (4)". */
	private static final Pattern POTION_DOSE = Pattern.compile("\\s?\\([1-4]\\)$");

	private final ItemManager itemManager;
	private final ItemNameIndex index;
	private final OwnedItems owned;

	@Inject
	private GearMatcher(ItemManager itemManager, ItemNameIndex index, OwnedItems owned)
	{
		this.itemManager = itemManager;
		this.index = index;
		this.owned = owned;
	}

	public BuildResult match(GearSetup setup, boolean includeInventoryAndEquipment, boolean includeInventoryItems)
	{
		BuildResult result = new BuildResult();

		for (EquipmentSlot slot : EquipmentSlot.values())
		{
			List<List<List<String>>> ranks = setup.getSlots().get(slot);
			if (ranks == null || ranks.isEmpty())
			{
				continue;
			}
			matchSlot(slot, ranks, includeInventoryAndEquipment, result);
		}

		if (includeInventoryItems)
		{
			for (List<String> names : setup.getInventory())
			{
				if (isPotion(names.get(0)))
				{
					addPotion(names.get(0), result);
					continue;
				}
				int id = firstOwned(names, includeInventoryAndEquipment, result);
				if (id >= 0)
				{
					result.getInventory().add(new BuildResult.InventoryPick(itemManager.getItemComposition(id).getName(), id, false));
				}
				else
				{
					result.getUnownedInventory().add(names.get(0));
				}
			}
		}

		return result;
	}

	private static boolean isPotion(String name)
	{
		return POTION_DOSE.matcher(name).find();
	}

	/**
	 * Potions are always tagged as exactly their 4-dose item, owned or not, so the tag never
	 * shows partly used doses. Gear uses variant matching; potions must not.
	 */
	private void addPotion(String name, BuildResult result)
	{
		String fourDose = POTION_DOSE.matcher(name).replaceFirst("(4)");
		List<Integer> ids = index.lookup(fourDose);
		if (ids.isEmpty())
		{
			result.getUnresolvedNames().add(fourDose);
			return;
		}
		// Lowest id is the ordinary item; higher duplicates are minigame or quest copies
		int id = itemManager.canonicalize(ids.get(0));
		result.getInventory().add(new BuildResult.InventoryPick(fourDose, id, true));
	}

	private void matchSlot(EquipmentSlot slot, List<List<List<String>>> ranks, boolean includeInvEquip, BuildResult result)
	{
		int bestSuggestion = -1;
		String bestSuggestionName = null;
		long bestPrice = Long.MAX_VALUE;
		int fallback = -1;
		String fallbackName = null;

		for (int r = 0; r < ranks.size(); r++)
		{
			for (List<String> names : ranks.get(r))
			{
				int ownedId = firstOwned(names, includeInvEquip, result);
				if (ownedId >= 0)
				{
					result.getPicks().add(new BuildResult.SlotPick(slot, r + 1, itemManager.getItemComposition(ownedId).getName(), ownedId));
					return;
				}

				// Not owned: remember the cheapest option as the suggestion for this slot
				for (String name : names)
				{
					for (int id : index.lookup(name))
					{
						int canon = OwnedItems.canonical(itemManager, id);
						if (fallback < 0)
						{
							fallback = canon;
							fallbackName = name;
						}
						long price = itemManager.getItemPrice(canon);
						if (price > 0 && price < bestPrice)
						{
							bestPrice = price;
							bestSuggestion = canon;
							bestSuggestionName = name;
						}
					}
				}
			}
		}

		if (bestSuggestion < 0)
		{
			bestSuggestion = fallback;
			bestSuggestionName = fallbackName;
			bestPrice = 0;
		}
		result.getMissing().add(new BuildResult.MissingSlot(slot, bestSuggestionName, bestSuggestion, bestPrice));
	}

	/**
	 * @return the canonical id of the first owned item among the candidate names, or -1
	 */
	private int firstOwned(List<String> names, boolean includeInvEquip, BuildResult result)
	{
		boolean resolvedAny = false;
		for (String name : names)
		{
			List<Integer> ids = index.lookup(name);
			if (ids.isEmpty())
			{
				continue;
			}
			resolvedAny = true;
			for (int id : ids)
			{
				int canon = OwnedItems.canonical(itemManager, id);
				if (owned.owns(canon, includeInvEquip))
				{
					return canon;
				}
			}
		}
		if (!resolvedAny && !names.isEmpty())
		{
			result.getUnresolvedNames().add(names.get(0));
		}
		return -1;
	}

	/**
	 * Fills {@code into} with slot display name to item name, used for before/after diffs.
	 */
	public static void summarize(BuildResult result, Map<String, String> into)
	{
		into.clear();
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			into.put(pick.getSlot().getDisplayName(), pick.getName());
		}
	}
}
