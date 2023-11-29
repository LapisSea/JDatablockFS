package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.IOTransactionBuffer;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.utils.IOUtils;

import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.StringJoiner;

public final class Blob extends IOInstance.Unmanaged<Blob> implements IOInterface{
	
	private final class BlobIO implements RandomIO{
		
		private final RandomIO data = Blob.this.selfIO();
		private       long     pos;
		
		private BlobIO() throws IOException{ }
		
		
		@Override
		public RandomIO setPos(long pos){
			if(pos<0) throw new IndexOutOfBoundsException();
			this.pos = Math.toIntExact(pos);
			return this;
		}
		@Override
		public long getPos() throws IOException{
			return Math.min(pos, getSize());
		}
		
		@Override
		public void setSize(long requestedSize) throws IOException{
			if(requestedSize<0) throw new IllegalArgumentException();
			if(transactionOpen) throw new UnsupportedOperationException();
			var cap = getCapacity();
			data.setSize(Math.min(cap, requestedSize));
		}
		@Override
		public long getSize() throws IOException{
			return data.getSize();
		}
		
		@Override
		public long getCapacity() throws IOException{
			if(transactionOpen){
				return transactionBuff.getCapacity(data.getCapacity());
			}
			return data.getCapacity();
		}
		
		@Override
		public RandomIO setCapacity(long newCapacity) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			
			data.setCapacity(newCapacity);
			pos = Math.min(pos, getSize());
			return this;
		}
		@Override
		public void close() throws IOException{ data.close(); }
		@Override
		public void flush() throws IOException{ data.flush(); }
		
		
		private IOTransactionBuffer.BaseAccess readAt;
		private IOTransactionBuffer.BaseAccess readAt(){
			if(readAt == null) readAt = this::readAt;
			return readAt;
		}
		
		private int readAt(long pos, byte[] b, int off, int len) throws IOException{
			return readAt((int)pos, b, off, len);
		}
		private int readAt(int pos, byte[] b, int off, int len) throws IOException{
			int remaining = (int)(getSize() - pos);
			if(remaining<=0) return -1;
			
			int clampedLen = Math.min(remaining, len);
			data.setPos(pos).readFully(b, off, clampedLen);
			return clampedLen;
		}
		
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
			return data.setPos(pos++).readInt1();
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
			
			long remaining = getSize() - pos;
			if(remaining<len){
				throw new EOFException();
			}
			
			long val = data.setPos(pos).readWord(len);
			pos += len;
			return val;
		}
		@Override
		public void write(int b) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.writeByte(pos, b);
				pos++;
				return;
			}
			
			data.setPos(pos).write(b);
			pos++;
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.write(pos, b, off, len);
				pos += len;
				return;
			}
			
			if(len>0){
				data.setPos(pos).write(b, off, len);
				pos += len;
			}
		}
		
		@Override
		public void writeAtOffsets(Collection<WriteChunk> writeData) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(writeData.isEmpty()) return;
			if(transactionOpen){
				for(var e : writeData){
					transactionBuff.write(e.ioOffset(), e.data(), e.dataOffset(), e.dataLength());
				}
				return;
			}
			data.writeAtOffsets(writeData);
		}
		
		@Override
		public void writeWord(long v, int len) throws IOException{
			if(len == 0) return;
			if(transactionOpen){
				transactionBuff.writeWord(pos, v, len);
			}else{
				data.setPos(pos).writeWord(v, len);
			}
			pos += len;
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
	}
	
	
	public static Blob request(DataProvider provider, long capacity) throws IOException{
		var ch = AllocateTicket.bytes(capacity).submit(provider);
		return new Blob(provider, ch.getPtr().makeReference(), IOType.of(Blob.class));
	}
	
	public Blob(DataProvider provider, Reference reference, IOType typeDef){
		super(provider, reference, typeDef);
		assert StandardStructPipe.of(getThisStruct()).getSizeDescriptor().getMin() == 0;
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	@SuppressWarnings("unused")
	private              boolean             transactionOpen;
	private final        IOTransactionBuffer transactionBuff = new IOTransactionBuffer();
	private static final VarHandle           TRANSACTION_OPEN;
	
	static{
		try{
			TRANSACTION_OPEN = MethodHandles.lookup().findVarHandle(Blob.class, "transactionOpen", boolean.class);
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
	public RandomIO io() throws IOException{
		return new BlobIO();
	}
	
	@Override
	public String toString(){
		var res = new StringJoiner(", ", "Blob{", "}");
		try(var io = selfIO()){
			res.add("size: " + io.getSize());
			res.add("capacity: " + io.getCapacity());
		}catch(IOException e){
			res.add("CORRUPTED: " + e);
		}
		return res.toString();
	}
}
