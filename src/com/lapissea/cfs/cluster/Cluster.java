package com.lapissea.cfs.cluster;

import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Construct;
import com.lapissea.cfs.io.struct.IOStruct.EnumValue;
import com.lapissea.cfs.io.struct.IOStruct.PointerValue;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.VariableNode.SelfPointer;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.Version;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.*;
import com.lapissea.util.function.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.Config.*;
import static java.util.stream.Collectors.*;

public class Cluster extends IOInstance.Contained{
	
	public class SafeSession implements AutoCloseable{
		
		private final boolean prevState;
		
		private SafeSession(){
			synchronized(Cluster.this){
				prevState=isSafeMode();
				safeMode=true;
			}
		}
		
		@Override
		public void close(){
			synchronized(Cluster.this){
				safeMode=prevState;
			}
		}
	}
	
	private static final IOStruct TYPE=IOStruct.thisClass();
	
	private static final int DEFAULT_MIN_CHUNK_SIZE=8;
	
	@EnumValue(index=0, customBitSize=16)
	private Version version;
	
	@PointerValue(index=1, type=StructLinkedList.class)
	IOList<ChunkPointer> freeChunks;
	
	@PointerValue(index=2, type=StructLinkedList.class)
	IOList<ChunkPointer> userChunks;
	
	private final IOInterface              data;
	final         Map<ChunkPointer, Chunk> chunkCache=new HashMap<>();
	
	final int minChunkSize;
	
	private volatile boolean safeMode;
	
	public boolean isSafeMode()                        { return safeMode; }
	
	public Cluster(IOInterface data) throws IOException{this(data, DEFAULT_MIN_CHUNK_SIZE);}
	public Cluster(IOInterface data, int minChunkSize) throws IOException{
		super(TYPE);
		this.data=data;
		this.minChunkSize=minChunkSize;
		
		if(data.isEmpty()){
			safeSession(this::initData);
		}
		
		readStruct();
	}
	
	
	public void safeSession(UnsafeRunnable<IOException> session) throws IOException{
		try(SafeSession ses=new SafeSession()){
			session.run();
		}
		if(!isSafeMode()) onSafeEnd();
	}
	
	
	private void initData() throws IOException{
		version=Version.last();
		writeStruct();
		
		TYPE.<SelfPointer<?>>getVar("freeChunks").allocNew(this, this);
		TYPE.<SelfPointer<?>>getVar("userChunks").allocNew(this, this);
		
		writeStruct();
		
		freeChunks.addElement(null);
		freeChunks.addElement(null);
	}
	
	@IOStruct.Get
	private IOList<ChunkPointer.PtrRef> getUserChunks(){ return IOList.unbox(userChunks); }
	@IOStruct.Set
	private void setUserChunks(IOList<ChunkPointer.PtrRef> unboxedFreeChunks){
		Function<ChunkPointer, ChunkPointer.PtrRef> box  =ChunkPointer.PtrFixed::new;
		Function<ChunkPointer.PtrRef, ChunkPointer> unbox=ChunkPointer.PtrRef::getValue;
		
		this.userChunks=IOList.box(unboxedFreeChunks, unbox, box);
	}
	@Construct
	private IOList<ChunkPointer.PtrRef> constructUserChunks(Chunk source) throws IOException{
		return new StructLinkedList<>(source, (Supplier<ChunkPointer.PtrRef>)ChunkPointer.PtrFixed::new);
	}
	
	
	@IOStruct.Get
	private IOList<ChunkPointer.PtrRef> getFreeChunks(){ return IOList.unbox(freeChunks); }
	@IOStruct.Set
	private void setFreeChunks(IOList<ChunkPointer.PtrRef> unboxedFreeChunks){
		Function<ChunkPointer, ChunkPointer.PtrRef> box  =ChunkPointer.PtrFixed::new;
		Function<ChunkPointer.PtrRef, ChunkPointer> unbox=ChunkPointer.PtrRef::getValue;
		
		this.freeChunks=IOList.box(unboxedFreeChunks, unbox, box);
	}
	@Construct
	private IOList<ChunkPointer.PtrRef> constructFreeChunks(Chunk source) throws IOException{
		var l=new StructLinkedList<>(source, (Supplier<ChunkPointer.PtrRef>)ChunkPointer.PtrFixed::new);
		
		SafeSession[] ss={null};
		l.changingListener=b->{
			if(b){
				ss[0]=new SafeSession();
			}else{
				ss[0].close();
				ss[0]=null;
				if(!isSafeMode()){
					try{
						onSafeEnd();
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
					}
				}
			}
		};
		
		return l;
	}
	
	public Chunk allocWrite(IOInstance obj) throws IOException{
		Chunk chunk=alloc(obj.getInstanceSize(), obj.getStruct().getKnownSize().isPresent());
		chunk.io(io->obj.writeStruct(this, io));
		return chunk;
	}
	
