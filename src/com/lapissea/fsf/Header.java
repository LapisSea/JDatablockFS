package com.lapissea.fsf;

import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeFunction;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

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
	private static final boolean LOG_ACTIONS     =true;
	
	public static byte[] getMagicBytes(){
		return MAGIC_BYTES.clone();
	}
	
	private static void initEmptyFile(IOInterface file) throws IOException{
		long[] pos={0};
		try(var out=new ContentOutputStream.Wrapp(new TrackingOutputStream(file.write(true), pos))){
			out.write(MAGIC_BYTES);
			
			var vers  =Version.values();
			var latest=vers[vers.length-1];
			out.write(latest.major);
			out.write(latest.minor);
			
			OffsetIndexSortedList.init(out, pos, FILE_TABLE_PADDING);
			FixedLenList.init(out, pos, new SizedNumber(BYTE, ()->(long)(file.getSize()*1.5)), FREE_CHUNK_CAPACITY);
			
		}
	}
	
	public final Version version;
	
	public final IOInterface source;
	
	private final OffsetIndexSortedList<FilePointer>        fileList;
	private final FixedLenList<SizedNumber, LongFileBacked> freeChunks;
	private final Map<Long, Chunk>                          chunkCache=new WeakValueHashMap<Long, Chunk>().defineStayAlivePolicy(3);
	
	public Header(IOInterface source) throws IOException{
		try(var in=source.read(0)){
			in.skipNBytes(FILE_HEADER_SIZE);
		}catch(EOFException e){//new empty file
			initEmptyFile(source);
		}
		
		this.source=source;
		long[] pos={0};
		
		try(var in=new ContentInputStream.Wrapp(new TrackingInputStream(source.read(0), pos))){
			if(!Arrays.equals(in.readNBytes(MAGIC_BYTES.length), MAGIC_BYTES)){
				throw new IOException("Not a \"File System In File\" file or is corrupted");
			}
			
			var versionBytes=in.readNBytes(2);
			
			version=Arrays.stream(Version.values())
			              .filter(v->v.is(versionBytes))
			              .findAny()
			              .orElseThrow(()->new IOException("Invalid version number "+TextUtil.toString(versionBytes)));
			
		}
		
		Chunk names=getByOffset(pos[0]);
		
		Chunk offsets=names.nextPhysical();
		
		Chunk frees=offsets.nextPhysical();
		
		fileList=new OffsetIndexSortedList<>(()->new FilePointer(this), names, offsets);
		
		freeChunks=new FixedLenList<>(frees, new SizedNumber(source::getSize));

//		LogUtil.println(freeChunks);
//		freeChunks.addElement(new LongFileBacked(20));
//		LogUtil.println(freeChunks);
//		freeChunks.addElement(new LongFileBacked(200));
//		freeChunks.deb();
//		freeChunks.ensureElementCapacity(3);
//		freeChunks.ensureElementCapacity(4);
//		freeChunks.deb();
//		LogUtil.println(freeChunks);
//		freeChunks.addElement(new LongFileBacked(2000));
//		LogUtil.println(freeChunks);
//		freeChunks.ensureElementCapacity(5);
//		freeChunks.deb();
//		LogUtil.println(freeChunks);
//
//		System.exit(0);
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
		
		
		Chunk chunk=alocChunk(initialSize, true);
		fileList.addElement(new FilePointer(this, pat, chunk.getOffset()));
		return chunk;
	}
	
	private Chunk alocChunk(long initialSize, boolean allowNonOptimal) throws IOException{
		return alocChunk(initialSize, NumberSize.bySize(initialSize), allowNonOptimal);
	}
	
	private Chunk alocChunk(long initialSize, NumberSize bodyType, boolean allowNonOptimal) throws IOException{
		if(!freeChunks.isEmpty()){
			int   bestInd=-1;
			Chunk best   =null;
			long  diff   =Long.MAX_VALUE;
			
			for(int i=0;i<freeChunks.size();i++){
				Chunk c =getByOffset(freeChunks.getElement(i).value);
				long  ds=c.getDataCapacity();
				
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
				best.setChunkUsed(true);
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
	
	private void mergeChunks(Chunk dest, Chunk toMerge) throws IOException, BitDepthOutOfSpaceException{
		dest.setDataCapacity(toMerge.nextPhysicalOffset()-dest.getDataStart());
		dest.syncHeader();
	}
	
	/*
	 * 1. end of file edge case easy file grow and populate
	 * 2. cannibalise physically next chunks
	 * 3. attempt to rechain any free chunks
	 * 4. create new chunks and chain
	 * */
	public void requestMemory(Chunk chainStart, Chunk chunk, long requestedMemory) throws IOException{
		if(chunk.hasNext()) return;
		
		long nextOffset=chunk.nextPhysicalOffset();
		
		try{
			var sourceSize=source.getSize();
			if(nextOffset >= sourceSize){//last at end of file
				var oldDataSize=chunk.getDataCapacity();
				
				chunk.setDataCapacity(chunk.getDataCapacity()+requestedMemory);
				chunk.syncHeader();
				
				try(var out=source.doRandom()){
					out.setPos(chunk.getDataStart()+oldDataSize);
					out.fillZero(requestedMemory);
				}
				if(LOG_ACTIONS) logChunkAction("GROWTH", chunk);
				return;
			}
		}catch(BitDepthOutOfSpaceException e){
			LogUtil.println("Can't grow anymore", chunk);
		}
		
		
		if(!freeChunks.isEmpty()){
			try{
				var nextInd=freeChunks.indexOf(new LongFileBacked(nextOffset));
				if(nextInd!=-1){
					var next=getByOffset(nextOffset);
					if(next.wholeSize() >= requestedMemory){
						mergeChunks(chunk, next);
						if(LOG_ACTIONS) logChunkAction("Merged free", chunk);
						freeChunks.removeElement(nextInd);
						return;
					}
				}
			}catch(BitDepthOutOfSpaceException e){
				LogUtil.println("failed to merge chunks", chunk);
			}
		}
		
		var initSize=Math.max(requestedMemory, MINIMUM_CHUNK_SIZE);
		
		NumberSize bodyNum=chunk.getBodyType()
		                        .next()
		                        .min(NumberSize.bySize((long)(chunk.getDataCapacity()*1.3)))//don't overestimate chunk size of last was cut off
		                        .max(NumberSize.bySize(initSize));//make sure init size can fit
		
		var newChunk=alocChunk(initSize, bodyNum, true);
		var off     =newChunk.getOffset();
		try{
			chunk.setNext(off);
		}catch(BitDepthOutOfSpaceException e){
			LogUtil.println("Failed to link "+newChunk+" to "+chunk+" because offset of "+off+" can not fit in to "+chunk.getNextType());
			freeChunk(newChunk);
			
			var oldData=chainStart.io().readAll();//todo: remove sanity check
			
			rescueChain(chainStart, requestedMemory);
			
			var newData=chainStart.io().readAll();
			
			if(!Arrays.equals(oldData, newData)){
				LogUtil.println(oldData);
				LogUtil.println(newData);
				throw new RuntimeException("rescue chain fail");
			}
			
			return;
		}
		
		chunk.syncHeader();
		if(LOG_ACTIONS) logChunkAction("CHAINED", chunk);
		
	}
	
	private void rescueChain(Chunk chainStart, long additionalSpace) throws IOException{
		freeChunks.deb();
		
		var chain=chainStart.loadWholeChain();
		
		for(int i=chain.size()-1;i >= 0;i--){
			var chunk=chain.get(i);
			if(chunk.getNextType().canFit(source.getSize())){
				var toFree=chain.subList(i+1, chain.size());
				
				LogUtil.println(toFree);
				
				var alocSize=toFree.stream().mapToLong(Chunk::getUsed).sum()+additionalSpace;
				var newChunk=alocChunk(alocSize, true);
				
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
					freeChunks.deb();
					
					if(!toFree.isEmpty()){
						freeChunkChain(toFree.get(0));
					}
				}
				return;
			}
		}
		
		
		var alocSize=chain.stream().mapToLong(Chunk::getUsed).sum()+additionalSpace;
		
		chainStart.moveTo(alocChunk(alocSize, false));
		
		try(var out=chainStart.io().write(false)){
			try(var in=chain.get(1).io().read()){
				in.transferTo(out);
			}
		}
		freeChunkChain(chain.get(1));
	}
	
	public void freeChunkChain(Chunk chunk) throws IOException{
		if(chunk.hasNext()) freeChunkChain(chunk.nextChunk());
		freeChunk(chunk);
	}
	
	private void freeChunk(Chunk chunk) throws IOException{
		
		if(chunk.isLastPhysical()){
			source.setCapacity(chunk.getOffset());
			return;
		}
		
		chunk.setChunkUsed(false);
		chunk.setUsed(0);
		chunk.clearNext();
		
		var offset    =chunk.getOffset();
		var nextOffset=chunk.nextPhysicalOffset();
		
		if(freeChunks.isEmpty()){
			freeChunks.addElement(new LongFileBacked(offset));
		}else{
			var ind=freeChunks.indexOf(new LongFileBacked(nextOffset));
			
			if(ind==-1){
				Chunk previous=null;
				for(var val : freeChunks){
					Chunk prev=getByOffset(val.value);
					var   next=prev.nextPhysicalOffset();
					if(next==offset){
						previous=prev;
						break;
					}
				}
				
				//no next or previous free chunk
				if(previous==null) freeChunks.addElement(new LongFileBacked(chunk.getOffset()));
				else{
					//merge chunk with previous
					try{
						previous.setDataCapacity(chunk.nextPhysicalOffset()-previous.getDataStart());
						previous.syncHeader();
						return;//don't sync chunk header bc it does not exist anymore
					}catch(BitDepthOutOfSpaceException ignored){ }
				}
			}else{
				try{
					//merge if next physical chunk also free
					Chunk nextChunk=getByOffset(nextOffset);
					mergeChunks(chunk, nextChunk);
					
					chunkCache.remove(nextOffset);
					
					var nextVal=freeChunks.getElement(ind);
					nextVal.value=chunk.getOffset();
					freeChunks.setElement(ind, nextVal);
					
				}catch(BitDepthOutOfSpaceException bitDepthOutOfSpaceException){
					freeChunks.addElement(new LongFileBacked(offset));//todo reformat header be able to merge
				}
			}
		}
		
		chunk.syncHeader();
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
		
		for(LongFileBacked val : freeChunks){
			result.add(getByOffset(val.value));
		}
		
		result.sort(Comparator.comparingLong(Chunk::getOffset));
		
		return result;
	}
	
	private long calcTotalDataSize(List<Chunk> chain){
		return chain.stream().mapToLong(Chunk::getDataCapacity).sum();
	}
	
	private Chunk firstChunk() throws IOException{
		return getByOffset((long)FILE_HEADER_SIZE);
	}
	
	public void defragment() throws IOException{
		LogUtil.println("defragment start");
		
		freeChunks.deb();
		
		var firstChunk=firstChunk();
		
		var headerChunks=headerStartChunks();
		headerChunks.sort(Comparator.comparingLong(Chunk::getOffset));
		
		var headerChains=new ArrayList<List<Chunk>>(headerChunks.size());
		for(Chunk chunk : headerChunks){
			headerChains.add(chunk.loadWholeChain());
		}
		
		long[] headerDataUsages      =headerChains.stream().mapToLong(this::calcTotalDataSize).toArray();
		long[] headerDataUsagesPadded=Arrays.stream(headerDataUsages).map(l->(long)(l*1.3)).toArray();
		
		
		UnsafeFunction<Long, Long, IOException> calcHeaderUsage=fileSizeO->{
			long fileSize=fileSizeO;
			long sum     =0;
			
			for(var usage : headerDataUsagesPadded){
				sum+=Chunk.headerSize(fileSize, usage)+usage;
			}
			
			return sum;
		};
		
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
				
				LogUtil.println(headerUsage, cleared);
			}while(headerUsage>cleared);
			
			fileSafeSizeMod=NumberSize.bySize(predictedSize);
			LogUtil.println(fileSafeSizeMod);
			
			var neededFreeChunks=freeChunks.size()+toMove.size()*2;
			LogUtil.println("neededFreeChunks", neededFreeChunks);
			
			if(freeChunks.ensureElementCapacity(neededFreeChunks)) continue;
			
			long needed=0;
			
			for(int i=0;i<fileList.size();i++){
				var p=fileList.getByIndex(i);
				needed+=new FilePointer(this, p.getLocalPath(), fileSafeSizeMod, p.getStart()).length();
			}
			
			LogUtil.println("needed file list memory", needed);
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
			var siz=chunk.getDataCapacity();
			
			chunk.moveTo(alocChunk(siz, false));
		}
		
		long counter=firstChunk.getOffset();
		
		for(int i=0, j=headerChains.size();i<j;i++){
			var   chain      =headerChains.get(i);
			Chunk headerChunk=chain.get(0);
			
			long siz=headerDataUsagesFinal[i];
			
			headerChunk.moveTo(new Chunk(this, counter, bySize(source.getSize()), 0, bySize(siz), siz));
			
			counter+=headerChunk.getHeaderSize()+siz;
			
			if(chain.size()>1){
				try(var out=headerChunk.io().write(headerChunk.getUsed(), false)){
					
					try(var in=chain.get(1).io().read()){
						in.transferTo(out);
					}
				}
				
				freeChunkChain(chain.get(1));
			}
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
	
	public List<Chunk> headerStartChunks(){
		List<Chunk> arll=new ArrayList<>(fileList.getShadowChunks());
		arll.addAll(freeChunks.getShadowChunks());
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
	
	public void notifyMovement(long oldOffset, long newOffset) throws IOException{
		chunkCache.remove(oldOffset);
		
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
		
	}
}

