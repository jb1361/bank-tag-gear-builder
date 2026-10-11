# Bank Tag Gear Builder

A [RuneLite](https://runelite.net) companion plugin for Bank Tags. Pick a boss or activity and the
plugin builds a bank tag from the OSRS Wiki's recommended gear, using only items you actually own.

## How it works

1. **Pick content** in the side panel. The list is every wiki page with a "Recommended equipment"
   table: bosses, raids, slayer bosses, wilderness bosses, minigames and skilling bosses.
2. **Choose a setup** when the page has more than one (melee / ranged / magic, roles, budget tiers).
3. **Build.** For every slot the plugin takes the highest-ranked recommended item you own, counting
   the bank plus equipped and inventory items. Charged, uncharged, degraded, imbued and ornamented
   versions count as the same item. Recommended inventory items (potions, food, runes, special
   attack weapons) are included where the wiki lists them.
4. **Output.** Either create the tag directly in Bank Tags, named after the content
   (e.g. `vorkath - ranged (crossbow)`) with the best weapon as its icon, or copy a standard Bank
   Tags import string to review, edit or share. Created tags get a Bank Tags layout shaped like
   the equipment screen, with two-handed and special attack weapons in the spare columns and
   inventory items on the rows below.

Slots where you own nothing on the list are left empty and the cheapest recommended item is shown
as a suggestion. Each tag shows the source and the date it was pulled.

## Updating a tag

Generated tags are re-matched against what you own whenever the bank is opened or closed and
whenever you press Build. If a better-ranked item is now owned or a tagged item is gone, the
tag's row shows "Update available" with before/after lines and an "Apply update" button. The
tag itself is never rewritten until you press that button or "Update tag" under a build
result, and what gets written is exactly the result shown, so grabbing gear or trading never
alters a tab on its own.

The chat message names what changed (e.g. "Neck: Amulet of fury replaced Amulet of glory(6)").
Tags you edited by hand in Bank Tags are flagged in the panel and the plugin asks before
overwriting them. "Refresh all" re-fetches each tag's wiki page and flags tags whose page changed.

## Settings

| Setting | Options | Default |
| --- | --- | --- |
| Output | Ask each time, Create tag automatically, Copy import string | Ask each time |
| Include inventory items | On, Off | On |
| Count equipped and inventory items | On, Off | On |
| Tag naming | Content + style, Content name | Content + style |
| Arrange items by slot | On, Off | On |

## Notes

- Potions are always tagged as exactly their 4-dose item whether or not you own them, so you can
  restock without rebuilding the tag.
- Charged items with numbered variants (Amulet of glory, Ring of dueling, Combat bracelet and so
  on) are tagged as exactly the highest-charge one you own, so the tab shows one of them.
- Runes the wiki puts in the rune pouch are always included and kept on their own row below the
  inventory. A combination rune you don't own is replaced by its two elemental runes. Runes the
  wiki lists directly in the inventory stay with the inventory items.
- Bank placeholders are not counted as owned.
- Your bank contents are cached per account whenever the bank is opened or closed, so after the
  first session the plugin works straight away at login. Open the bank to refresh the cache.
- Gear data comes only from the OSRS Wiki. The plugin resolves item names to item ids locally
  from the game cache, so no other services are contacted.
- The plugin cannot tell whether you meet an item's level or quest requirements. Items you own but
  can't equip yet are still picked.
- Import strings don't carry a layout; create the tag directly if you want the slot ordering.

## Development

Run the development client with the plugin loaded:

```
./gradlew run
```

If you log in with a Jagex account, follow the
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide.

## License

[BSD 2-Clause](LICENSE)
