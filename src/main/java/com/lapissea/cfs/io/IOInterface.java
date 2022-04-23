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
	
	interface Trans extends Closeable{}
	
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
	
	boolean isReadOnly();
	
	Trans openIOTransaction();
	
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
			public Trans openIOTransaction(){
				return that.openIOTransaction();
			}
			@Override
			public RandomIO io() throws IOException{
				return that.io().readOnly();
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
