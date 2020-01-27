package com.lapissea.fsf.io;

import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.InputStream;

public class TrackingInputStream extends InputStream{
	private final InputStream in;
	private final long[]      pos;
	private       long        mark;
	
	public TrackingInputStream(InputStream in, long[] pos){
		this.in=in;
		this.pos=pos;
	}
	
	
	@Override
	public int read() throws IOException{
		int b=in.read();
		if(b >= 0) pos[0]++;
		return b;
	}
	
	@Override
	public int read(@NotNull byte[] b) throws IOException{
		int read=in.read(b);
		if(read>0) pos[0]+=read;
		
		return read;
	}
	
	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException{
		int read=in.read(b, off, len);
		if(read>0) pos[0]+=read;
		
		return read;
	}
	
	@Override
	public long skip(long n) throws IOException{
		long skipped=in.skip(n);
		if(skipped>0) pos[0]+=skipped;
		
		return skipped;
	}
	
	@Override
	public int available() throws IOException{
		return in.available();
	}
	
	@Override
	public void close() throws IOException{
		in.close();
	}
	
	@Override
	public synchronized void mark(int readlimit){
		mark=pos[0];
		in.mark(readlimit);
	}
	
	@Override
	public synchronized void reset() throws IOException{
		in.reset();
		if(markSupported()){
			pos[0]=mark;
		}
	}
	
	@Override
	public boolean markSupported(){
		return in.markSupported();
	}
}
