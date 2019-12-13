package com.lapissea.fsf;

import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.InputStream;

public abstract class ContentInputStream extends InputStream implements ContentReader{
	
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
