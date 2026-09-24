package io.github.steinkoloss.upscaling;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Options -> Upscaling...: on/off, upscaler, render scale and sharpness. Saved on close. */
public final class UpscalingOptionsScreen extends OptionsSubScreen {
	public UpscalingOptionsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, Component.translatable("upscaling.options.title"));
	}

	@Override
	protected void addOptions() {
		OptionInstance<Boolean> enabled = OptionInstance.createBoolean(
				"upscaling.options.enabled",
				OptionInstance.cachedConstantTooltip(Component.translatable("upscaling.options.enabled.tooltip")),
				UpscalingConfig.enabled(),
				UpscalingConfig::setEnabled);

		OptionInstance<UpscalingConfig.Upscaler> upscaler = new OptionInstance<>(
				"upscaling.options.upscaler",
				OptionInstance.cachedConstantTooltip(Component.translatable("upscaling.options.upscaler.tooltip")),
				// Cycle buttons prefix the caption themselves.
				(caption, value) -> Component.literal(value.displayName()),
				new OptionInstance.Enum<>(
						Arrays.asList(UpscalingConfig.Upscaler.values()),
						Codec.STRING.xmap(name -> UpscalingConfig.Upscaler.valueOf(name), UpscalingConfig.Upscaler::name)),
				UpscalingConfig.upscaler(),
				UpscalingConfig::setUpscaler);

		OptionInstance<Integer> renderScale = new OptionInstance<>(
				"upscaling.options.renderScale",
				OptionInstance.cachedConstantTooltip(Component.translatable("upscaling.options.renderScale.tooltip")),
				(caption, percent) -> {
					String preset = UpscalingConfig.presetName(percent);
					String label = percent + "%" + (preset != null ? " (" + preset + ")" : "");
					return Options.genericValueLabel(caption, Component.literal(label));
				},
				new OptionInstance.IntRange(UpscalingConfig.MIN_SCALE_PERCENT, UpscalingConfig.MAX_SCALE_PERCENT),
				UpscalingConfig.scalePercent(),
				UpscalingConfig::setScalePercent);

		OptionInstance<Integer> sharpness = new OptionInstance<>(
				"upscaling.options.sharpness",
				OptionInstance.cachedConstantTooltip(Component.translatable("upscaling.options.sharpness.tooltip")),
				(caption, steps) -> Options.genericValueLabel(caption, steps == 0
						? Component.translatable("options.off")
						: Component.literal(String.format(java.util.Locale.ROOT, "%.2f", steps * 0.05f))),
				new OptionInstance.IntRange(0, 20),
				UpscalingConfig.sharpnessSteps(),
				UpscalingConfig::setSharpnessSteps);

		this.list.addSmall(enabled, upscaler);
		this.list.addBig(renderScale);
		this.list.addBig(sharpness);
	}

	@Override
	public void removed() {
		UpscalingConfig.save();
		super.removed();
	}
}
