package com.lapissea.cfs.cluster.extensions;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.cluster.AllocateTicket;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.TypeParser;
import com.lapissea.cfs.exceptions.IllegalKeyException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Read;
import com.lapissea.cfs.io.struct.IOStruct.Size;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.io.struct.IOStruct.Write;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeIntFunction;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.lapissea.cfs.GlobalConfig.*;

public class BlockMapCluster<K extends IOInstance>{
	
	
	
	private static class AutoLock implements AutoCloseable{
		private final Lock lock;
		
		public AutoLock(Lock lock){
			this.lock=lock;
			lock.lock();
		}
		
		@Override
		public void close(){
			lock.unlock();
		}
	}
	
	private static class FakeIO implements RandomIO{
		private MemoryData data=new MemoryData();
		private RandomIO   io  =data.io();
		
		private final UnsafeIntFunction<RandomIO, IOException> dataProvide;
		
		private FakeIO(UnsafeIntFunction<RandomIO, IOException> dataProvide){
			this.dataProvide=dataProvide;
		}
		
		private void tryMakeReal(long size) throws IOException{
			if(size>=MAX_FAKE_SIZE){
				makeReal();
			}
		}
		
		private synchronized void makeReal() throws IOException{
			if(data==null) return;
			if(io.getSize()==0) return;
			long pos=io.getPos();
			io.close();
			io=dataProvide.apply((int)data.getSize());
			try(var src=data.io()){
				Utils.transferExact(src, io, src.getSize());
			}
			data=null;
			io.setPos(pos);
		}
		
		@Override
		public long getPos() throws IOException{
			return io.getPos();
		}
		
		@Override
		public RandomIO setPos(long pos) throws IOException{
			io.setPos(pos);
			return this;
		}
		
		@Override
		public long getCapacity() throws IOException{
			return io.getCapacity();
		}
		
		@Override
		public RandomIO setCapacity(long newCapacity) throws IOException{
			tryMakeReal(newCapacity);
			io.setCapacity(newCapacity);
			return this;
		}
		
		@Override
		public void close() throws IOException{
			makeReal();
			io.close();
			
		}
		
		@Override
		public void flush() throws IOException{
			makeReal();
			io.flush();
		}
		
		@Override
		public int read() throws IOException{
			return io.read();
		}
		
		@Override
		public void write(int b) throws IOException{
			io.write(b);
			tryMakeReal(io.getPos());
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			io.fillZero(requestedMemory);
		}
		
		@Override
		public long getGlobalPos() throws IOException{
			return io.getGlobalPos();
		}
		
		@Override
		public void setSize(long targetSize) throws IOException{
			tryMakeReal(targetSize);
			io.setSize(targetSize);
		}
		
		@Override
		public long getSize() throws IOException{
			return io.getSize();
		}
	}
	
	private static class Entry<K extends IOInstance> extends IOInstance{
		
		private ReadWriteLock lock;
		
		@Value(index=0)
		private K            key;
		@Value(index=1, rw=ChunkPointer.AutoSizedIO.class)
		private ChunkPointer dataLocation;
		
		private final IOType keyType;
		
		public Entry(ReadWriteLock lock, K key, ChunkPointer dataLocation, IOType keyType){
			this.lock=lock;
			this.key=key;
			this.dataLocation=dataLocation;
			this.keyType=keyType;
		}
		
		private Entry(IOType keyType, K key){
			this(keyType);
			this.key=key;
		}
		private Entry(IOType keyType){
			this.keyType=keyType;
			lock=new ReentrantReadWriteLock();
		}
		
		@Write
		private void writeKey(Cluster cluster, ContentWriter dest, K key) throws IOException{
			key.writeStruct(cluster, dest);
		}
		
		@Read
		private K readKey(Cluster cluster, ContentReader src, K oldValue) throws IOException{
			K val=cluster.constructType(keyType);
			val.readStruct(cluster, src);
			return val;
		}
		
		@Size
		private long sizeKey(K key){
			return key.getInstanceSize();
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof Entry<?> entry&&
			       Objects.equals(keyType, entry.keyType)&&
			       Objects.equals(dataLocation, entry.dataLocation)&&
			       Objects.equals(key, entry.key);
		}
		
		@Override
		public int hashCode(){
			return Objects.hash(key, dataLocation, keyType);
		}
	}
	
