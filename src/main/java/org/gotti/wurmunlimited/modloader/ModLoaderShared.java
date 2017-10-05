package org.gotti.wurmunlimited.modloader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.dependency.DependencyResolver;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.ModEntry;
import org.gotti.wurmunlimited.modloader.interfaces.ModListener;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.Versioned;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.NotFoundException;

public abstract class ModLoaderShared<T extends Versioned> {
	
	private static Logger logger = Logger.getLogger(ModLoaderShared.class.getName());
	
	private class Entry extends ModInfo implements ModEntry<T> {
		
		T mod;
		
		public Entry(T mod, Properties properties, String name) {
			super(properties, name);
			this.mod = mod;
		}
		
		@Override
		public T getWurmMod() {
			return mod;
		}
	}
	
	private Class<? extends T> modClass;
	
	public ModLoaderShared(Class<? extends T> modClass) {
		this.modClass = modClass;
	}

	protected abstract void modcommInit();
	protected abstract void preInit();
	protected abstract void init();

	public List<T> loadModsFromModDir(Path modDir) throws IOException {
		final List<ModInfo> unorderedMods = new ArrayList<>();
		
		final String version = this.getClass().getPackage().getImplementationVersion();
		logger.info(String.format("ModLoader version %1$s", version));
		
		String modLoaderProvided = "modloader";
		if (version != null && !version.isEmpty()) {
			modLoaderProvided += "@" + version;
		}
		
		String steamVersion = getSteamVersion();

		try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(modDir, "*.properties")) {
			for (Path modInfo : directoryStream) {
				logger.log(Level.INFO, "Loading " + modInfo.toString());
				try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modInfo.getFileName().toString().replaceAll("\\.properties$", ""), "load")) {
					ModInfo mod = loadModFromInfo(modInfo);
					
					mod.getProperties().put("steamVersion", steamVersion);
					unorderedMods.add(mod);
				}
			}
		}
		
		ModInstanceBuilder<T> entryBuilder = new ModInstanceBuilder<T>(modClass);
		List<Entry> mods = new DependencyResolver<ModInfo>().provided(Collections.singleton(modLoaderProvided)).order(unorderedMods).stream().map(modInfo -> new Entry(entryBuilder.createModInstance(modInfo), modInfo.getProperties(), modInfo.getName())).collect(Collectors.toList());
		
		// new style mods with initable will do configure, preInit, init
		mods.stream().filter(modEntry -> (modEntry.mod instanceof Initable || modEntry.mod instanceof PreInitable) && modEntry.mod instanceof Configurable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modEntry.getName(), "configure")) {
				((Configurable) modEntry.mod).configure(modEntry.getProperties());
				}
			});

		try (EarlyLoadingChecker c = EarlyLoadingChecker.init("ModComm", "init")) {
			modcommInit();
		}

		mods.stream().filter(modEntry -> modEntry.mod instanceof PreInitable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modEntry.getName(), "preinit")) {
				((PreInitable)modEntry.mod).preInit();
				}
			});

		preInit();

		mods.stream().filter(modEntry -> modEntry.mod instanceof Initable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modEntry.getName(), "init")) {
				((Initable)modEntry.mod).init();
				}
			});

		init();

		// old style mods without initable or preinitable will just be configured, but they are handled last
		mods.stream().filter(modEntry -> !(modEntry.mod instanceof Initable || modEntry.mod instanceof PreInitable) && modEntry.mod instanceof Configurable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modEntry.getName(), "configure")) {
				((Configurable) modEntry.mod).configure(modEntry.getProperties());
				}
			});

		mods.stream().forEach(modEntry -> {
			String implementationVersion = modEntry.mod.getVersion();
			if (implementationVersion == null || implementationVersion.isEmpty()) {
				implementationVersion = "unversioned";
			}
			logger.info(String.format("Loaded %1$s as %2$s (%3$s)", modEntry.mod.getClass().getName(),  modEntry.getName(), implementationVersion));
		});
		
		// Send the list of initialized mods to all modlisteners
		mods.stream().filter(modEntry -> modEntry.mod instanceof ModListener).forEach(modEntry -> {
			try (EarlyLoadingChecker c = EarlyLoadingChecker.init(modEntry.getName(), "modListener")) {
				mods.stream().forEach(mod -> ((ModListener)modEntry.mod).modInitialized(mod));
				}
			});
		
		return mods.stream().map(modEntry -> modEntry.mod).collect(Collectors.toList());
	}
	
	public ModInfo loadModFromInfo(Path modInfo) throws IOException {
		Properties properties = new Properties();

		try (InputStream inputStream = Files.newInputStream(modInfo)) {
			properties.load(inputStream);
		}

		String modname = modInfo.getFileName().toString().replaceAll("\\.properties$", "");
		
		Path configFile = Paths.get("mods", modname + ".config");
		if (Files.exists(configFile)) {
			try (InputStream inputStream = Files.newInputStream(configFile)) {
				logger.log(Level.INFO, "Loading " + configFile.toString());
				properties.load(inputStream);
			}
		}
		
		return new ModInfo(properties, modname);
	}
	
	private String getSteamVersion() {
		final ClassPool classPool = HookManager.getInstance().getClassPool();
		
		try (DefrostingClassLoader loader = new DefrostingClassLoader(classPool)) {
			final Class<?> clazz = loader.loadClass("com.wurmonline.shared.constants.SteamVersion");
			final Method getCurrentVersion = ReflectionUtil.getMethod(clazz, "getCurrentVersion");
			return getCurrentVersion.invoke(clazz).toString();
		} catch (NotFoundException | CannotCompileException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new HookException(e);
		}
	}
}
