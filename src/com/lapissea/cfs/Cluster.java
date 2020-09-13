package com.lapissea.cfs;

import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.EnumValue;
import com.lapissea.cfs.io.struct.IOStruct.Set;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.Version;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UnsafeLongSupplier;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Config.*;

public class Cluster extends IOStruct.Instance.Contained{
	
	public class SafeSession implements AutoCloseable{
		
		private final boolean prevState;
		
		private SafeSession(){
			prevState=safeAllocationMode;
			safeAllocationMode=true;
		}
		
		@Override
		public void close() throws IOException{
			safeAllocationMode=prevState;
//			if(!safeAllocationMode) onSafeEnd();
		}
	}
	
	private static final int DEFAULT_MIN_CHUNK_SIZE=8;
	
	private static final IOStruct     TYPE     =IOStruct.thisClass();
	private static final ChunkPointer FIRST_PTR=new ChunkPointer(TYPE.requireKnownSize());
	
	
	@EnumValue(index=1, customBitSize=16)
	private Version version;
	
	@Value(index=2, rw=ChunkPointer.FixedIO.class, rwArgs="LONG")
	private ChunkPointer freeChunksPtr;
	
	private final IOInterface              data;
	private final Map<ChunkPointer, Chunk> chunkCache=new HashMap<>();
	
	private IOList<ChunkPointer> freeChunks;
	
	private final int minChunkSize;
	
	private boolean safeAllocationMode=false;
	
	
	public Cluster(IOInterface data) throws IOException{this(data, DEFAULT_MIN_CHUNK_SIZE);}
	public Cluster(IOInterface data, int minChunkSize) throws IOException{
		this.data=data;
		this.minChunkSize=minChunkSize;
		
		if(data.isEmpty()){
			try(var ses=safeSession()){
				initData();
			}
		}
		
		readStruct();
	}
	
	public SafeSession safeSession(){
		return new SafeSession();
	}
	
	public void safeSession(Runnable session) throws IOException{
		try(SafeSession ses=safeSession()){
			session.run();
		}
	}
	
	
	private void initData() throws IOException{
		version=Version.last();
		writeStruct();
		
		setFreeChunksPtr(appendNew(IOStruct.getInstance(StructLinkedList.class).requireMaximumSize()).getPtr());
		writeStruct();
//		onSafeEnd();
	}
	
	@Set
	private void setFreeChunksPtr(ChunkPointer freeChunksPtr) throws IOException{
		if(Objects.equals(freeChunksPtr, this.freeChunksPtr)) return;
		
		this.freeChunksPtr=freeChunksPtr;
		
		if(freeChunksPtr!=null){
			Function<ChunkPointer, ChunkPointer.PtrRef> box=ChunkPointer.PtrFixed::new;
			
			IOList<ChunkPointer.PtrRef> data=new StructLinkedList<>(getChunk(freeChunksPtr), ()->box.apply(null));
			
			freeChunks=new IOList<>(){
				@Override
				public String toString(){
					return data.toString();
				}
				
				@Override
				public int size(){
					return data.size();
				}
				@Override
				public ChunkPointer getElement(int index) throws IOException{
					return data.getElement(index).getValue();
				}
				@Override
				public void setElement(int index, ChunkPointer value) throws IOException{
					var boxed=box.apply(value);
					if(boxed.structType().getKnownSize().isPresent()||data.getElement(index).getInstanceSize()==boxed.getInstanceSize()){
						data.setElement(index, boxed);
					}else{
						try(var ses=safeSession()){
							data.setElement(index, boxed);
						}
						onSafeEnd();
					}
				}
				@Override
				public void ensureCapacity(int elementCapacity) throws IOException{
					try(var ses=safeSession()){
						data.ensureCapacity(elementCapacity);
					}
					onSafeEnd();
				}
				@Override
				public void removeElement(int index) throws IOException{
					try(var ses=safeSession()){
						data.removeElement(index);
					}
					onSafeEnd();
				}
				@Override
				public void addElement(int index, ChunkPointer value) throws IOException{
					try(var ses=safeSession()){
						data.addElement(index, box.apply(value));
					}
					onSafeEnd();
				}
				@Override
				public void validate() throws IOException{
					data.validate();
				}
				@Override
				public void free() throws IOException{
					data.free();
				}
			};
			freeChunks.validate();
		}else{
			freeChunks=null;
		}
	}
	
	private Chunk makeChunkReal(Chunk chunk) throws IOException{
		assert !chunkCache.containsKey(chunk.getPtr());
		chunk.writeStruct();
		
		Chunk read=getChunk(chunk.getPtr());
		assert read.equals(chunk);
		return read;
	}
	
