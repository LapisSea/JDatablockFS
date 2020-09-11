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
		public void close(){
			safeAllocationMode=prevState;
			if(!safeAllocationMode) onSafeEnd();
		}
	}
	
	private static final int DEFAULT_MIN_CHUNK_SIZE=8;
	
	private static final ChunkPointer FIRST_PTR=new ChunkPointer(IOStruct.thisClass().requireKnownSize());
	
	
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
		
		try(var ses=safeSession()){
			if(data.isEmpty()) initData();
			
			readStruct();
		}
	}
	
	public SafeSession safeSession(){
		return new SafeSession();
	}
	
	public void safeSession(Runnable session){
		try(SafeSession ses=safeSession()){
			session.run();
		}
	}
	
	
	private void initData() throws IOException{
//		this.structType().logStruct();
		
		version=Version.last();
		writeStruct();
		
		setFreeChunksPtr(appendNew(IOStruct.getInstance(StructLinkedList.class).requireMaximumSize()).getPtr());
		writeStruct();
	}
	
	@Set
	private void setFreeChunksPtr(ChunkPointer freeChunksPtr) throws IOException{
		if(Objects.equals(freeChunksPtr, this.freeChunksPtr)) return;
		
		this.freeChunksPtr=freeChunksPtr;
		
		if(freeChunksPtr!=null){
			Function<ChunkPointer, ChunkPointer.PtrRef> box=ChunkPointer.PtrSmart::new;
			
			IOList<ChunkPointer.PtrRef> data=new StructLinkedList<>(getChunk(freeChunksPtr), ()->box.apply(null));
			
			freeChunks=new IOList<>(){
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
					try(var ses=safeSession()){
						data.setElement(index, box.apply(value));
					}
				}
				@Override
				public void ensureCapacity(int elementCapacity) throws IOException{
					try(var ses=safeSession()){
						data.ensureCapacity(elementCapacity);
					}
				}
				@Override
				public void removeElement(int index) throws IOException{
					try(var ses=safeSession()){
						data.removeElement(index);
					}
				}
				@Override
				public void addElement(int index, ChunkPointer value) throws IOException{
					try(var ses=safeSession()){
						data.addElement(index, box.apply(value));
					}
				}
				@Override
				public void validate() throws IOException{
					data.validate();
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
		
		Chunk chunk=null;
		
		if(!safeAllocationMode){
			chunk=tryReuse(requestedCapacity);
		}
		
		if(chunk==null){
			chunk=appendNew(requestedCapacity);
		}
		
		if(DEBUG_VALIDATION){
			checkCached(chunk);
		}
		
		return chunk;
	}
	
	private void onSafeEnd(){
	
	}
	
	private Chunk tryReuse(long requestedCapacity) throws IOException{
		if(freeChunks.isEmpty()) return null;
		
		Chunk largest     =null;
		int   largestIndex=-1;
		
		for(int i=0;i<freeChunks.size();i++){
			Chunk chunk=freeChunks.getElement(i).dereference(this);
			var   cap  =chunk.getCapacity();
			
			if(cap<requestedCapacity){
				continue;
			}
			
			if(cap<=requestedCapacity+minChunkSize/2){
				chunk.modifyAndSave(c->c.setUsed(true));
				freeChunks.removeElement(i);
				LogUtil.println("aloc reuse exact", chunk);
				return chunk;
			}
			
			if(largest==null||largest.getCapacity()<cap){
				largest=chunk;
				largestIndex=i;
			}
		}
		
		
		if(largest!=null&&largest.getCapacity()>requestedCapacity){
			long totalSpace=largest.totalSize();
			
			Chunk chunkUse=new Chunk(this, largest.getPtr(), requestedCapacity, NumberSize.bySize(requestedCapacity*4/3), calcPtrSize());
			
			long  remaining        =totalSpace-chunkUse.totalSize();
			Chunk remainingChunk   =new Chunk(this, new ChunkPointer(chunkUse.dataEnd()), remaining, calcPtrSize());
			long  remainingCapacity=remaining-remainingChunk.getInstanceSize();
			
			if(remainingCapacity>0){
				remainingChunk.setCapacity(remainingCapacity);
				remainingChunk.setUsed(false);
				
				
				assert remainingChunk.dataEnd()==largest.dataEnd():remainingChunk.dataEnd()+" "+largest.dataEnd();
				
				destroyChunk(largest);
				
				chunkUse=makeChunkReal(chunkUse);
				remainingChunk=makeChunkReal(remainingChunk);
				
				freeChunks.setElement(largestIndex, remainingChunk.getPtr());
				
				LogUtil.println("aloc reuse split", largest, " -> ", chunkUse, remainingChunk);
				
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
	
	private void popLast(Chunk chunk) throws IOException{
		LogUtil.println("freeing deallocated", chunk);
		destroyChunk(chunk);
		data.setCapacity(chunk.getPtr().getValue());
	}
	
	
	private void listAsFree(Chunk toFree) throws IOException{
		toFree.setSize(0);
		toFree.setNextPtr(null);
		toFree.syncStruct();
		
		toFree.zeroOutCapacity();
		
		UnsafeBiConsumer<Chunk, Chunk, IOException> merge=(target, next)->{
			long cap=next.dataEnd()-target.dataStart();
			destroyChunk(next);
			target.modifyAndSave(c->c.setCapacity(cap));
			assert target.dataEnd()==next.dataEnd():target.dataEnd()+" "+next.dataEnd();
		};
		
		validate();
		if(toFree.getPtr().equals(112)){
			int i=0;
		}
		
		Function<Chunk, long[]> rang=ch->new long[]{ch.getPtr().value(), ch.dataEnd()};
		
		LogUtil.println("freeing", toFree);
		
		
		Chunk next     =toFree.nextPhysical();
		int   nextIndex=-1;
		
		if(next!=null){
			if(next.isUsed()) next=null;
			else{
				var ptr=next.getPtr();
				nextIndex=freeChunks.indexOf(v->v.equals(ptr));
			}
		}
		
		Chunk prev;
		int   prevIndex=freeChunks.indexOf(v->toFree.getPtr().equals(v.dereference(this).dataEnd()));
		if(prevIndex!=-1){
			prev=freeChunks.getElement(prevIndex).dereference(this);
		}else prev=null;
		
		
		if(next!=null&&prev!=null){
			LogUtil.println("free triple merge", rang.apply(prev), rang.apply(toFree), rang.apply(next));
			destroyChunk(toFree);
			freeChunks.removeElement(nextIndex);
			merge.accept(prev, next);
			
		}else if(prev!=null){
			LogUtil.println("free merge", rang.apply(prev), rang.apply(toFree));
			merge.accept(prev, toFree);
			
		}else if(next!=null){
			LogUtil.println("free merge", rang.apply(toFree), rang.apply(next));
			freeChunks.setElement(nextIndex, toFree.getPtr());
			merge.accept(toFree, next);
			
		}else{
			LogUtil.println("free list", toFree);
			freeChunks.addElement(toFree.getPtr());
		}
		
		
		if(DEBUG_VALIDATION){
			if(freeChunks.size()>1){
				freeChunks.validate();
				
				List<long[]> ranges=new ArrayList<>();
				for(int i=0;i<freeChunks.size();i++){
					ChunkPointer c=freeChunks.getElement(i);
					
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
				LogUtil.println(ranges);
			}
		}
	}
	
	public void free(List<Chunk> toFree) throws IOException{
		validate();
		if(DEBUG_VALIDATION){
			for(Chunk chunk : toFree){
				checkCached(chunk);
				assert !chunk.isUsed():chunk+" is used!";
			}
		}
		
		for(Chunk chunk : toFree.stream().sorted(Comparator.comparing(Chunk::getPtr).reversed()).collect(Collectors.toUnmodifiableList())){
			if(isLastPhysical(chunk)){
				popLast(chunk);
				validate();
				continue;
			}
			listAsFree(chunk);
			validate();
		}
		validate();
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
					free+=chunk.getCapacity();
				}
			}
			
			for(var freeChunk : freeChunks){
				var chunk=freeChunk.dereference(this);
				assert !chunk.isUsed();
			}

//			LogUtil.printTable("used", used, "total", total, "free", free, "raw size", data.getSize(), "chunk ratio", used/(double)total, "raw ratio", used/(double)data.getSize());
			
		}catch(IOException e){
			throw new IOException("Invalid data "+this+"\n"+data.hexdump(), e);
		}
	}
}
