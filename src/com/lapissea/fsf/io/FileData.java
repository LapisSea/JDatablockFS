package com.lapissea.fsf.io;

import com.lapissea.fsf.FileEntry;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.IOException;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public class FileData implements IOInterface{
	
	private class FakeFileData implements RandomIO{
		private final byte[] buff=new byte[8];
		
		private RandomIO realData;
		
		private long getOrZero(UnsafeFunctionOL<RandomIO, IOException> getter) throws IOException{
			return getOr(getter, 0);
		}
		
		private long getOr(UnsafeFunctionOL<RandomIO, IOException> getter, long val) throws IOException{
			if(realData==null) return val;
			return getter.apply(realData);
		}
		
		private RandomIO ensureData(long initialCapacity) throws IOException{
			if(realData==null) realData=initData(initialCapacity);
			return realData;
		}
		
		@NotNull
		private RandomIO initData(long initialCapacity) throws IOException{
			initEntryData(initialCapacity);
			return FileData.this.doRandom();
		}
		
		@Override
		public synchronized long getPos() throws IOException{
			return getOrZero(RandomIO::getPos);
		}
		
		@Override
		public synchronized RandomIO setPos(long pos) throws IOException{
			if(realData!=null) realData.setPos(pos);
			return this;
		}
		
		@Override
		public synchronized long getSize() throws IOException{
			return getOrZero(RandomIO::getSize);
		}
		
		@Override
		public synchronized RandomIO setSize(long targetSize) throws IOException{
			if(targetSize<=0) return this;
			ensureData(targetSize).setSize(targetSize);
			return this;
		}
		
		@Override
		public synchronized long getCapacity() throws IOException{
			return getOrZero(RandomIO::getCapacity);
		}
		
		@Override
		public synchronized RandomIO setCapacity(long newCapacity) throws IOException{
			if(newCapacity<=0) return this;
			ensureData(newCapacity).setCapacity(newCapacity);
			return this;
		}
		
		@Override
		public synchronized void close() throws IOException{
			if(realData==null) return;
			realData.close();
		}
		
		@Override
		public synchronized void flush() throws IOException{
			if(realData==null) return;
			realData.flush();
		}
		
		@Override
		public synchronized int read() throws IOException{
			if(realData==null) return -1;
			return realData.read();
		}
		
		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException{
			if(realData==null) return -1;
			return realData.read(b, off, len);
		}
		
		@Override
		public synchronized byte[] contentBuf(){
			return buff;
		}
		
		@Override
		public synchronized void write(int b) throws IOException{
			ensureData(1).write(b);
		}
		
		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException{
			ensureData(len).write(b, off, len);
		}
		
		@Override
		public synchronized void fillZero(long requestedMemory) throws IOException{
			realData.fillZero(requestedMemory);
		}
		
		@Override
		public synchronized long getGlobalPos() throws IOException{
			return getOr(RandomIO::getGlobalPos, -1);
		}
	}
	
	
	private final Header<?>             header;
	private final IOList.Ref<FileEntry> value;
	
	private FileEntry entry;
	
	
	public FileData(Header<?> header, IOList.Ref<FileEntry> value){
		this.header=header;
		this.value=value;
		entry=value.getUnchecked();
	}
	
	private synchronized void initEntryData(long initialCapacity) throws IOException{
		if(DEBUG_VALIDATION){
			Assert(value.get().getData()==null);
		}
		
		header.alocEntryData(value, initialCapacity);
		entry=value.get();
	}
	
	private FileEntry getEntry(){
		if(DEBUG_VALIDATION){
			Assert(value.getUnchecked().getId().equals(entry.getId()));
		}
		return entry;
	}
	
	@Override
	@NotNull
	public RandomIO doRandom() throws IOException{
		var chunk=getEntry().getData();
		if(chunk==null) return new FakeFileData();
		return chunk.dereference(header).io().doRandom();
	}
	
	@Override
	public String getName(){
		try{
			return value.get().getId().toString();
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public ChunkPointer getPointer(){
		return getEntry().getData();
	}
}
