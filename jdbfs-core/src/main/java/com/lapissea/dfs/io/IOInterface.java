package com.lapissea.dfs.io;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.UnmanagedRegistry;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.util.function.UnsafeRunnable;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.File;
import java.io.IOException;


/**
 * This interface is a container or accessor of binary data that can provide a way to write/read contents in a random or sequential manner.
 */
public interface IOInterface extends RandomIO.Creator{
	/**
	 * Canonical weak identities shared by every provider over this source.
	 */
	default UnmanagedRegistry getUnmanagedRegistry(){ throw new UnsupportedOperationException("Source does not support unmanaged identities"); }
	
	final class Builder{
		private File    file;
		private boolean readOnly;
		private boolean noMap;
		private IOHook  hook;
		
		public Builder withFile(String fileName){ return withFile(new File(fileName)); }
		public Builder withFile(File file){
			this.file = file;
			return this;
		}
		
		public Builder asReadOnly(){ return withReadOnly(true); }
		public Builder withReadOnly(boolean readOnly){
			this.readOnly = readOnly;
			return this;
		}
		public Builder withHook(IOHook hook){
			this.hook = hook;
			return this;
		}
		
		public Builder noMap(){
			noMap = true;
			return this;
		}
		
		public IOInterface build() throws IOException{
			if(file == null){
				var b = MemoryData.builder();
				if(readOnly) throw new IOException("Can not make new empty read only data");
				b.withOnWrite(hook);
				return b.build();
			}
			if(noMap){
				return new FileRandomAccessData(hook, file, readOnly);
			}
			return new FileMemoryMappedData(hook, file, readOnly);
		}
	}
	
	static Builder build(){ return new Builder(); }
	
	default void setIOSize(long requestedSize) throws IOException{
		try(var io = io()){
			io.ensureCapacity(requestedSize);
			io.setSize(requestedSize);
		}
	}
	
	default long getIOSize() throws IOException{
		try(var io = io()){
			return io.getSize();
		}
	}
	
	@Override
	default byte[] readAll() throws IOException{
		var s = getIOSize();
		if(s>Integer.MAX_VALUE) throw new OutOfMemoryError();
		var iSize = (int)s;
		return read(0, iSize);
	}
	
	/**
	 * @return if this {@link IOInterface} allows for modification of data
	 */
	boolean isReadOnly();
	
	/**
	 * Opens a scope owned by the calling thread. Nested scopes join the root transaction. Every scope
	 * must call {@link IOTransaction#commit()}, otherwise the root rolls back!<br>
	 * <br>
	 * Backing IO access from other threads waits for the root scope to close.<br>
	 * Rollback refreshes live unmanaged identities and clears internal caches before releasing the access lock.<br>
	 * Objects allocated by the failed root lose their identity.
	 */
	IOTransaction startTransaction();
	
	/**
	 * Coordinates bookkeeping locks with backing IO. Acquire this before allocator/cache locks
	 * that may perform IO, and release it on the same thread. This does not open a transaction!
	 */
	default IOTransactionState.Access lockIO(){ return IOTransactionState.NOOP_ACCESS; }
	
	/**
	 * @see #startTransaction()
	 */
	default IOTransaction openIOTransaction(){ return startTransaction(); }
	
	default <E extends Throwable> void openIOTransaction(UnsafeRunnable<E> session) throws E, IOException{
		try(var transaction = startTransaction()){
			session.run();
			transaction.commit();
		}
	}
	default <T, E extends Throwable> T openIOTransaction(UnsafeSupplier<T, E> session) throws E, IOException{
		try(var transaction = startTransaction()){
			var result = session.get();
			transaction.commit();
			return result;
		}
	}
	
	/**
	 * @return true when there is a wall file backing the writes
	 */
	default boolean isDurable(){ return false; }
	
	default void applyTransaction(IOTransactionBuffer.TransactionExport changes) throws IOException{
		io(changes::apply);
	}
	
	default long getTransactionVersion(){ return 0; }
	
	default void checkTransactionVersion(long expected){
		if(getTransactionVersion() != expected){
			throw new IllegalStateException("Data provider is invalidated by transaction rollback. Objects must be reloaded");
		}
	}
	
	default IOInterface asReadOnly(){
		if(isReadOnly()) return this;
		class ReadOnly implements IOInterface, Stringify{
			@Override
			public boolean isReadOnly(){
				return true;
			}
			@Override
			public IOTransaction startTransaction(){
				return IOTransaction.NOOP;
			}
			@Override
			public long getTransactionVersion(){ return IOInterface.this.getTransactionVersion(); }
			@Override
			public UnmanagedRegistry getUnmanagedRegistry(){ return IOInterface.this.getUnmanagedRegistry(); }
			@Override
			public IOTransactionState.Access lockIO(){ return IOInterface.this.lockIO(); }
			@Override
			public void checkTransactionVersion(long expected){ IOInterface.this.checkTransactionVersion(expected); }
			@Override
			public RandomIO io() throws IOException{
				var io = IOInterface.this.readOnlyIO();
				if(!io.isReadOnly()){
					throw new IllegalStateException();
				}
				return io;
			}
			@Override
			public String toString(){
				return IOInterface.this.toString();
			}
			@Override
			public String toShortString(){
				return Utils.toShortString(IOInterface.this);
			}
		}
		return new ReadOnly();
	}
}
