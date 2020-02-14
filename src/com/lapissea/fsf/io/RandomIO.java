package com.lapissea.fsf.io;

import java.io.Flushable;
import java.io.IOException;
import java.util.Objects;

public interface RandomIO extends AutoCloseable, Flushable, ContentWriter, ContentReader{
	
	enum Mode{
		READ_ONLY(true, false),
		READ_WRITE(true, true);
		
		public final boolean canRead;
		public final boolean canWrite;
		
		Mode(boolean canRead, boolean canWrite){
			this.canRead=canRead;
			this.canWrite=canWrite;
		}
	}
	
	static RandomIO readOnly(RandomIO io){
		Objects.requireNonNull(io);
		
		class ReadOnly implements RandomIO{
			
			@Override
			public long getPos() throws IOException{
				return io.getPos();
			}
			
			@Override
			public RandomIO setPos(long pos) throws IOException{
				io.setPos(pos);
				return this;
			}
			
			@Override
			public long getSize() throws IOException{
				return io.getSize();
			}
			
			@Override
			public RandomIO setSize(long targetSize){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public long getCapacity() throws IOException{
				return io.getCapacity();
			}
			
			@Override
			public RandomIO setCapacity(long newCapacity){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public void close() throws IOException{
				io.close();
			}
			
			@Override
			public void flush() throws IOException{
				io.close();
			}
			
			@Override
			public int read() throws IOException{
				return io.read();
			}
			
			@Override
			public byte[] contentBuf(){
				return io.contentBuf();
			}
			
			@Override
			public void write(int b) throws IOException{
				throw new UnsupportedOperationException();
			}
			
			@Override
			public void fillZero(long requestedMemory){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public long getGlobalPos() throws IOException{
				return io.getGlobalPos();
			}
			
			@Override
			public String toString(){
				return "RO{"+io.toString()+"}";
			}
			
			@Override
			public int hashCode(){
				return io.hashCode();
			}
			
			@SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
			@Override
			public boolean equals(Object obj){
				return io.equals(obj);
			}
		}
		return new ReadOnly();
	}
	
	long getPos() throws IOException;
	
	RandomIO setPos(long pos) throws IOException;
	
	
	long getSize() throws IOException;
	
	RandomIO setSize(long targetSize) throws IOException;
	
	
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
	
	void fillZero(long requestedMemory) throws IOException;
	
	long getGlobalPos() throws IOException;
}
