package com.lapissea.cfs.cluster;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.impl.IOFileData;
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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.Config.*;
import static java.util.stream.Collectors.*;

public class Cluster extends IOInstance.Contained{
	
	public static class Builder{
		private IOInterface data;
		private int         minChunkSize=DEFAULT_MIN_CHUNK_SIZE;
		private boolean     readOnly    =false;
		
		public Builder withIO(IOInterface data){
			this.data=data;
			return this;
		}
		
		public Builder withNewMemory(){
			return withIO(new MemoryData());
		}
		
		public Builder withMemoryView(byte[] data){
			withIO(new MemoryData(data, true));
			return asReadOnly();
		}
		
		public Builder fromFile(File file){
			return withIO(new IOFileData(file));
		}
		
		public Builder withMinChunkSize(int minChunkSize){
			this.minChunkSize=minChunkSize;
			return this;
		}
		
		public Builder asReadOnly(){
			readOnly=true;
			return this;
		}
		
		public Cluster build() throws IOException{
			return new Cluster(data, minChunkSize, readOnly);
		}
	}
	
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
	
	private static final int DEFAULT_MIN_CHUNK_SIZE=8;
	
	
	public static Cluster build(Consumer<Builder> build) throws IOException{
		Builder builder=build();
		build.accept(builder);
		return builder.build();
	}
	
	public static Builder build(){
		return new Builder();
	}
	
	@EnumValue(index=0, customBitSize=16)
	private Version              version;
	@PointerValue(index=1, type=StructLinkedList.class)
	private IOList<ChunkPointer> freeChunks;
	@PointerValue(index=2, type=StructLinkedList.class)
	private IOList<ChunkPointer> userChunks;
	
	private final boolean              readOnly;
	private       boolean              safeMode;
	private final int                  minChunkSize;
	private final LinkedList<Chunk>    freeingQueue;
	private       IOList<ChunkPointer> effectiveFreeChunks;
	
	private final IOInterface              data;
	final         Map<ChunkPointer, Chunk> chunkCache;
	
	
	protected Cluster(IOInterface data, int minChunkSize, boolean readOnly) throws IOException{
		this.readOnly=readOnly;
		this.data=data;
		this.minChunkSize=minChunkSize;
		
		freeingQueue=new LinkedList<>(){
			@Override
			public Chunk set(int index, Chunk element){
				if(element==null) return remove(index);
				else return super.set(index, element);
			}
			
			@Override
			public boolean add(Chunk chunk){
				return chunk!=null&&super.add(chunk);
			}
		};
		effectiveFreeChunks=createBoxedFreeingQueue();
		chunkCache=new HashMap<>();
		
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
		var type=IOStruct.thisClass();
		
		type.<SelfPointer<?>>getVar("freeChunks").allocNew(this, this);
		type.<SelfPointer<?>>getVar("userChunks").allocNew(this, this);
		
		writeStruct();
		
		freeChunks.addElements(null, null);
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
		return StructLinkedList.build(b->b.withContainer(source)
		                                  .withElementConstructor(ChunkPointer.PtrFixed::new)
		                                  .withSolidNodes(true));
	}
	
	
	@IOStruct.Get
	private IOList<ChunkPointer.PtrRef> getFreeChunks(){ return IOList.unbox(freeChunks); }
	
	@IOStruct.Set
	private void setFreeChunks(IOList<ChunkPointer.PtrRef> unboxedFreeChunks){
		Function<ChunkPointer, ChunkPointer.PtrRef> box  =ChunkPointer.PtrFixed::new;
		Function<ChunkPointer.PtrRef, ChunkPointer> unbox=ChunkPointer.PtrRef::getValue;
		
		this.freeChunks=IOList.box(unboxedFreeChunks, unbox, box);
		effectiveFreeChunks=IOList.mergeView(List.of(createBoxedFreeingQueue(), freeChunks));
	}
	
