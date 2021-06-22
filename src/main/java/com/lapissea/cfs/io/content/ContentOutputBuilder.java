package com.lapissea.cfs.io.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ContentOutputBuilder extends ByteArrayOutputStream implements ContentWriter{
	public ContentOutputBuilder(){
	}
	public ContentOutputBuilder(int size){
		super(size);
	}
	
	public synchronized void writeTo(ContentWriter out) throws IOException{
		out.write(buf, 0, count);
	}
}
