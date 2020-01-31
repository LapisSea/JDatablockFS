package com.lapissea.fsf;

import com.lapissea.fsf.chunk.*;
import com.lapissea.fsf.collections.SparsePointerList;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.FixedNumber;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.headermodule.modules.DataMappedModule;
import com.lapissea.fsf.headermodule.modules.FreeChunksModule;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.TrackingInputStream;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.fsf.NumberSize.*;
import static com.lapissea.util.UtilL.*;

@SuppressWarnings("AutoBoxing")
public class Header{
	
	private static final class Region{
		static Region bySize(long start, long size){
			return new Region(start, start+size);
		}
		
		final long start;
		final long end;
		
		private Region(long start, long end){
			this.start=start;
			this.end=end;
		}
	}
	
	private static final byte[]  MAGIC_BYTES     ="LSFSIF".getBytes(StandardCharsets.UTF_8);
	public static final  int     FILE_HEADER_SIZE=MAGIC_BYTES.length+2;
	public static final  boolean LOG_ACTIONS     =true;
	
	public static byte[] getMagicBytes(){
		return MAGIC_BYTES.clone();
	}
	
	
	public static String normalizePath(String path){
		var p=Paths.get(path).normalize().toString();
		return p.equals(path)?path:p;
	}
	
	public final Version version;
	
	public final IOInterface source;
	
	@Deprecated
	private final FixedLenList<FixedNumber, ChunkPointer> headerPointers;
	
	private final SparsePointerList<FilePointer>          fileList;
	private final FixedLenList<SizedNumber, ChunkPointer> freeChunks;
	private final Map<Long, Chunk>                        chunkCache;
	
	private boolean defragmenting;
	private boolean safeAloc;
	
	private Region region;
	
	public final FileSystemInFile.Config config;
	
	public final List<HeaderModule> modules;
	
	public Header(IOInterface source, FileSystemInFile.Config config) throws IOException{
		
		var dmm=new DataMappedModule(this);
		var fkm=new FreeChunksModule(this);
		
		modules=List.of(fkm, dmm);
		
		this.source=source;
		this.config=config;
		
		chunkCache=config.newCacheMap();
		
		Version                                 version;
		FixedLenList<FixedNumber, ChunkPointer> headerPointers;
		
		Supplier<FixedNumber> headerPointersNum=()->new FixedNumber(LONG);
		
		try(var in=source.read()){
			var mgb=new byte[MAGIC_BYTES.length];
			in.readFully(mgb, 0, mgb.length);
			if(!Arrays.equals(mgb, MAGIC_BYTES)){
				throw new IOException("Not a \"File System In File\" file or is corrupted");
			}
			
			var versionBytes=in.readNBytes(2);
			
			version=Arrays.stream(Version.values())
			              .filter(v->v.is(versionBytes))
			              .findAny()
			              .orElseThrow(()->new IOException("Invalid version number "+TextUtil.toString(versionBytes)));
			
			headerPointers=new FixedLenList<>(headerPointersNum, firstChunk(), null);
		}catch(EOFException eof){
			//new empty file
			var vs=Version.values();
			version=vs[vs.length-1];
			
			var chunksFakeFile=new IOInterface.MemoryRA(false);
			
			try(var out=chunksFakeFile.write(true)){
				for(HeaderModule module : modules){
					module.init(out, config);
				}
			}
			
			List<MutableChunkPointer> localOffsets=new ArrayList<>();
			
			long[] pos={0};
			try(var in=new ContentInputStream.Wrapp(new TrackingInputStream(chunksFakeFile.read(), pos))){
				while(true){
					var chunk=Chunk.read(null, pos[0], in);
					in.skipNBytes(chunk.getCapacity());
					
					localOffsets.add(new MutableChunkPointer(chunk));
				}
			}catch(EOFException ignored){}
			
			try(var out=source.write(true)){
				out.write(MAGIC_BYTES);
				
				var vers  =Version.values();
				var latest=vers[vers.length-1];
				out.write(latest.major);
				out.write(latest.minor);
				
				FixedLenList.init(NumberSize.VOID, out, headerPointersNum.get(), localOffsets.size(), false);
				
				try(var in=chunksFakeFile.read()){
					in.transferTo(out);
				}
			}
			
			var ptrsData=firstChunk();
			
			headerPointers=new FixedLenList<>(headerPointersNum, ptrsData, null);
			
			for(var off : localOffsets){
				off.setValue(off.getValue()+ptrsData.nextPhysicalOffset());
				headerPointers.addElement(off);
			}
		}
		
		this.version=version;
		this.headerPointers=headerPointers;
		
		var iter=headerPointers.iterator();
		
		for(HeaderModule module : modules){
			module.read(iter::next);
		}
		Assert(!iter.hasNext());
		
		freeChunks=fkm.getList();
		
		fileList=dmm.getMappings();

//		validateFile();
		
	}
	
	public FilePointer getByPath(String path) throws IOException{
		return getByPathUnsafe(normalizePath(path));
	}
	
