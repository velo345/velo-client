package net.veloclient.velo.client;

import net.fabricmc.api.ClientModInitializer;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.hud.HudManager;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer;
import net.veloclient.velo.client.devtools.AnticheatTestController;
import net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule;
import net.veloclient.velo.client.modules.cosmetics.KillEffectsModule;
import net.veloclient.velo.client.modules.debug.KeybindConflictCheckerModule;
import net.veloclient.velo.client.modules.debug.LookingAtInspectorModule;
import net.veloclient.velo.client.modules.debug.ModuleProfilerOverlayModule;
import net.veloclient.velo.client.modules.debug.ResourceReloadHotkeyModule;
import net.veloclient.velo.client.modules.hud.ActionBarLogModule;
import net.veloclient.velo.client.modules.hud.ArmorDurabilityModule;
import net.veloclient.velo.client.modules.hud.ClockModule;
import net.veloclient.velo.client.modules.hud.CoordinatesModule;
import net.veloclient.velo.client.modules.hud.FpsCounterModule;
import net.veloclient.velo.client.modules.hud.HeldItemModule;
import net.veloclient.velo.client.modules.hud.KeystrokesModule;
import net.veloclient.velo.client.modules.hud.MinimapModule;
import net.veloclient.velo.client.modules.hud.MouseButtonsModule;
import net.veloclient.velo.client.modules.hud.PingDisplayModule;
import net.veloclient.velo.client.modules.hud.PotionTimersModule;
import net.veloclient.velo.client.modules.hud.ScoreboardHudModule;
import net.veloclient.velo.client.modules.hud.SessionStatsModule;
import net.veloclient.velo.client.modules.hud.WaypointsModule;
import net.veloclient.velo.client.modules.performance.FovModule;
import net.veloclient.velo.client.modules.performance.FrameTimeGraphModule;
import net.veloclient.velo.client.modules.performance.FullBrightModule;
import net.veloclient.velo.client.modules.performance.GpuUtilizationModule;
import net.veloclient.velo.client.modules.performance.InputSamplerModule;
import net.veloclient.velo.client.modules.performance.MemoryMonitorModule;
import net.veloclient.velo.client.modules.performance.ParticleLimiterModule;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.client.modules.performance.PolyBlurModule;
import net.veloclient.velo.client.modules.performance.RenderCullingModule;
import net.veloclient.velo.client.modules.qol.CustomCrosshairModule;
import net.veloclient.velo.client.modules.qol.FreeLookModule;
import net.veloclient.velo.client.modules.qol.NickHiderModule;
import net.veloclient.velo.client.modules.qol.SmallCapsModule;
import net.veloclient.velo.client.modules.qol.ToggleSneakModule;
import net.veloclient.velo.client.modules.qol.ToggleSprintModule;
import net.veloclient.velo.client.modules.qol.ZoomModule;
import net.veloclient.velo.client.modules.queue.BackgroundQueueModule;
import net.veloclient.velo.client.modules.rendering.BlockOutlineModule;
import net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule;
import net.veloclient.velo.client.modules.rendering.TntTimerModule;
import net.veloclient.velo.client.modules.servertools.ChunkBorderOverlayModule;
import net.veloclient.velo.client.modules.servertools.ChunkLoadProfilerModule;
import net.veloclient.velo.client.modules.servertools.ClientLogViewerModule;
import net.veloclient.velo.client.modules.servertools.EntityCountOverlayModule;
import net.veloclient.velo.client.modules.servertools.HitboxVisualizerModule;
import net.veloclient.velo.client.modules.servertools.LightLevelOverlayModule;
import net.veloclient.velo.client.modules.servertools.PacketTrafficMonitorModule;
import net.veloclient.velo.client.modules.servertools.ParticleDebugOverlayModule;
import net.veloclient.velo.client.modules.servertools.TpsTickGraphModule;
import net.veloclient.velo.client.modules.servertools.WorldBorderVisualizerModule;
import net.veloclient.velo.client.modules.utility.AutoReconnectModule;
import net.veloclient.velo.client.modules.utility.CommandKeybindsModule;
import net.veloclient.velo.client.modules.utility.CopyCoordinatesModule;
import net.veloclient.velo.client.modules.utility.SessionAutoFixerModule;
import net.veloclient.velo.client.modules.utility.VeloNetworkModule;
import net.veloclient.velo.module.ModuleRegistry;

