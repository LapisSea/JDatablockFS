package com.lapissea.fsf;

import java.io.Flushable;
import java.io.IOException;

public interface RandomIO extends AutoCloseable, Flushable{
	
	void trim();
	
	void setPos(long pos);
	
	long getPos();
	
	@Override
	void close() throws IOException;
	
	@Override
	void flush() throws IOException;
	
	////////
	
	
	int read();
	
	void read(byte[] b, int off, int len);
	
	default void read(byte[] b){
		read(b, 0, b.length);
	}
	
	
	////////
	
	
	void write(byte b);
	
	void write(byte[] b, int off, int len);
	
	default void write(byte[] b){
		write(b, 0, b.length);
	}
	
}