	private FilePointer getByPathUnsafe(String path) throws IOException{
		return fileList.findSingle(comp->comp.getLocalPath().equals(path));
	}
	
	public Chunk createFile(String path, long initialSize) throws IOException{
		String pat     =normalizePath(path);
		var    existing=getByPathUnsafe(pat);
		if(existing!=null){
			return existing.dereference();
		}
		
		
		Chunk chunk=aloc(initialSize, true);
		fileList.addElement(new FilePointer(this, pat, chunk.getOffset()));
		return chunk;
	}
	
	public class SafeAlocSession implements AutoCloseable{
		private final boolean lastsafeAloc;
		
		private SafeAlocSession(){
			lastsafeAloc=safeAloc;
			safeAloc=true;
		}
		
		@Override
		public void close(){
			safeAloc=lastsafeAloc;
		}
	}
	
	public SafeAlocSession safeAlocSession(){
		return new SafeAlocSession();
	}
	
	public Chunk aloc(long initialSize, boolean allowNonOptimal) throws IOException{
		return aloc(initialSize, NumberSize.bySize(initialSize), allowNonOptimal);
	}
	
	public Chunk aloc(long initialSize, NumberSize bodyType, boolean allowNonOptimal) throws IOException{
		if(!safeAloc){
			Chunk ch=tryRealoc(initialSize, bodyType, allowNonOptimal);
			if(ch!=null) return ch;
		}
		
		return alocNew(bodyType, initialSize);
	}
	
	private Chunk tryRealoc(long initialSize, NumberSize bodyType, boolean allowNonOptimal) throws IOException{
		if(freeChunks.isEmpty()) return null;
		
		Chunk best   =null;
		Chunk biggest=null;
		long  diff   =Long.MAX_VALUE;
		
		for(int i=0;i<freeChunks.size();i++){
			Chunk c=freeChunks.getElement(i).dereference(this);
			
			if(region!=null&&c.overlaps(region.start, region.end)){
				continue;
			}
			
			long ds=c.getCapacity();
			
			if(!allowNonOptimal){
				if(ds<initialSize){
					continue;
				}
			}
			
			if(biggest==null||biggest.getCapacity()<ds){
				biggest=c;
			}
			
			long newDiff=ds-initialSize;
			
			if(newDiff<diff){
				best=c;
				diff=newDiff;
				if(diff==0) break;
			}
		}
		
		if(best==null) return null;
		
		if(diff>0){
			if(biggest.getCapacity()-initialSize>Chunk.headerSize(source.getSize(), diff)*3){
				return alocFreeSplit(biggest, initialSize);
			}
		}
		
		return alocFreeReuse(best);
	}
	