/** Client-only entrypoint: registers every built-in module, the keybind and the HUD renderer. */
public final class VeloClientMod implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		registerModules();
		loadAddons();
		HudManager.register();
		VeloKeybinds.register();
		net.veloclient.velo.client.keybind.MenuKeySync.register();
		net.veloclient.velo.client.util.MemoryStatsRecorder.start();
		CapeFeatureRenderer.register();
		net.veloclient.velo.client.network.RewardsReporter.register();
		registerSocialOverlay();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
				client -> net.veloclient.velo.client.cosmetics.AnimatedCapeAsset.tickAll());
		net.veloclient.velo.client.profile.VeloProfileStore.loadActiveDeferred();
		// Previously only loaded lazily the first time the Crosshair select
		// screen was opened, so the persisted "equipped" crosshair never
		// actually rendered on a fresh launch - CustomCrosshairModule reads
		// CrosshairManager.equipped() every frame, which stayed null until
		// then, until the user opened that screen (which happened to load
		// it) at least once.
		net.veloclient.velo.client.crosshair.CrosshairManager.loadLibrary();
		ModuleRegistry.exportManifest();
		AnticheatTestController.initIfEnabled();
		net.veloclient.velo.client.devtools.ScreenshotTour.initIfRequested();
		VeloClient.LOGGER.info("Velo Client ready ({} modules registered)", ModuleRegistry.all().size());
	}

	/**
	 * Runs every installed add-on (see {@link net.veloclient.velo.client.addon.VeloAddon}) - e.g.
	 * the optional "Velo Client Addons" jar with the experimental modules. Before the profile loads,
	 * so their saved state restores like any built-in module's. One broken add-on is logged and
	 * skipped rather than taking the client down with it.
	 */
	private void loadAddons() {
		for (var container : net.fabricmc.loader.api.FabricLoader.getInstance()
				.getEntrypointContainers(net.veloclient.velo.client.addon.VeloAddon.ENTRYPOINT, net.veloclient.velo.client.addon.VeloAddon.class)) {
			try {
				container.getEntrypoint().onVeloInitialize();
				VeloClient.LOGGER.info("Loaded Velo add-on {}", container.getProvider().getMetadata().getId());
			} catch (Throwable t) {
				VeloClient.LOGGER.error("Velo add-on {} failed to initialize", container.getProvider().getMetadata().getId(), t);
			}
		}
	}

	/**
	 * Friend popups: drawn on the HUD while playing (see HudManager) and on top of every screen,
	 * where the mouse is free so they can be clicked - a click that lands on a popup is consumed
	 * before the screen underneath sees it.
	 */
	/** Options > Skin Customization gets a "Velo Skins" button (manage and switch skins). */
	private static void addSkinsButton(net.minecraft.client.gui.screen.Screen screen, int width) {
		//? if <26.1 {
		boolean skinOptions = screen instanceof net.minecraft.client.gui.screen.option.SkinOptionsScreen;
		//?} else {
		/*boolean skinOptions = screen instanceof net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
		*///?}
		if (!skinOptions) {
			return;
		}
		var button = new net.veloclient.velo.client.gui.widget.VeloButton(width - 112, 6, 106, 20, net.minecraft.text.Text.literal("Velo Skins  >"),
				b -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new net.veloclient.velo.client.gui.VeloSkinsScreen(screen))).primary();
		//? if <26.1 {
		net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(button);
		//?} else {
		/*net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(button);
		*///?}
	}

	/** Pause menu: a small "Report a bug" button in the bottom-left corner. */
	private static void addBugReportButton(net.minecraft.client.gui.screen.Screen screen, int height) {
		if (!(screen instanceof net.minecraft.client.gui.screen.GameMenuScreen)) {
			return;
		}
		var button = new net.veloclient.velo.client.gui.widget.VeloIconButton(6, height - 26, 20,
				net.veloclient.velo.client.gui.widget.VeloNavIcons.of("bug"), net.minecraft.text.Text.literal("Report a bug"),
				() -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new net.veloclient.velo.client.gui.BugReportScreen(screen)))
				.withPlainLabel();
		//? if <26.1 {
		net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(button);
		//?} else {
		/*net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(button);
		*///?}
	}

	/** First title screen after a crash: offer to send the crash report. */
	private static void offerCrashReport(net.minecraft.client.MinecraftClient client, net.minecraft.client.gui.screen.Screen screen) {
		if (!(screen instanceof net.minecraft.client.gui.screen.TitleScreen)) {
			return;
		}
		java.nio.file.Path crash = net.veloclient.velo.client.report.CrashCheck.pendingCrash();
		if (crash != null) {
			client.execute(() -> client.setScreen(new net.veloclient.velo.client.gui.BugReportScreen(screen, crash)));
		}
	}

	private void registerSocialOverlay() {
		net.veloclient.velo.client.social.SocialNotifications.register();
		// Velo's Statistics and Controls screens replace vanilla's.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			net.veloclient.velo.client.gui.VeloStatsScreen.maybeReplace(client, screen);
			net.veloclient.velo.client.gui.VeloKeybindsScreen.maybeReplace(client, screen);
			net.veloclient.velo.client.gui.VeloAdvancementsScreen.maybeReplace(client, screen);
			addSkinsButton(screen, scaledWidth);
			addBugReportButton(screen, scaledHeight);
			offerCrashReport(client, screen);
		});
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> {
				net.veloclient.velo.client.modules.qol.ShulkerPreview.render(s, context, mouseX, mouseY);
				net.veloclient.velo.client.social.NotificationOverlay.renderOverScreen(s, context, mouseX, mouseY);
			});
			net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
					!net.veloclient.velo.client.modules.qol.ShulkerPreview.click(s, click.x(), click.y(), click.button()));
			net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) ->
					!(key.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && net.veloclient.velo.client.modules.qol.ShulkerPreview.escape(s)));
			net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
					!net.veloclient.velo.client.social.NotificationOverlay.click(click.x(), click.y()));
		});
	}

	private void registerModules() {
		// HUD / QoL (section 6.2)
		ModuleRegistry.register(new FpsCounterModule());
		ModuleRegistry.register(new PingDisplayModule());
		ModuleRegistry.register(new CoordinatesModule());
		ModuleRegistry.register(new ClockModule());
		ModuleRegistry.register(new ArmorDurabilityModule());
		ModuleRegistry.register(new PotionTimersModule());
		ModuleRegistry.register(new HeldItemModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.TotemCounterModule());
		ModuleRegistry.register(new KeystrokesModule());
		ModuleRegistry.register(new MouseButtonsModule());
		net.veloclient.velo.client.worldmap.WorldMap.register();
		net.veloclient.velo.client.worldmap.WorldMapKeys.register();
		ModuleRegistry.register(new net.veloclient.velo.client.worldmap.WorldMapModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.HeartsModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.HungerModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.ArmorBarModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.XpBarModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.HotbarModule());
		ModuleRegistry.register(new ActionBarLogModule());
		ModuleRegistry.register(new SessionStatsModule());
		ModuleRegistry.register(new WaypointsModule());
		ModuleRegistry.register(new ScoreboardHudModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.hud.BossBarModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.qol.ShulkerPreviewModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.debug.BetterF3Module());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.servertools.SpawnRadiusModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.servertools.SimulationDistanceModule());
		ModuleRegistry.register(new ToggleSprintModule());
		ModuleRegistry.register(new ToggleSneakModule());
		ModuleRegistry.register(new ZoomModule());
		ModuleRegistry.register(new CustomCrosshairModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.qol.AttackIndicatorModule());
		ModuleRegistry.register(new MinimapModule());
		ModuleRegistry.register(new FreeLookModule());
		ModuleRegistry.register(new NickHiderModule());
		ModuleRegistry.register(new SmallCapsModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.qol.FriendsModule());

		// Rendering
		ModuleRegistry.register(new TntTimerModule());
		ModuleRegistry.register(new BlockOutlineModule());
		ModuleRegistry.register(new TimeWeatherFogModule());

		// Cosmetics (section 6.5)
		ModuleRegistry.register(new CapeCosmeticsModule());
		ModuleRegistry.register(new KillEffectsModule());
		ModuleRegistry.register(new VeloNetworkModule());

		// Performance (section 6.1)
		ModuleRegistry.register(new FrameTimeGraphModule());
		ModuleRegistry.register(new MemoryMonitorModule());
		ModuleRegistry.register(new GpuUtilizationModule());
		ModuleRegistry.register(new ParticleLimiterModule());
		ModuleRegistry.register(new PerformanceBoostModule());
		ModuleRegistry.register(new RenderCullingModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.performance.ShaderCacheModule());
		ModuleRegistry.register(new net.veloclient.velo.client.modules.performance.EntityDetailModule());
		ModuleRegistry.register(new PolyBlurModule());
		ModuleRegistry.register(new FullBrightModule());
		ModuleRegistry.register(new FovModule());
		ModuleRegistry.register(new InputSamplerModule());

		// Utility
		ModuleRegistry.register(new CopyCoordinatesModule());
		ModuleRegistry.register(new CommandKeybindsModule());
		ModuleRegistry.register(new AutoReconnectModule());
		ModuleRegistry.register(new SessionAutoFixerModule());

		// Server Tools (section 6.3)
		ModuleRegistry.register(new EntityCountOverlayModule());
		ModuleRegistry.register(new ChunkBorderOverlayModule());
		ModuleRegistry.register(new ChunkLoadProfilerModule());
		ModuleRegistry.register(new HitboxVisualizerModule());
		ModuleRegistry.register(new WorldBorderVisualizerModule());
		ModuleRegistry.register(new LightLevelOverlayModule());
		ModuleRegistry.register(new TpsTickGraphModule());
		ModuleRegistry.register(new PacketTrafficMonitorModule());
		ModuleRegistry.register(new ParticleDebugOverlayModule());
		ModuleRegistry.register(new ClientLogViewerModule());
		ModuleRegistry.register(new BackgroundQueueModule());

		// Debug (section 6.4)
		ModuleRegistry.register(new ResourceReloadHotkeyModule());
		ModuleRegistry.register(new KeybindConflictCheckerModule());
		ModuleRegistry.register(new LookingAtInspectorModule());
		ModuleRegistry.register(new ModuleProfilerOverlayModule());
	}
}
