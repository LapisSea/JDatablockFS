package com.lapissea.cfs.io;

import com.lapissea.cfs.config.GlobalConfig;

import java.io.Closeable;

public interface IOTransaction extends Closeable{
	
	boolean DISABLE_TRANSACTIONS = GlobalConfig.configFlag("disableTransactions", false);
	
	IOTransaction NOOP = new IOTransaction(){
		@Override
		public int getChunkCount(){ return 0; }
		@Override
		public long getTotalBytes(){ return 0; }
		@Override
		public void close(){ }
		@Override
		public String toString(){ return "NOOP"; }
	};
	
	/**
	 * Optional information used for profiling or debugging.
	 *
	 * @return number of separate ranges of bytes that may contain data that has changed
	 */
	int getChunkCount();
	/**
	 * Optional information used for profiling or debugging.
	 *
	 * @return number of bytes that may have been overwritten
	 */
	long getTotalBytes();
}
