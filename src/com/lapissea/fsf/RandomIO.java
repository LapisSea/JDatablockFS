package com.lapissea.fsf;

import java.io.Flushable;
import java.io.IOException;
import java.util.Objects;

public interface RandomIO extends AutoCloseable, Flushable, ContentWriter, ContentReader{
	
	RandomIO setPos(long pos) throws IOException;
	
	long getPos() throws IOException;
	
	long getSize() throws IOException;
	
	long getCapacity() throws IOException;
	
	RandomIO setCapacity(long newCapacity) throws IOException;
	
	@Override
	void close() throws IOException;
	
	@Override
	void flush() throws IOException;
	
	default void trim() throws IOException{
		var pos =getPos();
		var size=getSize();
		if(pos >= size) return;
		setCapacity(pos);
	}
	
	default long skip(long n) throws IOException{
		long toSkip=Math.min(n, remaining());
		setPos(getPos()+toSkip);
		return toSkip;
	}
	
	default long remaining() throws IOException{
		return getSize()-getPos();
	}
	
	////////
	
	
	@Override
	int read() throws IOException;
	
	@Override
	default int read(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		int i=off;
		for(int j=off+len;i<j;i++){
			var bi=read();
			if(bi<0) break;
			b[i]=(byte)bi;
		}
		return i-off;
	}
	
	default int read(byte[] b) throws IOException{
		return read(b, 0, b.length);
	}
	
	
	////////
	
	
	@Override
	void write(int b) throws IOException;
	
	@Override
	default void write(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		for(int i=off, j=off+len;i<j;i++){
			write(b[i]);
		}
	}
	
	default RandomIO write(byte[] b) throws IOException{
		write(b, 0, b.length);
		return this;
	}
	
	
	void fillZero(long requestedMemory) throws IOException;
}
