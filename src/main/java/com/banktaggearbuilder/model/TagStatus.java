package com.banktaggearbuilder.model;

import java.util.List;
import lombok.Value;

/**
 * A generated tag as listed in the panel, with the result of the last Build of its setup if
 * that differs from what the tag holds.
 */
@Value
public class TagStatus
{
	GeneratedTag tag;
	/** Result of the most recent Build for this tag's setup, or null if none this session. */
	BuildResult latest;
	/** True if the tag's items in Bank Tags differ from what this plugin last wrote. */
	boolean userEdited;
	/** Human readable before/after lines between the tag and {@link #latest}. Empty when none. */
	List<String> changes;

	public boolean isUpdateAvailable()
	{
		return latest != null && !changes.isEmpty();
	}
}
