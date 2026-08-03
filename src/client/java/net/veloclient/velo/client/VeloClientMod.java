package net.veloclient.velo.client;

import net.fabricmc.api.ClientModInitializer;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.hud.HudManager;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer;
import net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule;
import net.veloclient.velo.client.modules.debug.KeybindConflictCheckerModule;
import net.veloclient.velo.client.modules.debug.LookingAtInspectorModule;
import net.veloclient.velo.client.modules.debug.ResourceReloadHotkeyModule;
import net.veloclient.velo.client.modules.hud.ActionBarLogModule;
import net.veloclient.velo.client.modules.hud.ArmorDurabilityModule;
import net.veloclient.velo.client.modules.hud.ClockModule;
import net.veloclient.velo.client.modules.hud.CoordinatesModule;
import net.veloclient.velo.client.modules.hud.CpsCounterModule;
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
import net.veloclient.velo.client.modules.performance.MemoryMonitorModule;
import net.veloclient.velo.client.modules.performance.OptimizationModCompatModule;
import net.veloclient.velo.client.modules.performance.ParticleLimiterModule;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.client.modules.performance.PolyBlurModule;
import net.veloclient.velo.client.modules.qol.CustomCrosshairModule;
import net.veloclient.velo.client.modules.qol.ToggleSneakModule;
import net.veloclient.velo.client.modules.qol.ToggleSprintModule;
import net.veloclient.velo.client.modules.qol.ZoomModule;
import net.veloclient.velo.client.modules.servertools.ChunkBorderOverlayModule;
import net.veloclient.velo.client.modules.servertools.ClientLogViewerModule;
import net.veloclient.velo.client.modules.servertools.EntityCountOverlayModule;
import net.veloclient.velo.client.modules.servertools.HitboxVisualizerModule;
import net.veloclient.velo.client.modules.servertools.LightLevelOverlayModule;
import net.veloclient.velo.client.modules.servertools.PacketTrafficMonitorModule;
import net.veloclient.velo.client.modules.servertools.ParticleDebugOverlayModule;
import net.veloclient.velo.client.modules.servertools.SoundDebugOverlayModule;
import net.veloclient.velo.client.modules.servertools.TpsTickGraphModule;
import net.veloclient.velo.client.modules.servertools.WorldBorderVisualizerModule;
import net.veloclient.velo.client.modules.utility.CommandKeybindsModule;
import net.veloclient.velo.client.modules.utility.CopyCoordinatesModule;
import net.veloclient.velo.module.ModuleRegistry;

/** Client-only entrypoint: registers every built-in module, the keybind and the HUD renderer. */
public final class VeloClientMod implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		registerModules();
		HudManager.register();
		VeloKeybinds.register();
		CapeFeatureRenderer.register();
		net.veloclient.velo.client.gui.VeloBranding.register();
		net.veloclient.velo.client.profile.VeloProfileStore.loadActiveDeferred();
		ModuleRegistry.exportManifest();
		VeloClient.LOGGER.info("Velo Client ready ({} modules registered)", ModuleRegistry.all().size());
	}

	private void registerModules() {
		// HUD / QoL (section 6.2)
		ModuleRegistry.register(new FpsCounterModule());
		ModuleRegistry.register(new PingDisplayModule());
		ModuleRegistry.register(new CoordinatesModule());
		ModuleRegistry.register(new ClockModule());
		ModuleRegistry.register(new CpsCounterModule());
		ModuleRegistry.register(new ArmorDurabilityModule());
		ModuleRegistry.register(new PotionTimersModule());
		ModuleRegistry.register(new HeldItemModule());
		ModuleRegistry.register(new KeystrokesModule());
		ModuleRegistry.register(new MouseButtonsModule());
		ModuleRegistry.register(new ActionBarLogModule());
		ModuleRegistry.register(new SessionStatsModule());
		ModuleRegistry.register(new WaypointsModule());
		ModuleRegistry.register(new ScoreboardHudModule());
		ModuleRegistry.register(new ToggleSprintModule());
		ModuleRegistry.register(new ToggleSneakModule());
		ModuleRegistry.register(new ZoomModule());
		ModuleRegistry.register(new CustomCrosshairModule());
		ModuleRegistry.register(new MinimapModule());

		// Cosmetics (section 6.5)
		ModuleRegistry.register(new CapeCosmeticsModule());

		// Performance (section 6.1)
		ModuleRegistry.register(new FrameTimeGraphModule());
		ModuleRegistry.register(new MemoryMonitorModule());
		ModuleRegistry.register(new GpuUtilizationModule());
		ModuleRegistry.register(new ParticleLimiterModule());
		ModuleRegistry.register(new PerformanceBoostModule());
		ModuleRegistry.register(new PolyBlurModule());
		ModuleRegistry.register(new OptimizationModCompatModule());
		ModuleRegistry.register(new FullBrightModule());
		ModuleRegistry.register(new FovModule());

		// Utility
		ModuleRegistry.register(new CopyCoordinatesModule());
		ModuleRegistry.register(new CommandKeybindsModule());

		// Server Tools (section 6.3)
		ModuleRegistry.register(new EntityCountOverlayModule());
		ModuleRegistry.register(new ChunkBorderOverlayModule());
		ModuleRegistry.register(new HitboxVisualizerModule());
		ModuleRegistry.register(new WorldBorderVisualizerModule());
		ModuleRegistry.register(new LightLevelOverlayModule());
		ModuleRegistry.register(new TpsTickGraphModule());
		ModuleRegistry.register(new PacketTrafficMonitorModule());
		ModuleRegistry.register(new ParticleDebugOverlayModule());
		ModuleRegistry.register(new SoundDebugOverlayModule());
		ModuleRegistry.register(new ClientLogViewerModule());

		// Debug (section 6.4)
		ModuleRegistry.register(new ResourceReloadHotkeyModule());
		ModuleRegistry.register(new KeybindConflictCheckerModule());
		ModuleRegistry.register(new LookingAtInspectorModule());
	}
}
