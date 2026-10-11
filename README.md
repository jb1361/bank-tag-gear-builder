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

## Keeping tags up to date

Generated tags are re-checked every time the bank is opened or closed.

- **Notify only** (default): the panel marks the tag "Update available" with before/after lines
  for each changed slot. Apply it with one click.
- **Auto-update**: the tag is rebuilt and a chat message names what changed.
- **Off**: nothing is checked.

Tags you edited by hand in Bank Tags are never overwritten without asking. "Refresh all" re-fetches
each tag's wiki page and flags tags whose page changed.

## Settings

| Setting | Options | Default |
| --- | --- | --- |
| Output | Ask each time, Create tag automatically, Copy import string | Ask each time |
| When bank changes | Notify only, Auto-update, Off | Notify only |
| Include inventory items | On, Off | On |
| Count equipped and inventory items | On, Off | On |
| Tag naming | Content + style, Content name | Content + style |
| Arrange items by slot | On, Off | On |

## Notes

- Potions are always tagged as their 4-dose variant whether or not you own them, so you can
  restock without rebuilding the tag. Every dose in the bank matches the tag.
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
