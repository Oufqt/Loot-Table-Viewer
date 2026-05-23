package com.loottableviewer.model;

public class DropEntry
{
    private final int itemId;
    private final String itemName;
    private final String category;
    private final String quantityText;
    private final String dropRateText;
    private final String rarity;
    private final String notes;

    public DropEntry(int itemId, String itemName, String category, String dropRateText, String rarity, String notes)
    {
        this(itemId, itemName, category, dropRateText, rarity, notes, "");
    }

    public DropEntry(int itemId, String itemName, String category, String dropRateText, String rarity, String notes, String quantityText)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.category = category;
        this.quantityText = quantityText == null ? "" : quantityText;
        this.dropRateText = dropRateText;
        this.rarity = rarity;
        this.notes = notes;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName;
    }

    public String getCategory()
    {
        return category;
    }

    public String getQuantityText()
    {
        return quantityText;
    }

    public String getDropRateText()
    {
        return dropRateText;
    }

    public String getRarity()
    {
        return rarity;
    }

    public String getNotes()
    {
        return notes;
    }
}
