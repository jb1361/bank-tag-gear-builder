package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.Alternative;
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
 * Extracts {@code {{Recommended equipment}}}, {@code {{Inventory}}} and {@code {{Rune pouch}}}
 * templates from the wikitext of an OSRS Wiki strategy page.
 */
public class WikiTextParser
{
	private static final Pattern SLOT_PARAM = Pattern.compile("^(head|neck|cape|body|legs|weapon|2h|shield|ammo|hands|feet|ring|special)([1-5])$");
	private static final Pattern BR = Pattern.compile("(?i)<\\s*/?\\s*br\\s*/?\\s*>");
	private static final Pattern BR_AFTER_JOIN = Pattern.compile("(?i)(\\+|\\band)\\s*<\\s*/?\\s*br\\s*/?\\s*>\\s*");
	private static final Pattern BR_BEFORE_JOIN = Pattern.compile("(?i)<\\s*/?\\s*br\\s*/?\\s*>\\s*(\\+|and\\b)");
	private static final Pattern REF = Pattern.compile("(?is)<ref[^>]*/>|<ref[^>]*>.*?</ref>");
	private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");
	private static final Pattern TABBER_LABEL = Pattern.compile("(?m)^\\s*([^=\\n|]+?)\\s*=\\s*$");
	private static final Pattern HEADING = Pattern.compile("(?m)^(={2,6})\\s*(.+?)\\s*\\1\\s*$");

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
		List<List<String>> runePouch;
		/** True when this block is the union of several unlabelled example loadouts. */
		boolean fromTiers;
	}

	public List<GearSetup> parse(String wikitext)
	{
		String text = COMMENT.matcher(wikitext).replaceAll("");
		List<Template> headings = findHeadings(text);
		List<GearSetup> setups = new ArrayList<>();

		for (Template t : findTemplates(text, "recommended equipment"))
		{
			GearSetup setup = new GearSetup();
			setup.setStyle(cleanText(t.getParams().getOrDefault("style", "")));
			setup.setSection(sectionFor(headings, t.getStart()));
			for (Map.Entry<String, String> e : t.getParams().entrySet())
			{
				Matcher m = SLOT_PARAM.matcher(e.getKey());
				if (!m.matches())
				{
					continue;
				}
				EquipmentSlot slot = EquipmentSlot.fromWikiParam(m.group(1));
				int rank = Integer.parseInt(m.group(2));
				List<Alternative> alternatives = parseAlternatives(e.getValue());
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

		// A page with a top-level Equipment section lists its real loadouts there; tables
		// further down (per boss or phase) are partial and would only add duplicate gear
		boolean hasEquipmentSection = setups.stream().anyMatch(s -> s.getSection().equalsIgnoreCase("equipment"));
		if (hasEquipmentSection)
		{
			setups.removeIf(s -> !s.getSection().equalsIgnoreCase("equipment"));
		}

		List<InventoryBlock> inventories = findInventories(text);
		for (GearSetup setup : setups)
		{
			InventoryBlock inv = matchInventory(setup.getStyle(), inventories);
			if (inv != null)
			{
				setup.setInventory(inv.getEntries());
				setup.setRunePouch(inv.getRunePouch());
				setup.setInventoryFromTiers(inv.isFromTiers());
			}
		}

		return addCombined(setups);
	}

	/**
	 * Where a section holds several tables (melee, ranged and magic switches for a raid, for
	 * example), adds a combined setup for the section that builds all of them into one tag.
	 */
	private static List<GearSetup> addCombined(List<GearSetup> setups)
	{
		Map<String, List<GearSetup>> bySection = new LinkedHashMap<>();
		for (GearSetup s : setups)
		{
			bySection.computeIfAbsent(s.getSection(), k -> new ArrayList<>()).add(s);
		}

		List<GearSetup> out = new ArrayList<>();
		for (Map.Entry<String, List<GearSetup>> e : bySection.entrySet())
		{
			List<GearSetup> group = e.getValue();
			if (group.size() > 1)
			{
				GearSetup combined = new GearSetup();
				combined.setSection(e.getKey());
				List<String> styles = new ArrayList<>();
				for (GearSetup s : group)
				{
					styles.add(s.getStyle().isEmpty() ? "setup " + (styles.size() + 1) : s.getStyle());
				}
				combined.setStyle("All (" + String.join(" + ", styles) + ")");
				combined.setComponents(new ArrayList<>(group));
				combined.setInventory(union(group, true));
				combined.setRunePouch(union(group, false));
				combined.setInventoryFromTiers(group.stream().anyMatch(GearSetup::isInventoryFromTiers));
				out.add(combined);
			}
			out.addAll(group);
		}
		return out;
	}

	private static List<List<String>> union(List<GearSetup> setups, boolean inventory)
	{
		Map<String, List<String>> unique = new LinkedHashMap<>();
		for (GearSetup s : setups)
		{
			for (List<String> names : inventory ? s.getInventory() : s.getRunePouch())
			{
				unique.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
			}
		}
		return new ArrayList<>(unique.values());
	}

	/**
	 * Splits a slot value such as {@code {{plink|A}} / {{plink|B|pic=C}}{{efn|...}}} into
	 * alternatives. Items joined with "+" or "and" form one alternative with several items.
	 */
	List<Alternative> parseAlternatives(String value)
	{
		String v = removeTemplates(value, "efn");
		v = REF.matcher(v).replaceAll("");
		// A line break next to "+" or "and" is just wrapping, not a new alternative
		v = BR_AFTER_JOIN.matcher(v).replaceAll("$1 ");
		v = BR_BEFORE_JOIN.matcher(v).replaceAll(" $1");
		v = BR.matcher(v).replaceAll(" / ");
		v = replaceTopLevelWord(v, "and", "+");

		List<Alternative> alternatives = new ArrayList<>();
		for (String piece : splitTopLevel(v, "/>"))
		{
			Alternative alternative = new Alternative();
			for (String part : splitTopLevel(piece, "+"))
			{
				List<String> names = extractNames(part);
				if (!names.isEmpty())
				{
					alternative.getItems().add(names);
				}
			}
			if (!alternative.getItems().isEmpty())
			{
				alternatives.add(alternative);
			}
		}

		// "A and / B" (an "and" followed by a line break) leaves an empty-looking piece between
		// two real ones; merge an alternative that has no items into its neighbour. Already
		// handled above by skipping empties, so nothing more to do here.
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
		if (!plain.isEmpty() && !plain.equalsIgnoreCase("n/a") && !plain.equalsIgnoreCase("none")
			&& !plain.toLowerCase(Locale.ROOT).startsWith("see "))
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
				addBlock(blocks, label, tab);
			}
			idx = close;
		}

		if (blocks.isEmpty())
		{
			addBlock(blocks, "", text);
		}
		return blocks;
	}

	/** Adds one block per inventory (with the section's rune pouch, if any) found in {@code section}. */
	private void addBlock(List<InventoryBlock> blocks, String label, String section)
	{
		List<Template> inventories = findTemplates(section, "inventory");
		List<Template> pouches = findTemplates(section, "rune pouch");
		if (inventories.isEmpty())
		{
			if (!pouches.isEmpty())
			{
				blocks.add(new InventoryBlock(label, new ArrayList<>(), runeEntries(pouches.get(0)), false));
			}
			return;
		}
		for (int i = 0; i < inventories.size(); i++)
		{
			// Pair the i-th inventory with the i-th pouch when a section lists several of each
			// (a table of example setups), otherwise share the only pouch
			Template pouch = pouches.isEmpty() ? null : pouches.get(Math.min(i, pouches.size() - 1));
			blocks.add(new InventoryBlock(label, inventoryEntries(inventories.get(i)),
				pouch == null ? new ArrayList<>() : runeEntries(pouch), false));
		}
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
			List<Alternative> alternatives = parseAlternatives(value);
			if (alternatives.isEmpty())
			{
				continue;
			}
			List<String> names = alternatives.get(0).getItems().get(0);
			unique.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
		}
		return new ArrayList<>(unique.values());
	}

	private List<List<String>> runeEntries(Template pouch)
	{
		Map<String, List<String>> unique = new LinkedHashMap<>();
		for (int i = 1; i <= 4; i++)
		{
			String value = pouch.getParams().get(String.valueOf(i));
			if (value == null || value.trim().isEmpty())
			{
				continue;
			}
			List<Alternative> alternatives = parseAlternatives(value);
			if (!alternatives.isEmpty())
			{
				List<String> names = alternatives.get(0).getItems().get(0);
				unique.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
			}
		}
		return new ArrayList<>(unique.values());
	}

	/**
	 * Picks the inventory for a style: an exact or partial label match, the only inventory if
	 * there is one, or, when a page lists several inventories that are not labelled by style
	 * (a table of example setups), the union of all of them.
	 */
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
		if (inventories.size() == 1)
		{
			return inventories.get(0);
		}

		Map<String, List<String>> entries = new LinkedHashMap<>();
		Map<String, List<String>> runes = new LinkedHashMap<>();
		for (InventoryBlock b : inventories)
		{
			for (List<String> names : b.getEntries())
			{
				entries.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
			}
			for (List<String> names : b.getRunePouch())
			{
				runes.putIfAbsent(names.get(0).toLowerCase(Locale.ROOT), names);
			}
		}
		return new InventoryBlock("", new ArrayList<>(entries.values()), new ArrayList<>(runes.values()), true);
	}

	private static String normalize(String s)
	{
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/** Headings as pseudo-templates: name = heading text, params holds the level. */
	private static List<Template> findHeadings(String text)
	{
		List<Template> out = new ArrayList<>();
		Matcher m = HEADING.matcher(text);
		while (m.find())
		{
			Map<String, String> params = new HashMap<>();
			params.put("level", String.valueOf(m.group(1).length()));
			out.add(new Template(cleanText(m.group(2)), params, m.start(), m.end()));
		}
		return out;
	}

	/**
	 * The heading a position sits under. A generic "Equipment" sub-heading is replaced by its
	 * parent heading so a boss's equipment table is labelled with the boss.
	 */
	private static String sectionFor(List<Template> headings, int position)
	{
		Template nearest = null;
		for (Template h : headings)
		{
			if (h.getStart() < position)
			{
				nearest = h;
			}
		}
		if (nearest == null)
		{
			return "";
		}
		if (nearest.getName().equalsIgnoreCase("equipment") && Integer.parseInt(nearest.getParams().get("level")) > 2)
		{
			// Walk back to the heading this one belongs to: a sibling or parent, skipping deeper ones
			int level = Integer.parseInt(nearest.getParams().get("level"));
			for (int i = headings.indexOf(nearest) - 1; i >= 0; i--)
			{
				if (Integer.parseInt(headings.get(i).getParams().get("level")) <= level)
				{
					return headings.get(i).getName();
				}
			}
		}
		return nearest.getName();
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

	/**
	 * Replaces a whole word that occurs outside templates and links (so "Pestle and mortar"
	 * inside a plink is left alone) with {@code replacement}.
	 */
	private static String replaceTopLevelWord(String s, String word, String replacement)
	{
		StringBuilder out = new StringBuilder(s.length());
		int depth = 0;
		int i = 0;
		while (i < s.length())
		{
			char c = s.charAt(i);
			char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
			if ((c == '{' && n == '{') || (c == '[' && n == '['))
			{
				depth++;
				out.append(c).append(n);
				i += 2;
				continue;
			}
			if ((c == '}' && n == '}') || (c == ']' && n == ']'))
			{
				depth--;
				out.append(c).append(n);
				i += 2;
				continue;
			}
			if (depth == 0 && s.regionMatches(true, i, word, 0, word.length())
				&& (i == 0 || Character.isWhitespace(s.charAt(i - 1)))
				&& (i + word.length() >= s.length() || Character.isWhitespace(s.charAt(i + word.length()))))
			{
				out.append(replacement);
				i += word.length();
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
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
