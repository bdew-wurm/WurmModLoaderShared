package org.gotti.wurmunlimited.modloader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.dependency.DependencyProvider;
import org.gotti.wurmunlimited.modloader.dependency.DependencyResolver;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.ModEntry;
import org.gotti.wurmunlimited.modloader.interfaces.ModListener;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.Versioned;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.Loader;
import javassist.NotFoundException;
import javassist.Translator;

public abstract class ModLoaderShared<T extends Versioned> {
	
	private static Logger logger = Logger.getLogger(ModLoaderShared.class.getName());
	
	private class Entry implements ModEntry<T>, DependencyProvider {
		private T mod;
		private Properties properties;
		private String name;
		public Entry(T mod, Properties properties, String name) {
			this.mod = mod;
			this.properties = properties;
			this.name = name;
		}
		@Override
		public String getName() {
			return name;
		}
		@Override
		public Properties getProperties() {
			return properties;
		}
		@Override
		public T getWurmMod() {
			return mod;
		}
		@Override
		public Collection<String> getRequires() {
			return parseList(properties.getProperty("depend.requires", ""));
		}
		@Override
		public Collection<String> getConflicts() {
			return parseList(properties.getProperty("depend.conflicts", ""));
		}
		@Override
		public Collection<String> getBefore() {
			return parseList(properties.getProperty("depend.suggests", ""));
		}
		@Override
		public Collection<String> getAfter() {
			return parseList(properties.getProperty("depend.precedes", ""));
		}
		
		private List<String> parseList(String list) {
			return Arrays.stream(list.split(","))
					.map(String::trim)
					.filter(string -> !string.isEmpty())
					.collect(Collectors.toList());
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
		final List<Entry> unorderedMods = new ArrayList<Entry>();
		
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
				try (EarlyLoadingChecker c = initEarlyLoadingChecker(modInfo.getFileName().toString().replaceAll("\\.properties$", ""), "load")) {
					Entry mod = loadModFromInfo(modInfo);
					
					mod.getProperties().put("steamVersion", steamVersion);
					unorderedMods.add(mod);
				}
			}
		}
		
		List<Entry> mods = new DependencyResolver<Entry>().provided(Collections.singleton(modLoaderProvided)).order(unorderedMods);
		
