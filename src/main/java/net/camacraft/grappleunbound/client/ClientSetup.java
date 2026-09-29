package net.camacraft.grappleunbound.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.controllers.AirfrictionController;
import net.camacraft.grappleunbound.controllers.ForcefieldController;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.entities.grapplehook.RenderGrapplehookEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;

@Mod.EventBusSubscriber(bus=Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
	public static ClientSetup instance = null;
	
	public ClientSetup() {
	}

	public CrosshairRenderer crosshairRenderer;
	public ClientEventHandlers clientEventHandlers;
	public ClientControllerManager clientControllerManager;
	
	public static ArrayList<KeyMapping> keyBindings = new ArrayList<KeyMapping>();
	
	public static KeyMapping createKeyBinding(KeyMapping k) {
		keyBindings.add(k);
		return k;
	}
	
        public static KeyMapping key_boththrow = createKeyBinding(new KeyMapping("key.grappleunbound.boththrow", CustomKeyConflicts.INGAME_NO_CONFLICT, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_1, KeyMapping.CATEGORY_MISC));
        public static KeyMapping key_leftthrow = createKeyBinding(new KeyMapping("key.grappleunbound.leftthrow", InputConstants.UNKNOWN.getValue(), "key.grappleunbound.category"));
        public static KeyMapping key_rightthrow = createKeyBinding(new KeyMapping("key.grappleunbound.rightthrow", InputConstants.UNKNOWN.getValue(), "key.grappleunbound.category"));
	public static KeyMapping key_motoronoff = createKeyBinding(new KeyMapping("key.grappleunbound.motoronoff", GLFW.GLFW_KEY_LEFT_SHIFT, "key.grappleunbound.category"));
	public static KeyMapping key_jumpanddetach = createKeyBinding(new KeyMapping("key.grappleunbound.jumpanddetach", GLFW.GLFW_KEY_SPACE, "key.grappleunbound.category"));
	public static KeyMapping key_slow = createKeyBinding(new KeyMapping("key.grappleunbound.slow", GLFW.GLFW_KEY_LEFT_SHIFT, "key.grappleunbound.category"));
	public static KeyMapping key_climb = createKeyBinding(new KeyMapping("key.grappleunbound.climb", GLFW.GLFW_KEY_LEFT_SHIFT, "key.grappleunbound.category"));
	public static KeyMapping key_climbup = createKeyBinding(new KeyMapping("key.grappleunbound.climbup", InputConstants.UNKNOWN.getValue(), "key.grappleunbound.category"));
	public static KeyMapping key_climbdown = createKeyBinding(new KeyMapping("key.grappleunbound.climbdown", InputConstants.UNKNOWN.getValue(), "key.grappleunbound.category"));
        public static KeyMapping key_enderlaunch = createKeyBinding(new KeyMapping("key.grappleunbound.enderlaunch", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_1, "key.grappleunbound.category"));
        public static KeyMapping key_rocket = createKeyBinding(new KeyMapping("key.grappleunbound.rocket", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_1, "key.grappleunbound.category"));
        public static KeyMapping key_slide = createKeyBinding(new KeyMapping("key.grappleunbound.slide", GLFW.GLFW_KEY_LEFT_SHIFT, "key.grappleunbound.category"));
        public static KeyMapping key_hookconfig = createKeyBinding(new KeyMapping("key.grappleunbound.hookconfig", GLFW.GLFW_KEY_V, "key.grappleunbound.category"));

        static {
                key_boththrow.setKeyModifierAndCode(KeyModifier.NONE, key_boththrow.getKey());
                key_enderlaunch.setKeyModifierAndCode(KeyModifier.NONE, key_enderlaunch.getKey());
                key_rocket.setKeyModifierAndCode(KeyModifier.NONE, key_rocket.getKey());
        }
	@SubscribeEvent
	public static void clientSetup(final FMLClientSetupEvent event) {
	    instance = new ClientSetup();
	    instance.onClientSetup();
	}
	
	private static class GrapplehookEntityRenderFactory implements EntityRendererProvider<GrapplehookEntity> {
	    @Override
	    public EntityRenderer<GrapplehookEntity> create(Context manager) {
	        return new RenderGrapplehookEntity<>(manager, CommonSetup.grapplingHookItem.get());
	    }
	}

	@SubscribeEvent
	public static void registerKeyBinding(RegisterKeyMappingsEvent event){
		keyBindings.forEach(event::register);
	}
	
	public void onClientSetup() {
//		// register all the key bindings
//		for (int i = 0; i < keyBindings.size(); ++i)
//		{
//		    ClientRegistry.registerKeyBinding(keyBindings.get(i));
//		}
		
	    EntityRenderers.register(CommonSetup.grapplehookEntityType.get(), new GrapplehookEntityRenderFactory());

		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
    		() -> new ConfigScreenHandler.ConfigScreenFactory(((ClientProxy) ClientProxyInterface.proxy)::onConfigScreen));
		
	    this.registerPropertyOverride();
	    
		crosshairRenderer = new CrosshairRenderer();
		clientControllerManager = new ClientControllerManager();
		clientEventHandlers = new ClientEventHandlers();
	}
	
	public void registerPropertyOverride() {
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "rocket"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyRocket(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "double"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyDouble(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "motor"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyMotor(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "smart"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertySmart(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "enderstaff"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyEnderstaff(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "magnet"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyMagnet(stack, world, entity) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "attached"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				if (entity == null) {return 0;}
				return (ClientControllerManager.controllers.containsKey(entity.getId()) && !(ClientControllerManager.controllers.get(entity.getId()) instanceof AirfrictionController)) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.forcefieldItem.get(), new ResourceLocation("grappleunbound", "attached"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				if (entity == null) {return 0;}
				return (ClientControllerManager.controllers.containsKey(entity.getId()) && ClientControllerManager.controllers.get(entity.getId()) instanceof ForcefieldController) ? 1 : 0;
			}
		});
		ItemProperties.register(CommonSetup.grapplingHookItem.get(), new ResourceLocation("grappleunbound", "hook"), new ItemPropertyFunction() {
			public float call(ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
				return CommonSetup.grapplingHookItem.get().getPropertyHook(stack, world, entity) ? 1 : 0;
			}
		});
	}
}