	private Chunk appendNew(long requestedSize) throws IOException{
		ChunkPointer ptr=new ChunkPointer(data.getSize());
		
		Chunk chunk=makeChunkReal(new Chunk(this, ptr, requestedSize, calcPtrSize()));
		assert data.getSize()==chunk.dataStart():data.getSize()+"=="+chunk.dataStart();
		
		data.ioAt(chunk.dataStart(), io->{
			Utils.zeroFill(io::write, chunk.getCapacity());
		});
		
		assert data.getSize()==chunk.dataEnd():data.getSize()+"=="+chunk.dataEnd();
		
		LogUtil.println("aloc append", chunk);
		
		return chunk;
	}
	
	public Chunk allocWrite(IOStruct.Instance obj) throws IOException{
		Chunk chunk=alloc(obj.getInstanceSize());
		chunk.io(obj::writeStruct);
		return chunk;
	}
	
	public Chunk alloc(long requestedCapacity) throws IOException{
		
		Chunk chunk;
		
		chunk=tryReuse(requestedCapacity);
		
		if(chunk==null){
			chunk=appendNew(requestedCapacity);
		}
		
		if(DEBUG_VALIDATION){
			checkCached(chunk);
		}
		
		return chunk;
	}
	
	private void onSafeEnd() throws IOException{
		var nullCount=freeChunks.count(Objects::isNull);
		while(nullCount>2){
			freeChunks.removeElement(freeChunks.indexOfLast(null));
			nullCount--;
		}
		if(nullCount==0){
			freeChunks.addElement(null);
		}
	}
	
	private Chunk tryReuse(long requestedCapacity) throws IOException{
		if(freeChunks.isEmpty()) return null;
		if(freeChunks.size()==1&&freeChunks.getElement(0)==null) return null;
		
		Chunk largest=null;
		
		int  bestIndex   =-1;
		long bestOverAloc=Long.MAX_VALUE;
		
		for(int i=0;i<freeChunks.size();i++){
			var ptr=freeChunks.getElement(i);
			if(ptr==null) continue;
			
			Chunk chunk=ptr.dereference(this);
			var   cap  =chunk.getCapacity();
			
			if(cap<requestedCapacity){
				continue;
			}
			
			if(!safeAllocationMode){
				long maxSize =requestedCapacity+minChunkSize;
				long overAloc=maxSize-cap;
				if(overAloc>=0&&overAloc<bestOverAloc){
					bestOverAloc=overAloc;
					bestIndex=i;
					if(overAloc==0) break;
				}
			}
			
			if(largest==null||largest.getCapacity()<cap){
				largest=chunk;
			}
		}
		
		if(bestIndex!=-1){
			Chunk chunk=freeChunks.getElement(bestIndex).dereference(this);
			
			chunk.modifyAndSave(c->c.setUsed(true));
			
			freeChunks.removeElement(bestIndex);
			LogUtil.println("aloc reuse exact", chunk);
			return chunk;
		}
		
		if(largest!=null&&largest.getCapacity()>requestedCapacity){
			long totalSpace=largest.totalSize();
			
			Chunk chunkUse=new Chunk(this, largest.getPtr(), requestedCapacity, calcPtrSize());
			
			chunkUse.setPtr(new ChunkPointer(largest.dataEnd()-chunkUse.totalSize()));
			
			long freeCapacity=largest.getCapacity()-chunkUse.totalSize();
			
			if(freeCapacity>=DEFAULT_MIN_CHUNK_SIZE){
				chunkUse=makeChunkReal(chunkUse);
				largest.modifyAndSave(c->c.setCapacity(freeCapacity));
				
				LogUtil.println("aloc reuse split", largest, " -> ", chunkUse);
				
				return chunkUse;
			}
		}
		
		return null;
	}
	private NumberSize calcPtrSize() throws IOException{
		return NumberSize.bySize(data.getSize()).next();
	}
	
	public IOInterface getData(){
		return data;
	}
	
	@Override
	protected RandomIO getStructSourceIO() throws IOException{
		return getData().io();
	}
	
	public Chunk getChunkCached(ChunkPointer ptr){
		Objects.requireNonNull(ptr);
		return chunkCache.get(ptr);
	}
	
	public void checkSync(Chunk cached) throws IOException{
		checkCached(cached);
		
		Chunk read=readChunk(cached.getPtr());
		assert cached.equals(read):"\n"+TextUtil.toTable("unsaved changes -> cached,read", List.of(cached, read));
	}
	
	public void checkCached(Chunk chunk){
		Chunk cached=getChunkCached(chunk.getPtr());
		assert cached==chunk;
	}
	