	private Chunk alocFreeReuse(Chunk chunk) throws IOException{
		
		if(DEBUG_VALIDATION) validateFile();
		
		int chunkIndex=freeChunks.indexOf(new ChunkPointer(chunk));
		
		freeChunks.removeElement(chunkIndex);
		chunk.setUsed(true);
		chunk.syncHeader();
		
		if(DEBUG_VALIDATION) validateFile();
		if(LOG_ACTIONS) logChunkAction("REUSED", chunk);
		
		return chunk;
	}
	
	
	private Chunk alocFreeSplit(Chunk freeChunk, long initialCapacity) throws IOException{
		
		if(DEBUG_VALIDATION){
			Assert(!freeChunk.isUsed());
			validateFile();
		}
		
		var safeNext=safeNextType();
		var offset  =freeChunk.getOffset();
		
		var space=freeChunk.wholeSize();
		
		
		var alocChunkSize  =Chunk.wholeSize(safeNext, initialCapacity);
		var freeRemaining  =space-alocChunkSize;
		var freeChunkHeader=freeChunk.getHeaderSize();
		
		if(freeRemaining<=freeChunkHeader) return alocFreeReuse(freeChunk);
		
		var freeChunkCapacity=freeRemaining-freeChunkHeader;
		
		Chunk alocChunk=new Chunk(this, offset, safeNext, 0, initialCapacity);
		
		try{
			freeChunk.setCapacity(freeChunkCapacity);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		
		freeChunk.setOffset(offset+alocChunkSize);
		
		freeChunk.saveHeader();
		alocChunk.saveHeader();
		
		notifyMovement(offset, freeChunk);
		
		alocChunk=getByOffset(alocChunk.getOffset());
		
		if(DEBUG_VALIDATION) validateFile();
		
		if(LOG_ACTIONS) logChunkAction("ALOC F SPLIT", alocChunk);
		return alocChunk;
	}
	
	private Chunk alocNew(NumberSize bodyType, long initialSize) throws IOException{
		var chunk=initChunk(source.getSize(), safeNextType(), bodyType, initialSize);
		if(DEBUG_VALIDATION) validateFile();
		if(LOG_ACTIONS) logChunkAction("ALOC", chunk);
		return chunk;
	}
	
	private Chunk initChunk(long offset, NumberSize nextType, NumberSize bodyType, long initialSize) throws IOException{
		
		var chunk=new Chunk(this, offset, nextType, 0, bodyType, initialSize);
		putChunkInCache(chunk);
		
		try(var out=source.write(source.getSize(), true)){
			chunk.init(out);
		}
		
		
		return chunk;
	}
	
	private NumberSize safeNextType() throws IOException{ return NumberSize.bySize((long)(source.getSize()*1.1)); }
	
	private void logChunkAction(String action, Chunk chunk){
		if(!LOG_ACTIONS) throw new RuntimeException();
		
		var ay=new LinkedHashMap<String, Object>();
		ay.put("Chunk action", action);
		ay.putAll(TextUtil.mapObjectValues(chunk));
		
		LogUtil.printTable(ay);
	}
	
	public void requestMemory(Chunk chainStart, Chunk chunk, long requestedMemory) throws IOException{
		if(DEBUG_VALIDATION){
			Assert(!chunk.hasNext());
			validateFile();
		}
		
		var remaining=requestedMemory;
		var last     =chunk;
		
		remaining-=fileEndGrowAction(last, remaining);
		
		while(remaining>0){
			if(DEBUG_VALIDATION) validateFile();
			
			long allocated=forwardMergeAction(last, remaining);
			if(allocated>0){
				remaining-=allocated;
				continue;
			}
			
			try{
				Chunk chained=chainedAlocAction(last, remaining);
				last=chained;
				remaining-=chained.getCapacity();
				continue;
			}catch(BitDepthOutOfSpaceException e){
				rescueChainAction(chainStart, remaining);
				break;
			}
		}
		
		if(DEBUG_VALIDATION) validateFile();
	}
	
	private Chunk chainedAlocAction(Chunk chunk, long requestedMemory) throws IOException, BitDepthOutOfSpaceException{
		
		var initSize=Math.max(requestedMemory, config.minimumChunkSize);
		
		NumberSize bodyNum=chunk.getBodyType()
		                        .next()
		                        .min(NumberSize.bySize((long)(chunk.getCapacity()*1.3)))//don't overestimate chunk size of last was cut off
		                        .max(NumberSize.bySize(initSize));//make sure init size can fit
		
		var newChunk=aloc(initSize, bodyNum, true);
		
		try{
			chunk.setNext(newChunk);
		}catch(BitDepthOutOfSpaceException e){
			freeChunk(newChunk);
			throw e;
		}
		
		chunk.syncHeader();
		
		if(LOG_ACTIONS) logChunkAction("CHAINED", chunk);
		return newChunk;
	}
	
	private long forwardMergeAction(Chunk chunk, long requestedMemory) throws IOException{
		if(freeChunks.isEmpty()) return 0;
		
		try{
			Chunk next;
			int   nextInd;
			find:
			{
				long nextOffset=chunk.nextPhysicalOffset();
				
				for(int i=0;i<freeChunks.size();i++){
					ChunkPointer ptr=freeChunks.getElement(i);
					if(ptr.equals(nextOffset)){
						next=ptr.dereference(this);
						nextInd=i;
						break find;
					}
				}
				return 0;
			}
			
			var cap=next.wholeSize();
			
			mergeChunks(chunk, next);
			freeChunks.removeElement(nextInd);
			
			if(LOG_ACTIONS) logChunkAction("Merged free", chunk);
			
			return cap;
		}catch(BitDepthOutOfSpaceException e){
			return 0;
		}
	}
	
	private long fileEndGrowAction(Chunk chunk, long requestedMemory) throws IOException{
		if(!chunk.isLastPhysical()) return 0;
		
		//last at end of file
		var oldDataSize=chunk.getCapacity();
		
		try{
			chunk.setCapacity(chunk.getCapacity()+requestedMemory);
		}catch(BitDepthOutOfSpaceException e){
			return fileEndGrowAction(chunk, e.numberSize.maxSize-chunk.getCapacity());
		}
		
		chunk.syncHeader();
		
		try(var out=source.doRandom()){
			out.setPos(chunk.getDataStart()+oldDataSize);
			out.fillZero(requestedMemory);
		}
		if(LOG_ACTIONS) logChunkAction("GROWTH", chunk);
		
		return requestedMemory;
	}
	
	private void mergeChunks(Chunk dest, Chunk toMerge) throws IOException, BitDepthOutOfSpaceException{
		dest.setCapacity(toMerge.nextPhysicalOffset()-dest.getDataStart());
		dest.syncHeader();
	}
	
	private void rescueChainAction(Chunk chainStart, long requestedMemory) throws IOException{
		if(LOG_ACTIONS) logChunkAction("RESCUE", chainStart);
		
		byte[] oldData;
		long   oldCapacity;
		if(DEBUG_VALIDATION){
			oldData=chainStart.io().readAll();
			oldCapacity=chainStart.io().getCapacity();
		}
		
		rescueChain(chainStart, requestedMemory);
		
		if(DEBUG_VALIDATION){
			var expectedCapacity=oldCapacity+requestedMemory;
			var capacity        =chainStart.io().getCapacity();
			Assert(capacity >= expectedCapacity, capacity, expectedCapacity, requestedMemory);
			
			var newData=chainStart.io().readAll();
			
			if(!Arrays.equals(oldData, newData)){
				throw new AssertionError("rescue chain fail\n"+
				                         TextUtil.toString(oldData)+"\n"+
				                         TextUtil.toString(newData));
			}
		}
	}
	
	private void rescueChain(Chunk chainStart, long additionalSpace) throws IOException{
		
		var chain=chainStart.collectWholeChain();
		
		for(int i=chain.size()-1;i >= 0;i--){
			var chunk=chain.get(i);
			if(chunk.getNextType().canFit(source.getSize())){
				var toFree=chain.subList(i+1, chain.size());
				
				var alocSize=toFree.stream().mapToLong(Chunk::getCapacity).sum()+additionalSpace;
				var newChunk=aloc(alocSize, true);
				
				if(!toFree.isEmpty()){
					try(var out=newChunk.io().write(false)){
						try(var in=toFree.get(0).io().read()){
							in.transferTo(out);
						}
					}
					
				}
				
				try{
					chunk.setNext(newChunk);
					chunk.syncHeader();
				}catch(BitDepthOutOfSpaceException e){
					throw new ShouldNeverHappenError(e);
				}finally{
					if(!toFree.isEmpty()){
						freeChunkChain(toFree.get(0));
					}
				}
				return;
			}
		}
		
		var alocSize=chain.stream().mapToLong(Chunk::getSize).sum()+additionalSpace;
		
		chainStart.moveToAndFreeOld(aloc(alocSize, false));
		if(chain.size()>1){
			try(var out=chainStart.io().write(false)){
				try(var in=chain.get(1).io().read()){
					in.transferTo(out);
				}
			}
			chainStart.chainForwardFree();
		}
	}
	
	private void sourcedChunkIterOne(String prevSource, Chunk chunk, BiConsumer<String, Chunk> consumer) throws IOException{
		var source=prevSource+" -> "+chunk;
		consumer.accept(source, chunk);
		if(chunk.hasNext()) sourcedChunkIterOne(source, chunk.nextChunk(), consumer);
	}
	
	private void sourcedChunkIter(BiConsumer<String, Chunk> consumer) throws IOException{
		for(var chunk : fileList.getShadowChunks()) sourcedChunkIterOne("fileList", chunk, consumer);
		for(var chunk : freeChunks.getShadowChunks()) sourcedChunkIterOne("freeChunks", chunk, consumer);
		for(var pointer : fileList) sourcedChunkIterOne("File("+pointer.getLocalPath()+")", pointer.dereference(), consumer);
		for(var val : freeChunks) sourcedChunkIterOne("Free chunk", val.dereference(this), consumer);
	}
	
	private void removeChunkInCache(Long offset){
		chunkCache.remove(offset);
	}
	
	private void putChunkInCache(Chunk chunk){
		Long key=chunk.getOffset();
		var  old=chunkCache.put(key, chunk);
		if(DEBUG_VALIDATION) Assert(old==null, old, chunk);
	}
	
	private Chunk getChunkInCache(Long offset){
		return chunkCache.get(offset);
	}
	
	public void validateFile() throws IOException{
		
		for(var e : chunkCache.entrySet()){
			var cached=e.getValue();
			
			Assert(cached.getOffset()==e.getKey(), cached.getOffset(), e.getKey());
			
			if(!cached.isDirty()){
				var read=readChunk(e.getKey());
				if(!read.equals(cached)){
					
					if(cached.lastMod!=null) cached.lastMod.printStackTrace();
					
					throw new AssertionError("Chunk cache mismatch\n"+
					                         TextUtil.toTable("cached / read", List.of(cached, read)));
				}
			}
		}
		
		freeChunks.checkIntegrity();
		
		for(ChunkPointer ptr : freeChunks){
			Chunk freeChunk=ptr.dereference(this);
			Assert(!freeChunk.isUsed(), "Chunk not marked as free but listed under free chunks:", freeChunk, "in", freeChunks);
		}
		
		class Node{
			public final long a, b;
			public final String source;
			public final Chunk  chunk;
			
			Node(Chunk chunk, String source){
				this.a=chunk.getOffset();
				this.b=chunk.getOffset()+chunk.wholeSize();
				this.chunk=chunk;
				this.source=source;
			}
			
			boolean overlaps(Node other){
				return Math.max(other.a, a)-Math.min(other.b, b)<(a-b)+(other.a-other.b);
			}
		}
		
		List<Node> nodes=new ArrayList<>();
		
		sourcedChunkIter((newSource, chunk)->{
			var dup=nodes.stream().filter(r->r.chunk.equals(chunk)).findAny();
			if(dup.isPresent()) throw new AssertionError("Duplicate chunk reference:\n"+dup.get().source+"\n"+newSource);
			nodes.add(new Node(chunk, newSource));
		});
		
		for(var range1 : nodes){
			for(var range2 : nodes){
				if(range1.overlaps(range2)){
					throw new AssertionError("\n"+TextUtil.toTable("Overlapping", List.of(range1, range2)));
				}
			}
		}
	}
	
	public void freeChunkChain(Chunk chunk) throws IOException{
		if(chunk.hasNext()) freeChunkChain(chunk.nextChunk());
		freeChunk(chunk);
	}
	
	
	public void freeChunk(Chunk chunk) throws IOException{
		
		if(DEBUG_VALIDATION){
			sourcedChunkIter((src, ch)->{
				if(chunk.equals(ch)){
					throw new AssertionError("Trying to free chunk "+ch+" in use: "+src);
				}
			});
			
			validateFile();
		}
		
		chunk.setSize(0);
		chunk.clearNext();
		chunk.setUsed(false);
		
		Throwable e=null;
		try{
			
			if(deallocatingFreeAction(chunk)) return;
			
			var ff=forwardFreeMergeAction(chunk);
			if(ff) chunk.syncHeader();
			
			if(backwardFreeMergeAction(chunk)){
				if(ff){
					Assert(freeChunks.remove(new ChunkPointer(chunk)));
					chunkCache.remove(chunk.getOffset());
				}
				return;
			}
			if(ff) return;
			
			if(chunk.getOffset()==189){
				int i=0;
			}
			normalFreeAction(chunk);
			chunk.syncHeader();
		}catch(Throwable e1){
			e=e1;
		}
		if(DEBUG_VALIDATION){
			if(e==null) validateFile();
		}
		if(e!=null) throw UtilL.uncheckedThrow(e);
	}
	
	
	private boolean deallocatingFreeAction(Chunk chunk) throws IOException{
		if(!chunk.isLastPhysical()) return false;
		
		source.setCapacity(chunk.getOffset());
		removeChunkInCache(chunk);
		
		if(LOG_ACTIONS) logChunkAction("FREE DEALLOC", chunk);
		
		for(int i=0;i<freeChunks.size();i++){
			Chunk ch=freeChunks.getElement(i).dereference(this);
			if(ch.isLastPhysical()){
				freeChunks.removeElement(i);
				freeChunk(ch);
				break;
			}
		}
		
		return true;
	}
	
	private boolean backwardFreeMergeAction(Chunk chunkToMerge) throws IOException{
		if(DEBUG_VALIDATION) validateFile();
		
		if(freeChunks.isEmpty()) return false;
		
		var offset=chunkToMerge.getOffset();
		
		Chunk previous;
		find:
		{
			for(var val : freeChunks){
				Chunk prev=getByOffset(val.getValue());
				var   next=prev.nextPhysicalOffset();
				if(next==offset){
					previous=prev;
					break find;
				}
			}
			return false;
		}
		
		removeChunkInCache(chunkToMerge);
		chunkToMerge.nukeChunk();
		
		try{
			previous.setCapacity(chunkToMerge.nextPhysicalOffset()-previous.getDataStart());
		}catch(BitDepthOutOfSpaceException e){
			throw new NotImplementedException(e);//TODO: reformat header be able to merge
		}
		previous.syncHeader();
		
		if(LOG_ACTIONS){
			logChunkAction("FREE B MERGE", previous);
			logChunkAction("", chunkToMerge);
		}
		
		return true;
	}
	
	private boolean forwardFreeMergeAction(Chunk toMergeInTo) throws IOException{
		if(freeChunks.isEmpty()) return false;
		
		var nextPtr=new ChunkPointer(toMergeInTo.nextPhysicalOffset());
		
		var nextIndex=freeChunks.indexOf(nextPtr);
		
		if(nextIndex==-1) return false;
		
		//merge if next physical chunk also free
		Chunk next=nextPtr.dereference(this);
		
		try{
			mergeChunks(toMergeInTo, next);
		}catch(BitDepthOutOfSpaceException e){
			throw new NotImplementedException(e);//TODO: reformat header be able to merge
		}
		
		toMergeInTo.syncHeader();
		
		removeChunkInCache(nextPtr);
		
		freeChunks.setElement(nextIndex, new ChunkPointer(toMergeInTo));
		next.nukeChunk();
		
		if(LOG_ACTIONS) logChunkAction("FREE F MERGE", toMergeInTo);
		if(DEBUG_VALIDATION) validateFile();
		
		return true;
	}
	
	private void normalFreeAction(Chunk chunk) throws IOException{
		var ptr=new ChunkPointer(chunk);
		if(DEBUG_VALIDATION) Assert(!freeChunks.contains(ptr), "Duplicate", ptr, "in", freeChunks);
		freeChunks.addElement(ptr);
		if(LOG_ACTIONS) logChunkAction("FREED", chunk);
	}
	
	private Chunk readChunk(long offset) throws IOException{
		return Chunk.read(this, offset);
	}
	
	private void removeChunkInCache(ChunkPointer ptr){
		removeChunkInCache(ptr.getValue());
	}
	
	private void removeChunkInCache(Chunk chunk){
		removeChunkInCache(chunk.getOffset());
	}
	
	public Chunk getByOffsetCached(Long offset){
		return getChunkInCache(offset);
	}
	
	public Chunk getByOffset(Long offset) throws IOException{
		var cached=getChunkInCache(offset);
		if(cached!=null) return cached;
		
		var read=readChunk(offset);
		putChunkInCache(read);

//		if(LOG_ACTIONS) logChunkAction("READ", read);
		
		return read;
	}
	
	public VirtualFile[] listFiles() throws IOException{
		var files=new VirtualFile[fileList.size()];
		for(int i=0;i<files.length;i++){
			files[i]=new VirtualFile(fileList.getElement(i));
		}
		return files;
	}
	
	public List<Chunk> allChunkWalkerFlat() throws IOException{
		List<Chunk> chain=new ArrayList<>();
		allChunkWalkerFlat((h, c)->chain.add(c));
		return chain;
	}
	
	public Optional<ChunkWModule> allChunkWalkerFlat(BiPredicate<HeaderModule, Chunk> consumer) throws IOException{
		return allChunkWalkerFlat(m->true, consumer);
	}
	
	public Optional<ChunkWModule> allChunkWalkerFlat(Predicate<HeaderModule> moduleFilter, BiPredicate<HeaderModule, Chunk> consumer) throws IOException{
		var walker=allChunkWalker();
		while(walker.hasNext()){
			var pair=walker.next();
			
			try(var stream=pair.obj2){
				var module=pair.obj1;
				if(!moduleFilter.test(module)) continue;
				
				var opt=stream.map(iter->{
					while(iter.hasNext()){
						var chunk=iter.next();
						if(consumer.test(module, chunk)) return chunk;
					}
					return null;
				}).filter(Objects::nonNull).findAny();
				
				if(opt.isPresent()) return Optional.of(new ChunkWModule(opt.get(), module));
			}
		}
		
		return Optional.empty();
	}
	
	public Map<HeaderModule, List<List<Chunk>>> allChunks() throws IOException{
		Map<HeaderModule, List<List<Chunk>>> moduleChunks=new LinkedHashMap<>();
		
		allChunkWalker().forEachRemaining(pair->{
			try(var stream=pair.obj2){
				moduleChunks.put(pair.obj1, stream.map(iter->{
					List<Chunk> chain=new ArrayList<>(4);
					iter.forEachRemaining(chain::add);
					return chain;
				}).collect(Collectors.toList()));
			}
		});
		return moduleChunks;
	}
	
	@SuppressWarnings("RedundantThrows")
	public Iterator<PairM<HeaderModule, Stream<Iterator<Chunk>>>> allChunkWalker() throws IOException{
		return new Iterator<>(){
			Iterator<HeaderModule> moduleIterator=modules.iterator();
			
			@Override
			public boolean hasNext(){
				return moduleIterator.hasNext();
			}
			
			@Override
			public PairM<HeaderModule, Stream<Iterator<Chunk>>> next(){
				HeaderModule module=moduleIterator.next();
				try{
					var stream=Stream.concat(module.getOwning().stream(), module.getReferenceStream().map(link->{
						try{
							return link.pointer.dereference(Header.this);
						}catch(IOException e){
							throw UtilL.uncheckedThrow(e);
						}
					})).map(rootChunk->{
						try{
							return rootChunk.chainWalker();
						}catch(IOException e){
							throw UtilL.uncheckedThrow(e);
						}
					});
					return new PairM<>(module, stream);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
	
	private long calcTotalWholeSize(List<Chunk> chain){ return chain.stream().mapToLong(Chunk::wholeSize).sum(); }
	
	private long calcTotalCapacity(List<Chunk> chain) { return chain.stream().mapToLong(Chunk::getCapacity).sum(); }
	
	private long calcTotalSize(List<Chunk> chain)     { return chain.stream().mapToLong(Chunk::getSize).sum(); }
	
	private List<List<Chunk>> chunksToChains(List<Chunk> chunks) throws IOException{
		var chains=new ArrayList<List<Chunk>>(chunks.size());
		for(Chunk chunk : chunks){
			chains.add(chunk.collectWholeChain());
		}
		return chains;
	}
	
	public Chunk firstChunk() throws IOException{
		return getByOffset((long)FILE_HEADER_SIZE);
	}
	
	private Chunk findChainStart(Chunk chunk) throws IOException{
		return findChainStart(new ChunkWModule(chunk, null));
	}
	
	private Chunk findChainStart(ChunkWModule chunk) throws IOException{
		var targetOffset=chunk.getChunk().getOffset();
		var searchResult=allChunkWalkerFlat(chunk::check, (m, ch)->ch.getNext()==targetOffset);
		
		if(searchResult.isEmpty()) return chunk.getChunk();
		var previous=searchResult.get();
		
		var root=findChainStart(previous);
		if(root!=null) return root;
		return chunk.getChunk();
	}
	
	private List<Chunk> prevDetectingMove(Chunk chunk) throws IOException{
		
		var ch=findChainStart(chunk);
		if(ch==chunk) return List.of();
		
		var chain=ch.collectWholeChain();
		
		var totalSize=calcTotalSize(chain);
		
		moveChainToChunk(ch, alocNew(bySize(totalSize), totalSize));
		return chain;
		
	}
	
	private Chunk clearRegion(long start, long size) throws IOException{
		Assert(size>0);
		return clearRegion(new Region(start, start+size));
	}
	
	private Chunk clearRegion(Region region) throws IOException{
		this.region=region;
		try{
			long start=region.start;
			long size =region.end-region.start;
			long end  =region.end;
			var  first=firstChunk();
			
			Assert(!first.overlaps(start, end));
			
			var startChunk=first.nextPhysical();
			
			Deque<Chunk> toMove=new LinkedList<>();
			{
				Chunk walk=startChunk;
				while(true){
					var next=walk.nextPhysical();
					if(next.getOffset()>start) break;
					walk=next;
				}
				
				while(true){
					toMove.addLast(walk);
					
					var next=walk.nextPhysical();
					if(next.getOffset() >= end) break;
					walk=next;
				}
			}
			
			var fo=new ChunkPointer(toMove.getFirst().getOffset());
			
			while(!toMove.isEmpty()){
				Chunk chunk=toMove.pop();
				if(!chunk.isUsed()) continue;
				
				var moved=prevDetectingMove(chunk);
				if(!moved.isEmpty()){
					toMove.removeAll(moved);
					continue;
				}
				
				chunk.moveToAndFreeOld(aloc(chunk.getSize()==0?chunk.getCapacity():chunk.getSize(), false));//TODO: check if trimming size has any side effects / regressions
			}
			
			Chunk freeRegion;
			
			if(freeChunks.contains(fo)) freeRegion=fo.dereference(this);
			else{
				Chunk walk=startChunk;
				while(true){
					var next=walk.nextPhysical();
					if(next.getOffset()>start){
						freeRegion=walk;
						break;
					}
					walk=next;
				}
				
				if(DEBUG_VALIDATION){
					Assert(!freeRegion.isUsed(), freeRegion, start, size, end);
				}
			}
			
			if(DEBUG_VALIDATION){
				validateFile();
				Assert(!freeRegion.isUsed(), "Illegal reference to used chunk", freeRegion, "["+start+"..."+end+"]", size);
				Assert(freeRegion.overlaps(start, end), "Freed start chunk", freeRegion, "["+start+"..."+end+"]", size);
				Assert(freeRegion.wholeSize() >= size, "Did not free enough of a region", freeRegion, "["+start+"..."+end+"]", size);
			}
			
			return freeRegion;
		}finally{
			this.region=null;
		}
	}
	
	private void moveChainToChunk(Chunk chunk, Chunk newChunk) throws IOException{
		if(DEBUG_VALIDATION){
			validateFile();
			chunk.checkCaching();
			newChunk.checkCaching();
		}
		
		var chain=chunk.collectWholeChain();
		var siz  =calcTotalSize(chain);
		
		
		if(DEBUG_VALIDATION){
			validateFile();
			Assert(siz<=newChunk.getCapacity(), siz, newChunk);
		}
		
		if(chain.size()>1){
			var second=chain.get(1);
			
			if(DEBUG_VALIDATION) validateFile();
			
			try(var out=newChunk.io().write(chunk.getSize(), false)){
				try(var in=second.io().read()){
					in.transferTo(out);
				}
			}
			
			if(DEBUG_VALIDATION) validateFile();
			
			Assert(!newChunk.hasNext());
			
			var oldOff=chunk.getOffset();
			
			chunk.moveTo(newChunk);
			
			chunk.setSize(siz);
			chunk.chainForwardFree();
			
			var old=getByOffset(oldOff);
			
			freeChunk(old);
			
			if(DEBUG_VALIDATION) validateFile();
		}else{
			chunk.moveToAndFreeOld(newChunk);
		}
		
	}
	
	private void compositChainTo(Chunk chunk, long pos, UnsafeFunctionOL<Chunk, IOException> capacityManager) throws IOException{
		
		chunk.checkCaching();
		
		var size=capacityManager.apply(chunk);
		
		if(chunk.getOffset()==pos){
			var chain=chunk.collectWholeChain();
			if(chain.size()==1){
				if(calcTotalCapacity(chain)==size){
					return;
				}
			}
		}
		
		Chunk freeRegion;
		while(true){
			freeRegion=clearRegion(Region.bySize(pos, Chunk.headerSize(source.getSize(), size)*3+size));
			
			var sizeNew=capacityManager.apply(chunk);
			
			if(sizeNew==size) break;
			size=sizeNew;
		}
		
		var defrag=alocFreeSplit(freeRegion, size);
		Assert(size<=defrag.getCapacity(), size, defrag);
		
		moveChainToChunk(chunk, defrag);
	}
	
	public void defragment() throws IOException{
		Assert(!defragmenting);
		
		if(DEBUG_VALIDATION) validateFile();
		if(LOG_ACTIONS) LogUtil.println("defragmenting");
		
		defragmenting=true;
		int counter=0;
		
		Object e1=null;
		
		try{
			long[] pos={firstChunk().nextPhysicalOffset()};
			
			for(HeaderModule module : modules){
				for(Chunk chunk : module.getOwning()){
					
					compositChainTo(chunk, pos[0], module::capacityManager);
					pos[0]+=chunk.wholeSize();
					if(DEBUG_VALIDATION) validateFile();
					
				}
			}
			
			UnsafeFunctionOL<Chunk, IOException> capacityManager=chunk->{
				long[] size={0}, capacity={0};
				chunk.calculateWholeChainSizes(size, capacity);
				
				if(size[0]>0) return size[0];
				return capacity[0];
			};
			
			for(HeaderModule module : modules){
				for(ChunkLink reference : module.getReferences()){
					var chunk=reference.pointer.dereference(this);
					if(!chunk.isUsed()) continue;
					
					compositChainTo(chunk, pos[0], capacityManager);
					pos[0]+=chunk.wholeSize();
					if(DEBUG_VALIDATION) validateFile();
					
				}
			}
			
		}catch(Throwable e){
			e1=e;
			throw e;
		}finally{
			defragmenting=false;
			if(DEBUG_VALIDATION&&e1==null) validateFile();
		}
	}
	
	private void fairDistribute(long[] values, long toDistribute){
		
		long totalUsage=Arrays.stream(values).sum();
		
		var free=toDistribute-totalUsage;
		
		if(free>0){
			int toUse=values.length;
			do{
				var bulkAdd=free/toUse;
				
				for(int i=0;i<toUse;i++){
					values[i]+=bulkAdd;
					free-=bulkAdd;
				}
				toUse--;
			}while(free>0);
		}else{
			Assert(free==0);
		}
		
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Header)) return false;
		Header header=(Header)o;
		return version==header.version&&
		       source.equals(header.source);
	}
	
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+version.hashCode();
		result=31*result+source.hashCode();
		
		return result;
	}
	
	@Deprecated
	public FixedLenList<FixedNumber, ChunkPointer> getHeaderPointers(){
		return headerPointers;
	}
	
	public void notifyMovement(long oldOffset, Chunk newOffset) throws IOException{
		if(LOG_ACTIONS){
			LogUtil.printTable("Chunk action", "MOVED", "from", oldOffset, "to", newOffset.getOffset());
		}
		
		removeChunkInCache(oldOffset);
		removeChunkInCache(newOffset);
		putChunkInCache(newOffset);
		
		for(int i=0;i<freeChunks.size();i++){
			ChunkPointer pointer=freeChunks.getElement(i);
			if(pointer.equals(oldOffset)){
				freeChunks.setElement(i, new ChunkPointer(newOffset));
				return;
			}
		}
		
		for(int i=0;i<headerPointers.size();i++){
			var ptr=headerPointers.getElement(i);
			if(ptr.equals(oldOffset)){
				headerPointers.setElement(i, new ChunkPointer(newOffset));
				return;
			}
			var chunk=ptr.dereference(this);
			
			do{
				if(chunk.getNext()==oldOffset){
					try{
						chunk.setNext(newOffset);
					}catch(BitDepthOutOfSpaceException e){
						throw new NotImplementedException(e);//todo implement handling this by copying the chunk to end
					}
					return;
				}
				chunk=chunk.nextChunk();
			}while(chunk!=null);
			
		}
		
		for(int i=0;i<fileList.size();i++){
			FilePointer p=fileList.getElement(i);
			
			if(p.getStart()==oldOffset){
				fileList.setElement(i, new FilePointer(this, p.getLocalPath(), p.getStartSize().max(NumberSize.bySize(newOffset.getOffset())), newOffset.getOffset()));
				return;
			}
			
			var chunk=p.dereference();
			do{
				if(chunk.getNext()==oldOffset){
					try{
						chunk.setNext(newOffset);
					}catch(BitDepthOutOfSpaceException e){
						throw new NotImplementedException(e);//todo implement handling this by copying the chunk to end
					}
					return;
				}
				chunk=chunk.nextChunk();
			}while(chunk!=null);
		}
		
		if(DEBUG_VALIDATION) validateFile();
	}
	
	public void deleteFile(String localPath) throws IOException{
		var file=getByPath(localPath);
		fileList.removeElement(fileList.indexOf(file));
		freeChunkChain(file.dereference());
	}
	
	@Override
	public String toString(){
		return "Header{"+
		       "version="+version+
		       ", source="+source+
		       '}';
	}
}

