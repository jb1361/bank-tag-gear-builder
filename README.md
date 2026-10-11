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

A tag only changes when you build it. Pick the same content and setup, press Build, check the
result, then press "Update tag" under the result or "Apply update" on the tag's row, which
appears when the build differs from what the tag holds. The result you see is exactly what is
written. Nothing is re-scanned behind your back, so grabbing gear or trading never alters a tab.

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
| Sync from wiki now | Tick to run; unticks itself | Off |

## Notes

- Potions are always tagged as exactly their 4-dose item whether or not you own them, so you can
  restock without rebuilding the tag.
- Charged items with numbered variants (Amulet of glory, Ring of dueling, Combat bracelet and so
  on) are tagged as exactly the highest-charge one you own, so the tab shows one of them.
- When you own several versions of one item, only the best one is tagged so the tab doesn't
  show duplicates: uncharged or broken copies lose to charged ones (Dizana's quiver), fortified
  `(f)` armour wins (Masori), and degraded gear prefers the most charge left. Locked `(l)` copies
  count the same as the plain item. Owning just one version tags all of its variants as before.
- Runes the wiki puts in the rune pouch are always included and kept on their own row below the
  inventory. A combination rune you don't own is replaced by its two elemental runes. Runes the
  wiki lists directly in the inventory stay with the inventory items.
- Bank placeholders are not counted as owned.
- Your bank contents are cached per account whenever the bank is opened or closed, so after the
  first session the plugin works straight away at login. Open the bank to refresh the cache.
- Wiki responses are cached on disk in the plugin's folder, keyed by the game cache revision.
  The wiki is only asked again after a game update, for a page not fetched before, when you
  press "Refresh all", or when you tick "Sync from wiki now" in the plugin settings, which
  marks every cached page as stale.
- Where a page and its "/Strategies" subpage would both be listed under the same name, only
  the main page is shown.
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

Gear data comes from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki), whose
content is licensed under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/).
The wikitext test fixtures in `src/test/resources` are copies of wiki pages under that licence.
