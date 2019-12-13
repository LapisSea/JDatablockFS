package com.lapissea.fsf;

import java.io.Flushable;
import java.io.IOException;

public interface RandomIO extends AutoCloseable, Flushable, ContentWriter, ContentReader{
	
	RandomIO setPos(long pos) throws IOException;
	
	long getPos() throws IOException;
	
	long getSize() throws IOException;
	
	RandomIO setSize(long newSize) throws IOException;
	
	@Override
	void close() throws IOException;
	
	@Override
	void flush() throws IOException;
	
	default void trim() throws IOException{
		var pos =getPos();
		var size=getSize();
		if(size >= pos) return;
		setSize(pos);
	}
	
	default long skip(long n) throws IOException{
		long toSkip=Math.min(n, remaining());
		setPos(getPos());
		return toSkip;
	}
	
	default long remaining() throws IOException{
		return getSize()-getPos();
	}
	
	////////
	
	
	@Override
	int read() throws IOException;
	
	@Override
	int read(byte[] b, int off, int len) throws IOException;
	
	default int read(byte[] b) throws IOException{
		return read(b, 0, b.length);
	}
	
	
	////////
	
	
	@Override
	void write(int b) throws IOException;
	
	@Override
	void write(byte[] b, int off, int len) throws IOException;
	
	default RandomIO write(byte[] b) throws IOException{
		write(b, 0, b.length);
		return this;
	}
	
}
