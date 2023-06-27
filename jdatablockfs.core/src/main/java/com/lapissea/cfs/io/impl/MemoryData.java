package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.IOHook;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.IOTransaction;
import com.lapissea.cfs.io.IOTransactionBuffer;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.utils.IOUtils;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.LongStream;

public abstract sealed class MemoryData<DataType> implements IOInterface{
	
	@SuppressWarnings("resource")
	public final class MemRandomIO implements RandomIO{
		
		private int pos;
		
		public MemRandomIO(){ }
		
		public MemRandomIO(int pos){
			if(pos<0) throw new IndexOutOfBoundsException(pos);
			this.pos = pos;
		}
		
		@Override
		public MemRandomIO setPos(long pos){
			if(pos<0) throw new IndexOutOfBoundsException();
			this.pos = Math.toIntExact(pos);
			return this;
		}
		
		@Override
		public long getPos(){
			return Math.min(pos, getSize());
		}
		
		@Override
		public long getSize(){
			return MemoryData.this.used;
		}
		
		@Override
		public void setSize(long targetSize){
			if(targetSize<0) throw new IllegalArgumentException();
			if(transactionOpen) throw new UnsupportedOperationException();
			var cap = getCapacity();
			if(targetSize>cap) targetSize = cap;
			MemoryData.this.used = Math.toIntExact(targetSize);
		}
		
		@Override
		public long getCapacity(){
			if(transactionOpen){
				return transactionBuff.getCapacity(used);
			}
			return used;
		}
		
		@Override
		public MemRandomIO setCapacity(long newCapacity){
			if(readOnly) throw new UnsupportedOperationException();
			
			MemoryData.this.setCapacity(newCapacity);
			pos = (int)Math.min(pos, getSize());
			return this;
		}
		
		@Override
		public void close(){ }
		
		@Override
		public void flush(){ }
		
		@Override
		public int read() throws IOException{
			if(transactionOpen){
				int b = transactionBuff.readByte(readAt(), pos);
				if(b>=0){
					this.pos++;
				}
				return b;
			}
			
			int remaining = (int)(getSize() - getPos());
			if(remaining<=0) return -1;
			return read1(fileData, pos++)&0xFF;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			if(transactionOpen){
				int read = transactionBuff.read(readAt(), pos, b, off, len);
				pos += read;
				return read;
			}
			
			int read = readAt(pos, b, off, len);
			if(read>=0) pos += read;
			return read;
		}
		
		@Override
		public long readWord(int len) throws IOException{
			if(transactionOpen){
				var word = transactionBuff.readWord(readAt(), pos, len);
				pos += len;
				return word;
			}
			
			int remaining = used - pos;
			if(remaining<len){
				throw new EOFException();
			}
			
			long val = MemoryData.this.readWord(fileData, pos, len);
			pos += len;
			return val;
		}
		
		private IOTransactionBuffer.BaseAccess readAt;
		private IOTransactionBuffer.BaseAccess readAt(){
			if(readAt == null) readAt = this::readAt;
			return readAt;
		}
		
		private int readAt(long pos, byte[] b, int off, int len){
			return readAt((int)pos, b, off, len);
		}
		private int readAt(int pos, byte[] b, int off, int len){
			int remaining = (int)(getSize() - pos);
			if(remaining<=0) return -1;
			
			int clampedLen = Math.min(remaining, len);
			readN(fileData, pos, b, off, clampedLen);
			return clampedLen;
		}
		
		@Override
		public void write(int b){
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.writeByte(pos, b);
				pos++;
				return;
			}
			
