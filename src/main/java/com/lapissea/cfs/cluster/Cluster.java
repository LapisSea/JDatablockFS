package com.lapissea.cfs.cluster;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.conf.AllocateTicket;
import com.lapissea.cfs.conf.ClusterConfig;
import com.lapissea.cfs.conf.PackingConfig;
import com.lapissea.cfs.exceptions.*;
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
import com.lapissea.cfs.objects.*;
import com.lapissea.cfs.objects.boxed.IOFloat;
import com.lapissea.cfs.objects.boxed.IOInt;
import com.lapissea.cfs.objects.boxed.IOLong;
import com.lapissea.cfs.objects.boxed.IOVoid;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafePredicate;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;
import static java.util.stream.Collectors.*;

public class Cluster extends IOInstance.Contained{
	
	public static class Builder{
		protected IOInterface   data;
		protected int           minChunkSize =DEFAULT_MIN_CHUNK_SIZE;
		protected boolean       readOnly     =false;
		protected PackingConfig packingConfig=PackingConfig.DEFAULT;
		protected ClusterConfig config       =ClusterConfig.DEFAULT;
		
		public Builder withPackingConfig(PackingConfig packingConfig){
			this.packingConfig=packingConfig;
			return this;
		}
		
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
			return new Cluster(data, packingConfig, config, minChunkSize, readOnly);
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
	
	@SuppressWarnings("FieldMayBeFinal")
	private static class TypedList extends IOInstance{
		
		static TypedList allocate(Cluster cluster, IOStruct struct) throws IOException{
			return new TypedList(struct, AllocateTicket.fitTo(StructLinkedList.class).submit(cluster).getPtr());
		}
		
		@IOStruct.Value(index=0, rw=IOStruct.ClusterDict.class)
		private IOStruct type;
		
		@IOStruct.Value(index=1, rw=ChunkPointer.AutoSizedIO.class)
		private ChunkPointer ptr;
		
		private WeakReference<IOList<?>> cache=new WeakReference<>(null);
		
		TypedList(IOStruct type, ChunkPointer ptr){
			this.type=type;
			this.ptr=ptr;
		}
		
		<T> IOList<T> getList(Cluster cluster) throws IOException{
			IOList<?> value=cache.get();
			if(value==null){
				Chunk chunk=ptr.dereference(cluster);
				value=StructLinkedList.build(e->e.withContainer(chunk));
				cache=new WeakReference<>(value);
			}
			return (IOList<T>)value;
		}
	}
	
	protected static final int DEFAULT_MIN_CHUNK_SIZE=8;
	
	
	public static Cluster build(Consumer<Builder> build) throws IOException{
		Builder builder=build();
		build.accept(builder);
		return builder.build();
	}
	
	public static Builder build(){
		return new Builder();
	}
	
	@EnumValue(index=0, customBitSize=16)
	private Version version;
	
	@PointerValue(index=1, type=StructHashMap.class)
	private IOMap<IOStruct, TypedList> globalObjectSet;
	
	@PointerValue(index=2, type=StructLinkedList.class)
	private IOList<AutoText> globalStrings;
	
	@PointerValue(index=3, type=StructLinkedList.class)
	private IOList<UserInfo>     userChunks;
	@PointerValue(index=4, type=StructLinkedList.class)
	private IOList<ChunkPointer> freeChunks;
	
	private final boolean              readOnly;
	private       boolean              safeMode;
	private final int                  minChunkSize;
	private final LinkedList<Chunk>    freeingQueue;
	private       IOList<ChunkPointer> effectiveFreeChunks;
	private final PackingConfig        packingConfig;
	private final ClusterConfig        config;
	
	private final IOInterface              data;
	final         Map<ChunkPointer, Chunk> chunkCache;
	
	private boolean freeingChunks;
	private boolean packing;
	
	private final TypeParser.Registry typeParsers=new TypeParser.Registry();
	
