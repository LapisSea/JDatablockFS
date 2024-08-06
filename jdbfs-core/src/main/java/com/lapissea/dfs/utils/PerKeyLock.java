package com.lapissea.dfs.utils;

import com.lapissea.util.function.UnsafeRunnable;
import com.lapissea.util.function.UnsafeSupplier;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class PerKeyLock<K>{
	
	private final Map<K, Lock> locks   = new HashMap<>();
	private final Lock         mapLock = new ReentrantLock();
	
	public <E extends Throwable> void syncRun(K key, UnsafeRunnable<E> action) throws E{
		var lock = lock(key);
		try{
			action.run();
		}finally{
			unlock(key, lock);
		}
	}
	public <T, E extends Throwable> T syncGet(K key, UnsafeSupplier<T, E> action) throws E{
		var lock = lock(key);
		try{
			return action.get();
		}finally{
			unlock(key, lock);
		}
	}
	
	private Lock lock(K key){
		mapLock.lock();
		try{
			var lock = locks.computeIfAbsent(key, s -> new ReentrantLock());
			lock.lock();
			return lock;
		}finally{
			mapLock.unlock();
		}
	}
	private void unlock(K key, Lock lock){
		mapLock.lock();
		try{
			var removed = locks.remove(key);
			lock.unlock();
			assert removed == lock;
		}finally{
			mapLock.unlock();
		}
	}
}
