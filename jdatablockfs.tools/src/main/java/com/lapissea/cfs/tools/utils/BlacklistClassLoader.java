package com.lapissea.cfs.tools.utils;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

public final class BlacklistClassLoader extends ClassLoader{
	
	private final List<Predicate<String>> blacklist;
	
	public BlacklistClassLoader(ClassLoader parent, List<Predicate<String>> blacklist){
		super(parent);
		this.blacklist=List.copyOf(blacklist);
	}
	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
		if(blacklist.stream().anyMatch(b->b.test(name))){
			throw new ClassNotFoundException("Blacklisted: "+name);
		}
		
		if(name.startsWith("java.lang")) return getParent().loadClass(name);
		
		var cls=super.loadClass(name, resolve);
		
		if(cls.getClassLoader()==getParent()){
			synchronized(getClassLoadingLock(name)){
				var r=getResourceAsStream(name.replace('.', '/')+".class");
				if(r==null) return getParent().loadClass(name);
				try(r){
					var bb=r.readAllBytes();
					return defineClass(name, bb, 0, bb.length);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}
		return cls;
	}
}
