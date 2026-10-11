package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.EquipmentSlot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;
import net.runelite.client.util.Text;

/**
 * Writes tags into the Bank Tags plugin's storage. Must be called on the client thread.
 * <p>
 * Tab names and icons are written to the Bank Tags config keys, which is what its TabManager
 * does on save. TabManager itself only holds the tab list while the bank is open, so it is
 * only updated (and only then saved through) when the bank is open and its list matches
 * the saved one. Saving through it with the bank closed would drop the player's other tabs.
 */
@Slf4j
@Singleton
public class BankTagWriter
{
	private final Client client;
	private final TagManager tagManager;
	private final TabManager tabManager;
	private final LayoutManager layoutManager;
	private final BankTagsService bankTagsService;
	private final ConfigManager configManager;

	@Inject
	private BankTagWriter(Client client, TagManager tagManager, TabManager tabManager, LayoutManager layoutManager,
		BankTagsService bankTagsService, ConfigManager configManager)
	{
		this.client = client;
		this.tagManager = tagManager;
		this.tabManager = tabManager;
		this.layoutManager = layoutManager;
		this.bankTagsService = bankTagsService;
		this.configManager = configManager;
	}

	public boolean isBankOpen()
	{
		return client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER) != null;
	}

	private List<String> savedTabNames()
	{
		return new ArrayList<>(Text.fromCSV(nullToEmpty(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG))));
	}

	/**
	 * True when Bank Tags' in-memory tab list is loaded and agrees with the saved list, which
	 * means it is safe to change the list through TabManager and let it save.
	 */
	private boolean liveTabsLoaded()
	{
		if (!isBankOpen())
		{
			return false;
		}
		List<String> live = tabManager.getTabs().stream().map(TagTab::getTag).collect(Collectors.toList());
		return live.equals(savedTabNames());
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
	 * @param exactIds        items tagged by exact id; every other item matches all its variants
	 * @param previousItemIds items this plugin previously wrote, removed if no longer wanted
	 */
	public void writeTag(String tag, List<Integer> itemIds, Set<Integer> exactIds, int iconItemId, List<Integer> previousItemIds, boolean layoutBySlot, BuildResult layoutSource)
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
			boolean variation = !exactIds.contains(id);
			// Drop any stale tag of the other kind so a potion never matches all doses
			if (previousItemIds != null && previousItemIds.contains(id))
			{
				tagManager.removeTag(id, tag);
			}
			tagManager.addTag(id, tag, variation);
		}

		if (layoutBySlot && layoutSource != null)
		{
			layoutManager.saveLayout(buildLayout(tag, layoutSource));
		}

		if (liveTabsLoaded())
		{
			// Bank is open: register the tab with Bank Tags' live list so it is kept if the
			// player reorders or adds tabs before closing the bank, then save through it.
			TagTab tab = tabManager.find(tag);
			if (tab == null)
			{
				tab = new TagTab();
				tab.setTag(tag);
				tab.setIconItemId(iconItemId > 0 ? iconItemId : 0);
				tabManager.add(tab);
			}
			else if (iconItemId > 0)
			{
				tab.setIconItemId(iconItemId);
			}
			tabManager.save();
		}
		else
		{
			List<String> tabs = savedTabNames();
			if (!tabs.contains(tag))
			{
				tabs.add(tag);
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
			}
			if (iconItemId > 0)
			{
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag, String.valueOf(iconItemId));
			}
		}

		if (isBankOpen())
		{
			// Show the new tag's contents right away. The tab strip itself is redrawn by
			// Bank Tags the next time the bank is opened.
			bankTagsService.openBankTag(tag, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
		}
	}

	/**
	 * Lays the tag out in the 8-wide bank grid, aiming for at most 9 rows and never more
	 * than needed. Equipment blocks (3 wide, 5 tall, shaped like the worn screen) sit side by
	 * side two per band, with two-handed, special attack and extra picks in the two columns
	 * to their right. The inventory fills a 4-wide area and the rune pouch sits beside it with
	 * its runes beneath it. With three styles the inventory and pouch go next to the third
	 * block instead of below it. Anything that does not fit spills into rows below.
	 * <pre>
	 *   .  H  .  .  H  .  2h S1      one or two styles: inventory below
	 *   C  N  A  C  N  A  S2 S3
	 *   W  B  Sh W  B  Sh .  .
	 *   .  L  .  .  L  .  .  .
	 *   G  F  R  G  F  R  .  .
	 *   I  I  I  I  .  P  .  .
	 *   I  I  I  I  .  R  R  .
	 * </pre>
	 */
	private static Layout buildLayout(String tag, BuildResult result)
	{
		final int w = BankTagsPlugin.BANK_ITEMS_PER_ROW;
		Grid grid = new Grid(tag, w);

		List<String> groups = result.getGroups();
		if (groups.isEmpty())
		{
			groups = new ArrayList<>();
			groups.add("");
		}
		int blocks = groups.size();
		int bands = (blocks + 1) / 2;

		// Reserve every block rectangle first so nothing else is packed into a block's gaps
		for (int g = 0; g < blocks; g++)
		{
			grid.reserveRect(blockRow(g), blockCol(g), 5, 3);
		}

		// Worn items into their block, everything else for the style into the band's spare columns
		List<BuildResult.SlotPick> deferred = new ArrayList<>();
		for (int g = 0; g < blocks; g++)
		{
			int row = blockRow(g);
			int col = blockCol(g);
			Set<EquipmentSlot> filled = new HashSet<>();
			List<Integer> spare = grid.rect(row, 6, 5, 2);
			for (BuildResult.SlotPick pick : result.getPicks())
			{
				if (!pick.getGroup().equals(groups.get(g)) || grid.has(pick.getItemId()))
				{
					continue;
				}
				int cell = filled.add(pick.getSlot()) ? equipmentCell(pick.getSlot(), w) : -1;
				if (cell >= 0)
				{
					grid.put(pick.getItemId(), w * row + col + cell);
				}
				else if (!grid.putFirstFree(pick.getItemId(), spare))
				{
					deferred.add(pick);
				}
			}
		}

		// Inventory area and pouch strip depend on how many blocks there are
		List<Integer> inventoryCells;
		int pouchCell;
		List<Integer> runeCells;
		if (blocks == 3)
		{
			inventoryCells = grid.rect(5, 3, 5, 4);
			pouchCell = w * 5 + 7;
			runeCells = grid.rect(6, 7, 4, 1);
		}
		else
		{
			int top = 5 * bands;
			inventoryCells = grid.rect(top, 0, 7, 4);
			pouchCell = w * top + 5;
			runeCells = grid.rect(top + 1, 5, 6, 2);
		}

		List<BuildResult.InventoryPick> runes = result.getRunePouch();
		BuildResult.InventoryPick pouch = null;
		if (!runes.isEmpty())
		{
			for (BuildResult.InventoryPick pick : result.getInventory())
			{
				if (pick.getName().toLowerCase().contains("rune pouch"))
				{
					pouch = pick;
					break;
				}
			}
		}

		List<BuildResult.InventoryPick> overflow = new ArrayList<>();
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			if (pick == pouch || grid.has(pick.getItemId()))
			{
				continue;
			}
			if (!grid.putFirstFree(pick.getItemId(), inventoryCells))
			{
				overflow.add(pick);
			}
		}
		if (pouch != null && !grid.has(pouch.getItemId()))
		{
			if (!grid.put(pouch.getItemId(), pouchCell))
			{
				overflow.add(pouch);
			}
		}
		for (BuildResult.InventoryPick rune : runes)
		{
			if (!grid.has(rune.getItemId()) && !grid.putFirstFree(rune.getItemId(), runeCells))
			{
				overflow.add(rune);
			}
		}

		// Spill-over: deferred picks first, then inventory, into free cells below the used area
		List<Integer> spill = grid.rect(grid.lastRow() + 1, 0, 8, w);
		for (BuildResult.SlotPick pick : deferred)
		{
			grid.putFirstFree(pick.getItemId(), spill);
		}
		for (BuildResult.InventoryPick pick : overflow)
		{
			grid.putFirstFree(pick.getItemId(), spill);
		}
		return grid.toLayout();
	}

	/** Blocks go two per band: (0,0) (0,3) (5,0) (5,3) ... */
	private static int blockRow(int block)
	{
		return 5 * (block / 2);
	}

	private static int blockCol(int block)
	{
		return 3 * (block % 2);
	}

	/** Cell offset within a 3-wide block for a worn slot, or -1 for slots that go in the spare columns. */
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
	 * A sparse bank grid being filled: tracks which cells are taken or reserved and which
	 * items are already placed. Note Layout.setItemAtPos takes (itemId, pos).
	 */
	private static class Grid
	{
		private final Layout layout;
		private final int width;
		private final Set<Integer> taken = new HashSet<>();
		private final Set<Integer> reserved = new HashSet<>();
		private final Set<Integer> items = new HashSet<>();
		private int lastRow = -1;

		Grid(String tag, int width)
		{
			this.layout = new Layout(tag);
			this.width = width;
		}

		/** Cells of a rectangle, row by row. */
		List<Integer> rect(int row, int col, int rows, int cols)
		{
			List<Integer> cells = new ArrayList<>(rows * cols);
			for (int r = row; r < row + rows; r++)
			{
				for (int c = col; c < col + cols && c < width; c++)
				{
					cells.add(width * r + c);
				}
			}
			return cells;
		}

		/** Keeps a rectangle free for one block's shape so packing never spills into its gaps. */
		void reserveRect(int row, int col, int rows, int cols)
		{
			reserved.addAll(rect(row, col, rows, cols));
		}

		boolean has(int itemId)
		{
			return items.contains(itemId);
		}

		/** Places an item in a specific cell, which may be reserved for it. */
		boolean put(int itemId, int pos)
		{
			if (taken.contains(pos))
			{
				return false;
			}
			layout.setItemAtPos(itemId, pos);
			taken.add(pos);
			items.add(itemId);
			lastRow = Math.max(lastRow, pos / width);
			return true;
		}

		/** Places an item in the first cell of {@code cells} that is neither taken nor reserved. */
		boolean putFirstFree(int itemId, List<Integer> cells)
		{
			for (int pos : cells)
			{
				if (!taken.contains(pos) && !reserved.contains(pos))
				{
					return put(itemId, pos);
				}
			}
			return false;
		}

		int lastRow()
		{
			return lastRow;
		}

		Layout toLayout()
		{
			return layout;
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
		if (isBankOpen() && tag.equals(bankTagsService.getActiveTag()))
		{
			bankTagsService.closeBankTag();
		}
		tagManager.removeTag(tag);
		if (liveTabsLoaded())
		{
			tabManager.remove(tag);
			tabManager.save();
		}
		else
		{
			List<String> tabs = savedTabNames();
			if (tabs.remove(tag))
			{
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
			}
			configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag);
		}
		layoutManager.removeLayout(tag);
	}

	/**
	 * The text Bank Tags' "Import tag tab" accepts. Negative ids tell it to match all
	 * variants of an item (charged, degraded, ornamented...); exact ids stay positive.
	 */
	public static String exportString(String tag, int iconItemId, List<Integer> itemIds, Set<Integer> exactIds)
	{
		List<String> data = new ArrayList<>();
		data.add("banktags");
		data.add("1");
		data.add(sanitizeTagName(tag));
		data.add(String.valueOf(Math.max(iconItemId, 0)));
		for (int id : itemIds)
		{
			data.add(String.valueOf(exactIds.contains(id) ? id : -id));
		}
		return Text.toCSV(data);
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
