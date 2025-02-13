package com.lapissea.dfs.utils;

import com.lapissea.util.function.UnsafeRunnable;
import com.lapissea.util.function.UnsafeSupplier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class PerKeyLock<K>{
	
	private static final class CountLock{
		
		private final Lock          lock  = new ReentrantLock();
		private final AtomicInteger count = new AtomicInteger(0);
		
		public void lock(){
			lock.lock();
			count.incrementAndGet();
		}
		public int unlock(){
			var c = count.decrementAndGet();
			lock.unlock();
			return c;
		}
	}
	
	private final Map<K, CountLock> locks = new ConcurrentHashMap<>();
	
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
	
	private CountLock lock(K key){
		var lock = locks.computeIfAbsent(key, s -> new CountLock());
		lock.lock();
		return lock;
	}
	private void unlock(K key, CountLock lock){
		var lockCount = lock.unlock();
		if(lockCount == 0){
			locks.remove(key);
		}
	}
}
