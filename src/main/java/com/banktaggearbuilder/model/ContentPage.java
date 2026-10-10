package com.banktaggearbuilder.model;

import lombok.Value;

/**
 * A wiki page that contains at least one Recommended equipment table.
 */
@Value
public class ContentPage
{
	String title;

	public String getDisplayName()
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

	@Override
	public String toString()
	{
		return getDisplayName();
	}
}
