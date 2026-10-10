package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.EquipmentSlot;
import com.banktaggearbuilder.model.GearSetup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * Extracts {@code {{Recommended equipment}}} and {@code {{Inventory}}} templates from
 * the wikitext of an OSRS Wiki strategy page.
 */
public class WikiTextParser
{
	private static final Pattern SLOT_PARAM = Pattern.compile("^(head|neck|cape|body|legs|weapon|2h|shield|ammo|hands|feet|ring|special)([1-5])$");
	private static final Pattern BR = Pattern.compile("(?i)<\\s*/?\\s*br\\s*/?\\s*>");
	private static final Pattern REF = Pattern.compile("(?is)<ref[^>]*/>|<ref[^>]*>.*?</ref>");
	private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");
	private static final Pattern TABBER_LABEL = Pattern.compile("(?m)^\\s*([^=\\n|]+?)\\s*=\\s*$");

	/**
	 * Wiki templates that stand in for "any cheap X". Listed best-first.
	 */
	private static final Map<String, List<String>> PSEUDO_ITEMS = new HashMap<>();

	static
	{
		PSEUDO_ITEMS.put("cheap food", Arrays.asList("Shark", "Manta ray", "Anglerfish", "Dark crab", "Sea turtle",
			"Monkfish", "Cooked karambwan", "Lobster", "Swordfish", "Tuna"));
		PSEUDO_ITEMS.put("cheap prayer", Arrays.asList("Prayer potion(4)", "Super restore(4)"));
		PSEUDO_ITEMS.put("cheap prayer potion", Arrays.asList("Prayer potion(4)", "Super restore(4)"));
		PSEUDO_ITEMS.put("cheap teleport", Arrays.asList("Teleport to house", "Varrock teleport", "Ring of dueling(8)",
			"Games necklace(8)", "Amulet of glory(6)"));
	}

	@Value
	private static class Template
	{
		String name;
		Map<String, String> params;
		int start;
		int end;
	}

	@Value
	private static class InventoryBlock
	{
		String label;
		List<List<String>> entries;
	}

	public List<GearSetup> parse(String wikitext)
	{
		String text = COMMENT.matcher(wikitext).replaceAll("");
		List<GearSetup> setups = new ArrayList<>();

		for (Template t : findTemplates(text, "recommended equipment"))
		{
			GearSetup setup = new GearSetup();
			setup.setStyle(cleanText(t.getParams().getOrDefault("style", "")));
			for (Map.Entry<String, String> e : t.getParams().entrySet())
			{
				Matcher m = SLOT_PARAM.matcher(e.getKey());
				if (!m.matches())
				{
					continue;
				}
				EquipmentSlot slot = EquipmentSlot.fromWikiParam(m.group(1));
				int rank = Integer.parseInt(m.group(2));
				List<List<String>> alternatives = parseAlternatives(e.getValue());
				if (slot != null && !alternatives.isEmpty())
				{
					setup.addRank(slot, rank, alternatives);
				}
			}
			if (!setup.isEmpty())
			{
				setups.add(setup);
			}
		}

		List<InventoryBlock> inventories = findInventories(text);
		for (GearSetup setup : setups)
		{
			InventoryBlock inv = matchInventory(setup.getStyle(), inventories);
			if (inv != null)
			{
				setup.setInventory(inv.getEntries());
			}
		}

		return setups;
	}

	/**
	 * Splits a slot value such as {@code {{plink|A}} / {{plink|B|pic=C}}{{efn|...}}} into
	 * alternatives, each with its candidate item names.
	 */
	List<List<String>> parseAlternatives(String value)
	{
		String v = removeTemplates(value, "efn");
		v = REF.matcher(v).replaceAll("");
		v = BR.matcher(v).replaceAll(" / ");

		List<List<String>> alternatives = new ArrayList<>();
		for (String piece : splitTopLevel(v, "/>+"))
		{
			List<String> names = extractNames(piece);
			if (!names.isEmpty())
			{
				alternatives.add(names);
			}
		}
		return alternatives;
	}

