package com.loottableviewer;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

@Singleton
public class ItemCategoryService
{
    private final ItemManager itemManager;

    @Inject
    public ItemCategoryService(ItemManager itemManager)
    {
        this.itemManager = itemManager;
    }

    public String categorize(int itemId, String itemName)
    {
        String safeName = itemName == null ? "" : itemName.toLowerCase();

        if (safeName.equals("nothing"))
        {
            return "Other";
        }

        if (safeName.contains("coin"))
        {
            return "Coins";
        }

        if (isRune(safeName))
        {
            return "Runes";
        }

        if (isAmmunition(safeName))
        {
            return "Ammunition";
        }

        if (itemId > 0)
        {
            try
            {
                ItemComposition item = itemManager.getItemComposition(itemId);
                if (item != null)
                {
                    if (isArmourName(safeName))
                    {
                        return "Armour";
                    }

                    if (isWeaponName(safeName))
                    {
                        return "Weapons";
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }

        if (isArmourName(safeName))
        {
            return "Armour";
        }

        if (isWeaponName(safeName))
        {
            return "Weapons";
        }

        return "Other";
    }

    private boolean isRune(String name)
    {
        return name.endsWith(" rune")
                || name.contains(" rune ")
                || name.equals("rune essence")
                || name.equals("pure essence");
    }

    private boolean isAmmunition(String name)
    {
        return name.contains(" arrow")
                || name.contains(" arrows")
                || name.contains(" bolt")
                || name.contains(" bolts")
                || name.contains(" dart")
                || name.contains(" darts")
                || name.contains(" knife")
                || name.contains(" knives")
                || name.contains(" javelin")
                || name.contains(" thrownaxe");
    }

    private boolean isWeaponName(String name)
    {
        return name.contains("sword")
                || name.contains("dagger")
                || name.contains("scimitar")
                || name.contains("mace")
                || name.contains("warhammer")
                || name.contains("spear")
                || name.contains("halberd")
                || name.contains("bow")
                || name.contains("staff")
                || name.contains("wand")
                || name.contains("axe")
                || name.contains("maul")
                || name.contains("whip")
                || name.contains("hasta")
                || name.contains("crossbow");
    }

    private boolean isArmourName(String name)
    {
        return name.contains("helm")
                || name.contains("helmet")
                || name.contains("body")
                || name.contains("platebody")
                || name.contains("platelegs")
                || name.contains("plateskirt")
                || name.contains("shield")
                || name.contains("kiteshield")
                || name.contains("sq shield")
                || name.contains("boots")
                || name.contains("gloves")
                || name.contains("vambraces")
                || name.contains("chaps")
                || name.contains("skirt")
                || name.contains("robe")
                || name.contains("coif")
                || name.contains("mail");
    }
}