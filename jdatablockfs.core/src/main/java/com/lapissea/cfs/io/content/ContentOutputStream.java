package com.lapissea.cfs.io.content;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public abstract class ContentOutputStream extends OutputStream implements ContentWriter{
	
	public static class BA extends ContentOutputStream{
		private final byte[] ba;
		private       int    pos;
		
		public BA(byte[] ba){ this.ba = ba; }
		
		@Override
		public void write(int b) throws IOException{
			ba[pos] = (byte)b;
			pos++;
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			System.arraycopy(b, off, ba, pos, len);
			pos += len;
		}
		@Override
		public void writeWord(long v, int len) throws IOException{
			MemPrimitive.setWord(v, ba, pos, len);
			pos += len;
		}
		
		public void reset(){
			pos = 0;
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + "{" + pos + "/" + ba.length + "}";
		}
	}
	
	public static class BB extends ContentOutputStream{
		private final ByteBuffer bb;
		
		public BB(ByteBuffer bb){
			this.bb = bb;
		}
		
		@Override
		public void write(int b) throws IOException{
			bb.put((byte)b);
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			bb.put(b, off, len);
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + "{" + bb.position() + "/" + bb.limit() + "}";
		}
	}
	
	public static class Wrapp extends ContentOutputStream{
		
		private final OutputStream os;
		
		public Wrapp(OutputStream os){
			this.os = os;
		}
		
		@Override
		public void write(int b) throws IOException{
			os.write(b);
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			os.write(b, off, len);
		}
		
		@Override
		public void flush() throws IOException{
			os.flush();
		}
		
		@Override
		public void close() throws IOException{
			os.close();
		}
		
	}
	
}
