package com.lapissea.cfs.utils;

import com.lapissea.util.function.UnsafeSupplier;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public interface ClosableLock{
	
	interface LockSession extends AutoCloseable{
		@Override
		void close();
	}
	
	static ClosableLock reentrant(){
		final class ReentrantClosableLock extends ReentrantLock implements ClosableLock, LockSession{
			@Override
			public LockSession open(){
				lock();
				return this;
			}
			
			@Override
			public void close(){
				unlock();
			}
		}
		
		return new ReentrantClosableLock();
	}
	
	default <T, E extends Throwable> T sync(UnsafeSupplier<T, E> func) throws E{
		try(var ignored = open()){
			return func.get();
		}
	}
	
	LockSession open();
	Condition newCondition();
	
}
