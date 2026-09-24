package io.github.steinkoloss.upscaling;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.logging.LogUtils;
import io.github.steinkoloss.upscaling.fsr.Fsr4DeviceFeatures;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class UpscalingClient implements ClientModInitializer {
	private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("upscaling", "upscaling"));

	@Override
	public void onInitializeClient() {
		KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.toggle", InputConstants.KEY_F8, CATEGORY));
		KeyMapping cycle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.cycle_scale", InputConstants.KEY_F9, CATEGORY));
		KeyMapping upscaler = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.cycle_upscaler", InputConstants.KEY_F7, CATEGORY));
		KeyMapping settings = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.open_settings", InputConstants.UNKNOWN.getValue(), CATEGORY));
		KeyMapping debug = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.upscaling.cycle_debug", InputConstants.KEY_F10, CATEGORY));

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> LOGGER.info(
				"Upscaling: FSR 4 device features {}", Fsr4DeviceFeatures.enabledOnCurrentDevice() ? "enabled" : "not available on this device/backend"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggle.consumeClick()) {
				boolean on = UpscalingConfig.toggle();
				UpscalingConfig.save();
				notify(client, on ? "Upscaling ON (" + Math.round(UpscalingConfig.scale() * 100) + "% render scale)" : "Upscaling OFF (native)");
			}
			while (cycle.consumeClick()) {
				String preset = UpscalingConfig.cycleScale();
				UpscalingConfig.save();
				notify(client, "Render scale: " + preset + " (" + Math.round(UpscalingConfig.scale() * 100) + "%)");
			}
			while (upscaler.consumeClick()) {
				notify(client, "Upscaler: " + UpscalingConfig.cycleUpscaler().displayName());
				UpscalingConfig.save();
			}
			while (settings.consumeClick()) {
				client.gui.setScreen(new UpscalingOptionsScreen(client.gui.screen(), client.options));
			}
			while (debug.consumeClick()) {
				notify(client, "Debug view: " + UpscalingConfig.cycleDebugView().displayName());
			}
			spinCamera(client);
			glideCamera(client);
		});
	}

	private static int spinTicks;

	/** Test aid: turns the camera at a steady rate so motion vectors have something to show. */
	private static void spinCamera(Minecraft client) {
		float degreesPerSecond = UpscalingConfig.debugSpin();
		if (degreesPerSecond == 0.0f || client.player == null || client.isPaused()) {
			return;
		}
		spinTicks++;
		client.player.setYRot(client.player.getYRot() + degreesPerSecond / 20.0f);
		client.player.setXRot(15.0f * (float) Math.sin(spinTicks * 0.05));
	}

	private static double glideStartX = Double.NaN;
	private static double glideStartY;
	private static double glideStartZ;
	private static int glideTicks;

	/**
	 * Test aid: slides the player back and forth along its view direction over the
	 * spawn area (so the terrain stays loaded), flying at a fixed height. Only the
	 * camera position changes, which isolates the translation part of the motion vectors.
	 */
	private static void glideCamera(Minecraft client) {
		float blocksPerSecond = UpscalingConfig.debugGlide();
		if (blocksPerSecond == 0.0f || client.player == null || client.isPaused()) {
			return;
		}
		if (Double.isNaN(glideStartX)) {
			glideStartX = client.player.getX();
			glideStartY = client.player.getY() + 12.0;
			glideStartZ = client.player.getZ();
		}
		client.player.getAbilities().flying = true;
		client.player.setDeltaMovement(0.0, 0.0, 0.0);
		if (UpscalingConfig.debugSpin() == 0.0f) {
			// Look down at the terrain being flown over.
			client.player.setXRot(35.0f);
		}
		glideTicks++;
		// Peak speed blocksPerSecond, 16 blocks each way.
		double amplitude = 16.0;
		double omega = blocksPerSecond / 20.0 / amplitude;
		double offset = amplitude * Math.sin(glideTicks * omega);
		double yaw = Math.toRadians(client.player.getYRot());
		client.player.setPos(glideStartX - Math.sin(yaw) * offset, glideStartY, glideStartZ + Math.cos(yaw) * offset);
	}

	private static void notify(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(message));
		}
	}
}
