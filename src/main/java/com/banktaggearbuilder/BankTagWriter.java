package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.EquipmentSlot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.util.Text;

/**
 * Writes tags into the Bank Tags plugin's storage.
 * <p>
 * Tab names and icons are written straight to the Bank Tags config keys rather than through
 * its TabManager, because TabManager only holds the tab list while the bank is open and
 * saving through it with the bank closed would drop the player's other tabs.
 */
@Singleton
public class BankTagWriter
{
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ConfigManager configManager;

	@Inject
	private BankTagWriter(TagManager tagManager, LayoutManager layoutManager, ConfigManager configManager)
	{
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.configManager = configManager;
	}

	/**
	 * Makes a name safe to use as a bank tag: lower case, no CSV or menu-breaking characters.
	 */
	public static String sanitizeTagName(String name)
	{
		String s = Text.standardize(name).replaceAll("[<>/:,*\"]", "").trim();
		return s.isEmpty() ? "gear" : s;
	}

	/**
	 * Creates or rewrites a tag so it contains exactly {@code itemIds}.
	 *
	 * @param previousItemIds items this plugin previously wrote, removed if no longer wanted
	 */
	public void writeTag(String tag, List<Integer> itemIds, int iconItemId, List<Integer> previousItemIds, boolean layoutBySlot, BuildResult layoutSource)
	{
		tag = sanitizeTagName(tag);

		Set<Integer> wanted = new HashSet<>(itemIds);
		if (previousItemIds != null)
		{
			for (int old : previousItemIds)
			{
				if (!wanted.contains(old))
				{
					tagManager.removeTag(old, tag);
				}
			}
		}
		for (int id : itemIds)
		{
			tagManager.addTag(id, tag, true);
		}

		List<String> tabs = new ArrayList<>(Text.fromCSV(nullToEmpty(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG))));
		if (!tabs.contains(tag))
		{
			tabs.add(tag);
			configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
		}
		if (iconItemId > 0)
		{
			configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag, String.valueOf(iconItemId));
		}

		if (layoutBySlot && layoutSource != null)
		{
			layoutManager.saveLayout(buildLayout(tag, layoutSource));
		}
	}

	/**
	 * Lays the tag out as a compact equipment-screen shape in the 8-wide bank grid:
	 * <pre>
	 *   .  H  .   .  2h S1 S2 S3
	 *   C  N  A   .  S4 S5 .  .
	 *   W  B  Sh  .  .  .  .  .
	 *   .  L  .   .  .  .  .  .
	 *   G  F  R   .  .  .  .  .
	 * </pre>
	 * followed by inventory items on the rows below.
	 */
	private static Layout buildLayout(String tag, BuildResult result)
	{
		final int w = BankTagsPlugin.BANK_ITEMS_PER_ROW;
		Layout layout = new Layout(tag);
		Set<Integer> placed = new HashSet<>();

		// Columns 4-7 to the right of the equipment block hold two-handed and special attack weapons
		int[] extraCells = new int[5 * 4];
		for (int row = 0, i = 0; row < 5; row++)
		{
			for (int col = 4; col < w; col++)
			{
				extraCells[i++] = w * row + col;
			}
		}
		int extra = 0;

		// Note: Layout.setItemAtPos takes (itemId, pos)
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			if (!placed.add(pick.getItemId()))
			{
				continue;
			}
			int pos = equipmentCell(pick.getSlot(), w);
			if (pos < 0)
			{
				if (extra >= extraCells.length)
				{
					continue;
				}
				pos = extraCells[extra++];
			}
			layout.setItemAtPos(pick.getItemId(), pos);
		}

		int pos = w * 5;
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			if (placed.add(pick.getItemId()))
			{
				layout.setItemAtPos(pick.getItemId(), pos++);
			}
		}
		return layout;
	}

	/** Grid cell for a worn slot, or -1 for slots that go in the spare columns. */
	private static int equipmentCell(EquipmentSlot slot, int w)
	{
		switch (slot)
		{
			case HEAD:
				return w * 0 + 1;
			case CAPE:
				return w * 1 + 0;
			case NECK:
				return w * 1 + 1;
			case AMMO:
				return w * 1 + 2;
			case WEAPON:
				return w * 2 + 0;
			case BODY:
				return w * 2 + 1;
			case SHIELD:
				return w * 2 + 2;
			case LEGS:
				return w * 3 + 1;
			case HANDS:
				return w * 4 + 0;
			case FEET:
				return w * 4 + 1;
			case RING:
				return w * 4 + 2;
			default:
				return -1;
		}
	}

	/**
	 * Items currently in the tag, as the ids Bank Tags stores them under.
	 */
	public List<Integer> currentItems(String tag)
	{
		return tagManager.getItemsForTag(sanitizeTagName(tag));
	}

	public void deleteTag(String tag)
	{
		tag = sanitizeTagName(tag);
		tagManager.removeTag(tag);
		List<String> tabs = new ArrayList<>(Text.fromCSV(nullToEmpty(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG))));
		if (tabs.remove(tag))
		{
			configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
		}
		configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag);
		layoutManager.removeLayout(tag);
	}

	/**
	 * The text Bank Tags' "Import tag tab" accepts. Negative ids tell it to match all
	 * variants of an item (charged, degraded, ornamented...).
	 */
	public static String exportString(String tag, int iconItemId, List<Integer> itemIds)
	{
		List<String> data = new ArrayList<>();
		data.add("banktags");
		data.add("1");
		data.add(sanitizeTagName(tag));
		data.add(String.valueOf(Math.max(iconItemId, 0)));
		for (int id : itemIds)
		{
			data.add(String.valueOf(-id));
		}
		return Text.toCSV(data);
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
