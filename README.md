# Loot Table Viewer

Loot Table Viewer is a RuneLite external plugin that combines received loot history with OSRS Wiki-style potential drop tables.

## Features

- Tracks received loot by source, including kills, chests, and other loot events.
- Stacks repeated loot from the same source so multiple kills stay grouped.
- Keeps separate tabs for different loot sources.
- Displays potential drop tables parsed from the OSRS Wiki.
- Groups potential drops by wiki table categories when the page provides them.
- Shows item icons, quantities, rates, Grand Exchange value, and high alchemy value where available.
- Opens OSRS Wiki links from the side panel.

## Network Usage

This plugin uses the OSRS Wiki MediaWiki API to fetch drop-table wikitext. Wiki requests are limited to `https://oldschool.runescape.wiki`, redirects are not followed, and responses are capped to avoid unexpectedly large downloads.

The plugin does not require a separate account, does not collect RuneLite credentials, and does not send data to a custom third-party service.

## Development

1. Open the project in IntelliJ IDEA.
2. Import it as a Gradle project.
3. Use Java 11.
4. Run the Gradle `run` task to launch RuneLite with the plugin loaded.

## Notes

- Some OSRS Wiki pages use custom layouts, so parsing is best-effort.
- If a source name does not match its wiki page name, add an alias in `SourceNameNormalizer`.
