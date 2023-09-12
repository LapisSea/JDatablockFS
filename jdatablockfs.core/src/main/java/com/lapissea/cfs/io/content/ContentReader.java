package com.lapissea.cfs.io.content;

import com.lapissea.cfs.BufferErrorSupplier;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.ZeroArrays;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.lapissea.cfs.config.GlobalConfig.BATCH_BYTES;

@SuppressWarnings({"PointlessArithmeticExpression", "PointlessBitwiseExpression", "unused", "UnusedReturnValue"})
public interface ContentReader extends AutoCloseable{
	
	default void read(ByteBuffer buff) throws IOException{
		buff.put((byte)tryRead());
	}
	default int read(ByteBuffer buff, int length) throws IOException{
		if(length>buff.remaining()) throw new IndexOutOfBoundsException("reading " + length + " remaining " + buff.remaining());
		
		if(buff.hasArray() && !buff.isReadOnly()){
			return read(buff.array(), buff.position() + buff.arrayOffset(), length);
		}
		byte[] bb   = new byte[Math.min(length, BATCH_BYTES)];
		int    read = read(bb);
		if(read>0){
			buff.put(bb, 0, read);
		}
		return read;
	}
	
	default int tryRead() throws IOException{
		var b = read();
		if(b<0) throw new EOFException();
		return b;
	}
	
	int read() throws IOException;
	
	default int read(byte[] b) throws IOException{
		return read(b, 0, b.length);
	}
	
	int read(byte[] b, int off, int len) throws IOException;
	
	default long readWord(final int len) throws IOException{
		if(len == 0) return 0;
		if(len<0 || len>8){
			throw new IllegalArgumentException();
		}
		
		byte[] readBuffer = new byte[len];
		readFully(readBuffer, 0, len);
		
		long val = 0;
		for(int i = 0; i<len; i++){
			val |= (readBuffer[i]&0xFFL)<<(i*8);
		}
		return val;
	}
	/**
	 * @return number of bytes skipped
	 */
	default long skip(NumberSize toSkip) throws IOException{
		return skip(toSkip.bytes);
	}
	/**
	 * @return number of bytes skipped
	 */
	long skip(long toSkip) throws IOException;
	
	
	default void skipExact(NumberSize toSkip) throws IOException{
		skipExact(toSkip.bytes);
	}
	
	default boolean optionallySkipExact(OptionalLong toSkip) throws IOException{
		if(toSkip.isPresent()){
			skipExact(toSkip.getAsLong());
			return true;
		}
		return false;
	}
	default boolean optionallySkipExact(OptionalInt toSkip) throws IOException{
		if(toSkip.isPresent()){
			skipExact(toSkip.getAsInt());
			return true;
		}
		return false;
	}
	
	private static void failSkip(long toSkip, long skipped) throws IOException{
		throw new IOException("Failed to skip " + toSkip + " bytes! Actually skipped: " + skipped);
	}
	default void skipExact(long toSkip) throws IOException{
		if(toSkip<0) throw new IllegalArgumentException("toSkip can not be negative");
		long skipSum = 0;
		while(skipSum<toSkip){
			long remaining = toSkip - skipSum;
			var  skipped   = skip(remaining);
			if(skipped == 0) failSkip(toSkip, skipSum);
			skipSum += skipped;
		}
		if(skipSum != toSkip) failSkip(toSkip, skipSum);
	}
	
	default char[] readChars2(int elementsToRead) throws IOException{
		int bytesPerElement  = 2;
		int elementsPerChunk = Math.min(elementsToRead, 256);
		
		var result = new char[elementsToRead];
		var buff   = new byte[elementsPerChunk*bytesPerElement];
		
		int remaining = result.length;
		while(remaining>0){
			int read = result.length - remaining;
			
			int readElements = Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i = 0; i<readElements; i++){
				result[read + i] = BBView.readChar2(buff, i*bytesPerElement);
			}
			
			remaining -= readElements;
		}
		
