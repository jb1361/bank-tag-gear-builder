package com.banktaggearbuilder.model;

import lombok.Value;

/**
 * A wiki page that contains at least one Recommended equipment table.
 */
@Value
public class ContentPage
{
	String title;
	/** Name shown in the picker; null means derive it from the title. */
	String displayOverride;

	public ContentPage(String title)
	{
		this(title, null);
	}

	public ContentPage(String title, String displayOverride)
	{
		this.title = title;
		this.displayOverride = displayOverride;
	}

	/** The short name derived from the title: "Vorkath/Strategies" becomes "Vorkath". */
	public static String shortName(String title)
	{
		String name = title;
		if (name.endsWith("/Strategies"))
		{
			name = name.substring(0, name.length() - "/Strategies".length());
		}
		int slash = name.indexOf('/');
		if (slash > 0)
		{
			// e.g. "Slayer task/Dark beast" -> "Dark beast (Slayer task)"
			name = name.substring(slash + 1) + " (" + name.substring(0, slash) + ")";
		}
		return name;
	}

	public String getDisplayName()
	{
		return displayOverride != null ? displayOverride : shortName(title);
	}

	@Override
	public String toString()
	{
		return getDisplayName();
	}
}
