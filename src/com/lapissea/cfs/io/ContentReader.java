package com.lapissea.cfs.io;

import java.io.EOFException;
import java.io.IOException;

@SuppressWarnings("PointlessBitwiseExpression")
public interface ContentReader{
	byte[] contentBuf();
	
	int read() throws IOException;
	
	default int read(byte[] b) throws IOException{
		return read(b, 0, b.length);
	}
	
	int read(byte[] b, int off, int len) throws IOException;
	
	default char readChar() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return (char)((readBuffer[0]<<8)+
		              (readBuffer[1]<<0));
	}
	
	default int readInt1() throws IOException{
		return read();
	}
	
	default int readUnsignedInt1() throws IOException{
		int ch=read();
		if(ch<0) throw new EOFException();
		return ch;
	}
	
	
	default short readInt2() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return (short)(((readBuffer[0]&255)<<8)+
		               ((readBuffer[1]&255)<<0));
	}
	
	default int readUnsignedInt2() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return ((readBuffer[0]&255)<<8)+
		       ((readBuffer[1]&255)<<0);
	}
	
	default int readUnsignedInt3() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 3);
		return (((readBuffer[0]&255)<<16)+
		        ((readBuffer[1]&255)<<8)+
		        ((readBuffer[2]&255)<<0));
	}
	
	default long readUnsignedInt4() throws IOException{
		return readInt4()&0xFFFFFFFFL;
	}
	
	default int readInt4() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 4);
		return (((readBuffer[0]&255)<<24)+
		        ((readBuffer[1]&255)<<16)+
		        ((readBuffer[2]&255)<<8)+
		        ((readBuffer[3]&255)<<0));
	}
	
	
	default long readUnsignedInt5() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 5);
		return (((long)(readBuffer[0]&255)<<32)+
		        ((long)(readBuffer[1]&255)<<24)+
		        ((readBuffer[2]&255)<<16)+
		        ((readBuffer[3]&255)<<8)+
		        ((readBuffer[4]&255)<<0));
	}
	
	default long readUnsignedInt6() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 6);
		return (((long)(readBuffer[0]&255)<<40)+
		        ((long)(readBuffer[1]&255)<<32)+
		        ((long)(readBuffer[2]&255)<<24)+
		        ((readBuffer[3]&255)<<16)+
		        ((readBuffer[4]&255)<<8)+
		        ((readBuffer[5]&255)<<0));
	}
	
	default long readInt8() throws IOException{
		byte[] readBuffer=contentBuf();
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
	
}
