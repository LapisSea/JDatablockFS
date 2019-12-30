package com.lapissea.fsf;

import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeFunction;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.fsf.NumberSize.*;
import static com.lapissea.util.UtilL.*;

@SuppressWarnings("AutoBoxing")
public class Header{
	
	public static String normalizePath(String path){
		var p=Paths.get(path).normalize().toString();
		return p.equals(path)?path:p;
	}
	
	private static final byte[]  MAGIC_BYTES     ="LSFSIF".getBytes(StandardCharsets.UTF_8);
	public static final  int     FILE_HEADER_SIZE=MAGIC_BYTES.length+2;
	public static final  boolean LOG_ACTIONS     =true;
	
	public static byte[] getMagicBytes(){
		return MAGIC_BYTES.clone();
	}
	
	private static void initHeaderChunks(IOInterface file) throws IOException{
		try(var out=file.write(true)){
			OffsetIndexSortedList.init(out, FILE_TABLE_PADDING);
			FixedLenList.init(out, new SizedNumber(BYTE, ()->(long)(file.getSize()*1.5)), FREE_CHUNK_CAPACITY);
		}
	}
	
	public final Version version;
	
	public final IOInterface source;
	
	private final FixedLenList<FixedNumber, ChunkPointer> headerPointers;
	
	private final OffsetIndexSortedList<FilePointer>      fileList;
	private final FixedLenList<SizedNumber, ChunkPointer> freeChunks;
	private final Map<Long, Chunk>                        chunkCache=new WeakValueHashMap<Long, Chunk>().defineStayAlivePolicy(3);
	
	private boolean defragmenting;
	
	public Header(IOInterface source) throws IOException{
		this.source=source;
		
		Version                                 version;
		FixedLenList<FixedNumber, ChunkPointer> headerPointers;
		
		FixedNumber headerPointersNum=new FixedNumber(LONG);
		
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
			
			headerPointers=new FixedLenList<>(headerPointersNum, firstChunk());
		}catch(EOFException eof){
			//new empty file
			var vs=Version.values();
			version=vs[vs.length-1];
			
			var chunksFakeFile=new IOInterface.MemoryRA();
			initHeaderChunks(chunksFakeFile);
			
			List<ChunkPointer> localOffsets=new ArrayList<>();
			
			long[] pos={0};
			try(var in=new ContentInputStream.Wrapp(new TrackingInputStream(chunksFakeFile.read(), pos))){
				while(true){
					var chunk=Chunk.read(null, pos[0], in);
					in.skipNBytes(chunk.getCapacity());
					
					localOffsets.add(new ChunkPointer(chunk));
				}
			}catch(EOFException ignored){}
			
			try(var out=source.write(true)){
				out.write(MAGIC_BYTES);
				
				var vers  =Version.values();
				var latest=vers[vers.length-1];
				out.write(latest.major);
				out.write(latest.minor);
				
				FixedLenList.init(out, headerPointersNum, localOffsets.size());
				
				try(var in=chunksFakeFile.read()){
					in.transferTo(out);
				}
			}
			
			var ptrsData=firstChunk();
			
			headerPointers=new FixedLenList<>(headerPointersNum, ptrsData);
			
			for(var off : localOffsets){
				off.value+=ptrsData.nextPhysicalOffset();
				headerPointers.addElement(off);
			}
		}
		
		this.version=version;
		this.headerPointers=headerPointers;
		
		var iter=headerPointers.iterator();
		
		Chunk names  =iter.next().dereference(this);
		Chunk offsets=iter.next().dereference(this);
		Chunk frees  =iter.next().dereference(this);
		
		fileList=new OffsetIndexSortedList<>(()->new FilePointer(this), names, offsets);
		
