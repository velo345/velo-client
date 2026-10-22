package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.EmojiChatModule;
import net.veloclient.velo.client.util.EmojiSuggest;
import net.veloclient.velo.client.util.Keys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Emoji Chat: the emoji list above the chat box while typing {@code :na} - Up/Down to choose, Tab to insert. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.ChatScreen.class)
public abstract class EmojiSuggestMixin {

	@Shadow
	protected net.minecraft.client.gui.widget.TextFieldWidget chatField;

	@Inject(method = "render", at = @At("TAIL"), require = 0)
	private void velo$emojiList(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!EmojiChatModule.suggestions() || chatField == null) {
			return;
		}
		EmojiSuggest.update(chatField.getText(), chatField.getCursor());
		EmojiSuggest.render(context, chatField.getX(), chatField.getY() - 3);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$emojiKeys(net.minecraft.client.input.KeyInput input, CallbackInfoReturnable<Boolean> cir) {
		if (!EmojiChatModule.suggestions() || chatField == null || !EmojiSuggest.visible()) {
			return;
		}
		int key = input.key();
		if (key == Keys.TAB) {
			EmojiSuggest.Completion done = EmojiSuggest.complete(chatField.getText(), chatField.getCursor());
			if (done != null) {
				chatField.setText(done.text());
				chatField.setCursor(done.cursor(), false);
			}
			cir.setReturnValue(true);
		} else if (key == Keys.UP || key == Keys.DOWN) {
			EmojiSuggest.move(key == Keys.UP ? -1 : 1);
			cir.setReturnValue(true);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.ChatScreen.class)
public abstract class EmojiSuggestMixin {

	@Shadow
	protected net.minecraft.client.gui.components.EditBox input;

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void velo$emojiList(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!EmojiChatModule.suggestions() || input == null) {
			return;
		}
		EmojiSuggest.update(input.getValue(), input.getCursorPosition());
		EmojiSuggest.render(context, input.getX(), input.getY() - 3);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$emojiKeys(net.minecraft.client.input.KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!EmojiChatModule.suggestions() || input == null || !EmojiSuggest.visible()) {
			return;
		}
		int key = event.key();
		if (key == Keys.TAB) {
			EmojiSuggest.Completion done = EmojiSuggest.complete(input.getValue(), input.getCursorPosition());
			if (done != null) {
				input.setValue(done.text());
				input.setCursorPosition(done.cursor());
			}
			cir.setReturnValue(true);
		} else if (key == Keys.UP || key == Keys.DOWN) {
			EmojiSuggest.move(key == Keys.UP ? -1 : 1);
			cir.setReturnValue(true);
		}
	}
}
*///?}