	private Chunk readChunk(ChunkPointer ptr) throws IOException{
		return Chunk.read(this, ptr);
	}
	
	public Chunk getChunk(ChunkPointer ptr) throws IOException{
		if(ptr.compareTo(getInstanceSize())<0) throw new IndexOutOfBoundsException("Illegal pointer "+ptr.toString());
		if(ptr.compareTo(data.getSize())>=0) throw new IndexOutOfBoundsException("Illegal pointer "+ptr.toString());
		
		
		Chunk cached=getChunkCached(ptr);
		if(cached!=null){
			if(DEBUG_VALIDATION){
				checkSync(cached);
				assert cached.getPtr().equals(ptr);
			}
			return cached;
		}
		
		Chunk newChunk=readChunk(ptr);
		chunkCache.put(ptr, newChunk);
		
		assert newChunk.getPtr().equals(ptr);
		return newChunk;
	}
	
	public boolean isLastPhysical(Chunk chunk) throws IOException{
		return chunk.dataEnd()>=data.getSize();
	}
	
	private void destroyChunk(Chunk chunk) throws IOException{
		chunkCache.remove(chunk.getPtr());
		chunk.zeroOutHead();
	}
	
	private void popChunk(Chunk chunk) throws IOException{
		LogUtil.println("freeing deallocated", chunk);
		chunkCache.remove(chunk.getPtr());
		data.setCapacity(chunk.getPtr().getValue());
	}
	
	
	private void listAsFree(Chunk toFree) throws IOException{
		toFree.setSize(0);
		toFree.setNextPtr(null);
		toFree.syncStruct();
		
		toFree.zeroOutCapacity();
		
		UnsafeBiConsumer<Chunk, Chunk, IOException> merge=(target, next)->{
			long cap=next.dataEnd()-target.dataStart();
			target.modifyAndSave(c->c.setCapacity(cap));
			destroyChunk(next);
		};
		
		Function<Chunk, long[]> rang=ch->new long[]{ch.getPtr().value(), ch.dataEnd()};
		
		LogUtil.println("freeing", toFree);
		
		
		Chunk next     =toFree.nextPhysical();
		int   nextIndex=-1;
		
		if(next!=null){
			if(next.isUsed()) next=null;
			else{
				var ptr=next.getPtr();
				nextIndex=freeChunks.indexOf(ptr);
			}
		}
		
		Chunk prev;
		int   prevIndex=freeChunks.find(v->v!=null&&toFree.getPtr().equals(v.dereference(this).dataEnd()));
		if(prevIndex!=-1){
			prev=freeChunks.getElement(prevIndex).dereference(this);
		}else{
			prev=null;
		}
		
		
		if(toFree.getPtr().equals(237)){
			int i=0;
		}
		
		if(next!=null&&prev!=null){
			LogUtil.println("free triple merge", rang.apply(prev), rang.apply(toFree), rang.apply(next));
			freeChunks.setElement(nextIndex, null);
			
			merge.accept(prev, next);
			destroyChunk(toFree);
			
		}else if(prev!=null){
			LogUtil.println("free merge", rang.apply(prev), rang.apply(toFree));
			merge.accept(prev, toFree);
			
		}else if(next!=null){
			LogUtil.println("free merge", rang.apply(toFree), rang.apply(next));
			freeChunks.setElement(nextIndex, toFree.getPtr());
			merge.accept(toFree, next);
			
		}else{
			LogUtil.println("free list", toFree);
			int emptyIndex=freeChunks.indexOf(null);
			if(emptyIndex!=-1){
				freeChunks.setElement(emptyIndex, toFree.getPtr());
			}else{
				freeChunks.addElement(toFree.getPtr());
			}
		}
		
		
		if(DEBUG_VALIDATION){
			if(freeChunks.size()>1){
				freeChunks.validate();
				
				List<long[]> ranges=new ArrayList<>();
				for(int i=0;i<freeChunks.size();i++){
					ChunkPointer c=freeChunks.getElement(i);
					if(c==null) continue;
					
					Chunk ch=c.dereference(this);
					ranges.add(rang.apply(ch));
				}
				ranges.sort(Comparator.comparingLong(e->e[0]));
				long last=-1;
				for(long[] range : ranges){
					assert range[0]!=last:
						TextUtil.toString(ranges)+"\n"+freeChunks;
					last=range[1];
				}
//				LogUtil.println(freeChunks, ranges);
			}
			validate();
		}
	}
	
