package com.lapissea.dfs.io.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ContentOutputBuilder extends ByteArrayOutputStream implements ContentWriter{
	public ContentOutputBuilder(){
	}
	public ContentOutputBuilder(int size){
		super(size);
	}
	
	public void writeTo(ContentWriter out) throws IOException{
		out.write(buf, 0, count);
	}
	
	public void writeTo(ContentOutputBuilder out){
		out.write(buf, 0, count);
	}
	
	@Override
	public void writeWord(long v, int len) throws IOException{
		
		int oldCapacity = buf.length;
		int minGrowth   = count + len - oldCapacity;
		if(minGrowth>0){
			ContentWriter.super.writeWord(v, len);
			return;
		}
		WordIO.setWord(v, buf, count, len);
		count += len;
	}
	
	@Override
	public void write(byte[] b){
		write(b, 0, b.length);
	}
	
	@Override
	public void writeBoolean(boolean v){
		writeInt1(v? 1 : 0);
	}
	
	@Override
	public void writeInt1(int v){
		write(v);
	}
}
