package com.loottableviewer;

import net.runelite.api.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemIdResolverTest
{
    @Test
    public void resolvesUntradeablePotentialDropIcons()
    {
        assertEquals(ItemID.OGRE_RIBS, ItemIdResolver.resolve("Ogre ribs"));
        assertEquals(ItemID.ENSOULED_OGRE_HEAD, ItemIdResolver.resolve("Ensouled ogre head"));
        assertEquals(ItemID.LONG_BONE, ItemIdResolver.resolve("Long bone"));
        assertEquals(ItemID.CURVED_BONE, ItemIdResolver.resolve("Curved bone"));
    }

    @Test
    public void ignoresCommonWikiNameSuffixes()
    {
        assertEquals(ItemID.OGRE_RIBS, ItemIdResolver.resolve("Ogre ribs (m)"));
        assertEquals(ItemID.LONG_BONE, ItemIdResolver.resolve("Long bone (noted)"));
    }
}
