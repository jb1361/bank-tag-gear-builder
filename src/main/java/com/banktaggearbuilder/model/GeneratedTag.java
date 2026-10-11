package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A tag this plugin created, persisted so it can be re-checked when the bank changes.
 */
@Data
public class GeneratedTag
{
	private String tag;
	private String pageTitle;
	private String displayName;
	private String style;
	private long revId;
	private long fetchedAt;
	private int iconItemId;
	/** The setup the tag was built from, so re-checks need no network. */
	private GearSetup setup;
	/** Item ids the plugin last wrote to the tag. */
	private List<Integer> itemIds = new ArrayList<>();
	/** Subset of itemIds tagged by exact id (4-dose potions) rather than all variants. */
	private List<Integer> exactItemIds = new ArrayList<>();
	/** Slot name -> item name chosen, for showing before/after diffs. */
	private Map<String, String> picks = new LinkedHashMap<>();
	/** Set when a newer wiki revision was seen by "Refresh all". */
	private boolean sourceChanged;
}
