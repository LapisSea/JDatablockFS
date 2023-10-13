package com.lapissea.cfs.type;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public final class BlacklistClassLoader extends ClassLoader{
	static{ registerAsParallelCapable(); }
	
	private final List<Predicate<String>> blacklist;
	private final boolean                 deepBlacklist;
	private final Map<Object, Lock>       lockMap = new ConcurrentHashMap<>();
	
	public BlacklistClassLoader(ClassLoader parent, List<Predicate<String>> blacklist){
		this(true, parent, blacklist);
	}
	public BlacklistClassLoader(boolean deepBlacklist, ClassLoader parent, List<Predicate<String>> blacklist){
		super(parent);
		this.deepBlacklist = deepBlacklist;
		this.blacklist = List.copyOf(blacklist);
	}
	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
		{
			Class<?> c = findLoadedClass(name);
			if(c != null) return c;
		}
		
		for(var b : blacklist){
			if(b.test(name)){
				throw new ClassNotFoundException("Blacklisted: " + name);
			}
		}
		
		if(name.startsWith("java.lang")) return getParent().loadClass(name);
		
		var cls = super.loadClass(name, resolve);
		if(!deepBlacklist) return cls;
		
		if(cls.getClassLoader() == getParent()){
			var lock = lockMap.computeIfAbsent(getClassLoadingLock(name), o -> new ReentrantLock());
			lock.lock();
			try{
				Class<?> c = findLoadedClass(name);
				if(c != null) return c;
				
				var r = getResourceAsStream(name.replace('.', '/') + ".class");
				if(r == null) return getParent().loadClass(name);
				try(r){
					var bb = r.readAllBytes();
					return defineClass(name, bb, 0, bb.length);
				}catch(Throwable e){
					throw new RuntimeException(e);
				}
			}finally{
				lock.unlock();
			}
		}
		return cls;
	}
}
