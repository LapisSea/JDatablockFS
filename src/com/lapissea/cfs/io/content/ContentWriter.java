package com.lapissea.cfs.io.content;

import com.lapissea.cfs.io.streams.ContentReaderOutputStream;
import com.lapissea.util.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@SuppressWarnings("PointlessBitwiseExpression")
public interface ContentWriter extends AutoCloseable, ContentBuff{
	
	default void writeChar(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[1]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 2);
	}
	
	void write(int b) throws IOException;
	
	default void write(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	void write(byte[] b, int off, int len) throws IOException;
	
	default void writeBoolean(boolean v) throws IOException{
		writeInt1(v?1:0);
	}
	
	default void writeInt1(int v) throws IOException{
		write(v);
	}
	
	default void writeInt2(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[1]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 2);
		
	}
	
	default void writeInt3(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 16)&0xFF);
		writeBuffer[1]=(byte)((v >>> 8)&0xFF);
		writeBuffer[2]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 3);
	}
	
	default void writeInt4(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 24)&0xFF);
		writeBuffer[1]=(byte)((v >>> 16)&0xFF);
		writeBuffer[2]=(byte)((v >>> 8)&0xFF);
		writeBuffer[3]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 4);
	}
	
	default void writeInt5(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 32);
		writeBuffer[1]=(byte)(v >>> 24);
		writeBuffer[2]=(byte)(v >>> 16);
		writeBuffer[3]=(byte)(v >>> 8);
		writeBuffer[4]=(byte)(v >>> 0);
		write(writeBuffer, 0, 5);
	}
	
	default void writeInt6(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 40);
		writeBuffer[1]=(byte)(v >>> 32);
		writeBuffer[2]=(byte)(v >>> 24);
		writeBuffer[3]=(byte)(v >>> 16);
		writeBuffer[4]=(byte)(v >>> 8);
		writeBuffer[5]=(byte)(v >>> 0);
		write(writeBuffer, 0, 6);
	}
	
	default void writeInt8(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 56);
		writeBuffer[1]=(byte)(v >>> 48);
		writeBuffer[2]=(byte)(v >>> 40);
		writeBuffer[3]=(byte)(v >>> 32);
		writeBuffer[4]=(byte)(v >>> 24);
		writeBuffer[5]=(byte)(v >>> 16);
		writeBuffer[6]=(byte)(v >>> 8);
		writeBuffer[7]=(byte)(v >>> 0);
		write(writeBuffer, 0, 8);
	}
	
	default void writeFloat4(float v) throws IOException{
		writeInt4(Float.floatToIntBits(v));
	}
	
	default void writeFloat8(double v) throws IOException{
		writeInt8(Double.doubleToLongBits(v));
	}
	
	default ContentOutputStream outStream(){return new ContentReaderOutputStream(this);}
	
	@Override
	default void close() throws IOException{}
	
	default ContentWriter bufferExactWrite(long amount)                { return bufferExactWrite(amount, true); }
	default ContentWriter bufferExactWrite(int amount)                 { return bufferExactWrite(amount, true); }
	
	default ContentWriter bufferExactWrite(long amount, boolean finish){ return bufferExactWrite(Math.toIntExact(amount), finish); }
	default ContentWriter bufferExactWrite(int amount, boolean finish) { return bufferExactWrite(amount, finish?BufferErrorSupplier.DEFAULT_WRITE:null); }
	
	default ContentWriter bufferExactWrite(long amount, @Nullable BufferErrorSupplier<? extends IOException> errorOnMismatch){
		return bufferExactWrite(Math.toIntExact(amount), errorOnMismatch);
	}
	default ContentWriter bufferExactWrite(int amount, @Nullable BufferErrorSupplier<? extends IOException> errorOnMismatch){
		ContentWriter that=this;
		class WriteArrayBuffer extends ByteArrayOutputStream implements ContentWriter{
			
			public WriteArrayBuffer(){
				super(amount);
			}
			
			@Override
			public synchronized void close() throws IOException{
				if(errorOnMismatch!=null){
					if(count!=amount) throw errorOnMismatch.apply(this.size(), amount);
				}
				that.write(buf, 0, count);
			}
			
			@Override
			public synchronized String toString(){
				return new String(buf, 0, Math.max(amount, count));
			}
		}
		
		return new WriteArrayBuffer();
	}
	
	static boolean isDirect(ContentWriter out){
		return
			out instanceof ByteArrayOutputStream||
			out instanceof ContentOutputStream.BA||
			out instanceof ContentOutputStream.BB;
	}
}
