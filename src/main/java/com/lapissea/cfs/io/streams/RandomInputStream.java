package com.lapissea.cfs.io.streams;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;

import java.io.IOException;

public class RandomInputStream extends ContentInputStream{
	
	private final RandomIO io;
	private       long     mark;
	
	public RandomInputStream(RandomIO io){
		this.io=io;
	}
	
	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException{
		return io.read(b, off, len);
	}
	
	@Override
	public int read() throws IOException{
		return io.read();
	}
	
	@Override
	public long skip(long n) throws IOException{
		return io.skip(n);
	}
	
	@Override
	public void close() throws IOException{
		io.close();
	}
	
	@Override
	public synchronized void mark(int readLimit){
		try{
			mark=io.getPos();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public synchronized void reset() throws IOException{
		io.setPos(mark);
	}
	
	@Override
	public boolean markSupported(){
		return true;
	}
	
	@Override
	public String toString(){
		return this.getClass().getSimpleName()+"{"+TextUtil.toString(io)+'}';
	}
	
	@Override
	public long getOffset() throws IOException{
		return io.getPos();
	}
	
}
