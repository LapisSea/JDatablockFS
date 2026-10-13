package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.core.UnmanagedRegistry;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.IOTransactionBuffer;
import com.lapissea.dfs.io.IOTransactionState;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.util.NotNull;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.LongStream;

public abstract class CursorIOData implements IOInterface{
	
	private static final Map<IOInterface, Thread> TO_SHUTDOWN = new IdentityHashMap<>();
	
	protected static synchronized <T extends IOInterface & Closeable> void bindCloseOnShutdown(T data){
		var shutdownThread = Thread.ofVirtual().name(data + " flusher").unstarted(() -> {
			try{
				data.close();
			}catch(Throwable e){
				e.printStackTrace();
			}
		});
		try{
			Runtime.getRuntime().addShutdownHook(shutdownThread);
			TO_SHUTDOWN.put(data, shutdownThread);
		}catch(IllegalStateException e){
			//Ignore
		}
	}
	protected static synchronized <T extends IOInterface & Closeable> void unbindCloseOnShutdown(T data){
		var thread = TO_SHUTDOWN.remove(data);
		if(thread == null) return;
		try{
			Runtime.getRuntime().removeShutdownHook(thread);
		}catch(IllegalStateException ignore){ }
	}
	
	@SuppressWarnings("resource")
	public final class CursorRandomIO implements RandomIO{
		
		private long pos;
		
		public CursorRandomIO(){
			if(closed) throw new IllegalStateException("closed");
		}
		
		public CursorRandomIO(long pos){
			if(closed) throw new IllegalStateException("closed");
			if(pos<0) throw new IndexOutOfBoundsException(pos);
			this.pos = pos;
		}
		
		@Override
		public CursorRandomIO setPos(long pos){
			try(var ignored = access(false)){
				if(pos<0) throw new IndexOutOfBoundsException();
				this.pos = pos;
				return this;
			}
		}
		
		@Override
		public long getPos(){
			try(var ignored = access(false)){
				return Math.min(pos, getSize());
			}
		}
		
		@Override
		public long getSize(){
			try(var ignored = access(false)){
				return getCapacity();
			}
		}
		
		@Override
		public void setSize(long targetSize){
			if(needsImplicitTransaction()){
				var requestedSize = targetSize;
				try{ openIOTransaction(() -> setSize(requestedSize)); }catch(IOException e){ throw new UncheckedIOException(e); }
				return;
			}
			if(readOnly) throw new UnsupportedOperationException();
			try(var ignored = access(true)){
				if(targetSize<0) throw new IllegalArgumentException();
				var cap = getCapacity();
				if(targetSize>cap) targetSize = cap;
				if(transactions.inTransaction()) transactionBuff.capacityChange(targetSize);
				else CursorIOData.this.used = targetSize;
			}
		}
		
		@Override
		public long getCapacity(){
			try(var ignored = access(false)){
				if(transactions.inTransaction()){
					return transactionBuff.getCapacity(used);
				}
				return used;
			}
		}
		
