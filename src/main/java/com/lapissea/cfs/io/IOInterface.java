package com.lapissea.cfs.io;

import java.io.IOException;


/**
 * This interface is a container or accessor of binary data that can provide a way to write/read contents in a random or sequential manner.
 */
public interface IOInterface extends RandomIO.Creator{
	
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
	
}
