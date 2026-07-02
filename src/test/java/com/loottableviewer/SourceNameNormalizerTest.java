package com.loottableviewer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SourceNameNormalizerTest
{
    @Test
    public void normalizeKeepsGodWarsBodyguardsSeparateFromBosses()
    {
        SourceNameNormalizer normalizer = new SourceNameNormalizer();

        assertEquals("Sergeant Strongstack", normalizer.normalize("Sergeant Strongstack"));
        assertEquals("Sergeant Steelwill", normalizer.normalize("Sergeant Steelwill"));
        assertEquals("Sergeant Grimspike", normalizer.normalize("Sergeant Grimspike"));
        assertEquals("Flight Kilisa", normalizer.normalize("Flight Kilisa"));
        assertEquals("Flockleader Geerin", normalizer.normalize("Flockleader Geerin"));
        assertEquals("Wingman Skree", normalizer.normalize("Wingman Skree"));
        assertEquals("Starlight", normalizer.normalize("Starlight"));
        assertEquals("Growler", normalizer.normalize("Growler"));
        assertEquals("Bree", normalizer.normalize("Bree"));
        assertEquals("Tstanon Karlak", normalizer.normalize("Tstanon Karlak"));
        assertEquals("Zakl'n Gritch", normalizer.normalize("Zakl'n Gritch"));
        assertEquals("Balfrug Kreeyath", normalizer.normalize("Balfrug Kreeyath"));
    }

    @Test
    public void normalizeKeepsExistingAliases()
    {
        SourceNameNormalizer normalizer = new SourceNameNormalizer();

        assertEquals("Chambers of Xeric", normalizer.normalize("cox"));
        assertEquals("Theatre of Blood", normalizer.normalize("tob"));
        assertEquals("Tombs of Amascut", normalizer.normalize("toa"));
        assertEquals("Chest (Barrows)", normalizer.normalize("barrows chest"));
        assertEquals("Chest (Barrows)", normalizer.normalize("barrows"));
    }
}
