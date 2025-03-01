package com.lapissea.dfs.io.content;

import com.lapissea.util.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

public abstract class ContentInputStream extends InputStream implements ContentReader{
	
	public static class Joining2 extends ContentInputStream{
		private ContentReader other;
		
		private ContentReader source;
		
		public Joining2(ContentReader first, ContentReader second){
			this.other = second;
			source = first;
		}
		
		private boolean shouldPop(int val){
			return val<0 && other != null;
		}
		
		private void pop(){
			source = other;
			other = null;
		}
		
		@Override
		public int read() throws IOException{
			int b = source.read();
			if(shouldPop(b)){
				pop();
				return read();
			}
			return b;
		}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			int read = source.read(b, off, len);
			if(shouldPop(read)){
				pop();
				return read(b, off, len);
			}
			return read;
		}
		
		@Override
		public String toString(){
			if(other == null) return "Joining{" + source + '}';
			return "Joining{" + source + " + " + other + '}';
		}
	}
	
	public static class BA extends ContentInputStream{
		
		private final byte[] ba;
		private       int    pos;
		
		public BA(@NotNull byte[] ba){
			this.ba = Objects.requireNonNull(ba);
		}
		
		
		@Override
		public int read(){
			int rem = available();
			if(rem == 0) return -1;
			return ba[pos++]&0xFF;
		}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len){
			int rem = available();
			if(rem == 0) return -1;
			int read = Math.min(len, rem);
			System.arraycopy(ba, pos, b, off, read);
			pos += read;
			return read;
		}
		@Override
		public long readWord(int len) throws IOException{
			try{
				var val = WordIO.getWord(ba, pos, len);
				pos += len;
				return val;
			}catch(IndexOutOfBoundsException err){
				throw new EOFException();
			}
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + "{" + pos + "/" + ba.length + "}";
		}
		
		@Override
		public int available(){
			return ba.length - pos;
		}
		
		public int getPos(){
			return pos;
		}
	}
	
	public static class BB extends ContentInputStream{
		
		private final ByteBuffer bb;
		
		public BB(ByteBuffer bb){
			this.bb = bb;
		}
		
		@Override
		public int read(){
			int rem = available();
			if(rem == 0) return -1;
			return bb.get()&0xFF;
		}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len){
			int rem = available();
			if(rem == 0) return -1;
			if(len<rem) len = rem;
			bb.get(b, off, len);
			return len;
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + "{" + bb.position() + "/" + bb.limit() + "}";
		}
		
		@Override
		public int available(){
			return bb.remaining();
		}
	}
	
	public static class Wrapp extends ContentInputStream{
		
		private final InputStream in;
		
		public Wrapp(InputStream in){
			this.in = in;
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
	
	@Override
	public ContentInputStream inStream(){
		return this;
	}
}