	private static final IOStruct   ENTRY_TYPE  =IOStruct.get(Entry.class);
	private static final TypeParser ENTRY_PARSER=new TypeParser(){
		@Override
		public boolean canParse(Cluster cluster, IOType type){
			if(type.getGenericArgs().size()!=1) return false;
			return type.getType()==ENTRY_TYPE;
		}
		
		@Override
		public UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type){
			IOType keyType=type.getGenericArgs().get(0);
			return c->new Entry<>(keyType);
		}
	};
	
	private static final int      MAX_FAKE_SIZE=2048;
	private static final RandomIO EMPTY_IO     =new MemoryData(new byte[0], true).io();
	
	private final ReadWriteLock    lock=new ReentrantReadWriteLock();
	private final Cluster          cluster;
	private final IOType           keyType;
	private final IOList<Entry<K>> data;
	
	public BlockMapCluster(@NotNull Cluster cluster, @NotNull Class<K> keyType) throws IOException{
		this.cluster=cluster;
		this.keyType=new IOType(keyType);
		
		cluster.getTypeParsers().register(ENTRY_PARSER);
		
		var entryType=new IOType(ENTRY_TYPE, this.keyType);
		var listType =new IOType(StructLinkedList.class, entryType);
		
		var userChunks=cluster.getUserChunks();
		if(userChunks.isEmpty()) AllocateTicket.user(listType).submit(cluster);
		
		
		Chunk dataChunk=userChunks.getElement(0).getObjPtr().getBlock(cluster);
		data=cluster.constructType(listType, dataChunk);
	}
	
	private int entryIndex(K key) throws IOException{
		for(int i=0;i<data.size();i++){
			if(Objects.equals(data.getElement(i).key, key)) return i;
		}
		return -1;
	}
	
	public int size(){
		try(var l=new AutoLock(lock.readLock())){
			return data.size();
		}
	}
	
	public void listKeys(Consumer<K> lister){
		try(var l=new AutoLock(lock.readLock())){
			for(Entry<K> e : data){
				lister.accept(e.key);
			}
		}
	}
	
	public K findKey(Predicate<K> finder){
		try(var l=new AutoLock(lock.readLock())){
			for(Entry<K> e : data){
				if(finder.test(e.key)) return e.key;
			}
		}
		return null;
	}
	
	public void deleteBlock(K key) throws IOException{
		try(var l=new AutoLock(lock.writeLock())){
			int i=entryIndex(key);
			if(i==-1) return;
			Entry<K> entry=data.getElement(i);
			try(var l1=new AutoLock(entry.lock.writeLock())){
				data.removeElement(i);
				if(entry.dataLocation!=null){
					entry.dataLocation.dereference(cluster).freeChaining();
				}
			}
		}
	}
	
	public void defineBlock(K key, UnsafeConsumer<RandomIO, IOException> ioAction) throws IOException{
		defineBlock(key, t->{
			ioAction.accept(t);
			return null;
		});
	}
	
	public <T> T defineBlock(K key, UnsafeFunction<RandomIO, T, IOException> ioAction) throws IOException{
		defineBlock(key);
		try{
			return openBlock(key, RandomIO.Mode.READ_WRITE, ioAction);
		}catch(IllegalKeyException e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	public void defineBlock(K key) throws IOException{
		try(var l=new AutoLock(lock.readLock())){
			if(entryIndex(key)!=-1) return;
		}
		try(var l=new AutoLock(lock.writeLock())){
			Entry<K> e=new Entry<>(keyType, key);
			if(DEBUG_VALIDATION){
				assert e.equals(new Entry<>(keyType,key));
			}
			data.addElement(e);
		}
	}
	
	public void openBlock(K key, RandomIO.Mode mode, UnsafeConsumer<RandomIO, IOException> ioAction) throws IOException, IllegalKeyException{
		openBlock(key, mode, t->{
			ioAction.accept(t);
			return null;
		});
	}
	
	public <T> T openBlock(K key, RandomIO.Mode mode, UnsafeFunction<RandomIO, T, IOException> ioAction) throws IOException, IllegalKeyException{
		Entry<K> e;
		try(var l=new AutoLock(lock.readLock())){
			int i=entryIndex(key);
			if(i==-1) throw new IllegalKeyException();
			e=data.getElement(i);
		}
		try(var l=new AutoLock(mode.canWrite?e.lock.writeLock():e.lock.readLock())){
			RandomIO io;
			if(e.dataLocation==null){
				if(mode.canWrite) io=new FakeIO(size->{
					try(var l1=new AutoLock(lock.writeLock())){
						Chunk chunk=AllocateTicket.bytes(size).submit(cluster);
						int   i    =entryIndex(key);
						data.modifyElement(i, entry->new Entry<>(entry.lock, entry.key, chunk.getPtr(), entry.keyType));
						return chunk.io();
					}
				});
				else io=EMPTY_IO;
			}else{
				io=e.dataLocation.dereference(cluster).io();
			}
			try(io){
				return ioAction.apply(io);
			}finally{
				if(io.getSize()==0){
					try(var lr=new AutoLock(lock.writeLock())){
						var      i    =entryIndex(key);
						Entry<K> entry=data.getElement(i);
						if(entry.dataLocation!=null){
							Chunk chunk=entry.dataLocation.dereference(cluster);
							try(var l2=new AutoLock(entry.lock.writeLock())){
								data.setElement(i, new Entry<>(entry.lock, entry.key, null, entry.keyType));
							}
							chunk.freeChaining();
						}
					}
				}
			}
		}
	}
	
	public void pack() throws IOException{
		cluster.pack();
	}
}
