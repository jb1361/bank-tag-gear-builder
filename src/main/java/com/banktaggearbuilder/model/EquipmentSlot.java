package com.banktaggearbuilder.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Equipment slots in the order the wiki's Recommended equipment template uses them,
 * which is also the order items are laid out in generated tags.
 */
@Getter
@RequiredArgsConstructor
public enum EquipmentSlot
{
	HEAD("head", "Head"),
	CAPE("cape", "Cape"),
	NECK("neck", "Neck"),
	AMMO("ammo", "Ammo"),
	WEAPON("weapon", "Weapon"),
	TWO_HANDED("2h", "Two-handed"),
	SHIELD("shield", "Shield"),
	BODY("body", "Body"),
	LEGS("legs", "Legs"),
	HANDS("hands", "Hands"),
	FEET("feet", "Feet"),
	RING("ring", "Ring"),
	SPECIAL("special", "Special attack");

	private final String wikiParam;
	private final String displayName;

	public static EquipmentSlot fromWikiParam(String param)
	{
		for (EquipmentSlot slot : values())
		{
			if (slot.wikiParam.equals(param))
			{
				return slot;
			}
		}
		return null;
	}
}