	private List<String> extractNames(String piece)
	{
		List<String> names = new ArrayList<>();
		for (Template t : findTemplates(piece, null))
		{
			String name = t.getName();
			if (name.equals("plink") || name.equals("plinkp") || name.equals("plinkt") || name.equals("plinkk"))
			{
				String page = cleanText(t.getParams().getOrDefault("1", ""));
				if (!page.isEmpty())
				{
					names.add(page);
				}
				String pic = cleanText(t.getParams().getOrDefault("pic", ""));
				if (!pic.isEmpty() && !pic.equalsIgnoreCase(page))
				{
					names.add(pic);
				}
			}
			else if (PSEUDO_ITEMS.containsKey(name))
			{
				names.addAll(PSEUDO_ITEMS.get(name));
			}
		}
		if (!names.isEmpty())
		{
			return names;
		}

		Matcher link = WIKILINK.matcher(piece);
		if (link.find())
		{
			String page = cleanText(link.group(1));
			if (!page.isEmpty())
			{
				names.add(page);
			}
			return names;
		}

		String plain = cleanText(removeTemplates(piece, null));
		if (!plain.isEmpty() && !plain.equalsIgnoreCase("n/a") && !plain.equalsIgnoreCase("none"))
		{
			names.add(plain);
		}
		return names;
	}

	private List<InventoryBlock> findInventories(String text)
	{
		List<InventoryBlock> blocks = new ArrayList<>();
		String lower = text.toLowerCase(Locale.ROOT);
		int idx = 0;
		while (true)
		{
			int open = lower.indexOf("<tabber>", idx);
			if (open < 0)
			{
				break;
			}
			int close = lower.indexOf("</tabber>", open);
			if (close < 0)
			{
				close = text.length();
			}
			String inner = text.substring(open + "<tabber>".length(), close);
			for (String tab : inner.split("\\|-\\|"))
			{
				String label = "";
				Matcher m = TABBER_LABEL.matcher(tab);
				if (m.find())
				{
					label = m.group(1).trim();
				}
				for (Template t : findTemplates(tab, "inventory"))
				{
					blocks.add(new InventoryBlock(label, inventoryEntries(t)));
				}
			}
			idx = close;
		}

		if (blocks.isEmpty())
		{
			for (Template t : findTemplates(text, "inventory"))
			{
				blocks.add(new InventoryBlock("", inventoryEntries(t)));
			}
		}
		return blocks;
	}

	private List<List<String>> inventoryEntries(Template inventory)
	{
		Map<String, List<String>> unique = new LinkedHashMap<>();
		for (int i = 1; i <= 28; i++)
		{
			String value = inventory.getParams().get(String.valueOf(i));
			if (value == null || value.trim().isEmpty())
			{
				continue;
			}
			List<List<String>> alternatives = parseAlternatives(value);
			if (alternatives.isEmpty())
			{
				continue;
			}
			List<String> names = alternatives.get(0);
			unique.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
		}
		return new ArrayList<>(unique.values());
	}

	private InventoryBlock matchInventory(String style, List<InventoryBlock> inventories)
	{
		if (inventories.isEmpty())
		{
			return null;
		}
		String want = normalize(style);
		for (InventoryBlock b : inventories)
		{
			if (normalize(b.getLabel()).equals(want))
			{
				return b;
			}
		}
		if (!want.isEmpty())
		{
			for (InventoryBlock b : inventories)
			{
				String have = normalize(b.getLabel());
				if (!have.isEmpty() && (have.contains(want) || want.contains(have)))
				{
					return b;
				}
			}
		}
		return inventories.size() == 1 ? inventories.get(0) : null;
	}

