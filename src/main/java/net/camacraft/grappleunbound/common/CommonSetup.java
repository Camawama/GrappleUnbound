package net.camacraft.grappleunbound.common;

import net.camacraft.grappleunbound.blocks.modifierblock.BlockGrappleModifier;
import net.camacraft.grappleunbound.blocks.modifierblock.TileEntityGrappleModifier;
import net.camacraft.grappleunbound.client.ClientProxyInterface;
import net.camacraft.grappleunbound.enchantments.DoublejumpEnchantment;
import net.camacraft.grappleunbound.enchantments.SlidingEnchantment;
import net.camacraft.grappleunbound.enchantments.WallrunEnchantment;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.items.EnderStaffItem;
import net.camacraft.grappleunbound.items.ForcefieldItem;
import net.camacraft.grappleunbound.items.GrapplehookItem;
import net.camacraft.grappleunbound.items.LongFallBoots;
import net.camacraft.grappleunbound.items.upgrades.BaseUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.DoubleUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.GrappleUpgradeRecipe;
import net.camacraft.grappleunbound.items.upgrades.ForcefieldUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.LimitsUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.MagnetUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.MotorUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.RocketUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.RopeUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.StaffUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.SwingUpgradeItem;
import net.camacraft.grappleunbound.items.upgrades.ThrowUpgradeItem;
import net.camacraft.grappleunbound.network.DetachSingleHookMessage;
import net.camacraft.grappleunbound.network.GrappleAttachMessage;
import net.camacraft.grappleunbound.network.GrappleItemCustomizationMessage;
import net.camacraft.grappleunbound.network.GrappleAttachPosMessage;
import net.camacraft.grappleunbound.network.GrappleDetachMessage;
import net.camacraft.grappleunbound.network.GrappleEndMessage;
import net.camacraft.grappleunbound.network.GrappleModifierMessage;
import net.camacraft.grappleunbound.network.HookRopeLengthMessage;
import net.camacraft.grappleunbound.network.KeypressMessage;
import net.camacraft.grappleunbound.network.LoggedInMessage;
import net.camacraft.grappleunbound.network.PlayerMovementMessage;
import net.camacraft.grappleunbound.network.SegmentMessage;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Optional;

