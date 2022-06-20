package com.lapissea.cfs.io.content;

import com.lapissea.cfs.Utils;

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
	@Override
	public void writeWord(long v, int len) throws IOException{
		
		int oldCapacity=buf.length;
		int minGrowth  =count+len-oldCapacity;
		if(minGrowth>0){
			ContentWriter.super.writeWord(v, len);
			return;
		}
		Utils.write8(v, buf, count, len);
		count+=len;
	}
}