	private static String normalize(String s)
	{
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/**
	 * Finds templates in {@code text}. If {@code name} is non-null only templates with that
	 * (case-insensitive) name are returned, including ones nested inside other templates.
	 */
	private static List<Template> findTemplates(String text, String name)
	{
		List<Template> out = new ArrayList<>();
		int i = 0;
		while (i < text.length() - 1)
		{
			if (text.charAt(i) == '{' && text.charAt(i + 1) == '{')
			{
				int end = findTemplateEnd(text, i);
				if (end < 0)
				{
					break;
				}
				String body = text.substring(i + 2, end - 2);
				List<String> parts = splitTopLevel(body, "|");
				String tname = parts.isEmpty() ? "" : parts.get(0).trim().toLowerCase(Locale.ROOT);
				if (name == null || tname.equals(name))
				{
					Map<String, String> params = new LinkedHashMap<>();
					int positional = 1;
					for (int p = 1; p < parts.size(); p++)
					{
						String part = parts.get(p);
						int eq = part.indexOf('=');
						// A '=' inside a nested template or link does not make this a named parameter
						if (eq >= 0 && depthAt(part, eq) == 0)
						{
							params.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
						}
						else
						{
							params.put(String.valueOf(positional++), part.trim());
						}
					}
					out.add(new Template(tname, params, i, end));
				}
				else
				{
					for (Template nested : findTemplates(body, name))
					{
						out.add(new Template(nested.getName(), nested.getParams(), nested.getStart() + i + 2, nested.getEnd() + i + 2));
					}
				}
				i = end;
			}
			else
			{
				i++;
			}
		}
		return out;
	}

	/** Returns the index just past the closing braces of the template starting at {@code start}, or -1. */
	private static int findTemplateEnd(String text, int start)
	{
		int depth = 0;
		for (int i = start; i < text.length() - 1; i++)
		{
			char c = text.charAt(i);
			char n = text.charAt(i + 1);
			if (c == '{' && n == '{')
			{
				depth++;
				i++;
			}
			else if (c == '}' && n == '}')
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return i + 1;
				}
			}
		}
		return -1;
	}

	private static int depthAt(String s, int index)
	{
		int depth = 0;
		for (int i = 0; i < index && i < s.length() - 1; i++)
		{
			char c = s.charAt(i);
			char n = s.charAt(i + 1);
			if ((c == '{' && n == '{') || (c == '[' && n == '['))
			{
				depth++;
				i++;
			}
			else if ((c == '}' && n == '}') || (c == ']' && n == ']'))
			{
				depth--;
				i++;
			}
		}
		return depth;
	}

	/** Splits on any of {@code separators} that occur outside templates and links. */
	private static List<String> splitTopLevel(String s, String separators)
	{
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
			if ((c == '{' && n == '{') || (c == '[' && n == '['))
			{
				depth++;
				cur.append(c).append(n);
				i++;
			}
			else if ((c == '}' && n == '}') || (c == ']' && n == ']'))
			{
				depth--;
				cur.append(c).append(n);
				i++;
			}
			else if (depth == 0 && separators.indexOf(c) >= 0)
			{
				parts.add(cur.toString());
				cur.setLength(0);
			}
			else
			{
				cur.append(c);
			}
		}
		parts.add(cur.toString());
		return parts;
	}

	/** Removes templates named {@code name}, or every top-level template when {@code name} is null. */
	private static String removeTemplates(String s, String name)
	{
		List<Template> found = findTemplates(s, name);
		if (found.isEmpty())
		{
			return s;
		}
		StringBuilder sb = new StringBuilder(s);
		Collections.reverse(found);
		for (Template t : found)
		{
			sb.delete(t.getStart(), t.getEnd());
		}
		return sb.toString();
	}

	private static String cleanText(String s)
	{
		String out = WIKILINK.matcher(s).replaceAll("$1");
		out = HTML_TAG.matcher(out).replaceAll("");
		out = out.replace("'''", "").replace("''", "").replace("&nbsp;", " ");
		return out.trim().replaceAll("\\s+", " ");
	}
}
