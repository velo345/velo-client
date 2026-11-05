package net.veloclient.velo.client.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.VeloClient;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Velo replaces Mod Menu: mods hand their settings screens to Mod Menu through its small API (a {@code "modmenu"}
 * entrypoint implementing {@code com.terraformersmc.modmenu.api.ModMenuApi}). Without Mod Menu installed those classes
 * don't exist, so the entrypoints can't even load. This writes the same API - interfaces only, with Mod Menu's default
 * methods - into a folder and adds it to the game's class path, so Velo's Mods page can open every mod's settings.
 *
 * <p>The classes are generated at start (ASM) with the game's runtime class names for Screen and Text, so the same
 * code works on every Minecraft version. With the real Mod Menu installed this does nothing at all.
 */
public final class ModMenuApiShim {

	private static final String PKG = "com/terraformersmc/modmenu/api/";
	private static boolean installed;

	private ModMenuApiShim() {
	}

	/** True when mods' Mod Menu integrations can be loaded (real Mod Menu or this shim). */
	public static boolean available() {
		return installed || FabricLoader.getInstance().isModLoaded("modmenu");
	}

	public static synchronized void install() {
		if (installed || FabricLoader.getInstance().isModLoaded("modmenu")) {
			return;
		}
		try {
			String screen = Screen.class.getName().replace('.', '/');
			String text = Text.class.getName().replace('.', '/');
			String sd = "L" + screen + ";";
			Path dir = FabricLoader.getInstance().getGameDir().resolve(".velo").resolve("modmenu-api");
			write(dir, "ConfigScreenFactory", factory(sd));
			write(dir, "UpdateChannel", updateChannel());
			write(dir, "UpdateInfo", updateInfo());
			write(dir, "UpdateChecker", updateChecker());
			write(dir, "ModMenuApi", api(sd, "L" + text + ";"));
			net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(dir);
			installed = true;
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client ->
					VeloClient.LOGGER.info("Velo Mods: Mod Menu API served by Velo - {} mods bring a settings screen", ModConfigs.settingsCount()));
		} catch (Throwable e) {
			VeloClient.LOGGER.warn("Velo: couldn't provide the Mod Menu API - mods' settings buttons stay hidden", e);
		}
	}

	private static void write(Path dir, String name, byte[] bytes) throws java.io.IOException {
		Path file = dir.resolve(PKG + name + ".class");
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static ClassWriter iface(String name) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, PKG + name, null, "java/lang/Object", null);
		return cw;
	}

	/** {@code interface ConfigScreenFactory<S extends Screen> { S create(Screen parent); }} */
	private static byte[] factory(String sd) {
		ClassWriter cw = iface("ConfigScreenFactory");
		cw.visitAnnotation("Ljava/lang/FunctionalInterface;", true).visitEnd();
		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "create", "(" + sd + ")" + sd, null, null).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] updateChecker() {
		ClassWriter cw = iface("UpdateChecker");
		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "checkForUpdates", "()L" + PKG + "UpdateInfo;", null, null).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] updateInfo() {
		ClassWriter cw = iface("UpdateInfo");
		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "isUpdateAvailable", "()Z", null, null).visitEnd();
		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getDownloadLink", "()Ljava/lang/String;", null, null).visitEnd();
		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getUpdateChannel", "()L" + PKG + "UpdateChannel;", null, null).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Mod Menu's update channel: a plain class with the three constants (only ever compared by mods). */
	private static byte[] updateChannel() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		String self = PKG + "UpdateChannel";
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, self, null, "java/lang/Object", null);
		for (String c : new String[] {"ALPHA", "BETA", "RELEASE"}) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, c, "L" + self + ";", null, null).visitEnd();
		}
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		cl.visitCode();
		for (String c : new String[] {"ALPHA", "BETA", "RELEASE"}) {
			cl.visitTypeInsn(Opcodes.NEW, self);
			cl.visitInsn(Opcodes.DUP);
			cl.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "<init>", "()V", false);
			cl.visitFieldInsn(Opcodes.PUTSTATIC, self, c, "L" + self + ";");
		}
		cl.visitInsn(Opcodes.RETURN);
		cl.visitMaxs(0, 0);
		cl.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * {@code interface ModMenuApi} with Mod Menu's defaults; its static helpers open Velo's Mods page instead
	 * ({@link ModConfigs#openModsScreen}).
	 */
	private static byte[] api(String sd, String td) {
		ClassWriter cw = iface("ModMenuApi");
		String factory = "L" + PKG + "ConfigScreenFactory;";
		defaultReturnNull(cw, "getModConfigScreenFactory", "()" + factory);
		defaultReturnNull(cw, "getUpdateChecker", "()L" + PKG + "UpdateChecker;");
		defaultEmptyMap(cw, "getProvidedConfigScreenFactories");
		defaultEmptyMap(cw, "getProvidedUpdateCheckers");
		MethodVisitor badges = cw.visitMethod(Opcodes.ACC_PUBLIC, "attachModpackBadges", "(Ljava/util/function/Consumer;)V", null, null);
		badges.visitCode();
		badges.visitInsn(Opcodes.RETURN);
		badges.visitMaxs(0, 0);
		badges.visitEnd();
		String bridge = ModConfigs.class.getName().replace('.', '/');
		MethodVisitor open = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createModsScreen", "(" + sd + ")" + sd, null, null);
		open.visitCode();
		open.visitVarInsn(Opcodes.ALOAD, 0);
		open.visitMethodInsn(Opcodes.INVOKESTATIC, bridge, "openModsScreen", "(" + sd + ")" + sd, false);
		open.visitInsn(Opcodes.ARETURN);
		open.visitMaxs(0, 0);
		open.visitEnd();
		MethodVisitor label = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createModsButtonText", "()" + td, null, null);
		label.visitCode();
		label.visitMethodInsn(Opcodes.INVOKESTATIC, bridge, "modsButtonText", "()" + td, false);
		label.visitInsn(Opcodes.ARETURN);
		label.visitMaxs(0, 0);
		label.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void defaultReturnNull(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void defaultEmptyMap(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/util/Map;", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
}
