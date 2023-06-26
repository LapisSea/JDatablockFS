package com.lapissea.cfs.utils;

import com.lapissea.util.function.UnsafeSupplier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
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
	static ReadWriteClosableLock noop(){
		final class NoopReadWriteClosableLock implements ReadWriteClosableLock{
			
			private static final Lock NOOP = new Lock(){
				@Override
				public void lock(){ }
				@Override
				public void lockInterruptibly(){ }
				@Override
				public boolean tryLock(){
					return true;
				}
				@Override
				public boolean tryLock(long time, TimeUnit unit){ return true; }
				@Override
				public void unlock(){ }
				@Override
				public Condition newCondition(){
					throw new UnsupportedOperationException();
				}
			};
			
			private static final ReadWriteClosableLock NOOP_RW = new NoopReadWriteClosableLock();
			
			
			private final LockSession readLock  = new LockSession(NOOP);
			private final LockSession writeLock = new LockSession(NOOP);
			
			@Override
			public LockSession read(){
				return readLock;
			}
			@Override
			public LockSession write(){
				return writeLock;
			}
		}
		
		return NoopReadWriteClosableLock.NOOP_RW;
	}
	
	LockSession read();
	LockSession write();
	
	default <T, E extends Throwable> T read(UnsafeSupplier<T, E> fun) throws E{
		try(var ignore = read()){
			return fun.get();
		}
	}
	default <T, E extends Throwable> T write(UnsafeSupplier<T, E> fun) throws E{
		try(var ignore = write()){
			return fun.get();
		}
	}
	
}
