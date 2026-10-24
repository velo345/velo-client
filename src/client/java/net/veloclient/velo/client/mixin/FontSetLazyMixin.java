package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
*///?}

/**
 * Faster font loading (26.x): vanilla's FontSet#selectProviders walks every codepoint every
 * provider supports - with a reference to minecraft:default that includes Unifont, ~60,000 of them,
 * each looked up in every provider - for each font, on the main thread during every resource load.
 * It does that only to (1) drop providers whose glyphs are all hidden behind earlier ones and (2)
 * build the glyphs-by-width table that obfuscated (§k) text picks random glyphs from. Velo ships
 * ~110 fonts (each UI size is its own font), so that walk took ~3 s of every launch.
 *
 * <p>Here the providers are only filtered by their options, in order. Keeping a fully hidden
 * provider changes nothing visible - glyph lookup still takes the first provider that has the
 * glyph - and the §k table is built the first time a font actually needs a random glyph.
 */
//? if >=26.1 {
/*@Mixin(FontSet.class)
public abstract class FontSetLazyMixin {

	@Unique
	private List<GlyphProvider.Conditional> velo$pendingProviders;
	@Unique
	private Set<FontOption> velo$pendingOptions;
	@Unique
	private boolean velo$building;

	@Shadow
	private List<GlyphProvider> selectProviders(List<GlyphProvider.Conditional> providers, Set<FontOption> options) {
		throw new AssertionError();
	}

	@Inject(method = "selectProviders", at = @At("HEAD"), cancellable = true)
	private void velo$selectWithoutWalk(List<GlyphProvider.Conditional> providers, Set<FontOption> options,
			CallbackInfoReturnable<List<GlyphProvider>> cir) {
		if (velo$building) {
			return; // the deferred §k table build below runs vanilla's walk
		}
		List<GlyphProvider> selected = new ArrayList<>(providers.size());
		for (GlyphProvider.Conditional conditional : providers) {
			if (conditional.filter().apply(options)) {
				selected.add(conditional.provider());
			}
		}
		velo$pendingProviders = providers;
		velo$pendingOptions = Set.copyOf(options);
		cir.setReturnValue(selected);
	}

	@Inject(method = "getRandomGlyph", at = @At("HEAD"))
	private void velo$buildWidthTableOnDemand(RandomSource random, int width, CallbackInfoReturnable<?> cir) {
		if (velo$pendingProviders == null) {
			return;
		}
		List<GlyphProvider.Conditional> providers = velo$pendingProviders;
		velo$pendingProviders = null;
		velo$building = true;
		try {
			selectProviders(providers, velo$pendingOptions);
		} finally {
			velo$building = false;
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.font.FontStorage")
public abstract class FontSetLazyMixin {
	// 1.21.11: not ported (Velo's many-fonts load cost is a 26.x launcher-version thing).
}
//?}