@Mod.EventBusSubscriber(bus=Mod.EventBusSubscriber.Bus.MOD)
public class CommonSetup {

	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "grappleunbound");
	public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, "grappleunbound");
	public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, "grappleunbound");
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "grappleunbound");
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "grappleunbound");
	public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "grappleunbound");

    public static RegistryObject<GrapplehookItem> grapplingHookItem = ITEMS.register("grapplinghook", GrapplehookItem::new);
    public static RegistryObject<EnderStaffItem> enderStaffItem = ITEMS.register("launcheritem", EnderStaffItem::new);
    public static RegistryObject<ForcefieldItem> forcefieldItem = ITEMS.register("repeller", ForcefieldItem::new);

    public static RegistryObject<BaseUpgradeItem> baseUpgradeItem = ITEMS.register("baseupgradeitem", BaseUpgradeItem::new);
    public static RegistryObject<DoubleUpgradeItem> doubleUpgradeItem = ITEMS.register("doubleupgradeitem", DoubleUpgradeItem::new);
    public static RegistryObject<ForcefieldUpgradeItem> forcefieldUpgradeItem = ITEMS.register("forcefieldupgradeitem", ForcefieldUpgradeItem::new);
    public static RegistryObject<MagnetUpgradeItem> magnetUpgradeItem = ITEMS.register("magnetupgradeitem", MagnetUpgradeItem::new);
    public static RegistryObject<MotorUpgradeItem> motorUpgradeItem = ITEMS.register("motorupgradeitem", MotorUpgradeItem::new);
    public static RegistryObject<RopeUpgradeItem> ropeUpgradeItem = ITEMS.register("ropeupgradeitem", RopeUpgradeItem::new);
    public static RegistryObject<StaffUpgradeItem> staffUpgradeItem = ITEMS.register("staffupgradeitem", StaffUpgradeItem::new);
    public static RegistryObject<SwingUpgradeItem> swingUpgradeItem = ITEMS.register("swingupgradeitem", SwingUpgradeItem::new);
    public static RegistryObject<ThrowUpgradeItem> throwUpgradeItem = ITEMS.register("throwupgradeitem", ThrowUpgradeItem::new);
    public static RegistryObject<LimitsUpgradeItem> limitsUpgradeItem = ITEMS.register("limitsupgradeitem", LimitsUpgradeItem::new);
    public static RegistryObject<RocketUpgradeItem> rocketUpgradeItem = ITEMS.register("rocketupgradeitem", RocketUpgradeItem::new);

    public static RegistryObject<Item> longFallBootsItem = ITEMS.register("longfallboots", ()->new LongFallBoots(ArmorMaterials.DIAMOND, 3));

    // smithing-table recipe that bakes an upgrade category into a hook's NBT
    public static RegistryObject<net.minecraft.world.item.crafting.RecipeSerializer<?>> upgradeRecipeSerializer =
            RECIPE_SERIALIZERS.register("hook_upgrade", GrappleUpgradeRecipe.Serializer::new);
    
    public static RegistryObject<WallrunEnchantment> wallrunEnchantment = ENCHANTMENTS.register("wallrunenchantment", WallrunEnchantment::new);
    public static RegistryObject<DoublejumpEnchantment> doubleJumpEnchantment = ENCHANTMENTS.register("doublejumpenchantment", DoublejumpEnchantment::new);
    public static RegistryObject<SlidingEnchantment> slidingEnchantment = ENCHANTMENTS.register("slidingenchantment", SlidingEnchantment::new);

	public static SimpleChannel network;    // used to transmit your network messages
	public static final ResourceLocation simpleChannelRL = new ResourceLocation("grappleunbound", "channel");

	public static RegistryObject<Block> grappleModifierBlock = BLOCKS.register("block_grapple_modifier", BlockGrappleModifier::new);
	public static RegistryObject<BlockItem> grappleModifierBlockItem = ITEMS.register("block_grapple_modifier", ()->new BlockItem(grappleModifierBlock.get(),new Item.Properties().stacksTo(64)));
	

	
	public static CommonEventHandlers eventHandlers; // constructed in the GrappleMod constructor

	// 2.0: SegmentMessage and GrappleAttachMessage carry per-bend ship data
	// 2.1: added GrappleItemCustomizationMessage (in-hand hook customization)
	// 2.2: added HookRopeLengthMessage (server-side rope length sync for ship pulling)
	public static final String NETWORK_PROTOCOL_VERSION = "2.2";

	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		// exact version match required when the mod is present on the other side; vanilla /
		// modless connections are still allowed (they simply can't use grapple features)
		network = NetworkRegistry.newSimpleChannel(simpleChannelRL, () -> NETWORK_PROTOCOL_VERSION,
	            NetworkRegistry.acceptMissingOr(NETWORK_PROTOCOL_VERSION),
	            NetworkRegistry.acceptMissingOr(NETWORK_PROTOCOL_VERSION));
		int id = 0;
		network.registerMessage(id++, PlayerMovementMessage.class, PlayerMovementMessage::encode, PlayerMovementMessage::new, PlayerMovementMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
		network.registerMessage(id++, GrappleEndMessage.class, GrappleEndMessage::encode, GrappleEndMessage::new, GrappleEndMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
		network.registerMessage(id++, GrappleModifierMessage.class, GrappleModifierMessage::encode, GrappleModifierMessage::new, GrappleModifierMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
		network.registerMessage(id++, KeypressMessage.class, KeypressMessage::encode, KeypressMessage::new, KeypressMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
		network.registerMessage(id++, GrappleAttachMessage.class, GrappleAttachMessage::encode, GrappleAttachMessage::new, GrappleAttachMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, GrappleDetachMessage.class, GrappleDetachMessage::encode, GrappleDetachMessage::new, GrappleDetachMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, DetachSingleHookMessage.class, DetachSingleHookMessage::encode, DetachSingleHookMessage::new, DetachSingleHookMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, GrappleAttachPosMessage.class, GrappleAttachPosMessage::encode, GrappleAttachPosMessage::new, GrappleAttachPosMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, SegmentMessage.class, SegmentMessage::encode, SegmentMessage::new, SegmentMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, LoggedInMessage.class, LoggedInMessage::encode, LoggedInMessage::new, LoggedInMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
		network.registerMessage(id++, GrappleItemCustomizationMessage.class, GrappleItemCustomizationMessage::encode, GrappleItemCustomizationMessage::new, GrappleItemCustomizationMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
		network.registerMessage(id++, HookRopeLengthMessage.class, HookRopeLengthMessage::encode, HookRopeLengthMessage::new, HookRopeLengthMessage::onMessageReceived, Optional.of(NetworkDirection.PLAY_TO_SERVER));
	}
	


	public static RegistryObject<BlockEntityType<TileEntityGrappleModifier>> grappleModifierTileEntityType = BLOCK_ENTITY_TYPES.register("block_grapple_modifier",()->BlockEntityType.Builder.of(TileEntityGrappleModifier::new, grappleModifierBlock.get()).build(null));

	public static RegistryObject<EntityType<GrapplehookEntity>> grapplehookEntityType = ENTITY_TYPES.register("grapplehook", ()->EntityType.Builder.<GrapplehookEntity>of(GrapplehookEntity::new, MobCategory.MISC)
			.sized(0.25F, 0.25F)
			// 8 chunks: the rope must stay visible out to hook_release_distance (96 blocks),
			// otherwise there's a dead zone where the rope is invisible but still attached
			.clientTrackingRange(8)
			.build("grappleunbound:grapplehook"));



	public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GrappleMod.MODID);
	public static final RegistryObject<CreativeModeTab> GRAPPLE_TAB = TABS.register("main", () -> CreativeModeTab.builder().displayItems(
			(itemDisplayParameters,output)-> {
				ITEMS.getEntries().forEach((registryObject)-> {
					// legacy: superseded by smithing-table upgrades; still registered so
					// existing worlds keep their placed blocks, but no longer obtainable
					if (registryObject.getId().equals(grappleModifierBlockItem.getId())) {
						return;
					}
					output.accept(new ItemStack(registryObject.get()));
				});
				// go through the sided interface (null on dedicated servers) instead of ClientProxy directly
				if (ClientProxyInterface.proxy != null) {
					ClientProxyInterface.proxy.fillGrappleVariants(output);
				}
			}).icon(()->new ItemStack(grapplingHookItem.get())).title(Component.translatable("itemGroup.grappleunbound")).build());


//	public static CreativeModeTab tabGrapplemod;
//	@SubscribeEvent
//	public static void registerTabs(CreativeModeTabEvent.Register event)
//	{
//		tabGrapplemod = event.registerCreativeModeTab(new ResourceLocation(GrappleMod.MODID, "main"),builder -> builder
//				.icon(() -> new ItemStack(grapplingHookItem.get()))
//				.title(Component.translatable("tabs.grappleunbound.main_tab"))
//				.displayItems((featureFlags, output) -> {
//					ITEMS.getEntries().stream().map(RegistryObject::get).forEach(output::accept);
//					if(ClientProxyInterface.proxy != null) {
//						ClientProxyInterface.proxy.fillGrappleVariants(output);
//					}
//					LongFallBoots.addToTab(output);
//				})
//		);
//	}
	

}
