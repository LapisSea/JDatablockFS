package com.lapissea.cfs.io;

import java.io.IOException;

public interface IOInterface extends Sizable.Mod, RandomIO.Creator{
	
	@Override
	default void setSize(long requestedSize) throws IOException{
		try(var io=io()){
			io.setSize(requestedSize);
		}
	}
	
	@Override
	default long getSize() throws IOException{
		try(var io=io()){
			return io.getSize();
		}
	}
	
	/**
	 * Tries to grows or shrinks capacity as closely as it is convenient for the underlying data. <br>
	 * <br>
	 * If growing, it is required target capacity is set to greater or equal to newCapacity.<br>
	 * If shrinking, it is not required target capacity is shrunk but is required to always be greater or equal to newCapacity.
	 */
	default void setCapacity(long newCapacity) throws IOException{
		try(var io=io()){
			io.setCapacity(newCapacity);
		}
	}
	
	default long getCapacity() throws IOException{
		try(var io=io()){
			return io.getCapacity();
		}
	}
	
	default byte[] readAll() throws IOException{
		try(var io=io()){
			byte[] data=new byte[Math.toIntExact(io.getSize())];
			io.readFully(data);
			return data;
		}
	}
	
	boolean isReadOnly();
	
}