		return result;
	}
	
	default char readChar2() throws IOException{
		return (char)readWord(2);
	}
	
	
	default int readUnsignedInt1() throws IOException{
		return tryRead();
	}
	
	default int[] readUnsignedInts1(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 1, (buff, i) -> Byte.toUnsignedInt(buff[i]));
	}
	
	default boolean readBoolean() throws IOException{
		return readUnsignedInt1() != 0;
	}
	
	default byte readInt1() throws IOException{
		return (byte)readUnsignedInt1();
	}
	
	default byte[] readInts1(int elementsToRead) throws IOException{
		byte[] result = new byte[elementsToRead];
		readFully(result);
		return result;
	}
	
	
	default short[] readInts2(int elementsToRead) throws IOException{
		int bytesPerElement  = 2;
		int elementsPerChunk = Math.min(elementsToRead, 256);
		
		var result = new short[elementsToRead];
		var buff   = new byte[elementsPerChunk*bytesPerElement];
		
		int remaining = result.length;
		while(remaining>0){
			int read = result.length - remaining;
			
			int readElements = Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i = 0; i<readElements; i++){
				result[read + i] = BBView.readInt2(buff, i*bytesPerElement);
			}
			
			remaining -= readElements;
		}
		
		return result;
	}
	
	default short readInt2() throws IOException{
		return (short)readWord(2);
	}
	
	
	default int[] readUnsignedInts2(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 2, BBView::readUnsignedInt2);
	}
	
	default int readUnsignedInt2() throws IOException{
		return (int)readWord(2);
	}
	
	
	default int[] readUnsignedInts3(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 3, BBView::readUnsignedInt3);
	}
	
	default int readUnsignedInt3() throws IOException{
		return (int)readWord(3);
	}
	
	
	default long[] readUnsignedInts4(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 4, this::readUnsignedInt4);
	}
	
	default int readUnsignedInt4Dynamic() throws IOException{
		NumberSize size = FlagReader.readSingle(this, NumberSize.FLAG_INFO);
		return size.readInt(this);
	}
	default int readInt4Dynamic() throws IOException{
		NumberSize size = FlagReader.readSingle(this, NumberSize.FLAG_INFO);
		return size.readIntSigned(this);
	}
	
	default long readUnsignedInt4() throws IOException{
		return readWord(4);
	}
	default long readUnsignedInt4(byte[] readBuffer, int offset){
		return BBView.readInt4(readBuffer, offset)&0xFFFFFFFFL;
	}
	
	
	default int[] readInts4(int elementsToRead) throws IOException{
		return readInts(elementsToRead, 4, BBView::readInt4);
	}
	
	default int readInt4() throws IOException{
		return (int)readWord(4);
	}
	
	
	default long[] readUnsignedInts5(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 5, BBView::readUnsignedInt5);
	}
	
	default long readUnsignedInt5() throws IOException{
		return readWord(5);
	}
	
	
	default long[] readUnsignedInt6(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 6, BBView::readUnsignedInt6);
	}
	
	default long readUnsignedInt6() throws IOException{
		return readWord(6);
	}
	
	default long[] readInts8(int elementsToRead) throws IOException{
		return readLongs(elementsToRead, 8, BBView::readInt8);
	}
	
	default long readUnsignedInt8Dynamic() throws IOException{
		NumberSize size = FlagReader.readSingle(this, NumberSize.FLAG_INFO);
		return size.read(this);
	}
	default long readInt8Dynamic() throws IOException{
		NumberSize size = FlagReader.readSingle(this, NumberSize.FLAG_INFO);
		return size.readSigned(this);
	}
	
	default long readInt8() throws IOException{
		return readWord(8);
	}
	
	
	private int[] readInts(int elementsToRead, int bytesPerElement, ReadIntFromBuff reader) throws IOException{
		int elementsPerChunk = Math.min(elementsToRead, 256);
		
		var result = new int[elementsToRead];
		var buff   = new byte[elementsPerChunk*bytesPerElement];
		
		int remaining = result.length;
		while(remaining>0){
			int read = result.length - remaining;
			
			int readElements = Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i = 0; i<readElements; i++){
				result[read + i] = reader.read(buff, i*bytesPerElement);
			}
			
			remaining -= readElements;
		}
		
		return result;
	}
	
	private long[] readLongs(int elementsToRead, int bytesPerElement, ReadLongFromBuff reader) throws IOException{
		int elementsPerChunk = Math.min(elementsToRead, 256);
		
		var result = new long[elementsToRead];
		var buff   = new byte[elementsPerChunk*bytesPerElement];
		
		int remaining = result.length;
		while(remaining>0){
			int read = result.length - remaining;
			
			int readElements = Math.min(remaining, elementsPerChunk);
			readFully(buff, 0, readElements*bytesPerElement);
			
			for(int i = 0; i<readElements; i++){
				result[read + i] = reader.read(buff, i*bytesPerElement);
			}
			
			remaining -= readElements;
		}
		
		return result;
	}
	
	default float readFloat4() throws IOException{
		return Float.intBitsToFloat((int)readWord(4));
	}
	default float[] readFloats4(int count) throws IOException{
		var buff = new float[count];
		readFloats4(buff);
		return buff;
	}
	default void readFloats4(float[] f) throws IOException{
		int    numSize = 4;
		byte[] bb      = readInts1(f.length*numSize);
		for(int i = 0; i<f.length; i++){
			f[i] = BBView.readFloat4(bb, i*numSize);
		}
	}
	
	default double readFloat8() throws IOException{
		return Double.longBitsToDouble(readWord(4));
	}
	default double[] readFloats8(int count) throws IOException{
		var buff = new double[count];
		readFloats8(buff);
		return buff;
	}
	default void readFloats8(double[] f) throws IOException{
		int    numSize = 8;
		byte[] bb      = readInts1(f.length*numSize);
		for(int i = 0; i<f.length; i++){
			f[i] = BBView.readFloat8(bb, i*numSize);
		}
	}
	
	default byte[] readFully(byte[] b) throws IOException{
		return readFully(b, 0, b.length);
	}
	
	default byte[] readFully(byte[] b, int off, int len) throws IOException{
		Objects.requireNonNull(b);
		if(len<0) throw new IndexOutOfBoundsException();
		
		int n = 0;
		while(n<len){
			int count = read(b, off + n, len - n);
			if(count<0){
				throw new EOFException("Underflow! requested=" + len + ", read=" + n + ", remaining=" + (len - n));
			}
			n += count;
		}
		return b;
	}
	
	default byte[] readRemaining() throws IOException{
		return new ContentReaderInputStream(this).readAllBytes();
	}
	
	//Code from DataInputStream#readUTF
	default String readUTF() throws IOException{
		var utfLen  = readUnsignedInt4Dynamic();
		var byteArr = new byte[utfLen];
		var charArr = new char[utfLen];
		
		int c, char2, char3, count = 0, charArrCount = 0;
		
		readFully(byteArr, 0, utfLen);
		
		while(count<utfLen){
			c = (int)byteArr[count]&0xff;
			if(c>127) break;
			count++;
			charArr[charArrCount++] = (char)c;
		}
		
		while(count<utfLen){
			c = (int)byteArr[count]&0xff;
			switch(c>>4){
				case 0, 1, 2, 3, 4, 5, 6, 7 -> {
					count++;
					charArr[charArrCount++] = (char)c;
				}
				case 12, 13 -> {
					count += 2;
					if(count>utfLen) throw new UTFDataFormatException("malformed input: partial character at end");
					char2 = byteArr[count - 1];
					if((char2&0xC0) != 0x80) throw new UTFDataFormatException("malformed input around byte " + count);
					charArr[charArrCount++] = (char)(((c&0x1F)<<6)|(char2&0x3F));
				}
				case 14 -> {
					count += 3;
					if(count>utfLen) throw new UTFDataFormatException("malformed input: partial character at end");
					char2 = byteArr[count - 2];
					char3 = byteArr[count - 1];
					if(((char2&0xC0) != 0x80) || ((char3&0xC0) != 0x80)) throw new UTFDataFormatException("malformed input around byte " + (count - 1));
					charArr[charArrCount++] = (char)(((c&0x0F)<<12)|((char2&0x3F)<<6)|((char3&0x3F)<<0));
				}
				default -> throw new UTFDataFormatException("malformed input around byte " + count);
			}
		}
		return new String(charArr, 0, charArrCount);
	}
	
	default ContentInputStream inStream(){ return new ContentReaderInputStream(this); }
	
	@Override
	default void close() throws IOException{ }
	
	
	record BufferTicket(ContentReader target, int amount, BufferErrorSupplier<? extends IOException> errorOnMismatch){
		public BufferTicket requireExact(){
			return requireExact(BufferErrorSupplier.DEFAULT_READ);
		}
		public BufferTicket requireExact(BufferErrorSupplier<? extends IOException> errorOnMismatch){
			return new BufferTicket(target, amount, errorOnMismatch);
		}
		
		public ContentInputStream submit() throws IOException{
			if(amount == 0) return new ContentInputStream.BA(ZeroArrays.ZERO_BYTE);
			
			return new ContentInputStream.BA(target.readInts1(amount)){
				@Override
				public void close() throws IOException{
					super.close();
					if(errorOnMismatch != null){
						var av = available();
						if(av>0) throw errorOnMismatch.apply(getPos(), amount);
					}
				}
			};
		}
	}
	
	default BufferTicket readTicket(long amount){ return readTicket(Math.toIntExact(amount)); }
	default BufferTicket readTicket(int amount){
		return new BufferTicket(this, amount, null);
	}
	
	default long transferTo(ContentWriter out) throws IOException{ return transferTo(out, BATCH_BYTES); }
	default long transferTo(ContentWriter out, int batchSize) throws IOException{
		Objects.requireNonNull(out);
		long transferred = 0;
		
		byte[] buffer = new byte[batchSize];
		int    read;
		while((read = this.read(buffer))>=0){
			out.write(buffer, 0, read);
			transferred += read;
		}
		return transferred;
	}
	
	default long transferTo(OutputStream out) throws IOException{ return transferTo(out, BATCH_BYTES); }
	default long transferTo(OutputStream out, int batchSize) throws IOException{
		Objects.requireNonNull(out);
		long transferred = 0;
		
		byte[] buffer = new byte[batchSize];
		int    read;
		while((read = this.read(buffer))>=0){
			out.write(buffer, 0, read);
			transferred += read;
		}
		return transferred;
	}
}
