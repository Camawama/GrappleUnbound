package net.camacraft.grappleunbound.items.upgrades;

import com.google.gson.JsonObject;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.utils.GrappleCustomization.upgradeCategories;
import net.camacraft.grappleunbound.utils.HookUpgrades;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.Level;

/**
 * Smithing-table recipe that applies an upgrade category to a grappling hook: template = the
 * category's upgrade item, base = the hook, addition = a material. The hook keeps all of its
 * NBT (customization, applied upgrades) and gains the template's category. Runs in the vanilla
 * smithing table and shows up in the vanilla smithing recipe book/JEI category.
 */
public class GrappleUpgradeRecipe extends SmithingTransformRecipe {

	// vanilla keeps these package-private; we need our own copies for network (de)serialization
	private final Ingredient template;
	private final Ingredient base;
	private final Ingredient addition;
	private final ItemStack result;

	public GrappleUpgradeRecipe(ResourceLocation id, Ingredient template, Ingredient base, Ingredient addition, ItemStack result) {
		super(id, template, base, addition, result);
		this.template = template;
		this.base = base;
		this.addition = addition;
		this.result = result;
	}

	private static upgradeCategories categoryOf(ItemStack templateStack) {
		if (templateStack.getItem() instanceof BaseUpgradeItem) {
			return ((BaseUpgradeItem) templateStack.getItem()).category;
		}
		return null;
	}

	@Override
	public boolean matches(Container container, Level level) {
		if (!super.matches(container, level)) {
			return false;
		}
		upgradeCategories category = categoryOf(container.getItem(0));
		// an upgrade can only be applied once per hook
		return category != null && !HookUpgrades.has(container.getItem(1), category);
	}

	@Override
	public ItemStack assemble(Container container, RegistryAccess registryAccess) {
		ItemStack upgraded = container.getItem(1).copy();
		upgraded.setCount(1);
		upgradeCategories category = categoryOf(container.getItem(0));
		if (category != null) {
			HookUpgrades.add(upgraded, category);
		}
		return upgraded;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return CommonSetup.upgradeRecipeSerializer.get();
	}

	public static class Serializer implements RecipeSerializer<GrappleUpgradeRecipe> {
		@Override
		public GrappleUpgradeRecipe fromJson(ResourceLocation id, JsonObject json) {
			Ingredient template = Ingredient.fromJson(GsonHelper.getNonNull(json, "template"));
			Ingredient base = Ingredient.fromJson(GsonHelper.getNonNull(json, "base"));
			Ingredient addition = Ingredient.fromJson(GsonHelper.getNonNull(json, "addition"));
			ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
			return new GrappleUpgradeRecipe(id, template, base, addition, result);
		}

		@Override
		public GrappleUpgradeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
			Ingredient template = Ingredient.fromNetwork(buf);
			Ingredient base = Ingredient.fromNetwork(buf);
			Ingredient addition = Ingredient.fromNetwork(buf);
			ItemStack result = buf.readItem();
			return new GrappleUpgradeRecipe(id, template, base, addition, result);
		}

		@Override
		public void toNetwork(FriendlyByteBuf buf, GrappleUpgradeRecipe recipe) {
			recipe.template.toNetwork(buf);
			recipe.base.toNetwork(buf);
			recipe.addition.toNetwork(buf);
			buf.writeItem(recipe.result);
		}
	}
}
