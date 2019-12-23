package com.lapissea.fsf;

import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public abstract class ContentInputStream extends InputStream implements ContentReader{
	
	public static class BA extends ContentInputStream{
		private final byte[] ba;
		private       int    pos;
		
		public BA(byte[] ba){
			this.ba=ba;
		}
		
		
		@Override
		public int read() throws IOException{
			int rem=ba.length-pos;
			if(rem==0) return -1;
			return ba[pos++]&0xFF;
		}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			int rem=ba.length-pos;
			if(rem==0) return -1;
			if(len<rem) len=rem;
			System.arraycopy(ba, pos, b, off, len);
			pos+=len;
			return len;
		}
	}
	
	public static class BB extends ContentInputStream{
		private final ByteBuffer bb;
		
		public BB(ByteBuffer bb){
			this.bb=bb;
		}
		
		@Override
		public int read() throws IOException{
			int rem=bb.remaining();
			if(rem==0) return -1;
			return bb.get();
		}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			int rem=bb.remaining();
			if(rem==0) return -1;
			if(len<rem) len=rem;
			bb.get(b, off, len);
			return len;
		}
	}
	
	public static class Wrapp extends ContentInputStream{
		
		private final InputStream in;
		
		public Wrapp(InputStream in){
			this.in=in;
		}
		
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			return in.read(b, off, len);
		}
		
		@Override
		public long skip(long n) throws IOException{
			return in.skip(n);
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
			in.mark(readlimit);
		}
		
		@Override
		public synchronized void reset() throws IOException{
			in.reset();
		}
		
		@Override
		public boolean markSupported(){
			return in.markSupported();
		}
		
		@Override
		public int read() throws IOException{
			return in.read();
		}
	}
	
}
