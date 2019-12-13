package com.lapissea.fsf;

import java.io.EOFException;
import java.io.IOException;

@SuppressWarnings("PointlessBitwiseExpression")
public interface ContentReader{
	int read() throws IOException;
	
	int read(byte[] b, int off, int len) throws IOException;
	
	default int readUnsignedByte() throws IOException{
		int ch=read();
		if(ch<0) throw new EOFException();
		return ch;
	}
	
	
	default short readShort() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (short)((ch1<<8)+(ch2<<0));
	}
	
	default int readUnsignedShort() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (ch1<<8)+(ch2<<0);
	}
	
	default char readChar() throws IOException{
		int ch1=read();
		int ch2=read();
		if((ch1|ch2)<0)
			throw new EOFException();
		return (char)((ch1<<8)+(ch2<<0));
	}
	
	default int readInt() throws IOException{
		int ch1=read();
		int ch2=read();
		int ch3=read();
		int ch4=read();
		if((ch1|ch2|ch3|ch4)<0)
			throw new EOFException();
		return ((ch1<<24)+(ch2<<16)+(ch3<<8)+(ch4<<0));
	}
	
	
	default long readLong() throws IOException{
		byte[] readBuffer=new byte[8];
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
	
	default void readFully(byte[] b, int off, int len) throws IOException{
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
	
	default int readByte() throws IOException{
		return read();
	}
}