	protected Cluster(IOInterface data, PackingConfig packingConfig, ClusterConfig config, int minChunkSize, boolean readOnly) throws IOException{
		this.packingConfig=packingConfig;
		this.config=config;
		this.readOnly=readOnly;
		this.data=data;
		this.minChunkSize=minChunkSize;
		
		freeingQueue=new LinkedList<>(){
			private static final long serialVersionUID=-6734353874678934315L;
			
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
		
		registerParsers();
		
		if(data.isEmpty()){
			if(readOnly) throw new MalformedClusterDataException("Cannot create cluster from read only empty data");
			safeSession(this::initData);
		}
		
		readStruct();
	}
	
	private void registerParsers(){
		getTypeParsers().register(StructLinkedList.TYPE_PARSER);
		getTypeParsers().register(ChunkPointer.TYPE_PARSER);
		for(Class<? extends IOInstance> c : List.of(
			AutoText.class,
			IOVoid.class,
			IOLong.class,
			IOInt.class,
			IOFloat.class,
			IOTypeLayout.class
		                                           )){
			getTypeParsers().register(TypeParser.rawExact(IOStruct.of(c)));
		}
	}
	
	
	public void safeSession(UnsafeRunnable<IOException> session) throws IOException{
		try(SafeSession ses=new SafeSession()){
			session.run();
		}
		if(!isSafeMode()) onSafeEnd();
	}
	
	protected void initData() throws IOException{
		version=Version.last();
		writeStruct();
		
		initPointerVarAll(this);

//		typeDictionary.initData();
		
		writeStruct();
		
		freeChunks.addElements(null, null);
	}
	
	@Construct
	private IOList<UserInfo> constructUserChunks(Chunk source) throws IOException{
		return StructLinkedList.build(b->b.withContainer(source)
		                                  .withElementConstructor(UserInfo::new)
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
		                                                                   .withSolidNodes(true));
		
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
		
		var uTyp=ticket.userData();
		
		if(uTyp!=null&&!typeParsers.canParse(this, uTyp)) throw new IllegalArgumentException("Unknown type: "+uTyp);
		
		Chunk chunk=MAllocer.AUTO.alloc(this, getFreeData(), ticket.withBytes(ticket.disableResizing()?ticket.bytes():Math.max(getMinChunkSize(), ticket.bytes())));
		
		if(chunk!=null){
			
			if(DEBUG_VALIDATION){
				assert (uTyp!=null)==chunk.isUserData();
				checkCached(chunk);
			}
			
			if(uTyp!=null){
				getTypeDictionary().addElement(uTyp);
				var info=new UserInfo(uTyp, chunk.getPtr());
				userChunks.addElement(info);
				chunk.precacheUserInfo(info);
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
//			freeChunks.addElement(null);
		}else{
			while(freeChunks.countGreaterThan(Objects::isNull, 3)){
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
		assert cached==chunk:"Fake "+chunk;
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
//			assert !chunk.isUsed():chunk+" is used!";
			
			if(chunk.isUserData()){
				var ptr=chunk.getPtr().getValue();
				assert userChunks.anyMatches(info->info.getPtr().equals(ptr)):chunk+" is user created but not listed as such!";
			}
			assert !getFreeData().contains(chunk.getPtr()):chunk;
		}
		
		
		if(chunk.isUserData()){
			boolean oldFreeingChunks=freeingChunks;
			freeingChunks=true;
			
			var ptr=chunk.getPtr().getValue();
			userChunks.removeElement(userChunks.find(info->info.getPtr().equals(ptr)));
			
			freeingChunks=oldFreeingChunks;
		}
		
		chunk.modifyAndSave(ch->{
			ch.setUsed(false);
			ch.setSize(0);
			ch.clearNextPtr();
			ch.clearUserMark();
		});
		
		freeingQueue.add(chunk);
		
		if(!freeingChunks){
			processFreeQueue();
		}
	}
	
	private Chunk popBestChunk(){
		if(freeChunks.size()>5){
			return freeingQueue.removeFirst();
		}
		
		ChunkPointer bestPtr  =null;
		int          bestIndex=-1;
		for(int i=0, j=freeingQueue.size();i<j;i++){
			var ptr=freeingQueue.get(i).getPtr();
			if(bestPtr==null||ptr.compareTo(bestPtr)<0){
				bestPtr=ptr;
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
		
		//TODO: solve blacklist timeframes
//		autoPack();
	}
	
	private void autoPack() throws IOException{
		if(packing||packingConfig.autoPackTime().isZero()) return;
		
		boolean shouldPack=freeChunks.size()>packingConfig.freeChunkCountTrigger()&&
		                   freeChunks.count(Objects::nonNull)>packingConfig.freeChunkCountTrigger();
		if(!shouldPack){
			long freeSum=0;
			for(ChunkPointer freeChunk : freeChunks){
				if(freeChunk==null) continue;
				freeSum+=getChunk(freeChunk).totalSize();
			}
			double freeRatio=freeSum/(double)getData().getSize();
			if(packingConfig.freeSpaceRatioTrigger()<freeRatio){
				shouldPack=true;
			}
		}
		
		if(shouldPack){
			try{
				pack(packingConfig.autoPackTime());
			}catch(ActionStopException ignored){ }catch(UnreferencedChunkException e){
				//TODO: this is a bandage, use proper pack blacklist timeframes
			}
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
	
	private void modifyChain(Chunk firstChunk, Chunk target, long toAlloc) throws IOException{
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
					
					transferAmount+=firstChunk.getCapacity();
					Chunk newBlock=AllocateTicket.bytes(toAlloc+transferAmount)
					                             .submit(this);
					
					if(DEBUG_VALIDATION){
						try(var io=firstChunk.io()){
							assert io.getCapacity()==transferAmount:io.getCapacity()+" "+transferAmount;
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
	}
	
	private long growChunk(Chunk target, long toAllocate) throws IOException{
		long possibleGrowth=target.getBodyNumSize().maxSize-target.getCapacity();
		if(possibleGrowth==0) return 0;
		
		long toGrow          =Math.min(toAllocate, possibleGrowth);
		long newChunkCapacity=target.getCapacity()+toGrow;
		
		data.setCapacity(data.getCapacity()+toGrow);
		
		target.setCapacityConfident(newChunkCapacity);
		
		return toGrow;
	}
	
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		
		if(DEBUG_VALIDATION){
			assert firstChunk.collectNext().contains(target):target+" is not a part of chain "+firstChunk;
			for(Chunk chunk : getFirstChunk().physicalIterator()){
				if(firstChunk.equals(chunk.next())) throw new IllegalArgumentException(firstChunk+" is not the first chunk in a chain!");
			}
		}
		
		long remaining=toAllocate;
		
		if(isLastPhysical(target)){
			remaining-=growChunk(target, toAllocate);
		}
		
		if(remaining>0){
			modifyChain(firstChunk, target, remaining);
		}
	}
	
	public String chainDump(){
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
			
			for(Chunk chunk=getFirstChunk();chunk!=null;chunk=chunk.nextPhysical()){ }
			
			Set<ChunkPointer> unique=new HashSet<>();
			
			for(var freePtr : freeChunks){
				if(freePtr==null) continue;
				if(!unique.add(freePtr)){
					if(DEBUG_VALIDATION){
						Chunk c;
						try{
							c=getChunk(freePtr);
							if(c.isUserData()) throw new IllegalStateException(c+" is user data but listed as free");
						}catch(IOException e){c=null;}
						throw new IllegalStateException("Duplicate chunk ptr: "+freePtr+(c==null?" <err>":" "+c)+" in "+freeChunks);
					}else{
						LogUtil.println("Possible corruption! Duplicate free chunk", freePtr, "in", freeChunks);
						freeChunks.find(freePtr::equals, freeChunks::removeElement);
					}
				}
				var chunk=getChunk(freePtr);
				assert !chunk.isUsed():chunk;
			}
			
		}catch(IOException e){
			throw new IOException("Invalid data "+this+"\n"+data.hexdump(), e);
		}
	}
	
	public void copyDataAndMoveChunk(Chunk oldChunk, Chunk newChunk) throws IOException{
		if(DEBUG_VALIDATION) oldChunk.requireReal();
		PointerStack stack=requireChunkPointerStack(oldChunk.getPtr());
		copyDataAndMoveChunk(stack, newChunk);
	}
	
	public void copyDataAndMoveChunk(Chunk oldChunk, AllocateTicket ticket) throws IOException{
		if(DEBUG_VALIDATION) oldChunk.requireReal();
		PointerStack stack=requireChunkPointerStack(oldChunk.getPtr());
		copyDataAndMoveChunk(stack, ticket);
	}
	
	public void copyDataAndMoveChunk(PointerStack stack, AllocateTicket ticket) throws IOException{
		Chunk oldChunk=getChunk(stack.headPtr());
		
		if(DEBUG_VALIDATION){
			assert stack.isModifiable();
			assert oldChunk.isUsed():oldChunk;
		}
		
		AllocateTicket ticketTailor=ticket.withDataPopulated((c, dest)->{
			try(var io=oldChunk.io()){
				Utils.transferExact(io, dest, oldChunk.getSize());
			}
		});
		
		if(ticketTailor.bytes()==0) ticketTailor=ticketTailor.withBytes(oldChunk.getCapacity());
		else assert ticketTailor.bytes()>=oldChunk.getCapacity();
		
		ticketTailor=ticketTailor.shouldDisableResizing(oldChunk.isNextDisabled());
		
		Chunk chunk=alloc(ticketTailor);
		chunk.modifyAndSave(ch->chunk.setIsUserData(oldChunk.isUserData()));
		
		moveChunkRef(stack, chunk, true);
		
		validate();
	}
	
	public void copyDataAndMoveChunk(PointerStack stack, Chunk newChunk) throws IOException{
		Chunk oldChunk=getChunk(stack.headPtr());
		
		if(DEBUG_VALIDATION){
			assert stack.isModifiable();
			newChunk.requireReal();
			assert newChunk.getCapacity()>=oldChunk.getCapacity();
			assert oldChunk.isUsed():oldChunk+" "+newChunk;
		}
		
		try(var io=oldChunk.io()){
			try(var dest=newChunk.io()){
				Utils.transferExact(io, dest, oldChunk.getSize());
			}
		}
		
		moveChunkRef(stack, newChunk, true);
		
		validate();
	}
	
	private PointerStack requireChunkPointerStack(ChunkPointer ptr) throws IOException{
		return findPointer(val->val.equals(ptr))
			       .orElseThrow(()->new UnreferencedChunkException("unreferenced chunk: "+ptr));
	}
	
	public void moveChunkRef(Chunk oldChunk, Chunk newChunk) throws IOException{
		var oldPtr=oldChunk.getPtr().getValue();
		
		moveChunkRef(findPointer(val->val.equals(oldPtr))
			             .orElseThrow(()->new UnreferencedChunkException("trying to move unreferenced chunk: "+oldChunk.toString()+" to "+newChunk)), newChunk);
	}
	
	public void moveChunkRef(PointerStack oldStack, Chunk newChunk) throws IOException{
		moveChunkRef(oldStack, newChunk, false);
	}
	
	public void moveChunkRef(PointerStack stack, Chunk newChunk, boolean freeOld) throws IOException{
		ChunkPointer oldPtr  =stack.headPtr();
		ChunkPointer newPtr  =newChunk.getPtr();
		Chunk        oldChunk=getChunk(oldPtr);
		
		if(DEBUG_VALIDATION){
			stack.requireRelevant(this);
		}
		
		if(getConfig().logActions()) LogUtil.printTable("Action", "moving",
		                                                "Source", oldPtr,
		                                                "Chunk", newChunk);
		
		stack.set(newPtr);
		
		chunkCache.remove(oldPtr);
		chunkCache.put(newPtr, oldChunk);
		oldChunk.setLocation(newPtr);
		oldChunk.readStruct();
		
		if(freeOld){
			Chunk toFree=getChunk(oldPtr);
			toFree.modifyAndSave(chunk->{
				chunk.clearUserMark();
				chunk.setUsed(false);
			});
			toFree.freeChaining();
		}
	}
	
	
	public static record PointerFrame(
		ChunkPointer ptr,
		String deb
	){}
	
	public static record PointerStack(
		List<PointerFrame> stack,
		UnsafeConsumer<ChunkPointer, IOException> setter
	){
		public static PointerStack EMPTY=new PointerStack(List.of(), null);
		
		public String debStr(){
			return stack.stream()
			            .map(f->f.ptr+" -> "+f.deb)
			            .collect(joining("\n"));
		}
		
		public ChunkPointer headPtr(){
			return stack.get(stack.size()-1).ptr;
		}
		
		public void set(ChunkPointer ptr) throws IOException{
			if(!isModifiable()) throw new UnsupportedOperationException();
			setter.accept(ptr);
		}
		
		public PointerStack push(PointerFrame el, UnsafeConsumer<ChunkPointer, IOException> setter){
			return new PointerStack(Stream.concat(stack.stream(), Stream.of(el)).collect(toUnmodifiableList()), setter);
		}
		
		@Override
		public String toString(){
			return "PointerStack{"+
			       (stack.isEmpty()?"empty":stack.stream().map(TextUtil::toShortString).collect(Collectors.joining(" -> ")))+
			       (isModifiable()?"":", read only")+
			       "}";
		}
		
		public boolean isModifiable(){
			return setter!=null;
		}
		
		public void requireRelevant(Cluster cluster) throws IOException{
			ChunkPointer ptr    =headPtr();
			var          restack=cluster.findPointer(ptr).orElseThrow();
			var          ptrs   =stack.stream().map(f->f.ptr).toArray();
			var          rePtrs =restack.stack.stream().map(f->f.ptr).toArray();
			if(Arrays.equals(ptrs, rePtrs)) return;
			throw new OutOfSyncDataException(TextUtil.toString("tested/read\n", ptrs, "\n", rePtrs));
		}
	}
	
	public Optional<PointerStack> findPointer(ChunkPointer ptr) throws IOException{
		long val=ptr.getValue();
		return findPointer(p->p.equals(val));
	}
	
	public Optional<PointerStack> findPointer(UnsafePredicate<ChunkPointer, IOException> finder) throws IOException{
		return memoryWalk(ptr->finder.test(ptr.headPtr()));
	}
	
	public Optional<PointerStack> memoryWalk(UnsafePredicate<PointerStack, IOException> valueFeedMod) throws IOException{
		return memoryWalk(this, valueFeedMod);
	}
	
	public Optional<PointerStack> memoryWalk(IOInstance.Contained root, UnsafePredicate<PointerStack, IOException> valueFeedMod) throws IOException{
		return memoryWalk(root::writeStruct, root, valueFeedMod);
	}
	
	public Optional<PointerStack> memoryWalk(UnsafeRunnable<IOException> saveInstance, IOInstance instance, UnsafePredicate<PointerStack, IOException> valueFeedMod) throws IOException{
		return memoryWalk(saveInstance, instance, PointerStack.EMPTY, valueFeedMod);
	}
	
	public Optional<PointerStack> memoryWalk(UnsafeRunnable<IOException> saveInstance, IOInstance instance, PointerStack stack, UnsafePredicate<PointerStack, IOException> valueFeedMod) throws IOException{
		
		BiFunction<IOInstance, VariableNode<?>, String> debStr=(inst, variable)->{
			if(DEBUG_VALIDATION) return inst.getStruct().instanceClass.getSimpleName()+"."+variable.toString()+" = "+variable.getValueAsObj(inst);
			return null;
		};
		
		for(VariableNode<?> variable : instance.getStruct().getVariables()){
			try{
				Object val=variable.getValueAsObj(instance);
				if(val==null) continue;
				
				if(val instanceof ChunkPointer ptr){
					
					var ptrToDo=stack.push(new PointerFrame(ptr, debStr.apply(instance, variable)), newPtr->{
						((VariableNode<ChunkPointer>)variable).setValueAsObj(instance, newPtr);
						saveInstance.run();
					});
					if(valueFeedMod.test(ptrToDo)) return Optional.of(ptrToDo);
					
					Chunk ch=getChunk(ptr);
					
					Optional<PointerStack> o=memoryWalk(ch::writeStruct, ch, ptrToDo, valueFeedMod);
					if(o.isPresent()) return o;
					continue;
				}
				
				IOInstance pointedInstance=null;
				if(variable instanceof SelfPointer){
					pointedInstance=(IOInstance)val;
					var sf=(SelfPoint<?>)val;
					val=sf.getSelfPtr();
					if(DEBUG_VALIDATION){
						var  ptr    =(ObjectPointer<?>)val;
						long oldOff =ptr.getOffset();
						long testVal=oldOff==1?2:1;
						ptr.setOffset(testVal);
						assert sf.getSelfPtr().getOffset()==testVal:
							variable+" "+sf+" "+sf.getSelfPtr()+" getSelfPtr() returns unchangeable data";
						ptr.setOffset(oldOff);
					}
				}
				
				if(val instanceof ObjectPointer<?> ptr){
					if(ptr.hasPtr()){
						
						var ptrToDo=stack.push(new PointerFrame(ptr.getDataBlock(), debStr.apply(instance, variable)), newPtr->{
							ptr.set(newPtr, ptr.getOffset());
							saveInstance.run();
						});
						if(valueFeedMod.test(ptrToDo)) return Optional.of(ptrToDo);
						
						Chunk chunk=ptr.getBlock(this);
						{
							Optional<PointerStack> o=memoryWalk(chunk::writeStruct, chunk, ptrToDo, valueFeedMod);
							if(o.isPresent()) return o;
						}
						Object read;
						if(pointedInstance!=null) read=pointedInstance;
						else{
							read=ptr.read(this);
						}
						
						if(read instanceof IOInstance inst){
							UnsafeRunnable<IOException> saver;
							if(inst instanceof Contained cont){
								saver=()->{
									cont.writeStruct();
									cont.validateWrittenData();
								};
							}else if(inst.getStruct().getKnownSize().isPresent()){
								saver=()->((ObjectPointer<IOInstance>)ptr).write(this, inst);
							}else{
								saver=null;
							}
							Optional<PointerStack> o=memoryWalk(saver, inst, ptrToDo, valueFeedMod);
							if(o.isPresent()) return o;
						}
						//TODO: handle chaining pointers when needed
					}
				}else if(val instanceof IOInstance inst){
					Optional<PointerStack> o=memoryWalk(saveInstance, inst, stack, valueFeedMod);
					if(o.isPresent()) return o;
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
		return Optional.empty();
	}
	
	void chainToChunk(Chunk chainStart, Chunk destChunk) throws IOException{
		if(DEBUG_VALIDATION){
			for(Chunk chunk : getFirstChunk().physicalIterator()){
				if(chainStart.getPtr().equals(chunk.getNextPtr())) throw new IllegalArgumentException(chainStart+" is not the chain start");
			}
		}
		
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
		try{
			pack(null);
		}catch(ActionStopException ignored){ }
	}
	
	public void pack(Duration timeAllowed) throws IOException, ActionStopException{
		if(packing) return;
		try{
			packing=true;
			doPack(timeAllowed);
		}finally{
			packing=false;
		}
	}
	
	private void doPack(Duration timeAllowed) throws IOException, ActionStopException{
		if(getConfig().logActions()) LogUtil.printTable("Action", "PACK", "Type", timeAllowed!=null?timeAllowed.toString():"FULL");
		Instant end=timeAllowed==null?null:Instant.now().plus(timeAllowed);
		
		Pack.mergeChains(this, end);

//		Pack.freeChunkScan(this, freeChunks, end);
		Pack.memoryReorder(this, freeChunks, end);
	}
	
	public boolean isSafeMode() { return safeMode; }
	
	public int getMinChunkSize(){ return minChunkSize; }
	
	public boolean isReadOnly() { return readOnly; }
	
	public IOList<IOTypeLayout> getTypeDictionary() throws IOException{
		return getIndexedObjectDB(IOTypeLayout.class);
	}
	
	public IOList<String> getGlobalStrings(){
		return IOList.box(globalStrings, AutoText::getData, AutoText::new);
	}
	
	public <T extends IOInstance> IOList<T> getIndexedObjectDB(Class<T> type) throws IOException{
		return getIndexedObjectDB(IOStruct.of(type));
	}
	public <T extends IOInstance> IOList<T> getIndexedObjectDB(IOStruct struct) throws IOException{
		TypedList value=globalObjectSet.getValue(struct);
		if(value==null){
			return IOList.lateInit(()->{
				TypedList lis=TypedList.allocate(this, struct);
				globalObjectSet.putValue(struct, lis);
				return lis.getList(this);
			});
		}
		return value.getList(this);
	}
	
	
	public TypeParser.Registry getTypeParsers(){
		return typeParsers;
	}
	
	
	public <T extends IOInstance> T constructType(IOTypeLayout type) throws IOException{
		return constructType(type, (Chunk)null);
	}
	
	public <T extends IOInstance> T constructType(AllocateTicket ticket) throws IOException{
		Objects.requireNonNull(ticket.userData());
		return constructType(ticket.userData(), ticket);
	}
	
	public <T extends IOInstance> T constructType(IOTypeLayout type, AllocateTicket ticket) throws IOException{
		if(!getTypeParsers().canParse(this, type)){
			throw new RuntimeException("Unknown type: "+type);
		}
		return constructType(type, ticket.submit(this));
	}
	
	public <T extends IOInstance> T constructType(Chunk dest) throws IOException{
		return constructType(dest.getUserInfo().getType(), dest);
	}
	
	@SuppressWarnings("unchecked")
	public <T extends IOInstance> T constructType(IOTypeLayout type, Chunk dest) throws IOException{
		if(dest!=null&&dest.isUserData()){
			return (T)dest.getUserInfo().getObjPtr().read(this);
		}
		return (T)getTypeParsers().parse(this, type).apply(dest);
	}
	
	public IOList<UserInfo> getUserChunks(){
		return userChunks;
	}
	
	public ClusterConfig getConfig(){
		return config;
	}
	
	@Override
	public void writeStruct() throws IOException{
		super.writeStruct();
		validateWrittenData();
		if(getTypeDictionary()!=null) getTypeDictionary().validate();
	}
}