		@Override
		public CursorRandomIO setCapacity(long newCapacity) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> { setCapacity(newCapacity); });
				return this;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				setCapacity0(newCapacity, true);
				return this;
			}
		}
		private void setCapacity0(long newCapacity, boolean log) throws IOException{
			var oldSize = getSize();
			CursorIOData.this.setCapacity(newCapacity, log);
			if(newCapacity<oldSize) pos = Math.min(pos, getSize());
		}
		
		@Override
		public void close(){ }
		
		@Override
		public void flush(){ }
		
		@Override
		public int read() throws IOException{
			try(var ignored = access(false)){
				if(pos>=getSize()) return -1;
				if(transactions.inTransaction()){
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
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			try(var ignored = access(false)){
				Objects.checkFromIndexSize(off, len, b.length);
				if(len == 0) return 0;
				if(pos>=getSize()) return -1;
				len = (int)Math.min(len, getSize() - pos);
				if(transactions.inTransaction()){
					int read = transactionBuff.read(readAt, pos, b, off, len);
					if(read != -1) pos += read;
					return read;
				}
				
				int read = readAt(pos, b, off, len);
				if(read>=0) pos += read;
				return read;
			}
		}
		
		@Override
		public long readWord(int len) throws IOException{
			try(var ignored = access(false)){
				if(getSize() - pos<len) throw new EOFException();
				if(transactions.inTransaction()){
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
		}
		
		private final IOTransactionBuffer.BaseAccess readAt = this::readAt;
		
		private int readAt(long pos, byte[] b, int off, int len) throws IOException{
			long remaining = getSize() - pos;
			if(remaining<=0){
				return -1;
			}
			
			int clampedLen = (int)Math.min(remaining, len);
			int storedLen  = (int)Math.min(clampedLen, Math.max(0, used - pos));
			if(storedLen>0) readN(pos, b, off, storedLen);
			if(storedLen<clampedLen) Arrays.fill(b, off + storedLen, off + clampedLen, (byte)0);
			return clampedLen;
		}
		
		@Override
		public void write(int b) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> write(b));
				return;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				if(transactions.inTransaction()){
					transactionBuff.writeByte(pos, b);
					pos++;
					return;
				}
				
				var  cap       = getCapacity();
				long remaining = cap - pos;
				if(remaining<=0) setCapacity0(Math.max(4, Math.max(cap + 1, cap + 1 - remaining)), false);
				var oldPos = pos;
				write1(pos, (byte)b);
				pos++;
				used = Math.max(used, pos);
				if(hook != null) logWriteEvent(oldPos);
			}
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> write(b, off, len));
				return;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				if(transactions.inTransaction()){
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
		}
		
		@Override
		public void writeAtOffsets(Collection<WriteChunk> writeData) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> writeAtOffsets(writeData));
				return;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				if(writeData.isEmpty()) return;
				if(transactions.inTransaction()){
					transactionBuff.writeChunks(writeData);
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
		}
		
		private void write0(byte[] b, int off, int len) throws IOException{
			if(len == 0) return;
			
			var cap       = getCapacity();
			var remaining = cap - pos;
			if(remaining<len) setCapacity0(Math.max(4, Math.max((long)(cap*4D/3), cap + len - remaining)), false);
			
			writeN(pos, b, off, len);
		}
		
		@Override
		public void writeWord(long v, int len) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> writeWord(v, len));
				return;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				if(transactions.inTransaction()){
					transactionBuff.writeWord(pos, v, len);
					pos += len;
					return;
				}
				
				if(len == 0) return;
				
				var cap       = getCapacity();
				var remaining = cap - pos;
				if(remaining<len) setCapacity(Math.max(4, Math.max((long)(cap*4D/3), cap + len - remaining)));
				
				CursorIOData.this.writeWord(pos, v, len);
				var oldPos = pos;
				pos += len;
				used = Math.max(used, pos);
				if(hook != null) logWriteEvent(oldPos, oldPos + len);
			}
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			if(needsImplicitTransaction()){
				openIOTransaction(() -> fillZero(requestedMemory));
				return;
			}
			try(var ignored = access(true)){
				if(readOnly) throw new UnsupportedOperationException();
				var pos = this.pos;
				IOUtils.zeroFill(this, requestedMemory);
				this.pos = pos;
			}
		}
		@Override
		public boolean isReadOnly(){
			return readOnly;
		}
		
		@Override
		public boolean inTransaction(){
			try(var ignored = access(false)){
				return transactions.inTransaction();
			}
		}
		
		@Override
		public String toString(){
			try(var ignored = access(false)){
				int count = 64;
				
				long start = getPos(), end = start + count;
				
				var used = getSize();
				
				var overshoot = end - used;
				if(overshoot>0){
					start = Math.max(0, start - overshoot);
					end = used;
				}
				
				String transactionStr = transactions.inTransaction()? ", transaction: {" + transactionBuff.infoString() + "}" : "";
				
				String name = getClass().getSimpleName();
				String pre  = "{pos=" + getPos() + " / " + getSize() + transactionStr;
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
		}
		@Override
		public boolean isDirect(){
			try(var ignored = access(false)){
				return !transactions.inTransaction();
			}
		}
	}
	
	/**
	 * File backends use this to journal standalone mutations as implicit root transactions.
	 */
	protected boolean needsImplicitTransaction(){ return false; }
	protected final void physicalCommitFailed(Throwable failure){ transactions.physicalCommitFailed(failure); }
	protected final boolean hasActiveTransaction()              { return transactions.inTransaction(); }
	protected final void applyDirect(IOTransactionBuffer.TransactionExport changes) throws IOException{
		IOInterface.super.applyTransaction(changes);
	}
	
	private final IOHook hook;
	
	protected long used;
	
	private final    boolean readOnly;
	private volatile boolean closed;
	
	private final UnmanagedRegistry   unmanagedRegistry = new UnmanagedRegistry(this);
	private final IOTransactionState  transactions      = new IOTransactionState(this);
	private final IOTransactionBuffer transactionBuff   = transactions.buffer();
	
	public CursorIOData(IOHook hook, boolean readOnly){
		this.readOnly = readOnly;
		this.hook = hook;
	}
	
	@Override
	public final UnmanagedRegistry getUnmanagedRegistry(){ return unmanagedRegistry; }
	
	protected void markClosed(){ closed = true; }
	
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
		try(var ignored = access(false)){
			return new CursorRandomIO();
		}
	}
	@Override
	public RandomIO ioAt(long offset) throws IOException{
		try(var ignored = access(false)){
			return new CursorRandomIO(offset);
		}
	}
	
	@Override
	public long getIOSize(){
		try(var ignored = access(false)){
			if(transactions.inTransaction()){
				return transactionBuff.getCapacity(used);
			}
			return used;
		}
	}
	
	private void setCapacity(long newCapacity, boolean log) throws IOException{
		if(readOnly) throw new UnsupportedOperationException();
		if(transactions.inTransaction()){
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
	
	@Override
	public IOTransaction startTransaction(){
		if(closed) throw new IllegalStateException("closed");
		if(readOnly) return IOTransaction.NOOP;
		if(IOTransaction.DISABLE_TRANSACTIONS) throw new UnsupportedOperationException("Cancellable transactions cannot be disabled");
		var result = transactions.open();
		if(closed){
			try{ result.close(); }catch(IOException e){ throw new UncheckedIOException(e); }
			throw new IllegalStateException("closed");
		}
		return result;
	}
	@Override
	public long getTransactionVersion(){ return transactions.version(); }
	@Override
	public IOTransactionState.Access lockIO(){ return access(false); }
	@Override
	public void checkTransactionVersion(long expected){ transactions.checkVersion(expected); }
	
	protected final IOTransactionState.Access closeAccess(){ return transactions.closeAccess(); }
	private IOTransactionState.Access access(boolean write){
		var result = write? transactions.writeAccess() : transactions.access();
		if(closed){
			result.close();
			throw new IllegalStateException("closed");
		}
		return result;
	}
	
	@Override
	public byte[] readAll() throws IOException{
		try(var ignored = access(false)){
			if(transactions.inTransaction()) return IOInterface.super.readAll();
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
