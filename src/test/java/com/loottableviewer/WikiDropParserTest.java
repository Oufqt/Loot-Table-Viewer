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
}
