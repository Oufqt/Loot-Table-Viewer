package com.loottableviewer.model;

public class ReceivedItem
{
    private final int itemId;
    private final String itemName;
    private final int quantity;

    public ReceivedItem(int itemId, String itemName, int quantity)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName;
    }

    public int getQuantity()
    {
        return quantity;
    }
}
