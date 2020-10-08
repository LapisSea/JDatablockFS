package com.lapissea.cfs.io;

import java.io.IOException;

public interface Sizable{
	
	interface Mod extends Sizable{
		void setSize(long targetSize) throws IOException;
	}
	
	default boolean isEmpty() throws IOException{
		return getSize()==0;
	}
	
	long getSize() throws IOException;
	
}
