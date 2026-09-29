package net.camacraft.grappleunbound.enchantments;

import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.config.GrappleConfigUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.registries.ForgeRegistries;

public class WallrunEnchantment extends Enchantment {
	// WallJump Unbound's wall jump: both take over the player at a wall on the same boots, and
	// would fight over the same moment. Only one of them goes on a pair (compared by id, so
	// neither mod needs the other installed)
	private static final ResourceLocation WALLJUMP_WALL_JUMP = new ResourceLocation("walljumpunbound", "wall_jump");

	public WallrunEnchantment() {
		super(GrappleConfigUtils.getRarityFromInt(GrappleConfig.getConf().enchantments.wallrun.enchant_rarity_wallrun), EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] {EquipmentSlot.FEET});
	}
	
	@Override
    public int getMinCost(int enchantmentLevel)
    {
        return 1;
    }

	@Override
    public int getMaxCost(int enchantmentLevel)
    {
        return this.getMinCost(enchantmentLevel) + 40;
    }

	@Override
	public int getMaxLevel()
    {
        return 1;
    }

	@Override
	protected boolean checkCompatibility(Enchantment other) {
		return super.checkCompatibility(other) && !WALLJUMP_WALL_JUMP.equals(ForgeRegistries.ENCHANTMENTS.getKey(other));
	}
}
