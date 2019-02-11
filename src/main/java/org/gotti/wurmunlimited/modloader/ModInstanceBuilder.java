package org.gotti.wurmunlimited.modloader;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

import javassist.Loader;
import javassist.NotFoundException;

/**
 * Helper which creates mod instance from a mod class.
 *
 * @param <T>
 */
class ModInstanceBuilder<T> {

	private static Logger logger = Logger.getLogger(ModInstanceBuilder.class.getName());

	private Map<String, ClassLoader> classLoaders = new HashMap<>();
	private Class<? extends T> modClass;

	public ModInstanceBuilder(Class<? extends T> modClass) {
		this.modClass = modClass;
	}

	T createModInstance(ModInfo entry) {

		Properties properties = entry.getProperties();
		String modInfo = entry.getName();
		final String className = properties.getProperty("classname");
		if (className == null) {
			throw new HookException("Missing property classname for mod " + modInfo);
		}

		try {
			ClassLoader loader = HookManager.getInstance().getSharedLoader();
			final String classpath = entry.classPath();

			if (classpath != null && !entry.isSharedClassLoader()) {
				ClassLoader[] dependencies = entry.getImport().stream().map(classLoaders::get).filter(Objects::nonNull).toArray(ClassLoader[]::new);
				loader = createClassLoader(entry.getName(), classpath, loader, dependencies);
				classLoaders.put(entry.getName(), loader);
			}

			return loader.loadClass(className).asSubclass(modClass).newInstance();
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NotFoundException | MalformedURLException e) {
			throw new HookException(e);
		}
	}

	List<Path> getClassLoaderEntries(String modname, String classpath) {
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

	/**
	 * Create the classloader
	 * 
	 * @param modname
	 *            Mod name
	 * @param classpath
	 *            Classpath entries
	 * @param loader
	 *            Parent loader
	 * @param dependencies
	 *            Dependencies to import when NOT using a shared classloader
	 * @return Classloader
	 * @throws MalformedURLException
	 * @throws NotFoundException
	 */
	private ClassLoader createClassLoader(String modname, String classpath, ClassLoader loader, ClassLoader... dependencies) throws MalformedURLException, NotFoundException {
		List<Path> pathEntries = getClassLoaderEntries(modname, classpath);
		logger.log(Level.INFO, "Classpath: " + pathEntries.toString());
		List<URL> urls = new ArrayList<>();
		for (Path path : pathEntries) {
			urls.add(path.toUri().toURL());
		}
		ClassLoader parent = loader;
		if (dependencies != null && dependencies.length > 0) {
			parent = new JoinClassLoader(parent, dependencies);
		}
		return new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
	}
}