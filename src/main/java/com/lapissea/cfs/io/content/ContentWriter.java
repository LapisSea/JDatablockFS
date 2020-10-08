package com.lapissea.cfs.io.content;

import com.lapissea.cfs.io.streams.ContentReaderOutputStream;
import com.lapissea.util.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@SuppressWarnings({"PointlessBitwiseExpression", "PointlessArithmeticExpression"})
public interface ContentWriter extends AutoCloseable, ContentBuff{
	
	void write(int b) throws IOException;
	
	default void write(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	void write(byte[] b, int off, int len) throws IOException;
	
	default void writeBoolean(boolean v) throws IOException{
		writeInt1(v?1:0);
	}
	
	
	default void writeInts1(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	default void writeInt1(int v) throws IOException{
		write(v);
	}
	
	default void writeInts2(int[] b) throws IOException{
		int    numSize=2;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt2(bb, i*numSize, b[i]);
		}
		write(bb);
	}
	
	default void writeInt2(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt2(writeBuffer, 0, v);
		write(writeBuffer, 0, 2);
	}
	
	private void writeInt2(byte[] writeBuffer, int off, int v){
		writeBuffer[off+0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[off+1]=(byte)((v >>> 0)&0xFF);
	}
	
	default void writeInts3(int[] b) throws IOException{
		int    numSize=3;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt3(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt3(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt3(writeBuffer, 0, v);
		write(writeBuffer, 0, 3);
	}
	
	private void writeInt3(byte[] writeBuffer, int off, int v){
		writeBuffer[off+0]=(byte)((v >>> 16)&0xFF);
		writeBuffer[off+1]=(byte)((v >>> 8)&0xFF);
		writeBuffer[off+2]=(byte)((v >>> 0)&0xFF);
	}
	
	default void writeInts4(int[] b) throws IOException{
		int    numSize=4;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt4(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt4(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt4(writeBuffer, 0, v);
		write(writeBuffer, 0, 4);
	}
	
	private void writeInt4(byte[] writeBuffer, int off, int v){
		writeBuffer[off+0]=(byte)((v >>> 24)&0xFF);
		writeBuffer[off+1]=(byte)((v >>> 16)&0xFF);
		writeBuffer[off+2]=(byte)((v >>> 8)&0xFF);
		writeBuffer[off+3]=(byte)((v >>> 0)&0xFF);
	}
	
	default void writeInts5(long[] b) throws IOException{
		int    numSize=5;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt5(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt5(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt5(writeBuffer, 0, v);
		write(writeBuffer, 0, 5);
	}
	
	private void writeInt5(byte[] writeBuffer, int off, long v){
		writeBuffer[off+0]=(byte)(v >>> 32);
		writeBuffer[off+1]=(byte)(v >>> 24);
		writeBuffer[off+2]=(byte)(v >>> 16);
		writeBuffer[off+3]=(byte)(v >>> 8);
		writeBuffer[off+4]=(byte)(v >>> 0);
	}
	
	default void writeInts6(long[] b) throws IOException{
		int    numSize=6;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt6(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt6(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt6(writeBuffer, 0, v);
		write(writeBuffer, 0, 6);
	}
	
	private void writeInt6(byte[] writeBuffer, int off, long v){
		writeBuffer[off+0]=(byte)(v >>> 40);
		writeBuffer[off+1]=(byte)(v >>> 32);
		writeBuffer[off+2]=(byte)(v >>> 24);
		writeBuffer[off+3]=(byte)(v >>> 16);
		writeBuffer[off+4]=(byte)(v >>> 8);
		writeBuffer[off+5]=(byte)(v >>> 0);
	}
	
	default void writeInts8(long[] b) throws IOException{
		int    numSize=8;
		byte[] bb     =new byte[b.length*numSize];
		for(int i=0;i<b.length;i++){
			writeInt8(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt8(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeInt8(writeBuffer, 0, v);
		write(writeBuffer, 0, 8);
	}
	
	private void writeInt8(byte[] writeBuffer, int off, long v){
		writeBuffer[off+0]=(byte)(v >>> 56);
		writeBuffer[off+1]=(byte)(v >>> 48);
		writeBuffer[off+2]=(byte)(v >>> 40);
		writeBuffer[off+3]=(byte)(v >>> 32);
		writeBuffer[off+4]=(byte)(v >>> 24);
		writeBuffer[off+5]=(byte)(v >>> 16);
		writeBuffer[off+6]=(byte)(v >>> 8);
		writeBuffer[off+7]=(byte)(v >>> 0);
	}
	
	default void writeFloats4(float[] f) throws IOException{
		int    numSize=4;
		byte[] bb     =new byte[f.length*numSize];
		for(int i=0;i<f.length;i++){
			writeFloat4(bb, i*numSize, f[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeFloat4(float v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeFloat4(writeBuffer, 0, v);
		write(writeBuffer, 0, 4);
	}
	
	private void writeFloat4(byte[] writeBuffer, int off, float v){
		writeInt4(writeBuffer, off, Float.floatToIntBits(v));
	}
	
	default void writeFloats8(double[] f) throws IOException{
		int    numSize=8;
		byte[] bb     =new byte[f.length*numSize];
		for(int i=0;i<f.length;i++){
			writeFloat8(bb, i*numSize, f[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeFloat8(double v) throws IOException{
		writeInt8(Double.doubleToLongBits(v));
	}
	
	private void writeFloat8(byte[] writeBuffer, int off, double v){
		writeInt8(writeBuffer, off, Double.doubleToLongBits(v));
	}
	
	default void writeChars2(String c) throws IOException{
		int    numSize=2;
		byte[] bb     =new byte[c.length()*numSize];
		for(int i=0;i<c.length();i++){
			writeChar2(bb, i*numSize, c.charAt(i));
		}
		write(bb, 0, bb.length);
	}
	
	default void writeChars2(char[] c) throws IOException{
		int    numSize=2;
		byte[] bb     =new byte[c.length*numSize];
		for(int i=0;i<c.length;i++){
			writeChar2(bb, i*numSize, c[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeChar2(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeChar2(writeBuffer, 0, v);
		write(writeBuffer, 0, 2);
	}
	
	private void writeChar2(byte[] writeBuffer, int off, int v){
		writeBuffer[off+0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[off+1]=(byte)((v >>> 0)&0xFF);
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
