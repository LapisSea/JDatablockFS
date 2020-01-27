package com.lapissea.fsf.io;

import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.OutputStream;

public class TrackingOutputStream extends OutputStream{
	private final OutputStream out;
	private final long[]        pos;
	
	public TrackingOutputStream(OutputStream out, long[] pos){
		this.out=out;
		this.pos=pos;
	}
	
	
	@Override
	public void write(int b) throws IOException{
		pos[0]++;
		out.write(b);
	}
	
	@Override
	public void write(@NotNull byte[] b, int off, int len) throws IOException{
		pos[0]+=len;
		out.write(b, off, len);
	}
	
	@Override
	public void close() throws IOException{
		out.close();
	}
	
	@Override
	public void flush() throws IOException{
		out.flush();
	}
}
