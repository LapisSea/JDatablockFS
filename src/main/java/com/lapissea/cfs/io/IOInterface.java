package com.lapissea.cfs.io;

import java.io.IOException;

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

//	/**
//	 * Tries to grows or shrinks capacity as closely as it is convenient for the underlying data. <br>
//	 * <br>
//	 * If growing, it is required target capacity is set to greater or equal to newCapacity.<br>
//	 * If shrinking, it is not required target capacity is shrunk but is required to always be greater or equal to newCapacity.
//	 */
//	default void setCapacity(long newCapacity) throws IOException{
//		try(var io=io()){
//			io.setCapacity(newCapacity);
//		}
//	}
//
//	default long getCapacity() throws IOException{
//		try(var io=io()){
//			return io.getCapacity();
//		}
//	}
	
	@Override
	default byte[] readAll() throws IOException{
		return read(0, Math.toIntExact(getIOSize()));
	}
	
	boolean isReadOnly();
	
}
