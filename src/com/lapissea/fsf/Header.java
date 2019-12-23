package com.lapissea.fsf;

import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.fsf.NumberSize.*;

@SuppressWarnings("AutoBoxing")
public class Header{
	
	public enum Version{
		V01(0, 1);
		
		public final byte major;
		public final byte minor;
		
		Version(int major, int minor){
			this.major=(byte)major;
			this.minor=(byte)minor;
		}
		
		public boolean is(byte[] versionBytes){
			return versionBytes[0]==major&&versionBytes[1]==minor;
		}
	}
	
	private static class SizedNumber extends FileObject.FullLayout<SizedNumber> implements FixedLenList.ElementHead<SizedNumber, LongFileBacked>{
		
		private static final SequenceLayout<SizedNumber> LAYOUT=FileObject.sequenceBuilder(List.of(
			new FileObject.FlagDef<>(BYTE,
			                         (writer, head)->writer.writeEnum(head.size),
			                         (reader, head)->head.size=reader.readEnum(NumberSize.class))
		                                                                                          ));
		
		private NumberSize size;
		
		public SizedNumber(){
			this(null);
		}
		
		public SizedNumber(NumberSize size){
			super(LAYOUT);
			this.size=size;
		}
		
		@Override
		public SizedNumber copy(){
			return new SizedNumber(size);
		}
		
		@Override
		public boolean willChange(LongFileBacked element){
			return NumberSize.bySize(element.value).max(size)!=size;
		}
		
		@Override
		public void update(LongFileBacked element){
			size=NumberSize.bySize(element.value).max(size);
		}
		
		@Override
		public int getElementSize(){
			return size.bytes;
		}
		
		@Override
		public LongFileBacked newElement(){
			return new LongFileBacked();
		}
		
		@Override
		public void readElement(ContentInputStream src, LongFileBacked dest) throws IOException{
			dest.value=size.read(src);
		}
		
		@Override
		public void writeElement(ContentOutputStream dest, LongFileBacked src) throws IOException{
			size.write(dest, src.value);
		}
		
		@Override
		public String toString(){
			return size+"S";
		}
	}
	
	public static String normalizePath(String path){
		var p=Paths.get(path).normalize().toString();
		return p.equals(path)?path:p;
	}
	
