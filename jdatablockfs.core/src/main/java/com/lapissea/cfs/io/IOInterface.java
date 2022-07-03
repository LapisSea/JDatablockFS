package com.lapissea.cfs.io;

import com.lapissea.cfs.Utils;
import com.lapissea.util.function.UnsafeRunnable;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.Closeable;
import java.io.IOException;


/**
 * This interface is a container or accessor of binary data that can provide a way to write/read contents in a random or sequential manner.
 */
public interface IOInterface extends RandomIO.Creator{
	
	interface IOTransaction extends Closeable{
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
	
	default void setIOSize(long requestedSize) throws IOException{
		try(var io=io()){
			io.ensureCapacity(requestedSize);
			io.setSize(requestedSize);
		}
	}
	
	default long getIOSize() throws IOException{
		try(var io=io()){
			return io.getSize();
		}
	}
	
	@Override
	default byte[] readAll() throws IOException{
		return read(0, Math.toIntExact(getIOSize()));
	}
	
	/**
	 * @return if this {@link IOInterface} allows for modification of data
	 */
	boolean isReadOnly();
	
	/**
	 * Call to this function signifies that future write events need to be treated as an atomic
	 * operation. (atomic meaning, partial execution is not acceptable) This stands true until
	 * {@link IOTransaction#close()} has been called. This function may be called multiple times
	 * before any close has been called. The transaction ends only when all {@link IOTransaction}s
	 * have been closed.
	 * The changes of data must be visible to while the transaction is ongoing, although they do not
	 * need to be stored.
	 *
	 * @return a transaction object that is active until closed
	 * @see IOTransactionBuffer
	 */
	IOTransaction openIOTransaction();
	
	default <E extends Throwable> void openIOTransaction(UnsafeRunnable<E> session) throws E, IOException{
		try(var ignored=openIOTransaction()){
			session.run();
		}
	}
	default <T, E extends Throwable> T openIOTransaction(UnsafeSupplier<T, E> session) throws E, IOException{
		try(var ignored=openIOTransaction()){
			return session.get();
		}
	}
	
	default IOInterface asReadOnly(){
		if(isReadOnly()) return this;
		var that=this;
		return new IOInterface(){
			@Override
			public boolean isReadOnly(){
				return true;
			}
			@Override
			public IOTransaction openIOTransaction(){
				return that.openIOTransaction();
			}
			@Override
			public RandomIO io() throws IOException{
				var io=that.readOnlyIO();
				if(!io.isReadOnly()){
					throw new IllegalStateException();
				}
				return io;
			}
			@Override
			public String toString(){
				return that.toString();
			}
			public String toShortString(){
				return Utils.toShortString(that);
			}
		};
	}
}
