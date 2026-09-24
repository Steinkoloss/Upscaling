package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.steinkoloss.upscaling.UpscalingOptionsScreen;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an "Upscaling..." button to the Options screen, after the vanilla ones. */
@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Shadow
	protected abstract Button openScreenButton(Component message, Supplier<Screen> screenToScreen);

	@Inject(
			method = "init",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;")
	)
	private void upscaling$addButton(CallbackInfo ci, @Local GridLayout.RowHelper helper) {
		Screen self = this;
		helper.addChild(this.openScreenButton(
				Component.translatable("upscaling.options.button"),
				() -> new UpscalingOptionsScreen(self, this.minecraft.options)));
	}
}
