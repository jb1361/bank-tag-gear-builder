package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Data;

/**
 * One choice within a rank of a slot. Usually a single item, but the wiki sometimes joins
 * items that go together with "+" or "and" (e.g. "Twisted bow + Toxic blowpipe"), in which
 * case every owned item of the group is picked.
 * <p>
 * Each item is a list of candidate names: the wiki page name first, then the "pic=" item name.
 */
@Data
public class Alternative
{
	private List<List<String>> items = new ArrayList<>();

	public static Alternative single(List<String> names)
	{
		Alternative a = new Alternative();
		a.items.add(names);
		return a;
	}

	public static Alternative single(String name)
	{
		return single(Collections.singletonList(name));
	}
}
