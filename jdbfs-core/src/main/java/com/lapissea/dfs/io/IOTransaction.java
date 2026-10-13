package com.lapissea.dfs.io;

import com.lapissea.dfs.config.ConfigDefs;

import java.io.Closeable;
import java.io.IOException;

public interface IOTransaction extends Closeable{
	
	boolean DISABLE_TRANSACTIONS = ConfigDefs.DISABLE_TRANSACTIONS.resolveValLocking();
	
	IOTransaction NOOP = new IOTransaction(){
		@Override
		public void commit(){ }
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
	 * Marks this scope as successful. Changes are applied when the successful root scope closes.
	 * A nested scope joins its root and an unsuccessful child makes the entire root rollback only.<br>
	 * Must be called last, right before closing
	 */
	void commit() throws IOException;
	
	/**
	 * Closing without calling {@link #commit()} is considered a failed transaction and will roll back!
	 */
	@Override
	void close() throws IOException;
	
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