	@Construct
	private IOList<ChunkPointer.PtrRef> constructFreeChunks(Chunk source) throws IOException{
		StructLinkedList<ChunkPointer.PtrRef> l=StructLinkedList.build(b->b.withContainer(source)
		                                                                   .withElementConstructor(ChunkPointer.PtrFixed::new)
		                                                                   .withSolidNodes(false));
		
		ObjectHolder<SafeSession> ss=new ObjectHolder<>();
		l.changingListener=b->{
			if(b){
				ss.obj=new SafeSession();
			}else{
				ss.obj.close();
				ss.obj=null;
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
	
	private IOList<ChunkPointer> createBoxedFreeingQueue(){
		return IOList.box(IOList.wrap(freeingQueue), Chunk::getPtr, ptr->{
			if(ptr==null) return null;
			try{
				return getChunk(ptr);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
	}
	
	NumberSize calcPtrSize(boolean disableNext) throws IOException{
		if(disableNext) return NumberSize.VOID;
		return NumberSize.bySize(getData().getSize()).next();
	}
	
	public Chunk alloc(AllocateTicket ticket) throws IOException{
		
		Chunk chunk=MAllocer.AUTO.alloc(this, getFreeData(), ticket.withBytes(Math.max(getMinChunkSize(), ticket.requestedBytes())));
		
		if(chunk!=null){
			if(DEBUG_VALIDATION){
				assert ticket.userData()==chunk.isUserData();
				checkCached(chunk);
			}
			
			if(ticket.userData()){
				userChunks.addElement(chunk.getPtr());
			}
		}
		return chunk;
	}
	
	private IOList<ChunkPointer> getFreeData(){
		return effectiveFreeChunks;
	}
	
	private void onSafeEnd() throws IOException{
		if(freeChunks==null) return;
		
		if(freeChunks.count(Objects::isNull)==0){
			freeChunks.addElement(null);
		}else{
			while(freeChunks.count(Objects::isNull)>2){
				freeChunks.removeElement(freeChunks.indexOfLast(null));
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
		assert cached.equals(read):"\n"+TextUtil.toTable("unsaved chunk changes -> cached,read", List.of(cached, read));
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
	
	public synchronized void free(List<Chunk> toFree) throws IOException{
		if(toFree.size()==1){
			free(toFree.get(0));
			return;
		}
		
		batchFree(()->{
			for(Chunk chunk : toFree){
				free(chunk);
			}
		});
	}
	
	public synchronized void free(Chunk chunk) throws IOException{
		if(DEBUG_VALIDATION){
			checkCached(chunk);
			assert !chunk.isUsed():chunk+" is used!";
			
			if(chunk.isUserData()){
				assert userChunks.contains(chunk.getPtr()):chunk+" is user created but not listed as such!";
			}
		}
		
		freeingQueue.add(chunk);
		
		if(chunk.isUserData()){
			boolean oldFreeingChunks=freeingChunks;
			freeingChunks=true;
			userChunks.removeElement(chunk.getPtr());
			freeingChunks=oldFreeingChunks;
			chunk.modifyAndSave(Chunk::clearUserMark);
		}
		
		if(!freeingChunks){
			processFreeQueue();
		}
	}
	
	private boolean freeingChunks;
	
	private Chunk popBestChunk(){
		ChunkPointer lastPtr  =null;
		int          bestIndex=-1;
		for(int i=0, j=freeingQueue.size();i<j;i++){
			var ptr=freeingQueue.get(i).getPtr();
			if(lastPtr==null||ptr.compareTo(lastPtr)>0){
				lastPtr=ptr;
				bestIndex=i;
			}
		}
		assert bestIndex!=-1;
		return freeingQueue.remove(bestIndex);
	}
	
	private void processFreeQueue() throws IOException{
		try{
			assert !freeingChunks;
			freeingChunks=true;
			
			
			while(!freeingQueue.isEmpty()){
				Chunk val;
				if(freeingQueue.size()==1) val=freeingQueue.removeFirst();
				else val=popBestChunk();
				
				MAllocer.AUTO.dealloc(val, getFreeData());
			}
		}finally{
			freeingChunks=false;
		}
	}
	
	
	public void batchFree(UnsafeRunnable<IOException> session) throws IOException{
		if(freeingChunks){
			session.run();
		}else{
			freeingChunks=true;
			session.run();
			freeingChunks=false;
			processFreeQueue();
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
				chainAppendChunk=AllocateTicket.bytes(toAlloc+transferAmount)
				                               .withApproval(chunk->siz.canFit(chunk.getPtr()))
				                               .submit(this);
				
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
						Chunk newBlock=AllocateTicket.bytes(toAlloc+transferAmount)
						                             .submit(this);
						
						if(DEBUG_VALIDATION){
							try(var io=firstChunk.io()){
								assert io.getSize()==transferAmount:io.getSize()+" "+transferAmount;
							}
						}
						
						copyDataAndMoveChunk(firstChunk, newBlock);
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
			long possibleGrowth=target.getBodyNumSize().maxSize-target.getCapacity();
			if(possibleGrowth==0) return 0;
			
			long toGrow          =Math.min(toAllocate, possibleGrowth);
			long newChunkCapacity=target.getCapacity()+toGrow;
			
			data.setCapacity(data.getCapacity()+toGrow);
			
			target.setCapacityConfident(newChunkCapacity);
			
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
			
			for(var entry : chunkCache.entrySet()){
				if(!entry.getKey().equals(entry.getValue().getPtr())){
					throw new RuntimeException("invalid entry state"+entry);
				}
				Chunk ch=entry.getValue();
				checkSync(ch);
			}
			
			//noinspection StatementWithEmptyBody
			for(Chunk chunk=getFirstChunk();chunk!=null;chunk=chunk.nextPhysical()){ }
			
			Set<ChunkPointer> unique=new HashSet<>();
			
			for(var freePtr : freeChunks){
				if(freePtr==null) continue;
				if(!unique.add(freePtr)){
					if(DEBUG_VALIDATION){
						Chunk c;
						try{
							c=getChunk(freePtr);
						}catch(IOException e){c=null;}
						throw new IllegalStateException("Duplicate chunk ptr: "+freePtr+(c==null?" <err>":" "+c)+" in "+freeChunks);
					}else{
						LogUtil.println("Possible corruption! Duplicate free chunk", freePtr, "in", freeChunks);
						freeChunks.find(freePtr::equals, freeChunks::removeElement);
					}
				}
				var chunk=getChunk(freePtr);
				assert !chunk.isUsed();
			}
			
		}catch(IOException e){
			throw new IOException("Invalid data "+this+"\n"+data.hexdump(), e);
		}
	}
	
	public void copyDataAndMoveChunk(Chunk oldChunk, Chunk newChunk) throws IOException{
		assert newChunk.getCapacity()>=oldChunk.getCapacity();
		try(var io=oldChunk.io()){
			try(var dest=newChunk.io()){
				Utils.transferExact(io, dest, oldChunk.getSize());
			}
		}
		oldChunk.modifyAndSave(Chunk::clearNextPtr);
		
		var oldPtr=oldChunk.getPtr();
		moveChunkRef(oldChunk, newChunk);
		
		getChunk(oldPtr).freeChaining();
	}
	
	public void moveChunkRef(Chunk oldChunk, Chunk newChunk) throws IOException{
		var oldPtr=oldChunk.getPtr();
		var newPtr=newChunk.getPtr();
		
		
		boolean found=memoryWalk(val->{
			if(val.isValue(oldPtr)){
				LogUtil.println("moving", oldChunk, "to", newChunk);
				val.set(newPtr);
				return true;
			}
			return false;
		});
		
		assert found:
			"trying to move unreferenced chunk: "+oldChunk.toString()+" to "+newChunk;
		
		chunkCache.remove(oldPtr);
		chunkCache.put(newPtr, oldChunk);
		oldChunk.setLocation(newPtr);
		oldChunk.readStruct();
	}
	
	
	private static record PointerVal(
		List<ChunkPointer> stack,
		UnsafeConsumer<ChunkPointer, IOException> setter
	){
		public ChunkPointer value(){
			return stack.get(stack.size()-1);
		}
		
		public boolean isValue(ChunkPointer value)          { return value().equals(value); }
		
		public void set(ChunkPointer ptr) throws IOException{ setter.accept(ptr); }
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
			if(!chunk.hasNext()) return false;
			
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
			return memoryWalk(next::writeStruct, next, stackNew, valueFeedMod);
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
					IOInstance pointedInstance=null;
					if(variable instanceof SelfPointer){
						pointedInstance=(IOInstance)val;
						val=((SelfPoint<?>)val).getSelfPtr();
					}
					
					if(val instanceof ObjectPointer<?> ptr&&ptr.hasPtr()){
						var stackNew=pushStack(stack, ptr.getDataBlock());
						var ptrToDo=new PointerVal(stackNew, newPtr->{
							ptr.set(newPtr, ptr.getOffset());
							saveInstance.run();
						});
						if(valueFeedMod.test(ptrToDo)) return true;
						
						Object read;
						if(pointedInstance!=null) read=pointedInstance;
						else read=ptr.read(this);
						
						if(read instanceof IOInstance inst){
							UnsafeRunnable<IOException> saver;
							if(inst instanceof IOInstance.Contained cont){
								saver=()->{
									cont.writeStruct();
									cont.validateWrittenData();
								};
							}else if(inst.getStruct().getKnownSize().isPresent()){
								saver=()->((ObjectPointer<IOInstance>)ptr).write(this, inst);
							}else{
								saver=null;
							}
							if(memoryWalk(saver, inst, stackNew, valueFeedMod)) return true;
						}
						//TODO: handle chaining pointers when needed
					}
				}
			}catch(Throwable e){
				if(DETAILED_WALK_REPORT){
					String instanceStr;
					try{
						instanceStr=instance.toString();
					}catch(Throwable e1){
						instanceStr="<invalid instance: "+instance.getStruct()+" due to "+e1+">";
					}
					throw new IOException("failed to walk on "+variable.toString()+" in "+instanceStr, e);
				}
				throw e;
			}
		}
		return false;
	}
	
	private void chainToChunk(Chunk chainStart, Chunk destChunk) throws IOException{
		try(var io=chainStart.io()){
			try(var dest=destChunk.io()){
				assert dest.getCapacity()>=io.getSize();
				io.inStream().transferTo(dest.outStream());
			}
		}
		var oldPtr=chainStart.getPtr();
		moveChunkRef(chainStart, destChunk);
		getChunk(oldPtr).freeChaining();
	}
	
	public void pack() throws IOException{
		
		List<List<Chunk>> chains=new ArrayList<>();
		
		UnsafeConsumer<ChunkPointer, IOException> logPtr=ptr->{
			Chunk chunk=getChunk(ptr);
			IntStream.range(0, chains.size())
			         .filter(i->chains.get(i).contains(chunk))
			         .findAny().ifPresent(chains::remove);
			if(chunk.hasNext()){
				chains.add(chunk.collectNext());
			}
		};
		
		for(Chunk chunk : getFirstChunk().physicalIterator()){
			logPtr.accept(chunk.getPtr());
		}
		
		//TODO: use memwalk when user data becomes walkable
//		memoryWalk(ptr->{
//			logPtr.accept(ptr.value());
//			return false;
//		});
		
		LogUtil.println(chains);
		for(List<Chunk> chain : chains){
			chainToChunk(chain.get(0), AllocateTicket.bytes(chain.stream().mapToLong(Chunk::getCapacity).sum()).submit(this));
		}
		
		while(!freeChunks.isEmpty()){
			ChunkPointer firstFreePtr=null;
			int          firstIndex  =-1;
			
			for(int i=0;i<freeChunks.size();i++){
				ChunkPointer ptr=freeChunks.getElement(i);
				if(ptr==null) continue;
				
				if(firstFreePtr==null||ptr.compareTo(firstFreePtr)<0){
					Chunk freeChunk=getChunk(ptr);
					Chunk next     =freeChunk.nextPhysical();
					if(next!=null&&next.isUsed()){
						next=next.nextPhysical();
						if(next==null||!next.isUsed()){
							firstIndex=i;
							firstFreePtr=ptr;
						}
					}
				}
			}
			if(firstIndex==-1){
				for(int i=0;i<freeChunks.size();i++){
					ChunkPointer ptr=freeChunks.getElement(i);
					if(ptr==null) continue;
					
					if(firstFreePtr==null||ptr.compareTo(firstFreePtr)<0){
						firstIndex=i;
						firstFreePtr=ptr;
					}
				}
			}
			
			Objects.requireNonNull(firstFreePtr);
			
			Chunk freeChunk=getChunk(firstFreePtr);
			Chunk next     =freeChunk.nextPhysical();
			if(next==null) return;
			
			Chunk movedCopy=freeChunk.fakeCopy();
			Chunk sameCopy =freeChunk.fakeCopy();
			sameCopy.setUsed(true);
			sameCopy.setNextSize(calcPtrSize(next.getNextSize()==NumberSize.VOID));
			sameCopy.setCapacityConfident(next.getCapacity());
			
			movedCopy.setLocation(sameCopy.getPtr().addPtr(sameCopy.getInstanceSize()+next.getCapacity()));
			
			long freeSpace=sameCopy.dataEnd()-movedCopy.dataStart();
			if(freeSpace>0){
				movedCopy.setCapacityConfident(freeSpace);
				
				assert movedCopy.dataEnd()==freeChunk.dataEnd():movedCopy.dataEnd()+" "+freeChunk.dataEnd();
				assert sameCopy.dataEnd()==movedCopy.getPtr().getValue():sameCopy.dataEnd()+" "+movedCopy.getPtr().getValue();
				
				movedCopy.writeStruct();
				
				
				freeChunks.setElement(firstIndex, movedCopy.getPtr());
				
				validate();
				
				sameCopy.writeStruct();
				freeChunk.readStruct();
				
				validate();
				
				copyDataAndMoveChunk(next, freeChunk);
				
				validate();
			}else{
				Chunk chunk=AllocateTicket.bytes(next.getCapacity())
				                          .shouldDisableResizing(next.getNextSize()==NumberSize.VOID)
				                          .withApproval(c->c.getPtr().compareTo(next.getPtr())>0)
				                          .submit(this);
				copyDataAndMoveChunk(next, chunk);
			}
			
		}
	}
	
	public boolean isSafeMode() { return safeMode; }
	
	public int getMinChunkSize(){ return minChunkSize; }
	
	public boolean isReadOnly() { return readOnly; }
}