			int remaining = (int)(getCapacity() - getPos());
			if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity() + 1, getCapacity() + 1 - remaining)));
			write1(fileData, pos, (byte)b);
			if(hook != null) logWriteEvent(pos);
			pos++;
			used = Math.max(used, pos);
		}
		
		@Override
		public void write(byte[] b, int off, int len){
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.write(pos, b, off, len);
				pos += len;
				return;
			}
			
			var oldPos = pos;
			write0(b, off, len);
			
			pos += len;
			used = Math.max(used, pos);
			if(hook != null) logWriteEvent(oldPos, oldPos + len);
		}
		
		@Override
		public void writeAtOffsets(Collection<WriteChunk> writeData){
			if(readOnly) throw new UnsupportedOperationException();
			if(writeData.isEmpty()) return;
			if(transactionOpen){
				for(WriteChunk(long ioOffset, int dataOffset, int dataLength, byte[] data) : writeData){
					transactionBuff.write(ioOffset, data, dataOffset, dataLength);
				}
				return;
			}
			
			long required = Long.MIN_VALUE;
			for(var writeDatum : writeData){
				long ioEnd = writeDatum.ioEnd();
				if(ioEnd>required) required = ioEnd;
			}
			
			if(getCapacity()<required) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), required)));
			
			used = Math.max(used, Math.toIntExact(required));
			
			for(WriteChunk(long ioOffset, int dataOffset, int dataLength, byte[] data) : writeData){
				writeN(data, dataOffset, fileData, Math.toIntExact(ioOffset), dataLength);
			}
			
			if(hook != null) logWriteEvent(writeData.stream().flatMapToLong(e -> LongStream.range(e.ioOffset(), e.ioEnd())));
		}
		
		private void write0(byte[] b, int off, int len){
			if(len == 0) return;
			
			int remaining = (int)(getCapacity() - getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity() + len - remaining)));
			
			writeN(b, off, fileData, pos, len);
		}
		
		@Override
		public void writeWord(long v, int len) throws IOException{
			if(transactionOpen){
				transactionBuff.writeWord(pos, v, len);
				pos += len;
				return;
			}
			
			if(len == 0) return;
			
			int remaining = (int)(getCapacity() - getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity() + len - remaining)));
			
			MemoryData.this.writeWord(v, fileData, pos, len);
			var oldPos = pos;
			pos += len;
			used = Math.max(used, pos);
			if(hook != null) logWriteEvent(oldPos, oldPos + len);
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			var pos = this.pos;
			IOUtils.zeroFill(this::write, requestedMemory);
			this.pos = pos;
		}
		@Override
		public boolean isReadOnly(){
			return readOnly;
		}
		
		@Override
		public boolean inTransaction(){
			return transactionOpen;
		}
		
		@Override
		public String toString(){
			int count = 64;
			
			int start = (int)getPos(), end = start + count;
			
			var used = (int)getSize();
			
			int overshoot = end - used;
			if(overshoot>0){
				start = Math.max(0, start - overshoot);
				end = used;
			}
			
			String transactionStr = transactionOpen? ", transaction: {" + transactionBuff.infoString() + "}" : "";
			
			String name = getClass().getSimpleName();
			String pre  = "{pos=" + getPos() + transactionStr;
			if(start != 0 || start != end){
				pre += ", data=";
			}
			if(start != 0) pre += start + " ... ";
			
			var more = used - end;
			var post = more == 0? "}" : " ... " + more + "}";
			
			var result = new StringBuilder(name.length() + pre.length() + post.length() + end - start);
			
			result.append(name).append(pre);
			try(var io = ioAt(start)){
				for(int i = start; i<end - 1; i++){
					char c = (char)io.readInt1();
					result.append(switch(c){
						case 0 -> '␀';
						case '\n' -> '↵';
						case '\r' -> '®';
						case '\b' -> '␈';
						case '\t' -> '↹';
						default -> c;
					});
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			
			result.append(post);
			
			return result.toString();
		}
		@Override
		public boolean isDirect(){
			return !transactionOpen;
		}
	}
	
	private final IOHook hook;
	
	protected DataType fileData;
	protected int      used;
	
	private final boolean readOnly;
	
	@SuppressWarnings("unused")
	private       boolean             transactionOpen;
	private final IOTransactionBuffer transactionBuff = new IOTransactionBuffer();
	
	private MemoryData(DataType fileData, Builder info){
		
		var ok = getLength(fileData)>=used;
		if(!ok) throw new IllegalArgumentException(TextUtil.toString(getLength(fileData), ">=", used));
		
		this.fileData = fileData;
		this.used = info.getUsed() == -1? getLength(fileData) : info.getUsed();
		this.readOnly = info.isReadOnly();
		
		hook = info.getOnWrite();
	}
	
	private void logWriteEvent(long single){
		logWriteEvent(LongStream.of(single));
	}
	private void logWriteEvent(long start, long end){
		logWriteEvent(LongStream.range(start, end));
	}
	private void logWriteEvent(LongStream ids){
		try{
			hook.writeEvent(this, ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
	}
	
	public IOHook getHook(){
		return hook;
	}
	
	@Override
	@NotNull
	public MemRandomIO io(){
		return new MemRandomIO();
	}
	@Override
	public RandomIO ioAt(long offset) throws IOException{
		return new MemRandomIO((int)offset);
	}
	
	@Override
	public long getIOSize(){
		if(transactionOpen){
			return transactionBuff.getCapacity(used);
		}
		return used;
	}
	
	private void setCapacity(long newCapacity){
		setCapacity(Math.toIntExact(newCapacity));
	}
	private void setCapacity(int newCapacity){
		if(readOnly) throw new UnsupportedOperationException();
		if(transactionOpen){
			var siz = transactionBuff.getCapacity(used);
			transactionBuff.capacityChange(Math.min(siz, newCapacity));
			return;
		}
		
		long lastCapacity = getLength(fileData);
		if(lastCapacity == newCapacity) return;
		
		if(lastCapacity<newCapacity || lastCapacity>newCapacity*2L){
			var newc = lastCapacity<newCapacity? (int)Math.max(newCapacity, lastCapacity*4/3) : newCapacity;
			fileData = resize(fileData, newc);
		}
		used = Math.min(used, newCapacity);
		
		if(hook != null) logWriteEvent(lastCapacity, newCapacity);
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	private static final VarHandle TRANSACTION_OPEN;
	
	static{
		try{
			TRANSACTION_OPEN = MethodHandles.lookup().findVarHandle(MemoryData.class, "transactionOpen", boolean.class);
		}catch(ReflectiveOperationException e){
			throw new Error(e);
		}
	}
	
	@Override
	public IOTransaction openIOTransaction(){
		if(IOTransaction.DISABLE_TRANSACTIONS) return IOTransaction.NOOP;
		return transactionBuff.open(this, TRANSACTION_OPEN);
	}
	
	@Override
	public byte[] readAll() throws IOException{
		if(transactionOpen) return IOInterface.super.readAll();
		var copy = new byte[used];
		readN(fileData, 0, copy, 0, used);
		return copy;
	}
	
	@Override
	public String toString(){
		return MemoryData.class.getSimpleName() + "#" + Integer.toHexString(hashCode()) + "{" + getIOSize() + " bytes}";
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof MemoryData<?> that &&
		       fileData.equals(that.fileData);
	}
	
	@Override
	public int hashCode(){
		return fileData.hashCode();
	}
	
	protected abstract int getLength(DataType fileData);
	protected abstract DataType resize(DataType oldFileData, int newFileSize);
	
	protected abstract byte read1(DataType fileData, int fileOffset);
	protected abstract void write1(DataType fileData, int fileOffset, byte b);
	protected abstract void readN(DataType fileData, int fileOffset, byte[] dest, int off, int len);
	protected abstract void writeN(byte[] src, int srcOffset, DataType fileData, int fileOffset, int len);
	
	protected abstract long readWord(DataType fileData, int fileOffset, int len);
	protected abstract void writeWord(long value, DataType fileData, int fileOffset, int len);
	
	public static MemoryData<?> empty(){
		return new Builder().build();
	}
	public static Builder builder(){
		return new Builder();
	}
	
	public static MemoryData<?> viewOf(byte[] data){
		return new MemoryData.Arr(data, new Builder().asReadOnly());
	}
	
	public interface DataInitializer{
		void init(ContentWriter dest) throws IOException;
	}
	
	@SuppressWarnings("unused")
	public static class Builder{
		private UnsafeSupplier<Object, IOException> dataProducer;
		private boolean                             readOnly = false;
		private int                                 used     = -1;
		private IOHook                              onWrite;
		
		public Builder withInitial(DataInitializer init){
			this.dataProducer = () -> {
				var builder = new ContentOutputBuilder(32);
				init.init(builder);
				return builder.toByteArray();
			};
			return this;
		}
		
		public Builder withCapacity(int capacity){
			this.dataProducer = () -> new byte[capacity];
			return this;
		}
		public Builder withData(IOInterface data){
			this.dataProducer = data::readAll;
			return this;
		}
		
		public Builder withRaw(byte[] data){
			var clone = data.clone();
			this.dataProducer = () -> clone;
			return this;
		}
		
		public Builder withRaw(ByteBuffer data){
			var d = ByteBuffer.allocate(data.limit());
			d.put(data.position(0));
			d.position(0);
			return withRaw0(d);
		}
		
		private Builder withRaw0(Object data){
			Objects.requireNonNull(data);
			this.dataProducer = () -> data;
			return this;
		}
		
		public Builder asReadOnly(){
			this.readOnly = true;
			return this;
		}
		
		public Builder withUsedLength(int used){
			this.used = used;
			return this;
		}
		
		public Builder withOnWrite(IOHook onWrite){
			this.onWrite = onWrite;
			return this;
		}
		
		private Object readData() throws IOException{
			if(dataProducer == null) return ZeroArrays.ZERO_BYTE;
			return dataProducer.get();
		}
		
		public IOHook getOnWrite(){
			return onWrite;
		}
		
		public int getUsed(){
			return used;
		}
		
		public boolean isReadOnly(){
			return readOnly;
		}
		
		public MemoryData<?> build(){
			Object actualData;
			try{
				actualData = readData();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			dataProducer = null;
			
			return switch(actualData){
				case byte[] data -> new Arr(data, this);
				case ByteBuffer data -> new Buff(data, this);
				default -> throw new RuntimeException("unknown data type " + actualData);
			};
		}
	}
	
	@Override
	public MemoryData<?> asReadOnly(){
		if(isReadOnly()) return this;
		return (switch(this){
			case Arr d -> builder().withRaw(d.fileData).withUsedLength(used);
			case Buff d -> builder().withRaw(d.fileData).withUsedLength(used);
		}).asReadOnly().build();
	}
	
	private static final class Arr extends MemoryData<byte[]>{
		
		private Arr(byte[] data, Builder info){
			super(data, info);
		}
		
		@Override
		protected int getLength(byte[] fileData){
			return fileData.length;
		}
		@Override
		protected byte read1(byte[] fileData, int fileOffset){
			return fileData[fileOffset];
		}
		@Override
		protected void write1(byte[] fileData, int fileOffset, byte b){
			fileData[fileOffset] = b;
		}
		@Override
		protected void readN(byte[] fileData, int fileOffset, byte[] dest, int off, int len){
			System.arraycopy(fileData, fileOffset, dest, off, len);
		}
		@Override
		protected void writeN(byte[] src, int srcOffset, byte[] fileData, int fileOffset, int len){
			System.arraycopy(src, srcOffset, fileData, fileOffset, len);
		}
		@Override
		protected byte[] resize(byte[] oldFileData, int newFileSize){
			return Arrays.copyOf(oldFileData, newFileSize);
		}
		
		@Override
		protected long readWord(byte[] fileData, int fileOffset, int len){
			return MemPrimitive.getWord(fileData, fileOffset, len);
		}
		@Override
		protected void writeWord(long value, byte[] fileData, int fileOffset, int len){
			MemPrimitive.setWord(value, fileData, fileOffset, len);
		}
	}
	
	private static final class Buff extends MemoryData<ByteBuffer>{
		
		private Buff(ByteBuffer data, Builder info){
			super(data, info);
		}
		
		@Override
		protected int getLength(ByteBuffer fileData){
			return fileData.limit();
		}
		@Override
		protected byte read1(ByteBuffer fileData, int fileOffset){
			return fileData.get(fileOffset);
		}
		@Override
		protected void write1(ByteBuffer fileData, int fileOffset, byte b){
			fileData.put(fileOffset, b);
		}
		@Override
		protected void readN(ByteBuffer fileData, int fileOffset, byte[] dest, int off, int len){
			fileData.get(fileOffset, dest, off, len);
		}
		@Override
		protected void writeN(byte[] src, int srcOffset, ByteBuffer fileData, int fileOffset, int len){
			fileData.put(fileOffset, src, srcOffset, len);
		}
		@Override
		protected ByteBuffer resize(ByteBuffer oldFileData, int newFileSize){
			ByteBuffer newFile = ByteBuffer.allocate(newFileSize);
			oldFileData.position(0);
			newFile.put(oldFileData);
			newFile.position(0);
			return newFile;
		}
		
		@Override
		protected long readWord(ByteBuffer fileData, int fileOffset, int len){
			final var lm1 = len - 1;
			long      val = 0;
			for(int i = 0; i<len; i++){
				val |= (fileData.get(fileOffset + i)&255L)<<((lm1 - i)*8);
			}
			return val;
		}
		
		@Override
		protected void writeWord(long value, ByteBuffer fileData, int fileOffset, int len){
			final var lm1 = len - 1;
			
			for(int i = 0; i<len; i++){
				fileData.put(fileOffset + i, (byte)(value >>> ((lm1 - i)*8)));
			}
		}
		
	}
}
