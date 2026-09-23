package io.github.steinkoloss.upscaling;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class UpscalingClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("upscaling", "upscaling"));

	@Override
	public void onInitializeClient() {
		KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.toggle", InputConstants.KEY_F8, CATEGORY));
		KeyMapping cycle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.cycle_scale", InputConstants.KEY_F9, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggle.consumeClick()) {
				boolean on = UpscalingConfig.toggle();
				notify(client, on ? "Upscaling ON (" + Math.round(UpscalingConfig.scale() * 100) + "% render scale)" : "Upscaling OFF (native)");
			}
			while (cycle.consumeClick()) {
				String preset = UpscalingConfig.cycleScale();
				notify(client, "Render scale: " + preset + " (" + Math.round(UpscalingConfig.scale() * 100) + "%)");
			}
		});
	}

	private static void notify(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(message));
		}
	}
}