	public void free(List<Chunk> toFree) throws IOException{
		if(DEBUG_VALIDATION){
			for(Chunk chunk : toFree){
				checkCached(chunk);
				assert !chunk.isUsed():chunk+" is used!";
			}
		}
		
		for(Chunk chunk : toFree.stream().sorted(Comparator.comparing(Chunk::getPtr).reversed()).collect(Collectors.toUnmodifiableList())){
			if(isLastPhysical(chunk)){
				popChunk(chunk);
				validate();
				continue;
			}
			listAsFree(chunk);
		}
	}
	
	public void allocTo(Chunk target, long toAllocate) throws IOException{
		
		UnsafeLongSupplier<IOException> chain=()->{
			assert !target.hasNext():target.toString();
			Chunk chained=alloc(Math.max(toAllocate, minChunkSize));
			
			target.modifyAndSave(ch->ch.setNextPtr(chained.getPtr()));
			return chained.getCapacity();
		};
		UnsafeLongSupplier<IOException> growFile=()->{
			long possibleGrowth  =target.getBodyNumSize().maxSize-target.getCapacity();
			long toGrow          =Math.min(toAllocate, possibleGrowth);
			long newChunkCapacity=target.getCapacity()+toGrow;
			
			data.setCapacity(data.getCapacity()+toGrow);
			target.modifyAndSave(c->c.setCapacity(newChunkCapacity));
			
			return toGrow;
		};
		
		long remaining=toAllocate;
		
		if(isLastPhysical(target)){
			remaining-=growFile.getAsLong();
		}
		
		while(remaining>0){
			remaining-=chain.getAsLong();
		}
	}
	
	public String chainDump() throws IOException{
		StringBuilder  sb     =new StringBuilder();
		HashSet<Chunk> visited=new HashSet<>();
		
		try{
			
			for(Chunk chunk=getFirstChunk();
			    chunk!=null;
			    chunk=chunk.nextPhysical()){
				if(visited.contains(chunk)) continue;
				
				List<Chunk> chain=chunk.collectNext();
				visited.addAll(chain);
				
				byte[] d=chunk.ioAt(0, io->{return io.readInts1(Math.toIntExact(io.getSize()));});
				
				sb.append('\n').append(new MemoryData(d, true).hexdump(chain.toString()));
			}
		}catch(Throwable e){
			sb.append("\n...").append("ERROR: ").append(e.getMessage());
		}
		
		return sb.toString();
	}
	
	public String chunkDump() throws IOException{
		StringBuilder sb=new StringBuilder(new MemoryData(data.read(0, (int)getInstanceSize()), true).hexdump("header"));
		try{
			
			
			for(Chunk chunk=getFirstChunk();
			    chunk!=null;
			    chunk=chunk.nextPhysical()){
				
				sb.append('\n').append(new MemoryData(data.read(chunk.dataStart(), (int)chunk.getCapacity()), true).hexdump(chunk.toString()));
			}
		}catch(Throwable e){
			sb.append("\n...").append("ERROR: ").append(e.getMessage());
		}
		
		return sb.toString();
	}
	
	public Chunk getFirstChunk() throws IOException{
		return getChunk(FIRST_PTR);
	}
	
	public void validate() throws IOException{
		try{
			long used =0;
			long total=0;
			long free =0;
			
			for(Chunk chunk=getFirstChunk();chunk!=null;chunk=chunk.nextPhysical()){
				if(chunk.isUsed()){
					used+=chunk.getSize();
					total+=chunk.getCapacity();
				}else{
//					assert freeChunks.contains(chunk.getPtr()):chunk+" marked as unused but not listed "+freeChunks;
					free+=chunk.getCapacity();
				}
			}
			
			for(var freeChunk : freeChunks){
				if(freeChunk==null) continue;
				var chunk=freeChunk.dereference(this);
				assert !chunk.isUsed();
			}

//			LogUtil.printTable("used", used, "total", total, "free", free, "raw size", data.getSize(), "chunk ratio", used/(double)total, "raw ratio", used/(double)data.getSize());
			
		}catch(IOException e){
			throw new IOException("Invalid data "+this+"\n"+data.hexdump(), e);
		}
	}
	
	public interface LinkAcceptor{
		void link(boolean chunkDest, long src, long dest, IOStruct.Instance destVal);
	}
	
	private void memoryWalk(LinkAcceptor acceptor, long structLocation, IOStruct.Instance instance){
	
	}
	
	public void memoryWalk(LinkAcceptor acceptor) throws IOException{
		
		var off=calcVarOffset(TYPE.varByName("freeChunksPtr")).getOffset();
		
		acceptor.link(true, off, freeChunksPtr.getValue(), (IOStruct.Instance)freeChunks);
	}
}