	private static final byte[]  MAGIC_BYTES="LSFSIF".getBytes(StandardCharsets.UTF_8);
	private static final boolean LOG_ACTIONS=false;
	
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
			FixedLenList.init(out, pos, new SizedNumber(BYTE), FREE_CHUNK_CAPACITY);
			
		}
	}
	
	public final Version version;
	
	public final IOInterface source;
	
	private final OffsetIndexSortedList<FilePointer>        fileList;
	private final FixedLenList<SizedNumber, LongFileBacked> freeChunks;
	private final Map<Long, Chunk>                          chunkCache=new WeakValueHashMap<Long, Chunk>().defineStayAlivePolicy(3);
	
	public Header(IOInterface source) throws IOException{
		var minLen=MAGIC_BYTES.length+2;
		try(var in=source.read(0)){
			in.skipNBytes(minLen);
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
		
		freeChunks=new FixedLenList<>(frees, new SizedNumber());
		
		LogUtil.println(freeChunks);
		freeChunks.addElement(new LongFileBacked(155));
		LogUtil.println(freeChunks);
		freeChunks.removeElement(0);
		LogUtil.println(freeChunks);
		freeChunks.addElement(new LongFileBacked(135));
		LogUtil.println(freeChunks);
		freeChunks.addElement(new LongFileBacked(251));
		LogUtil.println(freeChunks);
		freeChunks.addElement(new LongFileBacked(2000));
		LogUtil.println(freeChunks);
		LogUtil.println(new FixedLenList<>(frees, new SizedNumber()));
		System.exit(0);
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
		
		
		Chunk chunk=alocChunk(initialSize, NumberSize.bySize(initialSize));
		fileList.addElement(new FilePointer(this, pat, chunk.getOffset()));
		return chunk;
	}
	
	private Chunk alocChunk(long initialSize, NumberSize bodyType) throws IOException{
		if(!freeChunks.isEmpty()){
			int   bestInd=-1;
			Chunk best   =null;
			long  diff   =Long.MAX_VALUE;
			
			for(int i=0;i<freeChunks.size();i++){
				Chunk c =getByOffset(freeChunks.getElement(i).value);
				long  ds=c.getDataSize();
				
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
		dest.setDataSize(toMerge.nextPhysicalOffset()-dest.getDataStart());
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
				var oldDataSize=chunk.getDataSize();
				
				chunk.setDataSize(chunk.getDataSize()+requestedMemory);
				chunk.syncHeader();
				
				try(var out=source.write(chunk.getDataStart()+oldDataSize, true)){
					Utils.zeroFill(out, (int)requestedMemory);
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
		                        .min(NumberSize.bySize((long)(chunk.getDataSize()*1.3)))//don't overestimate chunk size of last was cut off
		                        .max(NumberSize.bySize(initSize));//make sure init size can fit
		
		var newChunk=alocChunk(initSize, bodyNum);
		var off     =newChunk.getOffset();
		try{
			chunk.setNext(off);
		}catch(BitDepthOutOfSpaceException e){
			LogUtil.println("Failed to link "+newChunk+" to "+chunk+" because offset of "+off+" can not fit in to "+chunk.getNextType());
			freeChunk(newChunk);
			rescueChain(chainStart, requestedMemory);
			return;
		}
		
		chunk.syncHeader();
		if(LOG_ACTIONS) logChunkAction("CHAINED", chunk);
		
	}
	
	private void rescueChain(Chunk chainStart, long additionalSpace) throws IOException{
		var chain=chainStart.loadWholeChain();
		
		for(int i=chain.size()-1;i >= 0;i--){
			var chunk=chain.get(i);
			if(chunk.getNextType().canFit(source.getSize())){
				var toFree=chain.subList(i+1, chain.size());
				
				var toFreeSize=toFree.stream().mapToLong(Chunk::getUsed).sum();
				
				var alocSize=toFreeSize+additionalSpace;
				var newChunk=alocChunk(alocSize, NumberSize.bySize(alocSize));
				
				try(var out=newChunk.io().write(false)){
					for(Chunk old : toFree){
						try(var in=old.io().read()){
							in.transferTo(out);
						}
					}
				}
				
				freeChunkChain(toFree.get(0));
				try{
					chunk.setNext(newChunk);
				}catch(BitDepthOutOfSpaceException e){
					throw new RuntimeException(e);//should never happen
				}
				return;
			}
		}
		
		var toFreeSize=chain.stream().mapToLong(Chunk::getUsed).sum();
		
		var alocSize=toFreeSize+additionalSpace;
		var newChunk=alocChunk(alocSize, NumberSize.bySize(alocSize));
		
		chainStart.moveTo(newChunk);
		
		try(var out=chainStart.io().write(false)){
			for(int i=1;i<chain.size();i++){
				Chunk old=chain.get(i);
				try(var in=old.io().read()){
					in.transferTo(out);
				}
			}
		}
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
		
		if(freeChunks.isEmpty()) freeChunks.addElement(new LongFileBacked(offset));
		else{
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
						previous.setDataSize(chunk.nextPhysicalOffset()-previous.getDataStart());
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
			result.addAll(headerChunks());
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
	
	private long calcTotalUsage(List<Chunk> chain){
		return chain.stream().mapToLong(Chunk::getUsed).sum();
	}
	
	
	public void defragment() throws IOException{
//		LogUtil.println("defragmenting not implemented");
//		if(true) throw null;
		
		var headerChunks=headerChunks();
		
		long pos=headerChunks.get(0).getOffset();
		
		long headerUsage=0;
		var  headerDirty=false;
		for(Chunk headerChunk : headerChunks){
			var chain=headerChunk.loadWholeChain();
			if(!headerDirty){
				if(chain.size()>1) headerDirty=true;
				else{
					var last=chain.get(chain.size()-1);
					if(last.getDataSize()!=last.getUsed()) headerDirty=true;
				}
			}
			var usage=calcTotalUsage(chain);
			
			headerUsage+=Chunk.headerSize(source.getSize(), usage)+usage*1.3;
		}
		
		if(headerDirty){
			//clear space
			
			long        toClear  =headerUsage;
			long        cleared  =0;
			var         nextChunk=headerChunks.get(0);
			List<Chunk> toMove   =new ArrayList<>();
			
			do{
				cleared+=nextChunk.wholeSize();
				toMove.add(nextChunk);
				nextChunk=nextChunk.nextPhysical();
				if(nextChunk==null) throw new NullPointerException();//should never happen??
			}while(toClear>cleared);
			
			var neededFreeChunks=freeChunks.size()+toMove.size();
			if(freeChunks.ensureElementCapacity(neededFreeChunks)){
				defragment();
				return;
			}
			
			NumberSize size=NumberSize.bySize(source.getSize()+cleared);
			
			long needed=0;
			
			for(int i=0;i<fileList.size();i++){
				var p=fileList.getByIndex(i);
				needed+=new FilePointer(this, p.getLocalPath(), size, p.getStart()).length();
			}
			
			if(fileList.ensureCapacity(needed)){
				defragment();
				return;
			}
			
			List<Chunk> oldLocations=new ArrayList<>();
			
			LogUtil.println(toMove.stream().map(Chunk::getOffset));
			LogUtil.println(headerChunks.stream().map(Chunk::getOffset));
			
			for(Chunk chunk : toMove){
				LogUtil.printTable(chunk);
				
				var   siz     =chunk.getDataSize();
				Chunk newChunk=alocChunk(siz, NumberSize.bySize(siz));
				
				oldLocations.add(chunk.moveTo(newChunk));
				LogUtil.printTable(chunk);
//				LogUtil.println("ay");
			}
			LogUtil.println(headerChunks.stream().map(Chunk::getOffset));
			
			
		}
		
		
	}
	
	public List<Chunk> headerChunks(){
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
	
	public void notifyMovement(long oldOffset, Chunk newChunk) throws IOException{
		chunkCache.remove(oldOffset);
		
		for(int i=0;i<fileList.size();i++){
			FilePointer p=fileList.getByIndex(i);
			
			if(p.getStart()==oldOffset){
				fileList.setByIndex(i, new FilePointer(this, p.getLocalPath(), p.getStartSize().max(NumberSize.bySize(newChunk.getOffset())), newChunk.getOffset()));
				return;
			}
			
			var chunk=p.dereference();
			do{
				if(chunk.getNext()==oldOffset){
					try{
						chunk.setNext(newChunk.getOffset());
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

