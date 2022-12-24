package com.lapissea.cfs.internal;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public interface ReadWriteClosableLock{
	
	final class LockSession implements AutoCloseable{
		private final Lock lock;
		private LockSession(Lock lock){ this.lock = lock; }
		@Override
		public void close(){
			lock.unlock();
		}
		
		public Lock getLock(){
			return lock;
		}
	}
	
	static ReadWriteClosableLock reentrant(){
		final class ReentrantReadWriteClosableLock extends ReentrantReadWriteLock implements ReadWriteClosableLock{
			
			
			private final LockSession readLock  = new LockSession(readLock());
			private final LockSession writeLock = new LockSession(writeLock());
			
			@Override
			public LockSession read(){
				readLock().lock();
				return readLock;
			}
			@Override
			public LockSession write(){
				writeLock().lock();
				return writeLock;
			}
		}
		
		return new ReentrantReadWriteClosableLock();
	}
	
	LockSession read();
	LockSession write();
	
}
