package net.veloclient.velo.client.devtools;

/** -Dvelo.tierTest=true: on the title screen, shows sample Tier Tagger names (real players, live data), screenshots them, quits. */
public final class TierTagTest {

	private TierTagTest() {
	}

	public static void register() {
		if (!Boolean.getBoolean("velo.tierTest")) {
			return;
		}
		//? if >=1.21.10 && <26.1 {
		int[] ticks = {0};
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.currentScreen == null || client.getOverlay() != null) {
				return;
			}
			ticks[0]++;
			if (ticks[0] == 40) {
				net.veloclient.velo.module.ModuleRegistry.get("tier-tagger").ifPresent(m -> m.setEnabled(true));
				client.setScreen(new Shot());
			}
			if (ticks[0] == 200) {
				net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, "tier-tags.png", client.getFramebuffer(), 1, m -> { });
			}
			if (ticks[0] == 220) {
				client.scheduleStop();
			}
		});
		//?}
	}

	//? if >=1.21.10 && <26.1 {
	private static final class Shot extends net.minecraft.client.gui.screen.Screen {
		private static final String[][] PLAYERS = {{"Swight", "ebd7af32-759e-41e2-b227-9eeb8576d609"}, {"ItzRealMe", "06ec3577-3299-45fa-bbdf-613b1f86c8ab"},
				{"Marlowww", "d219c8ee-d32e-4da2-b22e-0aa69d36c88a"}};

		Shot() {
			super(net.minecraft.text.Text.literal("Tier test"));
		}

		@Override
		public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
			context.fill(0, 0, this.width, this.height, 0xFF20242A);
			int y = 30;
			for (int pass = 0; pass < 2; pass++) {
				boolean sub = pass == 1;
				net.veloclient.velo.module.ModuleRegistry.get("tier-tagger").ifPresent(m -> {
					for (var f : ((net.veloclient.velo.module.Configurable) m).configFields()) {
						if (f instanceof net.veloclient.velo.module.ConfigField.ChoiceField c && c.label().equals("Tier list")) {
							c.set().accept(sub ? "SubTiers" : "PvPTiers");
						}
					}
				});
				context.drawTextWithShadow(this.textRenderer, sub ? "SubTiers" : "PvPTiers", 30, y, 0xFFAAAAAA);
				y += 16;
				for (String[] p : PLAYERS) {
					net.minecraft.text.Text t = net.veloclient.velo.client.tiers.TierTags.decorate(net.minecraft.text.Text.literal(p[0]),
							java.util.UUID.fromString(p[1]), false);
					context.drawTextWithShadow(this.textRenderer, t, 40, y, 0xFFFFFFFF);
					y += 14;
				}
				y += 10;
			}
		}
	}
	//?}
}
