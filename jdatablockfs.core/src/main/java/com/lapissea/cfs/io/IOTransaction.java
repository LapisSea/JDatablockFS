package com.lapissea.cfs.io;

import java.io.Closeable;

public interface IOTransaction extends Closeable{
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
