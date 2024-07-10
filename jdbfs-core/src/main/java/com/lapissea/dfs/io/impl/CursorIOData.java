package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.IOTransactionBuffer;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.util.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.LongStream;

public abstract class CursorIOData implements IOInterface{
	
	@SuppressWarnings("resource")
	public final class CursorRandomIO implements RandomIO{
		
		private long pos;
		
		public CursorRandomIO(){ }
		
		public CursorRandomIO(long pos){
			if(pos<0) throw new IndexOutOfBoundsException(pos);
			this.pos = pos;
		}
		
		@Override
		public CursorRandomIO setPos(long pos){
			if(pos<0) throw new IndexOutOfBoundsException();
			this.pos = pos;
			return this;
		}
		
		@Override
		public long getPos(){
			return Math.min(pos, getSize());
		}
		
		@Override
		public long getSize(){
			return CursorIOData.this.used;
		}
		
		@Override
		public void setSize(long targetSize){
			if(targetSize<0) throw new IllegalArgumentException();
			if(transactionOpen) throw new UnsupportedOperationException();
			var cap = getCapacity();
			if(targetSize>cap) targetSize = cap;
			CursorIOData.this.used = targetSize;
		}
		
		@Override
		public long getCapacity(){
			if(transactionOpen){
				return transactionBuff.getCapacity(used);
			}
			return used;
		}
		
		@Override
		public CursorRandomIO setCapacity(long newCapacity) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			setCapacity0(newCapacity, true);
			return this;
		}
		private void setCapacity0(long newCapacity, boolean log) throws IOException{
			CursorIOData.this.setCapacity(newCapacity, log);
			pos = Math.min(pos, getSize());
		}
		
		@Override
		public void close(){ }
		
		@Override
		public void flush(){ }
		
		@Override
		public int read() throws IOException{
			if(transactionOpen){
				int b = transactionBuff.readByte(readAt, pos);
				if(b>=0){
					this.pos++;
				}
				return b;
			}
			
			var remaining = getSize() - getPos();
			if(remaining<=0){
				return -1;
			}
			return Byte.toUnsignedInt(read1(pos++));
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			if(transactionOpen){
				int read = transactionBuff.read(readAt, pos, b, off, len);
				if(read != -1) pos += read;
				return read;
			}
			
			int read = readAt(pos, b, off, len);
			if(read>=0) pos += read;
			return read;
		}
		
		@Override
		public long readWord(int len) throws IOException{
			if(transactionOpen){
				var word = transactionBuff.readWord(readAt, pos, len);
				pos += len;
				return word;
			}
			
			long remaining = used - pos;
			if(remaining<len){
				throw new EOFException();
			}
			
			long val = CursorIOData.this.readWord(pos, len);
			pos += len;
			return val;
		}
		
		private final IOTransactionBuffer.BaseAccess readAt = this::readAt;
		
		private int readAt(long pos, byte[] b, int off, int len) throws IOException{
			long remaining = getSize() - pos;
			if(remaining<=0){
				return -1;
			}
			
			int clampedLen = (int)Math.min(remaining, len);
			readN(pos, b, off, clampedLen);
			return clampedLen;
		}
		
