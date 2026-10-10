package com.banktaggearbuilder.model;

import java.util.List;
import lombok.Value;

/**
 * The result of re-checking a generated tag against the current bank.
 */
@Value
public class TagStatus
{
	GeneratedTag tag;
	/** Latest match result, or null if the bank has not been seen yet this session. */
	BuildResult latest;
	/** True if the tag's items in Bank Tags differ from what this plugin last wrote. */
	boolean userEdited;
	/** Human readable before/after lines. Empty when the tag is current. */
	List<String> changes;

	public boolean isUpdateAvailable()
	{
		return latest != null && !changes.isEmpty();
	}
}