	public Chunk alloc(long requestedCapacity) throws IOException                         { return alloc(requestedCapacity, false); }
	public Chunk alloc(long requestedCapacity, boolean disableResizing) throws IOException{ return alloc(requestedCapacity, disableResizing, c->true); }
	public Chunk alloc(long requestedCapacity, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
		
		Chunk chunk=MAllocer.AUTO.alloc(this, requestedCapacity, disableResizing, approve);
		
		if(DEBUG_VALIDATION){
			if(chunk!=null) checkCached(chunk);
		}
		
		return chunk;
	}
	
	private void onSafeEnd() throws IOException{
		if(freeChunks==null) return;
		
		if(freeChunks.count(Objects::isNull)==0){
			freeChunks.addElement(null);
		}else{
			while(freeChunks.count(Objects::isNull)>2){
				freeChunks.removeElement(freeChunks.indexOf(null));
			}
		}
	}
	
	public IOInterface getData(){
		return data;
	}
	
	@Override
	protected RandomIO getStructSourceIO() throws IOException{
		return getData().io();
	}
	
	@Override
	protected Cluster getSourceCluster(){
		return this;
	}
	
	public Chunk getChunkCached(@NotNull ChunkPointer ptr){
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
		assert cached==chunk:"Fake chunk "+chunk;
	}
	
	private Chunk readChunk(ChunkPointer ptr) throws IOException{
		return Chunk.read(this, ptr);
	}
	