		@Override
		public void write(int b) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.writeByte(readAt, pos, b);
				pos++;
				return;
			}
			
			var  cap       = getCapacity();
			long remaining = cap - getPos();
			if(remaining<=0) setCapacity0(Math.max(4, Math.max(cap + 1, cap + 1 - remaining)), false);
			write1(pos, (byte)b);
			if(hook != null) logWriteEvent(pos);
			pos++;
			used = Math.max(used, pos);
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.write(readAt, pos, b, off, len);
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
		public void writeAtOffsets(Collection<WriteChunk> writeData) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(writeData.isEmpty()) return;
			if(transactionOpen){
				transactionBuff.writeChunks(readAt, writeData);
				return;
			}
			
			long required = Long.MIN_VALUE;
			for(var writeDatum : writeData){
				long ioEnd = writeDatum.ioEnd();
				if(ioEnd>required) required = ioEnd;
			}
			var cap = getCapacity();
			if(cap<required) setCapacity(Math.max(4, Math.max((long)(cap*4D/3), required)));
			
			used = Math.max(used, required);
			
			for(var e : writeData){
				writeN(e.ioOffset(), e.data(), e.dataOffset(), e.dataLength());
			}
			
			if(hook != null) logWriteEvent(writeData.stream().flatMapToLong(e -> LongStream.range(e.ioOffset(), e.ioEnd())));
		}
		
		private void write0(byte[] b, int off, int len) throws IOException{
			if(len == 0) return;
			
			var cap       = getCapacity();
			var remaining = cap - getPos();
			if(remaining<len) setCapacity0(Math.max(4, Math.max((long)(cap*4D/3), cap + len - remaining)), false);
			
			writeN(pos, b, off, len);
		}
		
		@Override
		public void writeWord(long v, int len) throws IOException{
			if(transactionOpen){
				transactionBuff.writeWord(readAt, pos, v, len);
				pos += len;
				return;
			}
			
			if(len == 0) return;
			
			var cap       = getCapacity();
			var remaining = cap - getPos();
			if(remaining<len) setCapacity(Math.max(4, Math.max((long)(cap*4D/3), cap + len - remaining)));
			
			CursorIOData.this.writeWord(pos, v, len);
			var oldPos = pos;
			pos += len;
			used = Math.max(used, pos);
			if(hook != null) logWriteEvent(oldPos, oldPos + len);
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			var pos = this.pos;
			IOUtils.zeroFill(this, requestedMemory);
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
			
			long start = getPos(), end = start + count;
			
			var used = getSize();
			
			var overshoot = end - used;
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
			
			var result = new StringBuilder((int)(name.length() + pre.length() + post.length() + end - start));
			
			result.append(name).append(pre);
			try(var io = ioAt(start)){
				for(long i = start; i<end - 1; i++){
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
	
	protected long used;
	
	private final boolean readOnly;
	
	@SuppressWarnings("unused")
	private       boolean             transactionOpen;
	private final IOTransactionBuffer transactionBuff = new IOTransactionBuffer();
	
	public CursorIOData(IOHook hook, boolean readOnly){
		this.readOnly = readOnly;
		this.hook = hook;
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
	
	public final IOHook getHook(){
		return hook;
	}
	
	@Override
	@NotNull
	public CursorRandomIO io(){
		return new CursorRandomIO();
	}
	@Override
	public RandomIO ioAt(long offset){
		return new CursorRandomIO(offset);
	}
	
	@Override
	public long getIOSize(){
		if(transactionOpen){
			return transactionBuff.getCapacity(used);
		}
		return used;
	}
	
	private void setCapacity(long newCapacity, boolean log) throws IOException{
		if(readOnly) throw new UnsupportedOperationException();
		if(transactionOpen){
			var siz = transactionBuff.getCapacity(used);
			transactionBuff.capacityChange(Math.min(siz, newCapacity));
			return;
		}
		
		long lastCapacity = getLength();
		if(lastCapacity == newCapacity) return;
		
		if(lastCapacity<newCapacity || lastCapacity>newCapacity*2L){
			var newc = lastCapacity<newCapacity? Math.max(newCapacity, lastCapacity*4/3) : newCapacity;
			resize(newc);
		}
		used = Math.min(used, newCapacity);
		
		if(log && hook != null) logWriteEvent(lastCapacity, newCapacity);
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	private static final VarHandle TRANSACTION_OPEN;
	
	static{
		try{
			TRANSACTION_OPEN = MethodHandles.lookup().findVarHandle(CursorIOData.class, "transactionOpen", boolean.class);
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
		var u = used;
		if(u>Integer.MAX_VALUE) throw new OutOfMemoryError();
		var iUsed = (int)u;
		var copy  = new byte[iUsed];
		try{
			readN(0, copy, 0, iUsed);
		}catch(Throwable e){
			throw switch(e){
				case IOException io -> io;
				default -> new IOException("Failed to read " + iUsed + " bytes", e);
			};
		}
		return copy;
	}
	
	@Override
	public String toString(){
		int h;
		try{
			var used = this.used;
			var siz  = (int)Math.min(used, 128);
			h = Arrays.hashCode(read(0, siz));
			
			var start = Math.max(used - siz, siz);
			if(start != used){
				h ^= Arrays.hashCode(read(start, (int)(used - start)));
			}
		}catch(IOException e){
			h = 0;
		}
		return getClass().getSimpleName() + "#" + Integer.toHexString(h) + "{" + getIOSize() + " bytes}";
	}
	
	@Override
	public abstract boolean equals(Object o);
	
	@Override
	public abstract int hashCode();
	
	protected abstract long getLength() throws IOException;
	protected abstract void resize(long newFileSize) throws IOException;
	
	protected abstract byte read1(long fileOffset) throws IOException;
	protected abstract void write1(long fileOffset, byte b) throws IOException;
	protected abstract void readN(long fileOffset, byte[] dest, int destOff, int len) throws IOException;
	protected abstract void writeN(long fileOffset, byte[] src, int srcOff, int len) throws IOException;
	
	protected abstract long readWord(long fileOffset, int len) throws IOException;
	protected abstract void writeWord(long fileOffset, long value, int len) throws IOException;
	
	@Override
	public abstract CursorIOData asReadOnly();
	
}
