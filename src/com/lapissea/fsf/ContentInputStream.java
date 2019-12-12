package com.lapissea.fsf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public abstract class ContentInputStream extends InputStream{
	
	public static class Wrapp extends ContentInputStream{
		
		private final InputStream in;
		
		public Wrapp(InputStream in){
			this.in=in;
		}
		
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
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
	
	public final int readUnsignedByte() throws IOException{
		int ch=read();
		if(ch<0)
			throw new EOFException();
		return ch;
	}
	
	
	public final short readShort() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (short)((ch1<<8)+(ch2<<0));
	}
	
	public final int readUnsignedShort() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (ch1<<8)+(ch2<<0);
	}
	
	public final char readChar() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (char)((ch1<<8)+(ch2<<0));
	}
	
	public final int readInt() throws IOException{
		int ch1=read();
		int ch2=read();
		int ch3=read();
		int ch4=read();
		if((ch1|ch2|ch3|ch4)<0)
			throw new EOFException();
		return ((ch1<<24)+(ch2<<16)+(ch3<<8)+(ch4<<0));
	}
	
	private byte readBuffer[]=new byte[8];
	
	public final long readLong() throws IOException{
		readFully(readBuffer, 0, 8);
		return (((long)readBuffer[0]<<56)+
		        ((long)(readBuffer[1]&255)<<48)+
		        ((long)(readBuffer[2]&255)<<40)+
		        ((long)(readBuffer[3]&255)<<32)+
		        ((long)(readBuffer[4]&255)<<24)+
		        ((readBuffer[5]&255)<<16)+
		        ((readBuffer[6]&255)<<8)+
		        ((readBuffer[7]&255)<<0));
	}
	
	public final void readFully(byte[] b, int off, int len) throws IOException{
		if(len<0)
			throw new IndexOutOfBoundsException();
		int n=0;
		while(n<len){
			int count=read(b, off+n, len-n);
			if(count<0)
				throw new EOFException();
			n+=count;
		}
	}
	
	public int readByte() throws IOException{
		return read();
	}
}
