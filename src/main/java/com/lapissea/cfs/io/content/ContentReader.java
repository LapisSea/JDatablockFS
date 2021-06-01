package com.lapissea.cfs.io.content;

import com.lapissea.cfs.BufferErrorSupplier;
import com.lapissea.cfs.io.ContentBuff;
import com.lapissea.util.ZeroArrays;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

@SuppressWarnings({"PointlessBitwiseExpression", "PointlessArithmeticExpression"})
public interface ContentReader extends AutoCloseable, ContentBuff{
	
	default void read(ByteBuffer buff) throws IOException{
		int b=read();
		if(b<0) throw new EOFException();
		buff.put((byte)b);
	}
	default int read(ByteBuffer buff, int length) throws IOException{
		if(length>buff.remaining()) throw new IndexOutOfBoundsException("reading "+length+" remaining "+buff.remaining());
		
		if(buff.hasArray()&&!buff.isReadOnly()){
			return read(buff.array(), buff.position(), length);
		}
		byte[] bb  =new byte[Math.min(length, 1024)];
		int    read=read(bb);
		if(read>0){
			buff.put(bb, 0, read);
		}
		return read;
	}
	
	int read() throws IOException;
	
	default int read(byte[] b) throws IOException{
		return read(b, 0, b.length);
	}
	
	int read(byte[] b, int off, int len) throws IOException;
	
	/**
	 * @return number of bytes skipped
	 */
	long skip(long toSkip) throws IOException;
	
	default char[] readChars2(int elementsToRead) throws IOException{
		int bytesPerElement =2;
		int elementsPerChunk=Math.min(elementsToRead, 256);
		
		var result=new char[elementsToRead];
		var buff  =new byte[elementsPerChunk*bytesPerElement];
		
		int remaining=result.length;
		while(remaining>0){
			int read=result.length-remaining;
			
			int readElements=Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i=0;i<readElements;i++){
				result[read+i]=readChar2(buff, i*bytesPerElement);
			}
			
			remaining-=readElements;
		}
		
