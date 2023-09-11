package com.lapissea.cfs.io.content;

import com.lapissea.cfs.BufferErrorSupplier;
import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.BiIntConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static com.lapissea.cfs.config.GlobalConfig.BATCH_BYTES;

@SuppressWarnings({"PointlessBitwiseExpression", "PointlessArithmeticExpression", "unused"})
public interface ContentWriter extends AutoCloseable{
	
	default void write(ByteBuffer b) throws IOException{
		write(b, b.position(), b.remaining());
	}
	
	default void write(ByteBuffer b, int off, int len) throws IOException{
		if(len == 0) return;
		if(b.hasArray() && !b.isReadOnly()){
			write(b.array(), off + b.arrayOffset(), len);
		}else{
			byte[] buff = new byte[MathUtil.snap(len, 8, BATCH_BYTES)];
			
			int remaining = len;
			int pos       = off + b.position();
			while(remaining>0){
				int toCpy = Math.min(remaining, buff.length);
				b.get(pos, buff, 0, toCpy);
				pos += toCpy;
				remaining -= toCpy;
				this.write(buff, 0, toCpy);
			}
		}
	}
	void write(int b) throws IOException;
	
	default void write(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	void write(byte[] b, int off, int len) throws IOException;
	
	default void writeWord(long v, int len) throws IOException{
		byte[]    writeBuffer = new byte[len];
		final var lm1         = len - 1;
		
		for(int i = 0; i<len; i++){
			writeBuffer[i] = (byte)(v >>> (i*8));
		}
		
		write(writeBuffer, 0, len);
	}
	
	default void writeBoolean(boolean v) throws IOException{
		writeInt1(v? 1 : 0);
	}
	
	
	default void writeInts1(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	default void writeInt1(int v) throws IOException{
		write(v);
	}
	
	default void writeInts2(int[] b) throws IOException{
		int    numSize = 2;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt2(bb, i*numSize, b[i]);
		}
		write(bb);
	}
	
	default void writeInt2(int v) throws IOException{
		writeWord(Integer.toUnsignedLong(v), 2);
	}
	
	default void writeInts3(int[] b) throws IOException{
		int    numSize = 3;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt3(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt3(int v) throws IOException{
		writeWord(v, 3);
	}
	
	default void writeInts4(int[] b) throws IOException{
		int    numSize = 4;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt4(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeUnsignedInt4Dynamic(int v) throws IOException{
		var siz = NumberSize.bySize(v);
		FlagWriter.writeSingle(this, NumberSize.FLAG_INFO, siz);
		if(siz != NumberSize.VOID) siz.writeInt(this, v);
	}
	
	default void writeInt4Dynamic(int v) throws IOException{
		var siz = NumberSize.bySizeSigned(v);
		FlagWriter.writeSingle(this, NumberSize.FLAG_INFO, siz);
		if(siz != NumberSize.VOID) siz.writeIntSigned(this, v);
	}
	
	default void writeInt4(int v) throws IOException{
		writeWord(Integer.toUnsignedLong(v), 4);
	}
	
	default void writeInts5(long[] b) throws IOException{
		int    numSize = 5;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt5(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt5(long v) throws IOException{
		writeWord(v, 5);
	}
	
	default void writeInts6(long[] b) throws IOException{
		int    numSize = 6;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt6(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeInt6(long v) throws IOException{
		writeWord(v, 6);
	}
	
	default void writeInts8(long[] b) throws IOException{
		int    numSize = 8;
		byte[] bb      = new byte[b.length*numSize];
		for(int i = 0; i<b.length; i++){
			ContentSupport.writeInt8(bb, i*numSize, b[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeUnsignedInt8Dynamic(long v) throws IOException{
		var siz = NumberSize.bySize(v);
		FlagWriter.writeSingle(this, NumberSize.FLAG_INFO, siz);
		siz.write(this, v);
	}
	default void writeInt8Dynamic(long v) throws IOException{
		var siz = NumberSize.bySizeSigned(Math.abs(v));
		FlagWriter.writeSingle(this, NumberSize.FLAG_INFO, siz);
		siz.writeSigned(this, v);
	}
	
	default void writeInt8(long v) throws IOException{
		writeWord(v, 8);
	}
	
	default void writeFloats4(float[] f) throws IOException{
		int    numSize = 4;
		byte[] bb      = new byte[f.length*numSize];
		for(int i = 0; i<f.length; i++){
			ContentSupport.writeFloat4(bb, i*numSize, f[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeFloat4(float v) throws IOException{
		writeInt4(Float.floatToIntBits(v));
	}
	
	default void writeFloats8(double[] f) throws IOException{
		int    numSize = 8;
		byte[] bb      = new byte[f.length*numSize];
		for(int i = 0; i<f.length; i++){
			ContentSupport.writeFloat8(bb, i*numSize, f[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeFloat8(double v) throws IOException{
		writeInt8(Double.doubleToLongBits(v));
	}
	
	default void writeChars2(String c) throws IOException{
		int    numSize = 2;
		byte[] bb      = new byte[c.length()*numSize];
		for(int i = 0; i<c.length(); i++){
			ContentSupport.writeChar2(bb, i*numSize, c.charAt(i));
		}
		write(bb, 0, bb.length);
	}
	
	default void writeChars2(char[] c) throws IOException{
		int    numSize = 2;
		byte[] bb      = new byte[c.length*numSize];
		for(int i = 0; i<c.length; i++){
			ContentSupport.writeChar2(bb, i*numSize, c[i]);
		}
		write(bb, 0, bb.length);
	}
	
	default void writeChar2(char v) throws IOException{
		writeWord(v, 2);
	}
	
	//Code from DataOutputStream#writeUTF
	default void writeUTF(CharSequence str) throws IOException{
		int strLen = str.length(), utfLen = strLen;
		
		for(int i = 0; i<strLen; i++){
			int c = str.charAt(i);
			if(c>=0x80 || c == 0) utfLen += (c>=0x800)? 2 : 1;
		}
		
		var sizSiz   = NumberSize.bySize(utfLen);
		var sizBytes = 1 + sizSiz.bytes;
		var byteArr  = new byte[utfLen + sizBytes];
		
		try(var buf = new ContentOutputStream.BA(byteArr)){
			buf.writeUnsignedInt4Dynamic(utfLen);
		}
		
		int count = sizBytes;
		
		int i;
		for(i = 0; i<strLen; i++){ // optimized for initial run of ASCII
			int c = str.charAt(i);
			if(c>=0x80 || c == 0) break;
			byteArr[count++] = (byte)c;
		}
		
		for(; i<strLen; i++){
			int c = str.charAt(i);
			if(c<0x80 && c != 0){
				byteArr[count++] = (byte)c;
			}else if(c>=0x800){
				byteArr[count++] = (byte)(0xE0|((c>>12)&0x0F));
				byteArr[count++] = (byte)(0x80|((c>>6)&0x3F));
				byteArr[count++] = (byte)(0x80|((c>>0)&0x3F));
			}else{
				byteArr[count++] = (byte)(0xC0|((c>>6)&0x1F));
				byteArr[count++] = (byte)(0x80|((c>>0)&0x3F));
			}
		}
		write(byteArr);
	}
	
	default ContentOutputStream outStream(){ return new ContentReaderOutputStream(this); }
	
	@Override
	default void close() throws IOException{ }
	
	record BufferTicket(ContentWriter target, int amount, BufferErrorSupplier<? extends IOException> errorOnMismatch, BiIntConsumer<byte[]> onFinish){
		public BufferTicket requireExact(){
			return requireExact(BufferErrorSupplier.DEFAULT_WRITE);
		}
		public BufferTicket requireExact(BufferErrorSupplier<? extends IOException> errorOnMismatch){
			return new BufferTicket(target, amount, errorOnMismatch, onFinish);
		}
		public BufferTicket onFinish(BiIntConsumer<byte[]> onFinish){
			return new BufferTicket(target, amount, errorOnMismatch, onFinish);
		}
		
		public final class WriteArrayBuffer extends ByteArrayOutputStream implements ContentWriter{
			
			private WriteArrayBuffer(){
				super(amount);
			}
			
			@Override
			public void close() throws IOException{
				if(errorOnMismatch != null){
					if(count != amount) throw errorOnMismatch.apply(this.size(), amount);
				}
				if(onFinish != null){
					onFinish.accept(count, buf);
				}
				if(count == 1){
					target.write(buf[0]);
				}else{
					target.write(buf, 0, count);
				}
			}
			
			@Override
			public void write(byte[] b, int off, int len){
				super.write(b, off, len);
				earlyCheck();
			}
			@Override
			public void writeWord(long v, int len){
				if(count + len<=buf.length){
					MemPrimitive.setWord(v, buf, count, len);
				}
				count += len;
				earlyCheck();
			}
			
			@Override
			public void write(int b){
				super.write(b);
				earlyCheck();
			}
			private void earlyCheck(){
				if(errorOnMismatch != null){
					if(count>amount) throw UtilL.uncheckedThrow(errorOnMismatch.apply(this.size(), amount));
				}
			}
			
			@Override
			public synchronized String toString(){
				return new String(buf, 0, Math.max(amount, count));
			}
		}
		
		public WriteArrayBuffer submit(){
			return new WriteArrayBuffer();
		}
	}
	
	default BufferTicket writeTicket(long amount){ return writeTicket(Math.toIntExact(amount)); }
	default BufferTicket writeTicket(int amount) { return new BufferTicket(this, amount, null, null); }
	
	/**
	 * @return a flag that signifies if this writer has significant overhead when calling many small write operations.<br>
	 * true = writing to this object has noticeable overhead. Write calls should be buffered.<br>
	 * false = cost of writing is minimal or none, buffering may not increase performance
	 */
	default boolean isDirect(){
		return false;
	}
}
