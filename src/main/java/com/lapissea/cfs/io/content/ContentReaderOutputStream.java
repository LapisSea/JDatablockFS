package com.lapissea.cfs.io.content;

import com.lapissea.util.NotNull;

import java.io.IOException;

public class ContentReaderOutputStream extends ContentOutputStream{
	private final ContentWriter writer;
	public ContentReaderOutputStream(ContentWriter writer){
		this.writer=writer;
	}
	@Override
	public void write(int b) throws IOException{
		writer.write(b);
	}
	@Override
	public void write(@NotNull byte[] b, int off, int len) throws IOException{
		writer.write(b, off, len);
	}
	
	@Override
	public ContentOutputStream outStream(){
		return this;
	}
	
	@Override
	public void close() throws IOException{
		writer.close();
	}
}
