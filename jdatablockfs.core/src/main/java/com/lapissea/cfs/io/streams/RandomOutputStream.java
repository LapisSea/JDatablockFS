package com.lapissea.cfs.io.streams;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;

import java.io.IOException;

public class RandomOutputStream extends ContentOutputStream{
	
	private final RandomIO io;
	private final boolean  trimOnClose;
	
	public RandomOutputStream(RandomIO io, boolean trimOnClose){
		this.io=io;
		this.trimOnClose=trimOnClose;
	}
	
	@Override
	public void write(@NotNull byte[] b, int off, int len) throws IOException{
		io.write(b, off, len);
	}
	@Override
	public void writeWord(long v, int len) throws IOException{
		io.writeWord(v, len);
	}
	
	@Override
	public void flush() throws IOException{
		io.flush();
	}
	
	@Override
	public void close() throws IOException{
		if(trimOnClose){
			io.trim();
		}
		io.close();
	}
	
	@Override
	public void write(int b) throws IOException{
		io.write(b);
	}
	
	@Override
	public String toString(){
		return this.getClass().getSimpleName()+'{'+TextUtil.toString(io)+'}';
	}
}
