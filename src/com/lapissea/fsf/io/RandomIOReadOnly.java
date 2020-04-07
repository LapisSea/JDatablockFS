package com.lapissea.fsf.io;

import java.io.IOException;

@SuppressWarnings("RedundantThrows")
public class RandomIOReadOnly implements RandomIO{
	private final RandomIO io;
	
	public RandomIOReadOnly(RandomIO io){
		while(io instanceof RandomIOReadOnly ro){
			io=ro.io;
		}
		this.io=io;
	}
	
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
	public RandomIO setSize(long targetSize) throws IOException{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public long getCapacity() throws IOException{
		return io.getCapacity();
	}
	
	@Override
	public RandomIO setCapacity(long newCapacity) throws IOException{
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
	public void fillZero(long requestedMemory) throws IOException{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public long getGlobalPos() throws IOException{
		return io.getGlobalPos();
	}
	
	@Override
	public String toString(){
		return "ReadOnly{"+io.toString()+"}";
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
	
	@Override
	public void trim() throws IOException{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public long skip(long n) throws IOException{
		return io.skip(n);
	}
	
	@Override
	public long remaining() throws IOException{
		return io.remaining();
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		return io.read(b, off, len);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		throw new UnsupportedOperationException();
	}
}
