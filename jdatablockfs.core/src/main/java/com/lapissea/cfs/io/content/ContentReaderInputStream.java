package com.lapissea.cfs.io.content;

import com.lapissea.util.NotNull;

import java.io.IOException;

public class ContentReaderInputStream extends ContentInputStream{
	private final ContentReader reader;
	public ContentReaderInputStream(ContentReader reader){
		this.reader = reader;
	}
	
	@Override
	public int read() throws IOException{
		return reader.read();
	}
	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException{
		return reader.read(b, off, len);
	}
	
	@Override
	public long skip(long n) throws IOException{
		return reader.skip(n);
	}
	@Override
	public void close() throws IOException{
		reader.close();
	}
	
	@Override
	public long getOffset() throws IOException{
//		if(reader instanceof RandomIO r) return r.getPos();
		return -1;
	}
}