		return result;
	}
	
	default char readChar2() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return readChar2(readBuffer, 0);
	}
	
	private char readChar2(byte[] readBuffer, int offset){
		return (char)((readBuffer[offset+0]<<8)+
		              (readBuffer[offset+1]<<0));
	}
	
	
	default int readUnsignedInt1() throws IOException{
		int ch=read();
		if(ch<0) throw new EOFException();
		return ch;
	}
	
	default int[] readUnsignedInts1(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 1, (buff, i)->Byte.toUnsignedInt(buff[i]));
	}
	
	default boolean readBoolean() throws IOException{
		return readUnsignedInt1()!=0;
	}
	
	default byte readInt1() throws IOException{
		return (byte)readUnsignedInt1();
	}
	
	default byte[] readInts1(int elementsToRead) throws IOException{
		byte[] result=new byte[elementsToRead];
		readFully(result);
		return result;
	}
	
	
	default short[] readInts2(int elementsToRead) throws IOException{
		int bytesPerElement =2;
		int elementsPerChunk=Math.min(elementsToRead, 256);
		
		var result=new short[elementsToRead];
		var buff  =new byte[elementsPerChunk*bytesPerElement];
		
		int remaining=result.length;
		while(remaining>0){
			int read=result.length-remaining;
			
			int readElements=Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i=0;i<readElements;i++){
				result[read+i]=readInt2(buff, i*bytesPerElement);
			}
			
			remaining-=readElements;
		}
		
		return result;
	}
	
	default short readInt2() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return readInt2(readBuffer, 0);
	}
	private short readInt2(byte[] readBuffer, int offset){
		return (short)(((readBuffer[offset+0]&255)<<8)+
		               ((readBuffer[offset+1]&255)<<0));
	}
	
	
	default int[] readUnsignedInts2(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 2, this::readUnsignedInt2);
	}
	
	default int readUnsignedInt2() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 2);
		return readUnsignedInt2(readBuffer, 0);
	}
	private int readUnsignedInt2(byte[] readBuffer, int offset){
		return ((readBuffer[offset+0]&255)<<8)+
		       ((readBuffer[offset+1]&255)<<0);
	}
	
	
	default int[] readUnsignedInts3(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 3, this::readUnsignedInt3);
	}
	
	default int readUnsignedInt3() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 3);
		return readUnsignedInt3(readBuffer, 0);
	}
	private int readUnsignedInt3(byte[] readBuffer, int offset){
		return (((readBuffer[offset+0]&255)<<16)+
		        ((readBuffer[offset+1]&255)<<8)+
		        ((readBuffer[offset+2]&255)<<0));
	}
	
	
	default long[] readUnsignedInts4(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 4, this::readUnsignedInt4);
	}
	
	default long readUnsignedInt4() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 4);
		return readUnsignedInt4(readBuffer, 0);
	}
	default long readUnsignedInt4(byte[] readBuffer, int offset){
		return readInt4(readBuffer, offset)&0xFFFFFFFFL;
	}
	
	
	default int[] readInts4(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 4, this::readInt4);
	}
	
	default int readInt4() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 4);
		return readInt4(readBuffer, 0);
	}
	private int readInt4(byte[] readBuffer, int offset){
		return (((readBuffer[offset+0]&255)<<24)+
		        ((readBuffer[offset+1]&255)<<16)+
		        ((readBuffer[offset+2]&255)<<8)+
		        ((readBuffer[offset+3]&255)<<0));
	}
	
	
	default long[] readUnsignedInts5(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 5, this::readUnsignedInt5);
	}
	
	default long readUnsignedInt5() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 5);
		return readUnsignedInt5(readBuffer, 0);
	}
	private long readUnsignedInt5(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset+0]&255)<<32)+
		        ((long)(readBuffer[offset+1]&255)<<24)+
		        ((readBuffer[offset+2]&255)<<16)+
		        ((readBuffer[offset+3]&255)<<8)+
		        ((readBuffer[offset+4]&255)<<0));
	}
	
	
	default long[] readUnsignedInt6(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 6, this::readUnsignedInt6);
	}
	
	default long readUnsignedInt6() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 6);
		return readUnsignedInt6(readBuffer, 0);
	}
	private long readUnsignedInt6(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset+0]&255)<<40)+
		        ((long)(readBuffer[offset+1]&255)<<32)+
		        ((long)(readBuffer[offset+2]&255)<<24)+
		        ((readBuffer[offset+3]&255)<<16)+
		        ((readBuffer[offset+4]&255)<<8)+
		        ((readBuffer[offset+5]&255)<<0));
	}
	
	default long[] readInts8(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 8, this::readInt8);
	}
	
	default long readInt8() throws IOException{
		byte[] readBuffer=contentBuf();
		readFully(readBuffer, 0, 8);
		return readInt8(readBuffer, 0);
	}
	private long readInt8(byte[] readBuffer, int offset){
		return (((long)readBuffer[offset+0]<<56)+
		        ((long)(readBuffer[offset+1]&255)<<48)+
		        ((long)(readBuffer[offset+2]&255)<<40)+
		        ((long)(readBuffer[offset+3]&255)<<32)+
		        ((long)(readBuffer[offset+4]&255)<<24)+
		        ((readBuffer[offset+5]&255)<<16)+
		        ((readBuffer[offset+6]&255)<<8)+
		        ((readBuffer[offset+7]&255)<<0));
	}
	
	
	private int[] readInts(int elementsToRead, int bytesPerElement, ReadIntFromBuff reader) throws IOException{
		int elementsPerChunk=Math.min(elementsToRead, 256);
		
		var result=new int[elementsToRead];
		var buff  =new byte[elementsPerChunk*bytesPerElement];
		
		int remaining=result.length;
		while(remaining>0){
			int read=result.length-remaining;
			
			int readElements=Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i=0;i<readElements;i++){
				result[read+i]=reader.read(buff, i*bytesPerElement);
			}
			
			remaining-=readElements;
		}
		
		return result;
	}
	
	private long[] readLongs(int elementsToRead, int bytesPerElement, ReadLongFromBuff reader) throws IOException{
		int elementsPerChunk=Math.min(elementsToRead, 256);
		
		var result=new long[elementsToRead];
		var buff  =new byte[elementsPerChunk*bytesPerElement];
		
		int remaining=result.length;
		while(remaining>0){
			int read=result.length-remaining;
			
			int readElements=Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i=0;i<readElements;i++){
				result[read+i]=reader.read(buff, i*bytesPerElement);
			}
			
			remaining-=readElements;
		}
		
		return result;
	}
	
	
	default byte[] readFully(byte[] b) throws IOException{
		return readFully(b, 0, b.length);
	}
	
	default byte[] readFully(byte[] b, int off, int len) throws IOException{
		Objects.requireNonNull(b);
		if(len<0) throw new IndexOutOfBoundsException();
		
		int n=0;
		while(n<len){
			int count=read(b, off+n, len-n);
			if(count<0){
				throw new EOFException("Underflow! requested="+len+", read="+n+", remaining="+(len-n));
			}
			n+=count;
		}
		return b;
	}
	
	default byte[] readRemaining() throws IOException{
		return inStream().readAllBytes();
	}
	
	default ContentInputStream inStream(){return new ContentReaderInputStream(this);}
	
	@Override
	default void close() throws IOException{}
	
	
	record BufferTicket(ContentReader target, int amount, BufferErrorSupplier<? extends IOException> errorOnMismatch){
		public BufferTicket requireExact(){
			return requireExact(BufferErrorSupplier.DEFAULT_READ);
		}
		public BufferTicket requireExact(BufferErrorSupplier<? extends IOException> errorOnMismatch){
			return new BufferTicket(target, amount, errorOnMismatch);
		}
		
		public ContentReader submit() throws IOException{
			if(amount==0) return new ContentInputStream.BA(ZeroArrays.ZERO_BYTE);
			
			return new ContentInputStream.BA(target.readInts1(amount)){
				@Override
				public void close() throws IOException{
					super.close();
					if(errorOnMismatch!=null){
						var av=available();
						if(av>0) throw errorOnMismatch.apply(getPos(), amount);
					}
				}
			};
		}
	}
	
	default BufferTicket readTicket(long amount){ return readTicket(Math.toIntExact(amount)); }
	default BufferTicket readTicket(int amount) { return new BufferTicket(this, amount, null); }
	
	static boolean isDirect(ContentReader in){
		return
			in instanceof ContentInputStream.BA||
			in instanceof ContentInputStream.BB||
			in instanceof ByteArrayInputStream;
	}
	
	default long transferTo(ContentWriter out) throws IOException{
		Objects.requireNonNull(out);
		long   transferred=0;
		byte[] buffer     =new byte[8192];
		int    read;
		while((read=this.read(buffer, 0, 8192))>=0){
			out.write(buffer, 0, read);
			transferred+=read;
		}
		return transferred;
	}
}
