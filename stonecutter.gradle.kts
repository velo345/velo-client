plugins {
	id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

// See https://stonecutter.kikugie.dev/wiki/config/params
//
// 26.1 ships Mojang's own official mappings instead of Yarn (Yarn has no
// builds published for 26.1/26.2 at all - confirmed against
// maven.fabricmc.net's real metadata). This block ports every Yarn-mapped
// symbol this codebase uses over to its real Mojmap equivalent for 26.1+,
// verified against the actual decompiled/javap'd 26.1 game jars rather than
// guessed - Mojang's naming diverges from Yarn's on almost every touched
// type (DrawContext -> GuiGraphicsExtractor, MinecraftClient -> Minecraft,
// Text -> Component, etc.), not just a handful of renames.
//
// IMPORTANT about how `replace()` actually matches: it's a simultaneous,
// non-overlapping multi-pattern matcher (Aho-Corasick), not a regex and NOT
// word-boundary-aware - confirmed empirically (a naive bare "Text" ->
// "Component" rule corrupted "drawTexture" into "drawComponenture" and
// mangled a mixin's target method descriptor string). Two things follow:
//   1. A LONGER registered pattern starting at the same or an earlier
//      position always wins and "shields" everything inside it (e.g.
//      registering "TextFieldWidget" separately protects it from the bare
//      "Text" rule below it).
//   2. Short/generic tokens ("Text", "Click") are NEVER used bare here -
//      only in patterns specific enough that they can't appear inside an
//      unrelated real API name (a Texture-related method, "onClick",
//      "mouseClicked", etc).
stonecutter parameters {
	swaps["mod_version"] = "\"${property("mod.version")}\";"
	swaps["minecraft"] = "\"${node.metadata.version}\";"
	dependencies["fapi"] = node.project.property("deps.fabric_api") as String

	replacements {
		string(current.parsed >= "26.1") {

			// --- Fully-qualified path swaps: package changed, simple name
			// didn't. Safe as a blanket rule since the source pattern
			// already includes the full package prefix, and (per the
			// longer-match-wins rule) a same-named-but-longer sibling like
			// "EntityType"/"Items"/"ItemStack" registered below always wins
			// over a shorter prefix like "Entity"/"Item" at the same start
			// position.
			replace("net.minecraft.block.BlockState", "net.minecraft.world.level.block.state.BlockState")
			replace("net.minecraft.block.entity.BlockEntity", "net.minecraft.world.level.block.entity.BlockEntity")
			replace("net.minecraft.client.gl.RenderPipelines", "net.minecraft.client.renderer.RenderPipelines")
			replace("net.minecraft.client.gui.screen.narration.NarrationMessageBuilder", "net.minecraft.client.gui.narration.NarrationElementOutput")
			replace("net.minecraft.client.gui.screen.narration.NarrationPart", "net.minecraft.client.gui.narration.NarratedElementType")
			replace("net.minecraft.client.gui.screen.DeathScreen", "net.minecraft.client.gui.screens.DeathScreen")
			replace("net.minecraft.client.gui.screen.GameMenuScreen", "net.minecraft.client.gui.screens.PauseScreen")
			replace("net.minecraft.client.gui.screen.TitleScreen", "net.minecraft.client.gui.screens.TitleScreen")
			replace("net.minecraft.client.gui.screen.Screen", "net.minecraft.client.gui.screens.Screen")
			replace("net.minecraft.client.gui.widget.ClickableWidget", "net.minecraft.client.gui.components.AbstractWidget")
			replace("net.minecraft.client.gui.widget.TextFieldWidget", "net.minecraft.client.gui.components.EditBox")
			replace("net.minecraft.client.gui.hud.InGameHud", "net.minecraft.client.gui.Gui")
			replace("net.minecraft.client.gui.hud.PlayerListHud", "net.minecraft.client.gui.components.PlayerTabOverlay")
			replace("net.minecraft.client.gui.DrawContext", "net.minecraft.client.gui.GuiGraphicsExtractor")
			replace("net.minecraft.client.gui.Click", "net.minecraft.client.input.MouseButtonEvent")
			replace("net.minecraft.client.font.TextRenderer", "net.minecraft.client.gui.Font")
			replace("net.minecraft.client.MinecraftClient", "net.minecraft.client.Minecraft")
			replace("net.minecraft.client.Mouse", "net.minecraft.client.MouseHandler")
			replace("net.minecraft.client.network.ClientPlayNetworkHandler", "net.minecraft.client.multiplayer.ClientPacketListener")
			replace("net.minecraft.client.network.PlayerListEntry", "net.minecraft.client.multiplayer.PlayerInfo")
			replace("net.minecraft.client.option.GameOptions", "net.minecraft.client.Options")
			replace("net.minecraft.client.option.KeyBinding", "net.minecraft.client.KeyMapping")
			replace("net.minecraft.client.option.CloudRenderMode", "net.minecraft.client.CloudStatus")
			replace("net.minecraft.client.option.GraphicsMode", "net.minecraft.client.GraphicsPreset")
			replace("net.minecraft.client.option.InactivityFpsLimit", "net.minecraft.client.InactivityFpsLimit")
			replace("net.minecraft.client.render.command.OrderedRenderCommandQueue", "net.minecraft.client.renderer.OrderedRenderCommandQueue")
			replace("net.minecraft.client.render.entity.EntityRendererFactory", "net.minecraft.client.renderer.entity.EntityRendererProvider")
			replace("net.minecraft.client.render.entity.feature.FeatureRendererContext", "net.minecraft.client.renderer.entity.RenderLayerParent")
			replace("net.minecraft.client.render.entity.feature.FeatureRenderer", "net.minecraft.client.renderer.entity.RenderLayer")
			replace("net.minecraft.client.render.entity.model.EntityModelLayers", "net.minecraft.client.model.geom.ModelLayers")
			replace("net.minecraft.client.render.entity.model.PlayerCapeModel", "net.minecraft.client.model.player.PlayerCapeModel")
			replace("net.minecraft.client.render.entity.state.PlayerEntityRenderState", "net.minecraft.client.renderer.entity.state.PlayerRenderState")
			replace("net.minecraft.client.render.GameRenderer", "net.minecraft.client.renderer.GameRenderer")
			replace("net.minecraft.client.render.OverlayTexture", "net.minecraft.client.renderer.texture.OverlayTexture")
			replace("net.minecraft.client.render.RenderLayers", "net.minecraft.client.renderer.RenderType")
			replace("net.minecraft.client.render.RenderTickCounter", "net.minecraft.client.DeltaTracker")
			replace("net.minecraft.client.render.Camera", "net.minecraft.client.Camera")
			replace("net.minecraft.client.sound.SoundInstanceListener", "net.minecraft.client.sounds.SoundEventListener")
			replace("net.minecraft.client.sound.WeightedSoundSet", "net.minecraft.client.sounds.WeighedSoundEvents")
			replace("net.minecraft.client.sound.SoundInstance", "net.minecraft.client.resources.sounds.SoundInstance")
			replace("net.minecraft.client.texture.NativeImageBackedTexture", "net.minecraft.client.renderer.texture.DynamicTexture")
			replace("net.minecraft.client.texture.NativeImage", "com.mojang.blaze3d.platform.NativeImage")
			replace("net.minecraft.client.util.InputUtil", "com.mojang.blaze3d.platform.InputConstants")
			replace("net.minecraft.client.util.math.MatrixStack", "org.joml.Matrix3x2fStack")
			replace("net.minecraft.client.world.ClientWorld", "net.minecraft.client.multiplayer.ClientLevel")
			replace("net.minecraft.entity.effect.StatusEffectInstance", "net.minecraft.world.effect.MobEffectInstance")
			replace("net.minecraft.entity.effect.StatusEffects", "net.minecraft.world.effect.MobEffects")
			replace("net.minecraft.entity.effect.StatusEffect", "net.minecraft.world.effect.MobEffect")
			replace("net.minecraft.entity.player.PlayerEntity", "net.minecraft.world.entity.player.Player")
			replace("net.minecraft.entity.EntityType", "net.minecraft.world.entity.EntityType")
			replace("net.minecraft.entity.EquipmentSlot", "net.minecraft.world.entity.EquipmentSlot")
			replace("net.minecraft.entity.LivingEntity", "net.minecraft.world.entity.LivingEntity")
			replace("net.minecraft.entity.Entity", "net.minecraft.world.entity.Entity")
			replace("net.minecraft.item.Items", "net.minecraft.world.item.Items")
			replace("net.minecraft.item.ItemStack", "net.minecraft.world.item.ItemStack")
			replace("net.minecraft.item.Item", "net.minecraft.world.item.Item")
			replace("net.minecraft.network.ClientConnection", "net.minecraft.network.Connection")
			replace("net.minecraft.network.packet.Packet", "net.minecraft.network.protocol.Packet")
			replace("net.minecraft.particle.ParticlesMode", "net.minecraft.server.level.ParticleStatus")
			replace("net.minecraft.registry.entry.RegistryEntry", "net.minecraft.core.Holder")
			replace("net.minecraft.registry.Registries", "net.minecraft.core.registries.Registries")
			replace("net.minecraft.scoreboard.number.StyledNumberFormat", "net.minecraft.network.chat.numbers.StyledFormat")
			replace("net.minecraft.scoreboard.number.NumberFormat", "net.minecraft.network.chat.numbers.NumberFormat")
			replace("net.minecraft.scoreboard.ScoreboardDisplaySlot", "net.minecraft.world.scores.DisplaySlot")
			replace("net.minecraft.scoreboard.ScoreboardEntry", "net.minecraft.world.scores.PlayerScoreEntry")
			replace("net.minecraft.scoreboard.ScoreboardObjective", "net.minecraft.world.scores.Objective")
			replace("net.minecraft.scoreboard.Scoreboard", "net.minecraft.world.scores.Scoreboard")
			replace("net.minecraft.scoreboard.AbstractTeam", "net.minecraft.world.scores.Team")
			replace("net.minecraft.scoreboard.Team", "net.minecraft.world.scores.PlayerTeam")
			replace("net.minecraft.server.integrated.IntegratedServer", "net.minecraft.client.server.IntegratedServer")
			replace("net.minecraft.sound.SoundCategory", "net.minecraft.sounds.SoundSource")
			replace("net.minecraft.sound.SoundEvents", "net.minecraft.sounds.SoundEvents")
			replace("net.minecraft.text.MutableText", "net.minecraft.network.chat.MutableComponent")
			replace("net.minecraft.text.Text", "net.minecraft.network.chat.Component")
			replace("net.minecraft.util.hit.BlockHitResult", "net.minecraft.world.phys.BlockHitResult")
			replace("net.minecraft.util.hit.EntityHitResult", "net.minecraft.world.phys.EntityHitResult")
			replace("net.minecraft.util.hit.HitResult", "net.minecraft.world.phys.HitResult")
			replace("net.minecraft.util.math.BlockPos", "net.minecraft.core.BlockPos")
			replace("net.minecraft.util.math.ColorHelper", "net.minecraft.util.ARGB")
			replace("net.minecraft.util.math.Vec3d", "net.minecraft.world.phys.Vec3")
			replace("net.minecraft.util.math.Box", "net.minecraft.world.phys.AABB")
			replace("net.minecraft.util.Identifier", "net.minecraft.resources.Identifier")
			// No Colors.WHITE-shaped constant in 26.1's util.ARGB (only
			// white(float)/white(int) methods, which need an alpha arg this
			// codebase's 3 call sites don't pass) - the plain literal is a
			// simpler, safe substitute than trying to match a method call
			// shape via text replacement.
			replace("Colors.WHITE", "0xFFFFFFFF")
			// Screen's own render() override point doesn't exist in 26.1 -
			// same retained-render-state model as widgets
			// (renderWidget -> extractWidgetRenderState), confirmed via
			// Fabric API's own HudElement interface adopting the same
			// convention (render -> extractRenderState(GuiGraphicsExtractor,
			// DeltaTracker)). Scoped to the two real shapes in this codebase
			// (Screen overrides use "mouseX, mouseY, delta"; HudModule's own
			// unrelated render() interface uses "x, y, tickDelta" and must
			// NOT be touched) rather than a blind "render(" rule.
			replace("public void render(DrawContext context, int mouseX, int mouseY, float delta)",
					"public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)")
			replace("super.render(context, mouseX, mouseY, delta)", "super.extractRenderState(context, mouseX, mouseY, delta)")
			replace("vanilla.render(context, tickCounter)", "vanilla.extractRenderState(context, tickCounter)")
			replace("Identifier.of(", "Identifier.fromNamespaceAndPath(")
			replace("VanillaHudElements.STATUS_EFFECTS", "VanillaHudElements.MOB_EFFECTS")
			replace("tickCounter.getTickProgress(", "tickCounter.getGameTimeDeltaPartialTick(")
			// client.options.hudHidden's real target diverges again in 26.2
			// (Options.hideGui is removed there entirely, HUD-hidden state
			// moves to the new Hud object) - handled with a manual //? if
			// block in HudManager.java instead of here (see the long
			// comment near the end of this file explaining why).
			replace(".getWindow().getScaledWidth()", ".getWindow().getGuiScaledWidth()")
			replace(".getWindow().getScaledHeight()", ".getWindow().getGuiScaledHeight()")
			// Screen widget registration and EditBox's text accessors were
			// both renamed wholesale in 26.1 (verified via javap against
			// the real EditBox class) - every call site in this codebase is
			// on an EditBox-typed variable, no collision risk found.
			replace("addDrawableChild(", "addRenderableWidget(")
			replace("addSelectableChild(", "addWidget(")
			replace(".getText(", ".getValue(")
			replace(".setText(", ".setValue(")
			replace("setChangedListener(", "setResponder(")
			replace("setPlaceholder(", "setHint(")
			replace("setDrawsBackground(", "setBordered(")
			// Options' OptionInstance getters dropped the "get" prefix in
			// 26.1 (and several were renamed outright) - verified via
			// javap against the real 26.1 Options class one by one, not
			// guessed. Each is specific/compound enough not to collide
			// with anything else in this codebase.
			replace("getPreset()", "graphicsPreset()")
			replace("getParticles()", "particles()")
			replace("getEntityShadows()", "entityShadows()")
			replace("getViewDistance()", "renderDistance()")
			replace("getEnableVsync()", "enableVsync()")
			replace("getCloudRenderMode()", "cloudStatus()")
			replace("getBiomeBlendRadius()", "biomeBlendRadius()")
			replace("getInactivityFpsLimit()", "inactivityFpsLimit()")
			replace("getFov()", "fov()")
			replace("getFovEffectScale()", "fovEffectScale()")
			replace("getSimulationDistance()", "simulationDistance()")
			replace("getSneakToggled()", "toggleCrouch()")
			replace("getSprintToggled()", "toggleSprint()")
			replace("getMaxFps()", "framerateLimit()")
			replace("getMipmapLevels()", "mipmapLevels()")
			replace("getGamma()", "gamma()")
			replace("getDamageTiltStrength()", "damageTiltStrength()")
			replace("getDistortionEffectScale()", "screenEffectScale()")
			replace("getEntityDistanceScaling()", "entityDistanceScaling()")
			replace("getPerspective()", "getCameraType()")
			// OptionInstance#getValue()/#setValue(...) -> #get()/#set(...)
			// (verified via javap) - far too generic to blanket-replace
			// (would corrupt Map.Entry#getValue(), RegistryKey#getValue(),
			// etc, all real and used elsewhere in this codebase), so each
			// is scoped to the exact real OptionInstance-returning call
			// chain it follows, plus the two local-variable-typed usages
			// (ToggleSneakModule/ToggleSprintModule's "option", FullBright
			// Module's "gamma") that hold an OptionInstance directly.
			// IMPORTANT: each source pattern below must match the ORIGINAL
			// Yarn-authored text this file is written in (replacements run
			// as a single simultaneous pass over the original source, not
			// a cascade over other rules' output) - so these key off the
			// pre-rename "getXxx()" accessor names, not the post-rename
			// Mojmap ones, even though the earlier bare "getXxx()"->
			// "realName()" rules above target the same original text. Being
			// longer, these compound rules win at the same start position.
			replace("getPreset().getValue()", "graphicsPreset().get()")
			replace("getPreset().setValue(", "graphicsPreset().set(")
			replace("getParticles().getValue()", "particles().get()")
			replace("getParticles().setValue(", "particles().set(")
			replace("getEntityShadows().getValue()", "entityShadows().get()")
			replace("getEntityShadows().setValue(", "entityShadows().set(")
			replace("getViewDistance().getValue()", "renderDistance().get()")
			replace("getViewDistance().setValue(", "renderDistance().set(")
			replace("getEnableVsync().getValue()", "enableVsync().get()")
			replace("getEnableVsync().setValue(", "enableVsync().set(")
			replace("getCloudRenderMode().getValue()", "cloudStatus().get()")
			replace("getCloudRenderMode().setValue(", "cloudStatus().set(")
			replace("getBiomeBlendRadius().getValue()", "biomeBlendRadius().get()")
			replace("getBiomeBlendRadius().setValue(", "biomeBlendRadius().set(")
			replace("getInactivityFpsLimit().getValue()", "inactivityFpsLimit().get()")
			replace("getInactivityFpsLimit().setValue(", "inactivityFpsLimit().set(")
			replace("getFov().getValue()", "fov().get()")
			replace("getFov().setValue(", "fov().set(")
			replace("getFovEffectScale().getValue()", "fovEffectScale().get()")
			replace("getFovEffectScale().setValue(", "fovEffectScale().set(")
			replace("getSimulationDistance().getValue()", "simulationDistance().get()")
			replace("getSimulationDistance().setValue(", "simulationDistance().set(")
			replace("getSneakToggled().getValue()", "toggleCrouch().get()")
			replace("getSneakToggled().setValue(", "toggleCrouch().set(")
			replace("getSprintToggled().getValue()", "toggleSprint().get()")
			replace("getSprintToggled().setValue(", "toggleSprint().set(")
			replace("getMaxFps().getValue()", "framerateLimit().get()")
			replace("getMaxFps().setValue(", "framerateLimit().set(")
			replace("getMipmapLevels().getValue()", "mipmapLevels().get()")
			replace("getMipmapLevels().setValue(", "mipmapLevels().set(")
			replace("getGamma().getValue()", "gamma().get()")
			replace("getGamma().setValue(", "gamma().set(")
			replace("getDamageTiltStrength().getValue()", "damageTiltStrength().get()")
			replace("getDamageTiltStrength().setValue(", "damageTiltStrength().set(")
			replace("getDistortionEffectScale().getValue()", "screenEffectScale().get()")
			replace("getDistortionEffectScale().setValue(", "screenEffectScale().set(")
			replace("getEntityDistanceScaling().getValue()", "entityDistanceScaling().get()")
			replace("getEntityDistanceScaling().setValue(", "entityDistanceScaling().set(")
			replace("option.getValue()", "option.get()")
			replace("option.setValue(", "option.set(")
			replace("gamma.getValue()", "gamma.get()")
			replace("gamma.setValue(", "gamma.set(")
			replace(".getYaw(", ".getYRot(")
			replace(".getPitch(", ".getXRot(")
			replace("client.world", "client.level")
			// Movement/action KeyMapping fields on Options were renamed
			// wholesale (verified via javap) - options.forwardKey etc had
			// no "get" prefix to begin with, so these are bare field-access
			// renames, safe as this codebase's only Options field accesses.
			replace("options.forwardKey", "options.keyUp")
			replace("options.leftKey", "options.keyLeft")
			replace("options.backKey", "options.keyDown")
			replace("options.rightKey", "options.keyRight")
			replace("options.sneakKey", "options.keyShift")
			replace("options.jumpKey", "options.keyJump")
			replace("options.sprintKey", "options.keySprint")
			// KeyBinding#isPressed() -> KeyMapping#isDown() - every call
			// site in this codebase is on a real key-mapping variable.
			replace(".isPressed()", ".isDown()")
			// client.textRenderer/this.textRenderer -> client.font/
			// this.font already renames the field access itself (see
			// above), which - being a longer, earlier-starting match -
			// swallows the immediately-following ".getWidth("/".fontHeight"
			// before the scoped textRenderer./renderer. rules ever get a
			// chance to fire on this specific receiver spelling. These
			// compound rules key off the full original text directly.
			replace("client.textRenderer.getWidth(", "client.font.width(")
			replace("client.textRenderer.fontHeight", "client.font.lineHeight")
			replace("this.textRenderer.getWidth(", "this.font.width(")
			replace("this.textRenderer.fontHeight", "this.font.lineHeight")
			// Screen#client (Yarn field) -> Screen#minecraft (Mojmap field) -
			// scoped to "this.client" (verified this codebase always
			// qualifies it explicitly, never bare "client" for the Screen
			// field, which would be ambiguous with the many unrelated
			// local variables also named "client" throughout the modules).
			replace("this.client", "this.minecraft")
			replace("this.clearChildren()", "this.clearWidgets()")
			replace("this.remove(", "this.removeWidget(")
			replace("Util.getOperatingSystem()", "Util.getPlatform()")
			// SoundEventListener/SoundInstance/SoundManager - verified via
			// javap.
			replace("onSoundPlayed(", "onPlaySound(")
			replace("sound.getId()", "sound.getIdentifier()")
			replace("sound.getCategory()", "sound.getSource()")
			replace("getSoundManager().registerListener(", "getSoundManager().addListener(")
			// Scoreboard - verified via javap.
			replace("getObjectiveForSlot(", "getDisplayObjective(")
			replace("getScoreboardEntries(", "listPlayerScores(")
			// Options#getTextBackgroundColor got mangled to
			// "getComponentBackgroundColor" by the generic Text->Component
			// rule (it's not a real vanilla method under either of those
			// names) - the real accessor is getBackgroundColor(float).
			// GuiGraphicsExtractor#text(...) covers the no-shadow-argument
			// call shape too (drawText's original callers here always pass
			// the trailing boolean explicitly) - same target as
			// drawTextWithShadow above, just not forcing shadow=true.
			// Scoped (not bare "drawText(" -> "text(") because Stonecutter
			// forbids two different source patterns resolving to the exact
			// same target string ("drawTextWithShadow(" -> "text(" already
			// exists above) - this codebase's only drawText(...) call sites
			// (ScoreboardHudModule) all share this exact prefix, so this
			// stays unambiguous while still hitting the real target method.
			replace("context.drawText(renderer, ", "context.text(renderer, ")
			// Minecraft#screen (Yarn: currentScreen) needs a different
			// target per version (plain field in 26.1, Gui#screen() in
			// 26.2) - handled with manual //? if blocks in each of the six
			// call-site files instead of here (see the long comment near
			// the end of this file explaining why).
			// LivingEntity#getEquippedStack -> #getItemBySlot, ItemStack's
			// damage/name accessors - verified via javap.
			replace("getEquippedStack(", "getItemBySlot(")
			replace(".isDamageable()", ".isDamageableItem()")
			replace(".getDamage()", ".getDamageValue()")
			replace("stack.getName()", "stack.getHoverName()")
			// ARGB#colorFromFloat replaces ColorHelper#fromFloats (name AND
			// arg order/shape unchanged, just renamed).
			replace("ColorHelper.fromFloats(", "ARGB.colorFromFloat(")
			// WorldBorder's compass-direction bound accessors became plain
			// min/max X/Z (verified via javap).
			replace("getBoundWest()", "getMinX()")
			replace("getBoundEast()", "getMaxX()")
			replace("getBoundNorth()", "getMinZ()")
			replace("getBoundSouth()", "getMaxZ()")
			replace("getBottomY()", "getMinY()")
			replace("getTopYInclusive()", "getMaxY()")
			// Screen#shouldPause -> #isPauseScreen, Screen#close ->
			// #onClose (both real overridable Screen hooks - "close(" is
			// scoped to the exact "public void close()" declaration shape,
			// which only this codebase's 2 genuine Screen overrides use).
			replace("public boolean shouldPause()", "public boolean isPauseScreen()")
			replace("public void close() {", "public void onClose() {")
			// Screen no longer has a separate public close()-trigger method
			// distinct from the onClose() override hook - callers invoke
			// onClose() directly now.
			replace("this.close()", "this.onClose()")
			// AbstractWidget's real render entrypoint for external callers
			// (VeloScrollRegion manually renders a list of widgets) is the
			// same extractRenderState hook subclasses override.
			replace("row.render(context, mouseX, mouseY, delta)", "row.extractRenderState(context, mouseX, mouseY, delta)")
			// VeloBranding references Minecraft inline/fully-qualified
			// rather than via import.
			replace("net.minecraft.client.MinecraftClient.getInstance().textRenderer", "net.minecraft.client.Minecraft.getInstance().font")
			// GuiGraphicsExtractor#item(ItemStack, x, y) is the equivalent
			// of drawItemWithoutEntity (verified in the method list pulled
			// earlier from the real class).
			replace("drawItemWithoutEntity(", "item(")
			// getTextBackgroundColor (real original text, not the
			// previously-guessed "getComponentBackgroundColor") ->
			// getBackgroundColor - same lesson as the OptionInstance chain
			// fixes above about matching literal original source.
			replace("getTextBackgroundColor(", "getBackgroundColor(")
			// F3 debug-overlay entry visibility system was restructured,
			// not just renamed (verified via javap): debugHudEntryList
			// field -> debugEntries, DebugHudEntries type ->
			// DebugScreenEntryList, setEntryVisibility(id, Visibility) ->
			// setStatus(id, DebugScreenEntryStatus) - the ALWAYS_ON/NEVER
			// constant names carried over unchanged.
			replace("net.minecraft.client.gui.hud.debug.DebugHudEntries", "net.minecraft.client.gui.components.debug.DebugScreenEntries")
			replace("net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility", "net.minecraft.client.gui.components.debug.DebugScreenEntryStatus")
			replace("DebugHudEntries", "DebugScreenEntries")
			replace("DebugHudEntryVisibility", "DebugScreenEntryStatus")
			replace("debugHudEntryList.setEntryVisibility(", "debugEntries.setStatus(")
			// Scoreboard/Team - verified via javap.
			replace(".hidden()", ".isHidden()")
			replace("getScoreHolderTeam(", "getPlayersTeam(")
			replace("Team.decorateName(", "PlayerTeam.formatNameForTeam(")
			replace("entry.formatted(", "entry.formatValue(")
			// KeyMapping#wasPressed -> #consumeClick (verified via javap -
			// same "did this get clicked since I last checked" semantics).
			replace("wasPressed()", "consumeClick()")
			// BlockPos.ofFloored -> BlockPos.containing, Entity#
			// squaredDistanceTo -> #distanceToSqr, Entity#getUuid ->
			// #getUUID (case change), Minecraft's network/server/crosshair
			// accessors - all verified via javap.
			replace("BlockPos.ofFloored(", "BlockPos.containing(")
			replace(".squaredDistanceTo(", ".distanceToSqr(")
			replace(".getUuid()", ".getUUID()")
			replace("client.getNetworkHandler()", "client.getConnection()")
			replace("client.getServer()", "client.getSingleplayerServer()")
			replace("client.crosshairTarget", "client.hitResult")
			replace("connection.getAveragePacketsSent()", "connection.getAverageSentPackets()")
			replace("connection.getAveragePacketsReceived()", "connection.getAverageReceivedPackets()")
			// IntegratedServer#getAverageTickTime() (ms, double) ->
			// #getAverageTickTimeNanos() (ns, long) - needs the unit
			// conversion inlined since this is a real shape change, not
			// just a rename.
			replace("integrated.getAverageTickTime()", "(integrated.getAverageTickTimeNanos() / 1_000_000.0)")
			// Registries.BLOCK/.ENTITY_TYPE became ResourceKey<Registry<T>>
			// references (no longer directly queryable registries) -
			// BuiltInRegistries holds the real usable DefaultedRegistry
			// instances now (verified via javap).
			replace("Registries.BLOCK.getId(", "BuiltInRegistries.BLOCK.getId(")
			replace("Registries.ENTITY_TYPE.getId(", "BuiltInRegistries.ENTITY_TYPE.getId(")
			replace("import net.minecraft.registry.Registries;", "import net.minecraft.core.registries.BuiltInRegistries;\nimport net.minecraft.core.registries.Registries;")
			// Level#getRegistryKey -> #dimension, #getRegistryManager ->
			// #registryAccess, ResourceKey#getValue -> #identifier
			// (verified via javap) - scoped as one chain since the
			// Identifier-extraction method name itself also changed.
			replace("getRegistryKey().getValue()", "dimension().identifier()")
			replace("getRegistryManager()", "registryAccess()")
			// LocalPlayer#sendMessage(Component, boolean) -> #
			// sendSystemMessage(Component) - the actionbar/chat distinction
			// isn't exposed on this call anymore; this codebase's one call
			// site always passed true (actionbar-style transient message).
			replace("player.sendMessage(net.minecraft.text.Text.literal(\"Copied: \" + text), true)",
					"player.sendSystemMessage(net.minecraft.network.chat.Component.literal(\"Copied: \" + text))")
			// KeyBindingHelper#registerKeyBinding -> KeyMappingHelper#
			// registerKeyMapping (verified via the real Fabric API repo),
			// Minecraft#keyboard field -> #keyboardHandler.
			replace("registerKeyBinding(", "registerKeyMapping(")
			replace("client.keyboard.", "client.keyboardHandler.")
			// KeyMapping-related renames, verified via javap.
			replace("getBoundKeyLocalizedText()", "getTranslatedKeyMessage()")
			replace("options.allKeys", "options.keyMappings")
			// Used for physical-key-conflict grouping - saveString()
			// (a stable string encoding of the current binding) serves the
			// same "group by what's physically bound" purpose the old
			// translation-key string did.
			replace("getBoundKeyTranslationKey()", "saveString()")
			replace("binding.getId()", "binding.getName()")
			replace("networkHandler.sendChatCommand(", "networkHandler.sendCommand(")
			replace(".getWindow().getHandle()", ".getWindow().handle()")
			// This codebase's "Minecraft.getInstance().textRenderer" call
			// sites actually read "MinecraftClient.getInstance()..." in the
			// real original source (the MinecraftClient->Minecraft bare
			// rule elsewhere doesn't include the trailing field/method
			// chain), same lesson as the client./this. receiver scoping
			// above.
			replace("MinecraftClient.getInstance().textRenderer.getWidth(", "Minecraft.getInstance().font.width(")
			replace("MinecraftClient.getInstance().textRenderer.fontHeight", "Minecraft.getInstance().font.lineHeight")
			// Bare (no .getWidth(/.fontHeight suffix) form of the same
			// chain - real original text confirmed via direct source grep
			// this time, not the compiler's (sometimes already-partially-
			// transformed-looking) error text.
			replace("MinecraftClient.getInstance().textRenderer", "Minecraft.getInstance().font")
			replace("MinecraftClient.getInstance().keyboard", "Minecraft.getInstance().keyboardHandler")
			replace("getRenderTickCounter().getDynamicDeltaTicks()", "getDeltaTracker().getGameTimeDeltaTicks()")
			// Util$OS#open(File) -> #openFile(File) (verified via javap -
			// several overloads now exist for URI/File/Path/String).
			// Scoped to the real Util.getPlatform() chain specifically -
			// this file ALSO calls the unrelated, real JDK method
			// java.awt.Desktop#open(File), which must not be touched.
			replace("Util.getOperatingSystem().open(dir)", "Util.getPlatform().openFile(dir)")
			// InputConstants.Type#createFromCode -> #getOrCreate (verified
			// via javap).
			replace(".createFromCode(", ".getOrCreate(")
			// Holder<MobEffect>#matches(Holder) -> #is(Holder) (verified
			// via javap) - scoped to this codebase's one usage.
			replace("effect.matches(", "effect.is(")
			// KeyBinding.Category -> KeyMapping.Category, KeyBinding#
			// updateKeysByCode -> KeyMapping#resetMapping - real original
			// text still says "KeyBinding" here (the bare KeyBinding->
			// KeyMapping rule elsewhere doesn't include these specific
			// trailing members).
			replace("KeyBinding.Category", "KeyMapping.Category")
			replace("KeyBinding.Category.create(", "KeyMapping.Category.register(")
			replace("KeyBinding.updateKeysByCode()", "KeyMapping.resetMapping()")
			// Real original text confirmed via direct source grep for each
			// of these (compiler error text was sometimes already-
			// transformed and misleading, as with several rules above).
			replace("StyledNumberFormat.RED", "StyledFormat.SIDEBAR_DEFAULT")
			replace("entry.name()", "entry.ownerName()")
			replace("options.attackKey", "options.keyAttack")
			replace("options.useKey", "options.keyUse")
			replace("world.getEntities()", "world.entitiesForRendering()")
			replace("client.particleManager", "client.particleEngine")
			replace("client.reloadResources()", "client.reloadResourcePacks()")
			replace("player.getMainHandStack()", "player.getMainHandItem()")
			replace("client.worldRenderer", "client.levelRenderer")
			// ScreenEvents.afterRender(screen) doesn't exist anymore - the
			// same event (confirmed via its real internal implementation,
			// fabric_getAfterRenderEvent()) is now exposed as afterExtract,
			// with an identical 5-arg callback shape.
			replace("ScreenEvents.afterRender(", "ScreenEvents.afterExtract(")
			// LevelRenderer#scheduleTerrainUpdate -> #needsUpdate,
			// #reload() (0-arg) -> #allChanged() (0-arg) in 26.1 specifically
			// - verified via javap. 26.2 drops both methods from
			// LevelRenderer entirely (real equivalent there is
			// levelExtractor.allChanged()) - this pair is handled with a
			// manual //? if block in PerformanceBoostModule.java instead of
			// here (see the long comment near the end of this file
			// explaining why).
			// Full chain, not just the suffix: "client.worldRenderer" (a
			// separate, shorter, earlier-starting rule elsewhere) would
			// otherwise win the position race and leave the trailing
			// method call unrenamed, same lesson as the font/textRenderer
			// fixes above.
			// Minecraft#targetedEntity -> #crosshairPickEntity,
			// GuiGraphicsExtractor's screen-size accessors dropped "get"/
			// "Window" (verified via javap).
			replace("client.targetedEntity", "client.crosshairPickEntity")
			replace("context.getScaledWindowWidth()", "context.guiWidth()")
			replace("context.getScaledWindowHeight()", "context.guiHeight()")
			// InputConstants.Key#getLocalizedText -> #getDisplayName,
			// KeyMapping#setBoundKey -> #setKey (verified via javap).
			replace(".getLocalizedText()", ".getDisplayName()")
			replace("binding.setBoundKey(", "binding.setKey(")
			// Objective#getNumberFormatOr(StyledFormat) ->
			// #numberFormatOrDefault(NumberFormat) - same arg, real param
			// type just widened to the NumberFormat interface.
			replace("getNumberFormatOr(", "numberFormatOrDefault(")
			// ParticleEngine#getDebugString -> #countParticles (closest
			// real equivalent debug-text accessor).
			replace("client.particleManager.getDebugString()", "client.particleEngine.countParticles()")
			// FullBrightMixin's @Inject targets (LivingEntity method names,
			// verified via javap AND by actually launching the 26.1 dev
			// client, which is what caught this - Mixin's string-based
			// method targets aren't checked by javac at all).
			replace("method = \"hasStatusEffect\"", "method = \"hasEffect\"")
			replace("method = \"getStatusEffect\"", "method = \"getEffect\"")
			// ClientConnectionMixin's @Inject method-descriptor strings use
			// JVM internal descriptor syntax (slash-separated, "L...;"),
			// which the dotted-path Packet rename above doesn't touch at
			// all - discovered by actually launching the 26.1 dev client
			// (Mixin only validates these strings at runtime, not javac).
			// Connection now also has an inherited two-overload
			// channelRead0 (an Object-typed one alongside the Packet-typed
			// one), so the bare "channelRead0" target is made explicit too
			// to avoid new ambiguity.
			replace("method = \"send(Lnet/minecraft/network/packet/Packet;)V\"",
					"method = \"send(Lnet/minecraft/network/protocol/Packet;)V\"")
			replace("method = \"channelRead0\"",
					"method = \"channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V\"")
			// MouseHandler#onMouseScroll -> #onScroll (verified by reading
			// the real decompiled 26.1 source directly - same (long, double,
			// double) param shape, just renamed).
			replace("method = \"onMouseScroll\"", "method = \"onScroll\"")
			// PlayerListHudMixin's @Redirect: its enclosing method "render"
			// is PlayerTabOverlay's own extractRenderState now, and the
			// INVOKE target it redirects (DrawContext#drawTextWithShadow)
			// moved wholesale to GuiGraphicsExtractor#text with Font/
			// Component's real packages - verified by actually launching
			// the 26.1 dev client, which is what caught every one of these
			// mixin descriptor strings (javac never validates them).
			replace("method = \"render\", at = @At(value = \"INVOKE\",", "method = \"extractRenderState\", at = @At(value = \"INVOKE\",")
			replace("target = \"Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V\"",
					"target = \"Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V\"")
			// Import line specifically (distinguished from ZoomModule's
			// inline, import-less usage by the "import "/";" bookends,
			// avoiding the same-source-different-target ambiguity that
			// would occur with a bare fully-qualified rule).
			replace("import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;",
					"import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;")
			// Minecraft FPS/GPU stats, LivingEntity's active-effects
			// collection, MobEffectInstance/MobEffect/EntityType display
			// accessors, Gui's effect-icon sprite lookup, Level chunk/
			// height/nbt accessors - all verified via javap.
			replace("client.getGpuUtilizationPercentage()", "client.getGpuUtilization()")
			replace("client.getCurrentFps()", "client.getFps()")
			replace(".getStatusEffects()", ".getActiveEffects()")
			// Gui#getMobEffectSprite's real target diverges per version too
			// (Gui in 26.1, a new Hud class in 26.2) - handled with a manual
			// //? if block in PotionTimersModule.java instead of here (see
			// the long comment near the end of this file explaining why).
			replace("effect.getEffectType().value().getName()", "effect.getEffect().value().getDisplayName()")
			replace("entry.getKey().getName()", "entry.getKey().getDescription()")
			replace("world.isChunkLoaded(", "world.hasChunk(")
			replace("Heightmap.Type", "Heightmap.Types")
			replace("world.getTopY(", "world.getHeight(")
			replace("world.getLightLevel(", "world.getBrightness(")
			replace("blockEntity.createNbt(", "blockEntity.saveWithFullMetadata(")
			replace("BuiltInRegistries.BLOCK.getId(", "BuiltInRegistries.BLOCK.getKey(")
			replace("BuiltInRegistries.ENTITY_TYPE.getId(", "BuiltInRegistries.ENTITY_TYPE.getKey(")
			// PolyBlur's post-processing shader pipeline (verified against
			// real vanilla GameRenderer.java, which loads this exact same
			// blur effect for the pause menu background).
			replace("net.minecraft.client.gl.PostEffectProcessor", "net.minecraft.client.renderer.PostChain")
			replace("net.minecraft.client.render.DefaultFramebufferSet", "net.minecraft.client.renderer.LevelTargetBundle")
			replace("net.minecraft.client.util.memory.ObjectAllocator", "com.mojang.blaze3d.resource.GraphicsResourceAllocator")
			replace("PostEffectProcessor", "PostChain")
			replace("client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY)",
					"client.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS)")
			// processor.render(...)'s target-fetching arg needs a different
			// form per version (Minecraft loses #getMainRenderTarget() in
			// 26.2, but GameRenderer keeps its own #mainRenderTarget() -
			// verified via javap) - handled with a manual //? if block in
			// PolyBlurModule.java instead (see the long comment near the
			// end of this file explaining why).
			// appendClickableNarrations(NarrationMessageBuilder) -> the
			// abstract hook AbstractWidget actually declares is
			// updateWidgetNarration(NarrationElementOutput); its own
			// .put(...) method is called .add(...) instead. Every one of
			// this codebase's 5 custom widgets uses the identical
			// signature/call shape, verified via javap against the real
			// AbstractWidget/NarrationElementOutput classes.
			replace("protected void appendClickableNarrations(NarrationMessageBuilder builder)",
					"protected void updateWidgetNarration(NarrationElementOutput builder)")
			replace("builder.put(", "builder.add(")
			// MinecraftClient#textRenderer (field) -> Minecraft#font -
			// scoped to the two receiver spellings this codebase actually
			// uses, same reasoning as the .getWidth()/.fontHeight scoping
			// above (NativeImage keeps unrelated method names that would
			// collide with a blind rule).
			replace("client.textRenderer", "client.font")
			replace("this.textRenderer", "this.font")
			// PositionedSoundInstance.ui(sound, pitch) -> the equivalent
			// simple/one-shot UI sound factory is SimpleSoundInstance.forUI
			// with the same 2-arg shape (verified via javap) - not the
			// generic PositionedSoundInstance->AbstractTickableSoundInstance
			// bare-type rename above, which is for a different (looping/
			// tickable) sound instance class entirely.
			replace("net.minecraft.client.sound.PositionedSoundInstance", "net.minecraft.client.resources.sounds.SimpleSoundInstance")
			replace("PositionedSoundInstance.ui(", "SimpleSoundInstance.forUI(")
			// AbstractWidget's own override hook: renderWidget ->
			// extractWidgetRenderState (mirrors the Screen-level
			// render -> extractRenderState rename above).
			replace("protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta)",
					"protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)")
			// Fabric API module restructuring for 26.1/26.2, verified
			// against the real FabricMC/fabric repo (default branch is
			// literally named "26.2") rather than guessed.
			replace("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper", "net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper")
			replace("KeyBindingHelper", "KeyMappingHelper")
			// ZoomModule uses the fully-qualified form (not imported) for
			// its BEFORE_ENTITIES->START_MAIN handler, whose real callback
			// takes LevelTerrainRenderContext specifically (verified
			// against the real Fabric API source) - different from the
			// three debug-overlay modules below, which import it bare and
			// use the plain LevelRenderContext (BEFORE_GIZMOS's real param
			// type). Scoped to ZoomModule's exact method declaration rather
			// than the bare fully-qualified name, since that name ALSO
			// appears in the other three files' import lines - a bare
			// scope would wrongly flip their import to
			// LevelTerrainRenderContext while their param stayed
			// LevelRenderContext (via the separate bare-name rule below),
			// an import/usage mismatch.
			replace("private void onFrame(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context)",
					"private void onFrame(net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext context)")
			replace("net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents", "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents")
			replace("WorldRenderContext", "LevelRenderContext")
			// BEFORE_DEBUG_RENDER doesn't exist in the new event set - the
			// three modules that use it are pure debug/visualization
			// overlays (world border, light levels, waypoints) drawn via
			// the Gizmos API, and BEFORE_GIZMOS is the semantically exact
			// replacement hook for that. ZoomModule's BEFORE_ENTITIES use
			// never actually touches its context parameter (it's a pure
			// per-frame FOV-animation timing hook) - START_MAIN (earliest
			// hook in the new event set) serves the same purpose.
			replace("WorldRenderEvents.BEFORE_DEBUG_RENDER", "LevelRenderEvents.BEFORE_GIZMOS")
			replace("WorldRenderEvents.BEFORE_ENTITIES", "LevelRenderEvents.START_MAIN")
			replace("WorldRenderEvents", "LevelRenderEvents")
			// The import itself becomes unused once the constant above is
			// inlined - repointed at a real class instead of deleted so it
			// stays valid Java (an unused import is a warning, not an
			// error; deleting the line via text replacement isn't possible
			// with this tool). Points at Util (not ARGB) specifically to
			// avoid an ambiguous-replacement error from two different
			// source strings both targeting "net.minecraft.util.ARGB".
			replace("net.minecraft.util.Colors", "net.minecraft.util.Util")
			replace("net.minecraft.world.border.WorldBorder", "net.minecraft.world.level.border.WorldBorder")
			// GizmoDrawing was split into a static Gizmos facade with
			// per-shape methods (verified via javap against the real 26.1
			// jar, not guessed) - box->cuboid, blockLabel->
			// billboardTextOverBlock (same 5-arg shape: String, BlockPos,
			// int, int, float), point unchanged. DrawStyle.stroked ->
			// GizmoStyle.stroke. ignoreOcclusion() has no direct
			// equivalent on the new GizmoProperties - setAlwaysOnTop() is
			// the closest (draws through terrain), though not perfectly
			// identical semantics; worth a visual check later.
			replace("net.minecraft.world.debug.gizmo.GizmoDrawing", "net.minecraft.gizmos.Gizmos")
			replace("GizmoDrawing.box(", "Gizmos.cuboid(")
			replace("GizmoDrawing.blockLabel(", "Gizmos.billboardTextOverBlock(")
			replace("GizmoDrawing.point(", "Gizmos.point(")
			replace(".ignoreOcclusion()", ".setAlwaysOnTop()")
			replace("DrawStyle.stroked(", "GizmoStyle.stroke(")
			replace("net.minecraft.client.render.DrawStyle", "net.minecraft.gizmos.GizmoStyle")
			replace("DrawStyle", "GizmoStyle")
			replace("net.minecraft.world.Heightmap", "net.minecraft.world.level.levelgen.Heightmap")
			replace("net.minecraft.world.LightType", "net.minecraft.world.level.LightLayer")

			// --- Bare simple-name renames (for code that refers to the type
			// unqualified after import). Each of these is either a distinct
			// enough word on its own, or - where it could appear inside a
			// longer unrelated identifier - only ever appears in this
			// codebase as itself (verified by grep, not assumed), so
			// there's nothing for it to corrupt.
			replace("MinecraftClient", "Minecraft")
			replace("DrawContext", "GuiGraphicsExtractor")
			replace("ClickableWidget", "AbstractWidget")
			replace("TextFieldWidget", "EditBox")
			replace("TextRenderer", "Font")
			replace("NarrationMessageBuilder", "NarrationElementOutput")
			replace("NarrationPart", "NarratedElementType")
			replace("ClientPlayNetworkHandler", "ClientPacketListener")
			replace("PlayerListEntry", "PlayerInfo")
			// Bare "PlayerListHud"/"ClientConnection" are unsafe here: this
			// codebase's own mixin class names are "PlayerListHudMixin" and
			// "ClientConnectionMixin" (unrelated to the vanilla rename, just
			// named after their target) - the file itself never gets
			// renamed, so a bare-word swap corrupting the public class name
			// to no longer match its filename is a compile error ("class
			// PlayerTabOverlayMixin is public, should be declared in a file
			// named PlayerTabOverlayMixin.java"). Scoped to the one real
			// usage shape (@Mixin target) instead.
			replace("PlayerListHud.class", "PlayerTabOverlay.class")
			// Shield this codebase's own "ClientConnectionMixin" class name
			// (self-mapped, longer match at the same start position wins
			// over the bare "ClientConnection" rule below) so the bare rule
			// can safely handle every OTHER real usage (e.g.
			// TpsTickGraphModule's "ClientConnection connection = ..."
			// declaration).
			replace("ClientConnectionMixin", "ClientConnectionMixin")
			replace("ClientConnection", "Connection")
			replace("InGameHud", "Gui")
			replace("GameOptions", "Options")
			replace("KeyBinding", "KeyMapping")
			replace("InputUtil", "InputConstants")
			replace("ClientWorld", "ClientLevel")
			replace("PlayerEntityRenderState", "PlayerRenderState")
			replace("PlayerEntity", "Player")
			replace("NativeImageBackedTexture", "DynamicTexture")
			replace("SoundCategory", "SoundSource")
			replace("MutableText", "MutableComponent")
			replace("Vec3d", "Vec3")
			replace("ScoreboardObjective", "Objective")
			replace("ScoreboardEntry", "PlayerScoreEntry")
			replace("ScoreboardDisplaySlot", "DisplaySlot")
			replace("AbstractTeam", "Team")
			replace("GameMenuScreen", "PauseScreen")
			// Bare "Mouse" is unsafe (collides with our own MouseScrollMixin/
			// MouseButtonsModule class names - and MouseScrollMixin's own
			// name has to stay put, since velo-client.client.mixins.json
			// references it by string and isn't run through this
			// preprocessor) - scoped to how the type is actually used here
			// (import + "Mouse.class" mixin target) instead.
			replace("Mouse.class", "MouseHandler.class")
			replace("StatusEffectInstance", "MobEffectInstance")
			replace("StatusEffects", "MobEffects")
			replace("StatusEffect", "MobEffect")
			replace("RegistryEntry", "Holder")
			replace("RenderTickCounter", "DeltaTracker")
			replace("StyledNumberFormat", "StyledFormat")
			replace("Box", "AABB")
			replace("LightType", "LightLayer")
			replace("CloudRenderMode", "CloudStatus")
			replace("GraphicsMode", "GraphicsPreset")
			replace("ParticlesMode", "ParticleStatus")
			replace("EntityRendererFactory", "EntityRendererProvider")
			replace("FeatureRendererContext", "RenderLayerParent")
			// Bare "FeatureRenderer" would also corrupt this codebase's own
			// "CapeFeatureRenderer" class name (same class of bug as the
			// PlayerListHud/ClientConnection mixins above) - scoped to the
			// two real usage shapes instead (extends clause, generic type
			// argument).
			replace("extends FeatureRenderer<", "extends RenderLayer<")
			replace("FeatureRenderer<", "RenderLayer<")
			replace("EntityModelLayers", "ModelLayers")
			replace("RenderLayers", "RenderType")
			replace("SoundInstanceListener", "SoundEventListener")
			replace("WeightedSoundSet", "WeighedSoundEvents")
			replace("MatrixStack", "Matrix3x2fStack")

			// --- Method/member renames. Each source pattern is either its
			// own unique compound (safe on its own) or explicitly scoped to
			// the receiver variable name(s) actually used in this codebase,
			// so it can't also match the *NativeImage* methods of the same
			// short name (see the getWidth/getHeight note below).
			replace("drawTextWithShadow(", "text(")
			replace("drawCenteredTextWithShadow(", "centeredText(")
			replace("drawGuiTexture(", "blitSprite(")
			replace("drawTexture(", "blit(")
			replace(".getColorArgb(", ".getPixel(")
			replace(".setColorArgb(", ".setPixel(")
			replace(".styled(", ".withStyle(")
			replace(".writeTo(", ".writeToFile(")
			replace("registerTexture(", "register(")
			replace("destroyTexture(", "release(")
			replace(".getMatrices(", ".pose(")
			// TextRenderer#getWidth/#fontHeight -> Font#width/#lineHeight -
			// NativeImage keeps getWidth()/getHeight() unchanged in 26.1
			// (verified via javap), so this can't be a bare ".getWidth("
			// rule - it's scoped to the exact receiver names this codebase
			// actually uses for the text renderer/font field.
			replace("textRenderer.getWidth(", "textRenderer.width(")
			replace("textRenderer.fontHeight", "textRenderer.lineHeight")
			replace("renderer.getWidth(", "renderer.width(")
			replace("renderer.fontHeight", "renderer.lineHeight")

			// --- Shields: real vanilla names that are unchanged in 26.1 but
			// contain "Text" as a substring (e.g. inside "Texture") and
			// would otherwise be corrupted by the bare "Text" rule below,
			// since nothing else "claims" that span of text to protect it.
			replace("getTextureManager", "getTextureManager")
			replace("getEffectTexture", "getEffectTexture")
			replace("OverlayTexture", "OverlayTexture")
			// ClientAsset.ResourceTexture (LocalPlayerSkinCapeMixin's 26.1+
			// branch, used to build a substitute cape texture asset).
			replace("ResourceTexture", "ResourceTexture")

			// --- The generic renames. Deliberately last and deliberately
			// narrow: by this point every real vanilla identifier that
			// contains "Text"/"Click" as a substring has already been
			// consumed above by a longer/earlier match, so what's left for
			// these two bare rules to touch is only genuine standalone
			// "Text"/"Click" type references (plus this codebase's own
			// identifiers that happen to contain them, which is harmless -
			// a consistently-renamed private field/method still compiles).
			replace("Click click", "MouseButtonEvent click")
			// KeyInput (Yarn) doesn't exist at all in 26.1 - keyPressed's
			// single-object param type is called KeyEvent instead, same
			// package. Used inline/fully-qualified rather than imported in
			// this codebase, which is why it wasn't caught by scanning
			// import lines - a reminder there could be other inline
			// fully-qualified references like it.
			replace("net.minecraft.client.input.KeyInput", "net.minecraft.client.input.KeyEvent")
			replace("Text", "Component")
		}

		// --- IMPORTANT: rules that are correct for 26.1 but need a DIFFERENT
		// target in 26.2 CANNOT be expressed as a bounded
		// "current.parsed >= X && current.parsed < Y" condition here, even
		// though that seems like the obvious approach. Confirmed by a real,
		// silent-corruption bug: Stonecutter generates each subproject via a
		// sequential chain of diffs across the configured version order
		// (1.21.11 -> 26.1 -> 26.2), not independently per version. A rule
		// that's true at the 26.1 step and false at the 26.2 step produces a
		// REVERSE diff at that transition, applied wherever its TARGET text
		// happens to appear in 26.1's generated output - including
		// completely unrelated code. This corrupted
		// "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents" (an
		// unrelated Fabric API import, own package name coincidentally
		// containing ".screen") into "...client.currentScreen.v1..." when a
		// bounded ".currentScreen" -> ".screen" rule's target text ".screen"
		// got reverse-matched going into 26.2. Every rule below that
		// genuinely diverges between 26.1 and 26.2 is handled with a manual
		// //? if <26.1 { } else if <26.2 { } else { } block in its own
		// source file instead (same proven-safe mechanism already used for
		// CapeFeatureRenderer.java/GameRendererFovMixin.java's structural
		// splits) - see HudManager.java, PerformanceBoostModule.java,
		// PotionTimersModule.java, PolyBlurModule.java, and the six
		// Minecraft#screen (Yarn: currentScreen) read sites.

		// --- 26.2-only deltas from 26.1, each verified via javap against the
		// real 26.2 client-only/common jars (not guessed - 26.2 changes a
		// surprising amount on its own beyond the 1.21.11->26.1 jump: Gui
		// itself split into a screen-management Gui + a rendering Hud class,
		// Options.hideGui/hudHidden disappeared entirely, EntityType's own
		// constant fields (PLAYER etc.) moved to a new EntityTypes class,
		// Items.LIGHTNING_ROD became a WeatheringCopperCollection<Item>
		// instead of a plain Item, and LevelRenderer#needsUpdate/#allChanged
		// don't exist anymore - the real call vanilla's own
		// VideoSettingsScreen makes on an option change is
		// Minecraft#levelExtractor.allChanged(), confirmed by javap -c on
		// the real Options/VideoSettingsScreen classes).
		string(current.parsed >= "26.2") {
			// Minecraft#screen/#setScreen(Screen) moved onto a new Gui field
			// (Minecraft#gui) as #screen()/#setScreen(Screen) - verified via
			// javap. IMPORTANT: this is deliberately a SUFFIX-only rule
			// (".setScreen(", not "this.client.setScreen(" or
			// "client.setScreen(") so its match never starts at the same
			// position as the shared, unbounded "this.client"->
			// "this.minecraft" rule. A same-start compound rule here
			// (tried first, reverted) statically wins that shielding contest
			// over the shorter rule REGARDLESS of whether its own condition
			// is true for the version actually being built - so on 26.1
			// (where >=26.2 is false) the longer rule "claims" the span but
			// never fires, and the shorter, correctly-true "this.client"
			// rule never gets a chance either, leaving literal
			// "this.client.setScreen(" in 26.1's output (a real, confirmed
			// bug, not a hypothetical). A suffix rule starting right after
			// "this.client"/"client" ends has no such overlap, so both
			// rules apply independently and correctly.
			replace(".setScreen(", ".gui.setScreen(")
			// ActionBarOverlayMixin/PotionTimersModule's InGameHud.class and
			// import-line usages hit the exact same same-start-position
			// shielding trap as setScreen above (both start exactly where
			// the shared bare "InGameHud"->"Gui" rule starts, and a longer,
			// false-for-26.1 rule there would statically shadow it) -
			// handled with manual //? if blocks in those two files instead
			// of here (see the long comment near the end of this file
			// explaining the general pattern).
			// EntityType's own constant fields (PLAYER, ZOMBIE, etc.) were
			// pulled out into a separate EntityTypes class in 26.2 -
			// verified via javap (EntityType.class itself has zero "static
			// final EntityType" fields left; they all live on the new
			// EntityTypes class instead). Fully-qualified so no import
			// juggling is needed inside CapeFeatureRenderer.java's existing
			// //? if/else branches.
			replace("entityType != EntityType.PLAYER", "entityType != net.minecraft.world.entity.EntityTypes.PLAYER")
			// Items.LIGHTNING_ROD's real type changed from a plain Item to a
			// WeatheringCopperCollection<Item> in 26.2 (the block gained a
			// weathering/waxed state family) - verified via javap.
			// .weathering().unaffected() is the unweathered/default Item,
			// matching what a plain Items.LIGHTNING_ROD reference meant
			// before.
			replace("Items.LIGHTNING_ROD", "Items.LIGHTNING_ROD.weathering().unaffected()")
		}
	}
}
