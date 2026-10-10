package com.lapissea.fuzz.distributed.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

interface CacheGate extends AutoCloseable{
	long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(25);
	
	@Override
	void close() throws IOException;
	
	static CacheGate acquire(Path cache, boolean wait) throws IOException{
		return acquireWithin(cache, wait? DEFAULT_TIMEOUT_MILLIS : 0);
	}
	
	/**
	 * @param timeoutMillis a zero timeout attempts once, a positive timeout retries until it expires
	 * @return the gate, or {@code null} while the lock is held (only under a zero timeout)
	 * @throws IOException when a positive timeout expires
	 */
	static CacheGate acquireWithin(Path cache, long timeoutMillis) throws IOException{
		if(timeoutMillis<0){
			throw new IllegalArgumentException("Timeout must not be negative");
		}
		return HostOs.local().bootstrap().acquireCacheGate(cache, timeoutMillis);
	}
	
	final class Lease implements CacheGate{
		private final Releaser release;
		private       boolean  closed;
		
		Lease(Releaser release){ this.release = release; }
		
		@Override
		public void close() throws IOException{
			if(closed){ return; }
			closed = true;
			release.release();
		}
	}
	
	interface Releaser{
		void release() throws IOException;
	}
}
