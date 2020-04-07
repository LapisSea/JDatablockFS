package com.lapissea.fsf.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ContentBuffer{
	
	private static class Buf extends ByteArrayOutputStream{
		public Buf(int capacity){
			super(capacity);
		}
		
		byte[] buf(){
			return this.buf;
		}
	}
	
	public class BufStream extends ContentOutputStream.Wrapp{
		
		private BufStream(){
			super(buf);
		}
		
		public int size(){
			return buf.size();
		}
		
		@Override
		public void close() throws IOException{
			dest.write(buf.buf(), 0, buf.size());
		}
		
		@Override
		public String toString(){
			return buf.toString();
		}
	}
	
	private final Buf       buf;
	private final BufStream stream;
	
	private ContentWriter dest;
	
	public ContentBuffer(){
		this(8);
	}
	
	public ContentBuffer(int capacity){
		buf=new Buf(capacity);
		stream=new BufStream();
	}
	
	public BufStream session(ContentWriter dest){
		this.dest=dest;
		buf.reset();
		return stream;
	}
	
	@Override
	public String toString(){
		return "ContentBuffer{"+buf+'}';
	}
}