		// new style mods with initable will do configure, preInit, init
		mods.stream().filter(modEntry -> (modEntry.mod instanceof Initable || modEntry.mod instanceof PreInitable) && modEntry.mod instanceof Configurable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = initEarlyLoadingChecker(modEntry.name, "configure")) {
				((Configurable) modEntry.mod).configure(modEntry.properties);
				}
			});

		try (EarlyLoadingChecker c = initEarlyLoadingChecker("ModComm", "init")) {
			modcommInit();
		}

		mods.stream().filter(modEntry -> modEntry.mod instanceof PreInitable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = initEarlyLoadingChecker(modEntry.name, "preinit")) {
				((PreInitable)modEntry.mod).preInit();
				}
			});

		preInit();

		mods.stream().filter(modEntry -> modEntry.mod instanceof Initable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = initEarlyLoadingChecker(modEntry.name, "init")) {
				((Initable)modEntry.mod).init();
				}
			});

		init();

		// old style mods without initable or preinitable will just be configured, but they are handled last
		mods.stream().filter(modEntry -> !(modEntry.mod instanceof Initable || modEntry.mod instanceof PreInitable) && modEntry.mod instanceof Configurable).forEach(modEntry -> {
			try (EarlyLoadingChecker c = initEarlyLoadingChecker(modEntry.name, "configure")) {
				((Configurable) modEntry.mod).configure(modEntry.properties);
				}
			});

		mods.stream().forEach(modEntry -> {
			String implementationVersion = modEntry.mod.getVersion();
			if (implementationVersion == null || implementationVersion.isEmpty()) {
				implementationVersion = "unversioned";
			}
			logger.info(String.format("Loaded %1$s as %2$s (%3$s)", modEntry.mod.getClass().getName(),  modEntry.name, implementationVersion));
		});
		
		// Send the list of initialized mods to all modlisteners
		mods.stream().filter(modEntry -> modEntry.mod instanceof ModListener).forEach(modEntry -> {
			try (EarlyLoadingChecker c = initEarlyLoadingChecker(modEntry.name, "modListener")) {
				mods.stream().forEach(mod -> ((ModListener)modEntry.mod).modInitialized(mod));
				}
			});
		
		return mods.stream().map(modEntry -> modEntry.mod).collect(Collectors.toList());
	}

	public Entry loadModFromInfo(Path modInfo) throws IOException {
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
		
		final String className = properties.getProperty("classname");
		if (className == null) {
			throw new IOException("Missing property classname for mod " + modInfo);
		}
		
		try {
			Loader loader = HookManager.getInstance().getLoader();
			final String classpath = properties.getProperty("classpath");

			final ClassLoader classloader;
			if (classpath != null) {
				classloader = createClassLoader(modname, classpath, loader, Boolean.valueOf(properties.getProperty("sharedClassLoader", "false")));
			} else {
				classloader = loader;
			}

			T mod = classloader.loadClass(className).asSubclass(modClass).newInstance();
			return new Entry(mod, properties, modname);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NotFoundException e) {
			throw new IOException(e);
		}
	}
	
	private List<Path> getClassLoaderEntries(String modname, String classpath) {
		List<Path> pathEntries = new ArrayList<>();
		
		String[] entries = classpath.split(",");
		for (String entry : entries) {
			Path modPath = Paths.get("mods", modname);
			
			FileSystem fs = modPath.getFileSystem();
			final PathMatcher matcher = fs.getPathMatcher("glob:" + entry);
			SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
				
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Path p = modPath.relativize(file);
					if (matcher.matches(p)) {
						pathEntries.add(file);
					}
					return FileVisitResult.CONTINUE;
				}
			};
			
			try {
				Files.walkFileTree(modPath, visitor);
			} catch (IOException e) {
				throw new HookException(e);
			}
		}
		
		return pathEntries;
	}
	
	private ClassLoader createClassLoader(String modname, String classpath, Loader parent, Boolean shared) throws MalformedURLException, NotFoundException {
		List<Path> pathEntries = getClassLoaderEntries(modname, classpath);
		logger.log(Level.INFO, "Classpath: " + pathEntries.toString());
		
		if (shared) {
			final ClassPool classPool = HookManager.getInstance().getClassPool();
			for (Path path : pathEntries) {
				classPool.appendClassPath(path.toString());
			}
			return parent;
		} else {
			List<URL> urls = new ArrayList<>();
			for (Path path : pathEntries) {
				urls.add(path.toUri().toURL());
			}
			return new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
		}
	}
	
	private interface EarlyLoadingChecker extends Closeable {
		@Override
		public void close();
	}
	
	private EarlyLoadingChecker initEarlyLoadingChecker(String modname, String phase) {

		AddToListTranslator translator = new AddToListTranslator(paramString -> paramString.startsWith("com.wurmonline.") && !paramString.endsWith("Exception"));
		
		try {
			HookManager.getInstance().getLoader().addTranslator(HookManager.getInstance().getClassPool(), translator);
			
			return new EarlyLoadingChecker() {
				
				@Override
				public void close() {
					
					for (String classname : translator.getLoadedClasses()) {
						logger.log(Level.WARNING, String.format("Mod %1$s loaded server class %3$s during phase %2$s", modname, phase, classname));
					}
					
					try {
						HookManager.getInstance().getLoader().addTranslator(HookManager.getInstance().getClassPool(), NOOP_TRANSLATOR);
					} catch (CannotCompileException | NotFoundException e) {
					}
				}
			};
		} catch (CannotCompileException | NotFoundException e) {
			throw new RuntimeException(e);
		}
		
	}
	
	private final static Translator NOOP_TRANSLATOR = new Translator() {
		@Override
		public void start(ClassPool paramClassPool) throws NotFoundException, CannotCompileException {
		}
		
		@Override
		public void onLoad(ClassPool paramClassPool, String paramString) throws NotFoundException, CannotCompileException {
		}
	};
	
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
