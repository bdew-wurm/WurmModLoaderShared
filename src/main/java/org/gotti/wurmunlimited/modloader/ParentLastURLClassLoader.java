package org.gotti.wurmunlimited.modloader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Version of URLClassLoader that first tries to load from the urls then checks the parent class loader
 */
public class ParentLastURLClassLoader extends URLClassLoader {
    public ParentLastURLClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // First, check if the class has already been loaded
            Class<?> c = findLoadedClass(name);

            // Then try to load it from the provided urls
            if (c == null) try {
                c = findClass(name);
            } catch (ClassNotFoundException ignored) {
                // Ignore if this fails, we still need to try parent
            }

            // If we havent found it yet try in parent
            if (c == null) c = getParent().loadClass(name);

            // We shouldn't get here as parent would throw if it's not found... but just in case
            if (c == null) throw new ClassNotFoundException(name);

            // Link class if requested
            if (resolve) resolveClass(c);

            return c;
        }
    }
}