	public Chunk getChunk(ChunkPointer ptr) throws IOException{
		if(ptr.compareTo(getInstanceSize())<0) throw new IndexOutOfBoundsException("Illegal pointer "+ptr);
		if(ptr.compareTo(data.getSize())>=0) throw new IndexOutOfBoundsException("Illegal pointer "+ptr);
		
		
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
	
	public void free(List<Chunk> toFree) throws IOException{
		if(DEBUG_VALIDATION){
			for(Chunk chunk : toFree){
				checkCached(chunk);
				assert !chunk.isUsed():chunk+" is used!";
				
				if(chunk.isUserData()){
					assert userChunks.contains(chunk.getPtr()):chunk+" is user created but not listed as such!";
				}
			}
		}
		
		for(Chunk chunk : toFree){
			if(chunk.isUserData()){
				userChunks.removeElement(chunk.getPtr());
			}
		}
		
		if(toFree.size()==1){
			var val=toFree.get(0);
			if(val.getPtr().equals(112)){
				int i=0;
			}
			MAllocer.AUTO.dealloc(val);
		}else{
			
			for(Chunk chunk : toFree.stream().sorted(Comparator.comparing(Chunk::getPtr).reversed()).collect(Collectors.toUnmodifiableList())){
				MAllocer.AUTO.dealloc(chunk);
			}
		}
	}
	
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		
		UnsafeLongConsumer<IOException> modChain=toAlloc->{
			List<Chunk> chunkChain=null;
			
			long  transferAmount=0;
			Chunk toTransfer    =null;
			Chunk toAppendTo    =target;
			
			UnsafeBiConsumer<Chunk, Chunk, IOException> chainToChunk=(src, dest)->{
				try(var in=src.io().inStream()){
					try(var out=dest.io().outStream()){
						in.transferTo(out.outStream());
					}
				}
			};
			
			Chunk chainAppendChunk;
			while(true){
				NumberSize siz=toAppendTo.getNextSize();
				chainAppendChunk=alloc(Math.max(toAlloc+transferAmount, minChunkSize), false, c->siz.canFit(c.getPtr()));
				
				if(chainAppendChunk==null){
					if(chunkChain==null){
						chunkChain=firstChunk.collectNext();
						assert chunkChain.get(0)==firstChunk;
						assert chunkChain.contains(target):target+" not in chain "+chunkChain;
					}
					
					
					int thisIndex=chunkChain.indexOf(toAppendTo);
					
					if(thisIndex==0){
						assert firstChunk==toAppendTo;
						
						transferAmount+=firstChunk.getSize();
						Chunk newBlock=alloc(Math.max(toAlloc+transferAmount, minChunkSize), false);
						
						try(var io=firstChunk.io()){
							assert io.getSize()==transferAmount:io.getSize()+" "+transferAmount;
							
							try(var dest=newBlock.io()){
								io.inStream().transferTo(dest.outStream());
							}
						}
						
						moveChunk(firstChunk, newBlock);
						return;
					}
					
					int index=chunkChain.indexOf(toAppendTo);
					
					assert index!=-1:
						chunkChain.toString();
					
					toTransfer=toAppendTo;
					toAppendTo=chunkChain.get(index-1);
					
					transferAmount+=toTransfer.getSize();
					continue;
				}
				
				if(toTransfer!=null){
					chainToChunk.accept(toTransfer, chainAppendChunk);
				}
				
				try{
					toAppendTo.setNext(chainAppendChunk);
				}catch(BitDepthOutOfSpaceException e){
					throw new ShouldNeverHappenError(e);
				}
				toAppendTo.syncStruct();
				
				break;
				
			}
		};
		UnsafeLongSupplier<IOException> growChunk=()->{
			long possibleGrowth  =target.getBodyNumSize().maxSize-target.getCapacity();
			long toGrow          =Math.min(toAllocate, possibleGrowth);
			long newChunkCapacity=target.getCapacity()+toGrow;
			
			data.setCapacity(data.getCapacity()+toGrow);
			target.modifyAndSave(c->c.setCapacity(newChunkCapacity));
			
			return toGrow;
		};
		
		long remaining=toAllocate;
		
		if(isLastPhysical(target)){
			remaining-=growChunk.getAsLong();
		}
		
		if(remaining>0){
			modChain.accept(remaining);
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
		return getChunk(new ChunkPointer(getStruct().requireKnownSize()));
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
				if(freeChunk==null) continue;
				var chunk=getChunk(freeChunk);
				assert !chunk.isUsed();
			}
			
		}catch(IOException e){
			throw new IOException("Invalid data "+this+"\n"+data.hexdump(), e);
		}
	}
	
	public void moveChunk(Chunk oldChunk, Chunk newChunk) throws IOException{
		var oldPtr=oldChunk.getPtr();
		var newPtr=newChunk.getPtr();
		
		LogUtil.println("moving", oldChunk, "to", newChunk);
		
		boolean found=memoryWalk(val->{
			LogUtil.println(val.stack);
			if(val.value().equals(oldPtr)){
				val.setter.accept(newPtr);
				return true;
			}
			return false;
		});
		
		assert found:"trying to move unreferenced chunk: "+oldChunk;
		
		chunkCache.remove(oldPtr);
		chunkCache.put(newPtr, oldChunk);
		oldChunk.setLocation(newPtr);
		oldChunk.readStruct();
		
		getChunk(oldPtr).freeChaining();
	}
	
	
	private static record PointerVal(
		List<ChunkPointer> stack,
		UnsafeConsumer<ChunkPointer, IOException> setter
	){
		public ChunkPointer value(){
			return stack.get(stack.size()-1);
		}
	}
	
	private List<ChunkPointer> pushStack(List<ChunkPointer> stack, ChunkPointer el){
		return Stream.concat(stack.stream(), Stream.of(el)).collect(toList());
	}
	
	private boolean memoryWalk(UnsafePredicate<PointerVal, IOException> valueFeedMod) throws IOException{
		return memoryWalk(this::writeStruct, this, List.of(), valueFeedMod);
	}
	@SuppressWarnings("unchecked")
	private boolean memoryWalk(UnsafeRunnable<IOException> saveInstance, IOInstance instance, List<ChunkPointer> stack, UnsafePredicate<PointerVal, IOException> valueFeedMod) throws IOException{
		if(instance instanceof Chunk chunk){
			if(chunk.hasNext()){
				var stackNew=pushStack(stack, chunk.getNextPtr());
				
				var ptrToDo=new PointerVal(stackNew, newPtr->{
					try{
						chunk.setNextPtr(newPtr);
					}catch(BitDepthOutOfSpaceException e){
						throw new IOException(e);
					}
					saveInstance.run();
				});
				if(valueFeedMod.test(ptrToDo)) return true;
				var next=Objects.requireNonNull(chunk.next());
				memoryWalk(next::writeStruct, next, stackNew, valueFeedMod);
			}
			return false;
		}
		
		for(VariableNode<?> variable : instance.getStruct().variables){
			try{
				var val=variable.getValueAsObj(instance);
				
				if(val instanceof ChunkPointer ptr){
					var stackNew=pushStack(stack, ptr);
					var ptrToDo=new PointerVal(stackNew, newPtr->{
						((VariableNode<ChunkPointer>)variable).setValueAsObj(instance, newPtr);
						saveInstance.run();
					});
					if(valueFeedMod.test(ptrToDo)) return true;
				}else{
					
					if(variable instanceof SelfPointer){
						val=((SelfPoint<?>)val).getSelfPtr();
					}
					
					if(val instanceof ObjectPointer<?> ptr&&ptr.hasPtr()){
						var stackNew=pushStack(stack, ptr.getDataBlock());
						var ptrToDo=new PointerVal(stackNew, newPtr->{
							ptr.set(newPtr, ptr.getOffset());
							saveInstance.run();
						});
						if(valueFeedMod.test(ptrToDo)) return true;
						
						var read=ptr.read(this);
						if(read instanceof IOInstance inst){
							UnsafeRunnable<IOException> saver;
							if(inst instanceof IOInstance.Contained cont){
								saver=cont::writeStruct;
							}else if(inst.getStruct().getKnownSize().isPresent()){
								saver=()->((ObjectPointer<IOInstance>)ptr).write(this, inst);
							}else{
								saver=null;
							}
							memoryWalk(saver, inst, stackNew, valueFeedMod);
						}
						//TODO: handle chaining pointers when needed
					}
				}
			}catch(Throwable e){
				throw new IOException("failed to walk on "+variable+" in "+instance, e);
			}
		}
		return false;
	}
	
	public Chunk userAlloc(long initialCapacity) throws IOException{
		Chunk newData=alloc(initialCapacity);
		newData.modifyAndSave(Chunk::markAsUser);
		userChunks.addElement(newData.getPtr());
//		maintainNullCount(userChunks);
		return newData;
	}
	
}
