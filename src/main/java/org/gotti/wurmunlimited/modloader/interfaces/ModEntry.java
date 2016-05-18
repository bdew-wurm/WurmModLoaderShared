package org.gotti.wurmunlimited.modloader.interfaces;

import java.util.Properties;

public interface ModEntry<T> {
	
	String getName();
	
	Properties getProperties();
	
	T getWurmMod();

}
