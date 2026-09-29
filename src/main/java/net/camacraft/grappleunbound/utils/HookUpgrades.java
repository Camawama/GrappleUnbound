package net.camacraft.grappleunbound.utils;

import net.camacraft.grappleunbound.utils.GrappleCustomization.upgradeCategories;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Upgrade categories applied directly to a grappling hook item (via smithing), stored as an int
 * array of category ordinals in the stack NBT. This is the modern replacement for per-modifier-
 * block unlocks: the upgrades travel with the hook itself.
 */
public class HookUpgrades {

	public static final String NBT_KEY = "upgrades";

	public static boolean has(ItemStack stack, upgradeCategories category) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains(NBT_KEY)) {
			return false;
		}
		for (int i : tag.getIntArray(NBT_KEY)) {
			if (i == category.toInt()) {
				return true;
			}
		}
		return false;
	}

	public static void add(ItemStack stack, upgradeCategories category) {
		if (has(stack, category)) {
			return;
		}
		CompoundTag tag = stack.getOrCreateTag();
		int[] old = tag.getIntArray(NBT_KEY);
		int[] updated = Arrays.copyOf(old, old.length + 1);
		updated[old.length] = category.toInt();
		tag.putIntArray(NBT_KEY, updated);
	}

	public static List<upgradeCategories> get(ItemStack stack) {
		List<upgradeCategories> result = new ArrayList<>();
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains(NBT_KEY)) {
			return result;
		}
		for (int i : tag.getIntArray(NBT_KEY)) {
			if (i >= 0 && i < upgradeCategories.size()) {
				result.add(upgradeCategories.fromInt(i));
			}
		}
		return result;
	}
}
