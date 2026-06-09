package com.loottableviewer;

import com.loottableviewer.model.DropEntry;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class WikiDropParserTest
{
    @Test
    public void parseStripsTemplateClosingBracesFromAlwaysDrop()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse("{{DropsTableHead|Other}}\n{{DropsLine|name=Bones|rarity=Always}}");

        assertFalse(drops.isEmpty());
        assertEquals("Always", drops.get(0).getDropRateText());
    }

    @Test
    public void parseIncludesClueScrollDropChance()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse("{{DropsTableHead|Other}}\n{{DropsLineClue|type=beginner|rarity=1/300}}");

        assertFalse(drops.isEmpty());
        assertEquals("Clue scroll (beginner)", drops.get(0).getItemName());
        assertEquals("1/300", drops.get(0).getDropRateText());
    }

    @Test
    public void parseIncludesDropQuantity()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse("{{DropsTableHead|Other}}\n{{DropsLine|name=Body rune|quantity=7|rarity=5/128}}");

        assertFalse(drops.isEmpty());
        assertEquals("7", drops.get(0).getQuantityText());
    }

    @Test
    public void parseNormalizesDropQuantityRange()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse("{{DropsTableHead|Other}}\n{{DropsLine|name=Coins|quantity=5 – 15|rarity=1/2}}");

        assertFalse(drops.isEmpty());
        assertEquals("5-15", drops.get(0).getQuantityText());
    }

    @Test
    public void parseUsesWikiHeadingsAsDropCategories()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "==Drop table 1==\n"
                + "===100%===\n"
                + "{{DropsTableHead|dropversion=Drop table 1}}\n"
                + "{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
                + "{{DropsTableBottom}}\n"
                + "===Runes and ammunition===\n"
                + "{{DropsTableHead|dropversion=Drop table 1}}\n"
                + "{{DropsLine|name=Body rune|quantity=7|rarity=5/128}}\n"
                + "{{DropsTableBottom}}"
        );

        assertEquals(2, drops.size());
        assertEquals("100%", drops.get(0).getCategory());
        assertEquals("Runes and ammunition", drops.get(1).getCategory());
    }

    @Test
    public void parseUsesWikiHeadingsForBareDropsTableHeads()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "==Drops==\n"
                + "===Weapons and armour===\n"
                + "{{DropsTableHead}}\n"
                + "{{DropsLine|name=Dragon med helm|quantity=1|rarity=5/104}}\n"
                + "{{DropsTableBottom}}\n"
                + "===Runes and ammunition===\n"
                + "{{DropsTableHead}}\n"
                + "{{DropsLine|name=Death rune|quantity=50-70|rarity=5/104}}\n"
                + "{{DropsTableBottom}}\n"
                + "===Other===\n"
                + "{{DropsTableHead}}\n"
                + "{{DropsLine|name=Clue scroll (elite)|quantity=1|rarity=1/75}}\n"
                + "{{DropsTableBottom}}"
        );

        assertEquals(3, drops.size());
        assertEquals("Weapons and armour", drops.get(0).getCategory());
        assertEquals("Runes and ammunition", drops.get(1).getCategory());
        assertEquals("Other", drops.get(2).getCategory());
    }

    @Test
    public void parseIncludesLateTertiaryDropsWithDefaultConfig()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });
        StringBuilder wiki = new StringBuilder(
            "==Drops==\n"
                + "===Main table===\n"
                + "{{DropsTableHead}}\n"
        );
        for (int i = 0; i < 35; i++)
        {
            wiki.append("{{DropsLine|name=Filler ")
                .append(i)
                .append("|quantity=1|rarity=1/100}}\n");
        }
        wiki.append("{{DropsTableBottom}}\n")
            .append("===Tertiary===\n")
            .append("{{DropsTableHead}}\n")
            .append("{{DropsLine|name=Giant egg sac(full)|quantity=1|rarity=1/20}}\n")
            .append("{{DropsLine|name=Pristine spider silk|quantity=1|rarity=1/50|gemw=no}}\n")
            .append("{{DropsLine|name=Jar of eyes|quantity=1|rarity=1/2000}}\n")
            .append("{{DropsLine|name=Sraracha|quantity=1|rarity=1/3000|gemw=No}}\n")
            .append("{{DropsTableBottom}}");

        List<DropEntry> drops = parser.parse(wiki.toString());

        assertEquals(39, drops.size());
        DropEntry silk = requireDrop(drops, "Pristine spider silk");
        assertEquals("Tertiary", silk.getCategory());
        assertEquals("1", silk.getQuantityText());
        assertEquals("1/50", silk.getDropRateText());
        assertEquals("1/20", requireDrop(drops, "Giant egg sac(full)").getDropRateText());
        assertEquals("1/2000", requireDrop(drops, "Jar of eyes").getDropRateText());
        assertEquals("1/3000", requireDrop(drops, "Sraracha").getDropRateText());
    }

    @Test
    public void parseSimplifiesUnsimplifiedWikiFractions()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "{{DropsTableHead|Weapons and armour}}\n"
                + "{{DropsLine|name=Dragon med helm|quantity=1|rarity=5/104}}\n"
                + "{{DropsLine|name=Grimy kwuarm|quantity=10-15 (noted)|rarity=15/800}}"
        );

        assertEquals("1/20.8", requireDrop(drops, "Dragon med helm").getDropRateText());
        assertEquals("1/53.33", requireDrop(drops, "Grimy kwuarm").getDropRateText());
    }

    @Test
    public void parseKeepsNestedRarityValuesTogether()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "{{DropsTableHead|Tertiary}}\n"
                + "{{DropsLine|name=Brimstone key|quantity=1|rarity={{Brimstone rarity|318}}|raritynotes=<ref group=d>Only on task.</ref>|gemw=No}}\n"
                + "{{DropsLineReward|name=Occult ornament kit|quantity=1|rarity=1/{{#expr:1/( 1/23 * 1/37 ) round 1}}}}"
        );

        assertEquals("1/318", requireDrop(drops, "Brimstone key").getDropRateText());
        assertEquals("1/851", requireDrop(drops, "Occult ornament kit").getDropRateText());
    }

    @Test
    public void parseUsesTableHeadCategoriesWhenDropSectionHeadingIsGeneric()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "==Drops==\n"
                + "{{DropsTableHead|Weapons and armour}}\n"
                + "{{DropsLine|name=Rune scimitar|quantity=1|rarity=1/128}}\n"
                + "{{DropsTableBottom}}\n"
                + "{{DropsTableHead|Runes and ammunition}}\n"
                + "{{DropsLine|name=Chaos rune|quantity=15|rarity=1/32}}\n"
                + "{{DropsTableBottom}}"
        );

        assertEquals(2, drops.size());
        assertEquals("Weapons and armour", drops.get(0).getCategory());
        assertEquals("Runes and ammunition", drops.get(1).getCategory());
    }

    @Test
    public void parseUsesRewardHeadingsForChestsAndCaskets()
    {
        WikiDropParser parser = new WikiDropParser(new LootTableViewerConfig()
        {
        });

        List<DropEntry> drops = parser.parse(
            "==Rewards==\n"
                + "====Ahrim's====\n"
                + "{{DropsTableHead}}\n"
                + "{{DropsLineReward|name=Ahrim's hood|quantity=1|rarity=1/2448}}\n"
                + "{{DropsTableBottom}}\n"
                + "===Master clue uniques===\n"
                + "{{DropsTableHead|leagueRegion=General}}\n"
                + "{{DropsLineReward|name=Occult ornament kit|quantity=1|rarity=1/851}}\n"
                + "{{DropsTableBottom}}"
        );

        assertEquals(2, drops.size());
        assertEquals("Ahrim's", drops.get(0).getCategory());
        assertEquals("Master clue uniques", drops.get(1).getCategory());
    }

    private static DropEntry requireDrop(List<DropEntry> drops, String itemName)
    {
        for (DropEntry drop : drops)
        {
            if (itemName.equals(drop.getItemName()))
            {
                return drop;
            }
        }

        throw new AssertionError("Missing drop: " + itemName);
    }
}