		freeChunks=new FixedLenList<>(new SizedNumber(source::getSize), frees);
	}
	
	public FilePointer getByPath(String path) throws IOException{
		return getByPathUnsafe(normalizePath(path));
	}
	
	private FilePointer getByPathUnsafe(String path) throws IOException{
		return fileList.findSingle(comp->comp.getLocalPath().compareTo(path));
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
	
	private Chunk aloc(long initialSize, boolean allowNonOptimal) throws IOException{
		return aloc(initialSize, NumberSize.bySize(initialSize), allowNonOptimal);
	}
	
	private Chunk aloc(long initialSize, NumberSize bodyType, boolean allowNonOptimal) throws IOException{
		if(!defragmenting&&!freeChunks.isEmpty()){
			int   bestInd=-1;
			Chunk best   =null;
			long  diff   =Long.MAX_VALUE;
			
			for(int i=0;i<freeChunks.size();i++){
				Chunk c =getByOffset(freeChunks.getElement(i).value);
				long  ds=c.getCapacity();
				
				if(!allowNonOptimal){
					if(ds<initialSize){
						continue;
					}
				}
				
				long newDiff=ds-initialSize;
				
				if(newDiff<diff){
					best=c;
					diff=newDiff;
					bestInd=i;
					if(diff==0) break;
				}
			}
			
			if(best!=null){
				if(LOG_ACTIONS) logChunkAction("REUSED", best);
				
				freeChunks.removeElement(bestInd);
				best.setUsed(true);
				best.syncHeader();
				return best;
			}
		}
		
		var chunk=new Chunk(this, source.getSize(), NumberSize.bySize((long)(source.getSize()*1.1)), 0, bodyType, initialSize);
		chunkCache.put(chunk.getOffset(), chunk);
		
		try(var out=source.write(source.getSize(), true)){
			chunk.init(out);
		}
		
		return chunk;
	}
	
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
		
		var initSize=Math.max(requestedMemory, MINIMUM_CHUNK_SIZE);
		
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
	
	private void mergeChunks(Chunk dest, Chunk toMerge) throws IOException, BitDepthOutOfSpaceException{
		dest.setCapacity(toMerge.nextPhysicalOffset()-dest.getDataStart());
		dest.syncHeader();
	}
	
	private void rescueChain(Chunk chainStart, long additionalSpace) throws IOException{
		
		var chain=chainStart.loadWholeChain();
		
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
				}catch(BitDepthOutOfSpaceException e){
					throw new RuntimeException(e);//should never happen
				}finally{
					if(!toFree.isEmpty()){
						freeChunkChain(toFree.get(0));
					}
				}
				return;
			}
		}
		
		
		var alocSize=chain.stream().mapToLong(Chunk::getSize).sum()+additionalSpace;
		
		chainStart.moveTo(aloc(alocSize, false));
		
		try(var out=chainStart.io().write(false)){
			try(var in=chain.get(1).io().read()){
				in.transferTo(out);
			}
		}
		freeChunkChain(chain.get(1));
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
	
	private void validateFile() throws IOException{
		
		class Range{
			public final long a, b;
			public final String source;
			public final Chunk  chunk;
			
			Range(Chunk chunk, String source){
				this.a=chunk.getOffset();
				this.b=chunk.getOffset()+chunk.wholeSize();
				this.chunk=chunk;
				this.source=source;
			}
			
			boolean overlap(Range other){
				return Math.max(other.a, a)-Math.min(other.b, b)<(a-b)+(other.a-other.b);
			}
		}
		
		List<Range> ranges=new ArrayList<>();
		
		sourcedChunkIter((newSource, chunk)->{
			var dup=ranges.stream().filter(r->r.chunk.equals(chunk)).findAny();
			if(dup.isPresent()) throw new AssertionError("Duplicate chunk reference:\n"+dup.get().source+"\n"+newSource);
			ranges.add(new Range(chunk, newSource));
		});
		
		for(var range1 : ranges){
			for(var range2 : ranges){
				if(range1.overlap(range2)){
					throw new AssertionError("\n"+TextUtil.toTable("Overlapping", List.of(range1, range2)));
				}
			}
		}
		
		
	}
	
	public void freeChunkChain(Chunk chunk) throws IOException{
		if(chunk.hasNext()) freeChunkChain(chunk.nextChunk());
		freeChunk(chunk);
	}
	
	private void freeChunk(Chunk chunk) throws IOException{
		
		if(DEBUG_VALIDATION){
			sourcedChunkIter((src, ch)->{
				if(chunk.equals(ch)){
					throw new AssertionError("Trying to free chunk in use: "+src);
				}
			});
		}
		
		chunk.setUsed(false);
		chunk.setSize(0);
		chunk.clearNext();
		try{
			
			if(deallocatingFreeAction(chunk)) return;
			
			if(forwardFreeMergeAction(chunk)){
				chunk.syncHeader();
				return;
			}
			
			if(backwardFreeMergeAction(chunk)) return;
			
			normalFreeAction(chunk);
			chunk.syncHeader();
		}finally{
			if(DEBUG_VALIDATION) validateFile();
		}
	}
	
	private boolean deallocatingFreeAction(Chunk chunk) throws IOException{
		if(!chunk.isLastPhysical()) return false;
		
		source.setCapacity(chunk.getOffset());
		chunkCache.remove(chunk.getOffset());
		
		if(LOG_ACTIONS) logChunkAction("FREE DEALLOC", chunk);
		return true;
	}
	
	private boolean backwardFreeMergeAction(Chunk chunk) throws IOException{
		
		var offset=chunk.getOffset();
		
		Chunk previous;
		find:
		{
			for(var val : freeChunks){
				Chunk prev=getByOffset(val.value);
				var   next=prev.nextPhysicalOffset();
				if(next==offset){
					previous=prev;
					break find;
				}
			}
			return false;
		}
		
		try{
			previous.setCapacity(chunk.nextPhysicalOffset()-previous.getDataStart());
		}catch(BitDepthOutOfSpaceException e){
			return false;
		}
		
		previous.syncHeader();
		if(LOG_ACTIONS) logChunkAction("FREE B MERGE", chunk);
		
		return true;
	}
	
	private boolean forwardFreeMergeAction(Chunk chunk) throws IOException{
		if(freeChunks.isEmpty()) return false;
		
		var offset =chunk.getOffset();
		var nextPtr=new ChunkPointer(chunk.nextPhysicalOffset());
		
		var nextIndex=freeChunks.indexOf(nextPtr);
		
		if(nextIndex==-1) return false;
		
		try{
			//merge if next physical chunk also free
			Chunk next=nextPtr.dereference(this);
			
			mergeChunks(chunk, next);
			
			chunkCache.remove(nextPtr.value);
			
			var nextVal=freeChunks.getElement(nextIndex);
			nextVal.value=chunk.getOffset();
			freeChunks.setElement(nextIndex, nextVal);
			
			if(LOG_ACTIONS) logChunkAction("FREE F MERGE", chunk);
			
			return true;
		}catch(BitDepthOutOfSpaceException e){
			return false;//todo reformat header be able to merge
		}
	}
	
	private void normalFreeAction(Chunk chunk) throws IOException{
		freeChunks.addElement(new ChunkPointer(chunk));
		if(LOG_ACTIONS) logChunkAction("FREED", chunk);
	}
	
	public Chunk getByOffset(Long offset) throws IOException{
		var cached=chunkCache.get(offset);
		if(cached!=null) return cached;
		
		var read=Chunk.read(this, offset);
		chunkCache.put(offset, read);
		
		return read;
	}
	
	public VirtualFile[] listFiles() throws IOException{
		var files=new VirtualFile[fileList.size()];
		for(int i=0;i<files.length;i++){
			files[i]=new VirtualFile(fileList.getByIndex(i));
		}
		return files;
	}
	
	public List<Chunk> allChunks(boolean includeHeader) throws IOException{
		List<Chunk> result=new ArrayList<>();
		
		if(includeHeader){
			result.addAll(headerStartChunks());
		}
		
		for(var pointer : fileList){
			result.add(pointer.dereference());
		}
		
		for(int i=0;i<result.size();i++){
			var c=result.get(i);
			if(c.hasNext()) result.add(c.nextChunk());
		}
		
		for(ChunkPointer val : freeChunks){
			result.add(getByOffset(val.value));
		}
		
		result.sort(Comparator.comparingLong(Chunk::getOffset));
		
		return result;
	}
	
	private long calcTotalCapacity(List<Chunk> chain){
		return chain.stream().mapToLong(Chunk::getCapacity).sum();
	}
	
	private List<List<Chunk>> chunksToChains(List<Chunk> chunks) throws IOException{
		var chains=new ArrayList<List<Chunk>>(chunks.size());
		for(Chunk chunk : chunks){
			chains.add(chunk.loadWholeChain());
		}
		return chains;
	}
	
	private Chunk firstChunk() throws IOException{
		return getByOffset((long)FILE_HEADER_SIZE);
	}
	
	private void clearRegion(long start, long size) throws IOException{
		long end  =start+size;
		var  first=firstChunk();
		Assert(!first.overlaps(start, end));
		
		var startChunk=first.nextPhysical();
		
		List<Chunk> toClear=new ArrayList<>();
		
		Chunk walk=startChunk;
		while(true){
			var next=walk.nextPhysical();
			if(next.getOffset()>start) break;
			walk=next;
		}
		
		while(true){
			toClear.add(walk);
			
			var next=walk.nextPhysical();
			if(next.getOffset() >= end) break;
			walk=next;
		}
		
		LogUtil.println(toClear);
		toClear.removeIf(ch->!ch.isUsed());
		LogUtil.println(toClear);
		
		List<Chunk> toMarkFree=toClear.stream()
		                              .map(c->new Chunk(this, c.getOffset(), c.getNextType(), 0, c.getBodyType(), size, c.getCapacity(), true))
		                              .collect(Collectors.toList());
		
		LogUtil.println(TextUtil.toTable(fileList));
		
		for(Chunk chunk : toClear){
			var siz=chunk.getSize()==0?chunk.getCapacity():chunk.getSize();
			
			var newLoc=aloc(siz, false);
			
			LogUtil.println("moving", chunk, "to", newLoc);
			
			chunk.moveTo(newLoc);
		}
		
		LogUtil.println(TextUtil.toTable(fileList));
		
		LogUtil.println(toMarkFree);
		LogUtil.println(toClear);
		
		for(Chunk chunk : toMarkFree){
			LogUtil.println(chunk);
			freeChunk(chunk);
		}
		
	}
	
	public void defragment() throws IOException{
		Assert(!defragmenting);
		
		defragmenting=true;
		LogUtil.println("defragmenting");
		
		try{
			
			clearRegion(100, 50);
			
			if(TRUE()) return;
			
			var headerChunks=headerStartChunks();
			headerChunks.sort(Comparator.comparingLong(Chunk::getOffset));
			
			long[] headerDataUsages      =chunksToChains(headerChunks).stream().mapToLong(this::calcTotalCapacity).toArray();
			long[] headerDataUsagesPadded=Arrays.stream(headerDataUsages).map(l->(long)(l*1.3)).toArray();
			
			UnsafeFunction<Long, Long, IOException> calcHeaderUsage=fileSizeO->{
				long fileSize=fileSizeO;
				long sum     =0;
				
				for(var usage : headerDataUsagesPadded){
					sum+=Chunk.headerSize(fileSize, usage)+usage;
				}
				
				return sum;
			};
			
			var firstChunk=firstChunk();
			UnsafeFunction<Long, PairM<List<Chunk>, Long>, IOException> calcToCLear=toCLearO->{
				long toClear=toCLearO;
				
				long cleared  =0;
				var  nextChunk=firstChunk;
				var  toMove   =new ArrayList<Chunk>();
				
				do{
					cleared+=nextChunk.wholeSize();
					toMove.add(nextChunk);
					nextChunk=nextChunk.nextPhysical();
					if(nextChunk==null) throw new NullPointerException();//should never happen??
				}while(toClear>cleared);
				
				return new PairM<>(toMove, cleared);
			};
			
			long        headerUsage;
			List<Chunk> toMove;
			long        cleared;
			long        predictedSize;
			NumberSize  fileSafeSizeMod;
			
			while(true){
				
				headerUsage=calcHeaderUsage.apply(source.getSize());
				do{
					var p=calcToCLear.apply(headerUsage);
					toMove=p.obj1;
					cleared=p.obj2;
					
					predictedSize=source.getSize()+cleared;
					headerUsage=calcHeaderUsage.apply(predictedSize);
					
				}while(headerUsage>cleared);
				
				fileSafeSizeMod=NumberSize.bySize(predictedSize);
				
				var neededFreeChunks=freeChunks.size()+toMove.size()*2;
				
				if(freeChunks.ensureElementCapacity(neededFreeChunks)) continue;
				
				long needed=0;
				
				for(int i=0;i<fileList.size();i++){
					var p=fileList.getByIndex(i);
					needed+=new FilePointer(this, p.getLocalPath(), fileSafeSizeMod, p.getStart()).length();
				}
				
				if(fileList.ensureCapacity(needed)) continue;
				
				break;
			}
			
			NumberSize fileSafeSize=fileSafeSizeMod;
			
			long[] headerDataUsagesFinal=headerDataUsagesPadded.clone();
			
			{
				var headerSizes  =Arrays.stream(headerDataUsagesPadded).map(siz->Chunk.headerSize(fileSafeSize, siz)).toArray();
				var freeDataSpace=cleared-Arrays.stream(headerSizes).sum();
				
				fairDistribute(headerDataUsagesFinal, freeDataSpace);
			}
			
			for(Chunk chunk : toMove){
				var siz=chunk.getCapacity();
				
				chunk.moveTo(aloc(siz, false));
			}
			
			List<List<Chunk>> headerChains=chunksToChains(headerChunks);
			
			long counter=FILE_HEADER_SIZE;
			
			for(int i=0, j=headerChains.size();i<j;i++){
				var chain=headerChains.get(i);
				
				Chunk headerChunk=chain.get(0);
				
				long siz=headerDataUsagesFinal[i];
				
				headerChunk.moveTo(new Chunk(this, counter, bySize(source.getSize()), 0, bySize(siz), siz));
				
				counter+=headerChunk.getHeaderSize()+siz;
				
				if(chain.size()>1){
					
					try(var out=headerChunk.io().write(headerChunk.getSize(), false)){
						try(var in=chain.get(1).io().read()){
							in.transferTo(out);
						}
					}
					
					headerChunk.chainForwardFree();
				}
			}
			
		}finally{
			defragmenting=false;
			if(DEBUG_VALIDATION) validateFile();
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
	
	public List<Chunk> headerStartChunks() throws IOException{
		List<Chunk> arll=new ArrayList<>(headerPointers.size()+1);
		arll.addAll(headerPointers.getShadowChunks());
		for(var ptr : headerPointers){
			arll.add(ptr.dereference(this));
		}
		return arll;
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
	
	public FixedLenList<FixedNumber, ChunkPointer> getHeaderPointers(){
		return headerPointers;
	}
	
	public void notifyMovement(long oldOffset, long newOffset) throws IOException{
		chunkCache.remove(oldOffset);
		
		var ind=headerPointers.indexOf(new ChunkPointer(oldOffset));
		if(ind!=-1){
			headerPointers.setElement(ind, new ChunkPointer(newOffset));
		}
		
		for(int i=0;i<fileList.size();i++){
			FilePointer p=fileList.getByIndex(i);
			
			if(p.getStart()==oldOffset){
				fileList.setByIndex(i, new FilePointer(this, p.getLocalPath(), p.getStartSize().max(NumberSize.bySize(newOffset)), newOffset));
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

//		if(DEBUG_VALIDATION) validateFile();
	}
}

