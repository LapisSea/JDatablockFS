package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkLinkWModule;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.endpoint.IdentifierIO;
import com.lapissea.fsf.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.headermodule.modules.FileHeaderModule;
import com.lapissea.fsf.headermodule.modules.FileIDsModule;
import com.lapissea.fsf.headermodule.modules.FolderModule;
import com.lapissea.fsf.headermodule.modules.FreeChunksModule;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.ContentWriter;
import com.lapissea.fsf.io.FileData;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.fsf.NumberSize.*;
import static com.lapissea.util.UtilL.*;
import static java.util.stream.Collectors.*;

@SuppressWarnings({"AutoBoxing", "RedundantThrows"})
public class Header<Identifier>{
	
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
	
	private static final byte[]       MAGIC_BYTES     ="LSFSIF".getBytes(StandardCharsets.UTF_8);
	public static final  int          FILE_HEADER_SIZE=MAGIC_BYTES.length+2;
	public static final  boolean      LOG_ACTIONS     =false;
	public static final  ChunkPointer FIRST_POINTER   =new ChunkPointer(FILE_HEADER_SIZE);
	
	public static byte[] getMagicBytes(){
		return MAGIC_BYTES.clone();
	}
	
	private boolean safeAloc;
	
	private Region region;
	
	public final  IOInterface                       source;
	public final  FileSystemInFile.Config           config;
	public final  IdentifierIO<Identifier>          identifierIO;
	public final  List<HeaderModule<?, Identifier>> modules;
	private final Map<ChunkPointer, Chunk>          chunkCache;
	
	private Version version;
	
	private IOList<ChunkPointer> freeChunks;
	private IOList<FileEntry>    fileIds;
	private Folder<Identifier>   root;
	
	private final FileHeaderModule<Identifier> headModule;
	
	public Header(IOInterface source, IdentifierIO<Identifier> identifierIO, FileSystemInFile.Config config) throws IOException{
		this.identifierIO=identifierIO;
		this.source=source;
		this.config=config;
		
		headModule=new FileHeaderModule<>(this);
		
		modules=List.of(
			new FreeChunksModule<>(this, v->freeChunks=v),
			new FileIDsModule<>(this, v->fileIds=v),
			new FolderModule<>(this, f->root=f)
		               );
		
		chunkCache=config.newCacheMap();
		
		try{
			readHead();
		}catch(EOFException eof){
			try(var s=safeAlocSession()){
				initFile(source);
				readHead();
			}
		}
		
	}
	
	private void initFile(IOInterface dest) throws IOException{
		//new empty file
		version=Version.last();
		
		List<ChunkPointer> chunks=new ArrayList<>();
		
		try(var out=dest.write(false)){
			out.write(MAGIC_BYTES);
			out.write(version.major);
			out.write(version.minor);
			
			headModule.init();
			
			for(var module : modules){
				var chunksMade=module.init();
				Assert(chunksMade.size()==module.getOwningChunkCount());
				for(Chunk chunk : chunksMade){
					chunks.add(chunk.reference());
				}
			}
		}
		
		headModule.getList().addAll(chunks);
	}
	
	private void readHead() throws IOException{
		//noinspection MismatchedQueryAndUpdateOfCollection
		IOList<ChunkPointer> headerPointers;
		try(var in=source.read()){
			var mgb=new byte[MAGIC_BYTES.length];
			in.readFully(mgb, 0, mgb.length);
			if(!Arrays.equals(mgb, MAGIC_BYTES)){
				throw new IOException(new String(mgb, StandardCharsets.UTF_8)+": not a \"File System In File\" file or is corrupted");
			}
			
			var versionBytes=in.readNBytes(2);
			
			version=Arrays.stream(Version.values())
			              .filter(v->v.is(versionBytes))
			              .findAny()
			              .orElseThrow(()->new IOException("Invalid version number "+TextUtil.toString(versionBytes)));
			
			headerPointers=headModule.getList();
		}
		
		var iter=IntStream.range(0, headerPointers.size())
		                  .mapToObj(headerPointers::makeReference)
		                  .iterator();
		
		for(var module : modules){
			module.read(iter::next);
		}
		
		Assert(!iter.hasNext());
	}
	
	
	private Optional<Folder<Identifier>> getFolder(Identifier path) throws IOException{
		return root.cd(identifierIO, path);
	}
	
	public Optional<IOList.Ref<FileTag<Identifier>>> getFilePtrByPath(Identifier path) throws IOException{
		return root.cdFile(identifierIO, path);
	}
	
	public Optional<FileID> getByPath(Identifier path) throws IOException{
		return getFilePtrByPath(path).map(p->p.getUnchecked().getFileID().orElseThrow());
	}
	
	public IOList.Ref<FileTag<Identifier>> createFileTag(Identifier path) throws IOException{
		var existing=getFilePtrByPath(path);
		if(existing.isPresent()) return null;
		
		var split=identifierIO.splitLast(path);
		
		Optional<Folder<Identifier>> p=getFolder(split.trail);
		if(p.isEmpty()) return null;//throw new IOException("Failed to create file "+path+": no folder at "+split.trail);
		var parent=p.get();
		
		var list=parent.getFiles();
		var ptr =new FileTag<>(this, split.target, new FileID(0));
		
		list.addElement(ptr);
		var ref=list.makeReference(list.size()-1);
		if(DEBUG_VALIDATION){
			Assert(ref.get().equals(ptr));
		}
		
		if(LOG_ACTIONS) logChunkAction("FILE TAGGED", null);
		return ref;
	}
	
	public FileTag<Identifier> alocIDToTag(IOList.Ref<FileTag<Identifier>> ref, long initialSize) throws IOException{
		return ref.modify(tag->{
			if(DEBUG_VALIDATION){
				Assert(tag.getFileID().isEmpty());
			}
			var id=alocFileID(initialSize);
			return tag.withID(id);
		});
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
	
	public Chunk aloc(FileObject data, boolean allowNonOptimal) throws IOException{
		return aloc(data::write, allowNonOptimal);
	}
	
	public Chunk aloc(UnsafeConsumer<ContentWriter, IOException> writer, boolean allowNonOptimal) throws IOException{
		ByteArrayOutputStream buff=new ByteArrayOutputStream();
		writer.accept(new ContentOutputStream.Wrapp(buff));
		
		var c=aloc(buff.size(), allowNonOptimal);
		try(var dest=c.io().write(false)){
			buff.writeTo(dest);
		}
		return c;
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
			return alocFreeSplit(biggest, initialSize);
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
		var ptr     =freeChunk.reference();
		
		var space=freeChunk.wholeSize();
		
		
		var alocChunkSize  =Chunk.wholeSize(safeNext, initialCapacity);
		var freeRemaining  =space-alocChunkSize;
		var freeChunkHeader=freeChunk.getHeaderSize();
		
		if(freeRemaining<=freeChunkHeader) return alocFreeReuse(freeChunk);
		
		var freeChunkCapacity=freeRemaining-freeChunkHeader;
		
		Chunk alocChunk=new Chunk(this, ptr, safeNext, 0, initialCapacity);
		
		try{
			freeChunk.setCapacity(freeChunkCapacity);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		
		freeChunk.setOffset(ptr.getValue()+alocChunkSize);
		
		freeChunk.saveHeader();
		notifyMovement(ptr, freeChunk);
		alocChunk.saveHeader();
		
		alocChunk=getChunk(ptr);
		alocChunk.syncHeader();
		
		if(DEBUG_VALIDATION) validateFile();
		
		if(LOG_ACTIONS) logChunkAction("ALOC F SPLIT", alocChunk);
		return alocChunk;
	}
	
	private Chunk alocNew(NumberSize bodyType, long initialSize) throws IOException{
		Assert(initialSize>0);
		var chunk=initChunk(safeNextType(), bodyType, initialSize);
		if(DEBUG_VALIDATION) validateFile();
		if(LOG_ACTIONS) logChunkAction("ALOC", chunk);
		return chunk;
	}
	
	private Chunk initChunk(NumberSize nextType, NumberSize bodyType, long initialSize) throws IOException{
		
		var chunk=new Chunk(this, source.getSize(), nextType, 0, bodyType, initialSize);
		try(var out=source.write(chunk.getOffset(), true)){
			chunk.init(out);
		}
		
		putChunkInCache(chunk);
		
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
			
			var newCap=next.nextPhysicalOffset()-chunk.getDataStart();
			
			if(chunk.canSetCapacity(newCap)){
				freeChunks.removeElement(nextInd);
				try(var out=source.write(next.getOffset(), false)){
					Utils.zeroFill(out, next.wholeSize());
				}
				chunk.setCapacity(newCap);
				chunk.syncHeader();
			}else{
				return 0;
			}
			
			if(LOG_ACTIONS) logChunkAction("Merged free", chunk);
			
			return cap;
		}catch(BitDepthOutOfSpaceException e){
			return 0;
		}
	}
	
	private long fileEndGrowAction(Chunk chunk, long requestedMemory) throws IOException{
		if(DEBUG_VALIDATION) Assert(requestedMemory>0, chunk, requestedMemory);
		if(!chunk.isLastPhysical()) return 0;
		
		long       newCapacity=chunk.getCapacity()+requestedMemory;
		NumberSize bodyTyp    =chunk.getBodyType();
		
		if(bodyTyp.canFit(newCapacity)){
			try(var out=source.doRandom()){
				out.setPos(source.getSize());
				Utils.zeroFill(out::write, requestedMemory);
			}
			
			try{
				chunk.setCapacity(newCapacity);
			}catch(BitDepthOutOfSpaceException e){
				throw new ShouldNeverHappenError(e);
			}
			
			chunk.syncHeader();
		}else{
			var possibleGrowth=bodyTyp.maxSize-chunk.getCapacity();
			if(possibleGrowth==0) return 0;
			return fileEndGrowAction(chunk, possibleGrowth);
		}
		
		if(LOG_ACTIONS) logChunkAction("GROWTH", chunk);
		
		return requestedMemory;
	}
	
	private void mergeChunks(Chunk dest, Chunk toMerge) throws IOException, BitDepthOutOfSpaceException{
		dest.setCapacity(toMerge.nextPhysicalOffset()-dest.getDataStart());
		try(var out=source.write(toMerge.getOffset(), false)){
			Utils.zeroFill(out, toMerge.wholeSize());
		}
		dest.syncHeader();
	}
	
	private void rescueChainAction(Chunk chainStart, long requestedMemory) throws IOException{
		if(LOG_ACTIONS) logChunkAction("RESCUE", chainStart);
		
		byte[] oldData;
		long   oldCapacity;
		String oldChain;
		if(DEBUG_VALIDATION){
			Assert(findChainStart(chainStart)==chainStart, chainStart);
			oldChain=TextUtil.toString(chainStart.collectWholeChain());
			oldData=chainStart.io().readAll();
			oldCapacity=chainStart.io().getCapacity();

//			if(oldChain.equals("[8/8B@233, 0/1B@330, 0/5B@59, 0/6B@119]")){
//				int i=0;
//			}
		}
		
		rescueChain(chainStart, requestedMemory);
		
		if(DEBUG_VALIDATION){
			var expectedCapacity=oldCapacity+requestedMemory;
			var capacity        =chainStart.io().getCapacity();
			var newChain        =TextUtil.toString(chainStart.collectWholeChain());
			Assert(capacity >= expectedCapacity, capacity, expectedCapacity, requestedMemory,
			       "\n", oldChain,
			       "\n", newChain
			      );
			
			var newData=chainStart.io().readAll();
			
			if(!Arrays.equals(oldData, newData)){
				throw new AssertionError("rescue chain fail "+chainStart+"\n"+
				                         TextUtil.toString(oldData)+"\n"+
				                         TextUtil.toString(newData));
			}
		}
	}
	
	private void rescueChain(Chunk chainStart, long additionalSpace) throws IOException{
		
		var chain=chainStart.collectWholeChain();
		
		List<Chunk> toFree;
		
		find:
		{
			for(int i=chain.size()-1;i >= 0;i--){
				var chunk=chain.get(i);
				if(chunk.getNextType().canFit(source.getSize())){
					toFree=chain.subList(i+1, chain.size());
					break find;
				}
			}
			
			toFree=chain;
		}
		
		var first   =toFree.get(0);
		var newChunk=aloc(calcTotalCapacity(toFree)+additionalSpace, false);
		
		first.io().transferTo(newChunk.io());
		
		first.transparentChainRestart(newChunk, true);
		
	}
	
	private void sourcedChunkIterOne(String prevSource, Chunk chunk, BiConsumer<String, Chunk> consumer) throws IOException{
		var source=prevSource+" -> "+chunk;
		consumer.accept(source, chunk);
		if(chunk.hasNext()) sourcedChunkIterOne(source, chunk.nextChunk(), consumer);
	}

//	private void sourcedChunkIterFolder(BiConsumer<String, Chunk> consumer, Folder<Identifier> folder) throws IOException{
//
//		for(var pointer : fileList){
//			var ref=pointer.getFile().getFilePtr(this).dereference(this);
//			if(ref!=null) sourcedChunkIterOne("File("+pointer.getLocalPath()+")", ref, consumer);
//		}
//	}
	
	private void sourcedChunkIter(BiConsumer<String, Chunk> consumer) throws IOException{
//		sourcedChunkIterOne("freeChunks", freeChunks.getData(), consumer);
//		for(var val : freeChunks) sourcedChunkIterOne("Free chunk", val.dereference(this), consumer);
//
//		sourcedChunkIterFolder(consumer, root);
		
	}
	
	private void removeChunkInCache(ChunkPointer ptr){
		chunkCache.remove(ptr);
	}
	
	private void putChunkInCache(Chunk chunk){
		ChunkPointer key=chunk.reference();
		var          old=chunkCache.put(key, chunk);
		if(DEBUG_VALIDATION) Assert(old==null, old, chunk);
	}
	
	private Chunk getChunkInCache(ChunkPointer ptr){
		return chunkCache.get(ptr);
	}
	
	public void validateFile() throws IOException{
		
		for(var e : chunkCache.entrySet()){
			var cached=e.getValue();
			var off   =e.getKey().getValue();
			Assert(cached.getOffset()==off, cached.getOffset(), e.getKey());
			
			if(!cached.isDirty()){
				var read=readChunk(off);
				if(!read.equals(cached)){
					
					if(cached.lastMod!=null) cached.lastMod.printStackTrace();
					
					throw new AssertionError("Chunk cache mismatch\n"+
					                         TextUtil.toTable("cached / read", List.of(cached, read)));
				}
			}
		}
		
		if(root==null) return;
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
			
			var dup=nodes.stream()
			             .filter(r->r.chunk.equals(chunk))
			             .findAny();
			
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
		
		allChunkWalkerFlat(true, s->{
			var cit=s.iterator();
			while(cit.hasNext()){
				var c=cit.next();
				Assert(c.nextPhysicalOffset()<=source.getSize(), c);
			}
		});
		
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
					chunkCache.remove(chunk.reference());
				}
				return;
			}
			if(ff) return;
			
			normalFreeAction(chunk);
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
		var ptr=chunk.reference();
		source.setCapacity(ptr.getValue());
		removeChunkInCache(ptr);
		
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
				Chunk prev=getChunk(val);
				var   next=prev.nextPhysicalOffset();
				if(next==offset){
					previous=prev;
					break find;
				}
			}
			return false;
		}
		
		chunkToMerge.syncHeader();
		
		try{
			previous.setCapacity(chunkToMerge.nextPhysicalOffset()-previous.getDataStart());
		}catch(BitDepthOutOfSpaceException e){
			throw new NotImplementedException(e);//TODO: reformat header be able to merge
		}
		previous.syncHeader();
		
		removeChunkInCache(chunkToMerge.reference());
		chunkToMerge.nukeChunk();
		
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
		chunk.syncHeader();
		freeChunks.addElement(ptr);
		if(LOG_ACTIONS) logChunkAction("FREED", chunk);
	}
	
	private Chunk readChunk(long offset) throws IOException{
		if(DEBUG_VALIDATION) Assert(offset >= FILE_HEADER_SIZE, offset);
		return Chunk.read(this, offset);
	}
	
	
	public Chunk getByOffsetCached(ChunkPointer ptr){
		return getChunkInCache(ptr);
	}
	
	
	public Chunk getChunkUnchecked(ChunkPointer ptr){
		try{
			return getChunk(ptr);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public synchronized Chunk getChunk(ChunkPointer ptr) throws IOException{
		if(ptr==null) return null;
		
		var cached=getChunkInCache(ptr);
		if(cached!=null) return cached;
		
		var read=readChunk(ptr.getValue());
		putChunkInCache(read);
		
		return read;
	}
	
	public Stream<FileTag<Identifier>> listFiles(){
		return root.filesDeep();
	}
	
	public void allChunkLinkWalkerFlat(boolean fakeFileHeadModule, UnsafeConsumer<Stream<ChunkLink>, IOException> stream) throws IOException{
		try(var s=allChunkLinkWalkerFlat(fakeFileHeadModule)){
			stream.accept(s);
		}
	}
	
	public Stream<ChunkLink> allChunkLinkWalkerFlat(boolean fakeFileHeadModule) throws IOException{
		return allChunkWalker(fakeFileHeadModule).flatMap(PairM::get2)
		                                         .flatMap(s->s);
	}
	
	public void allChunkWalkerFlat(boolean fakeFileHeadModule, UnsafeConsumer<Stream<Chunk>, IOException> stream) throws IOException{
		try(var s=allChunkWalkerFlat(fakeFileHeadModule)){
			stream.accept(s);
		}
	}
	
	public Stream<Chunk> allChunkWalkerFlat(boolean fakeFileHeadModule) throws IOException{
		return allChunkLinkWalkerFlat(fakeFileHeadModule)
			       .filter(ChunkLink::isSourceValidChunk)
			       .map(ChunkLink::sourceReference)
			       .map(this::getChunkUnchecked);
	}
	
	public interface ChunkLinkIter<Identifier>{
		
		default boolean consumeUnchecked(HeaderModule<?, Identifier> module, ChunkLink link){
			try{
				return consume(module, link);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		
		/**
		 * @return should stop iterating
		 */
		boolean consume(HeaderModule<?, Identifier> module, ChunkLink link) throws IOException;
	}
	
	public Optional<ChunkLinkWModule<Identifier>> chunkFinder(boolean fakeFileHeadModule, ChunkLinkIter<Identifier> consumer) throws IOException{
		return chunkFinder(fakeFileHeadModule, m->true, consumer);
	}
	
	public Optional<ChunkLinkWModule<Identifier>> chunkFinder(boolean fakeFileHeadModule, Predicate<HeaderModule<?, Identifier>> moduleFilter, ChunkLinkIter<Identifier> consumer) throws IOException{
		try(var s=allChunkWalker(fakeFileHeadModule)
			          .filter(pair->moduleFilter.test(pair.obj1))
			          .flatMap(pair->{
				          var module=pair.obj1;
				          return pair.obj2.flatMap(iter->iter)
				                          .filter(link->consumer.consumeUnchecked(module, link))
				                          .map(link->new ChunkLinkWModule<>(link, module));
				
			          })){
			return s.findAny();
		}finally{
			System.gc();
		}
	}
	
	public Map<HeaderModule<?, Identifier>, List<List<ChunkLink>>> collectAllChunks(boolean fakeFileHeadModule) throws IOException{
		Map<HeaderModule<?, Identifier>, List<List<ChunkLink>>> moduleChunks=new LinkedHashMap<>(modules.size());
		allChunkWalker(fakeFileHeadModule, stream->{
			stream.forEach(pair->{
				List<List<ChunkLink>> d;
				try(var s=pair.obj2){
					d=s.map(iter->{
						try(iter){
							return iter.collect(toList());
						}
					}).collect(toList());
				}
				moduleChunks.put(pair.obj1, d);
			});
		});
		return moduleChunks;
	}
	
	public void allChunkWalker(boolean fakeFileHeadModule, UnsafeConsumer<Stream<PairM<HeaderModule<?, Identifier>, Stream<Stream<ChunkLink>>>>, IOException> stream) throws IOException{
		try(var walker=allChunkWalker(fakeFileHeadModule)){
			stream.accept(walker);
		}
	}
	
	public Stream<PairM<HeaderModule<?, Identifier>, Stream<Stream<ChunkLink>>>> allChunkWalker(boolean fakeFileHeadModule) throws IOException{
		List<HeaderModule<?, Identifier>> mds;
		
		if(fakeFileHeadModule){
			mds=new ArrayList<>(modules.size()+1);
			mds.addAll(modules);
			mds.add(headModule);
		}else{
			mds=modules;
		}
		
		List<Runnable> onClose=new ArrayList<>();
		Stream<PairM<HeaderModule<?, Identifier>, Stream<Stream<ChunkLink>>>> ss=mds.stream().map(module->{
			var s=module.openChainStreamUnchecked();
			onClose.add(s::close);
			return new PairM<>(module, s);
		});
		return ss.onClose(()->onClose.forEach(Runnable::run));
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
		return getChunk(FIRST_POINTER);
	}
	
	private Optional<ChunkLinkWModule<Identifier>> findDependency(ChunkPointer ptr, HeaderModule<?, Identifier> module) throws IOException{
		return chunkFinder(true, m->module==null||module==m, (m, link)->link.hasPointer()&&link.getPointer().equals(ptr));
	}
	
	private Chunk findChainStart(Chunk chunk) throws IOException{
		return findChainStart(new ChunkLinkWModule<>(new ChunkLink(chunk), null));
	}
	
	private Chunk findChainStart(ChunkLinkWModule<Identifier> chunk) throws IOException{
		Optional<ChunkLinkWModule<Identifier>> matchLink=findDependency(new ChunkPointer(chunk.getLink().sourcePos), chunk.getModule());
		
		if(matchLink.isEmpty()) return null;
		var previous=matchLink.get();
		
		var root=findChainStart(previous);
		if(root!=null) return root;
		return getChunk(chunk.getLink().sourceReference());
	}
	
	private List<Chunk> prevDetectingMove(Chunk chunk) throws IOException{
		
		var ch=findChainStart(chunk);
		if(ch==null) return List.of();
		
		var chain=ch.collectWholeChain();
		
		var totalSize=calcTotalSize(chain);
		if(totalSize==0){
			totalSize=config.minimumChunkSize;
		}
		
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
			
			var oldPtr=chunk.reference();
			
			chunk.moveTo(newChunk);
			
			chunk.setSize(siz);
			chunk.chainForwardFree();
			
			var old=getChunk(oldPtr);
			
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
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof Header<?> header&&
		       version==header.version&&
		       source.equals(header.source);
	}
	
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+version.hashCode();
		result=31*result+source.hashCode();
		
		return result;
	}
	
	public void notifyMovement(ChunkPointer oldPtr, Chunk newOffset) throws IOException{
		if(LOG_ACTIONS){
			LogUtil.printTable("Chunk action", "MOVED", "from", oldPtr, "to", newOffset.getOffset());
		}
		
		var newPtr=newOffset.reference();
		
		ChunkLink link=findDependency(oldPtr, null)
			               .map(ChunkLinkWModule::getLink)
			               .orElseThrow(()->new NoSuchElementException("No dependency for: "+oldPtr));
		
		link.requireModifiable();
		
		removeChunkInCache(oldPtr);
		removeChunkInCache(newPtr);
		putChunkInCache(newOffset);
		
		link.setPointer(newPtr);
		
		if(DEBUG_VALIDATION) validateFile();
	}
	
	public void deleteFile(Identifier localPath) throws IOException{
		var fileRef=getFilePtrByPath(localPath).orElseThrow();
		
		FileTag<Identifier> filePtr=fileRef.get();
		fileRef.delete();
		
		var ptr=getFileData(filePtr)
			        .map(FileData::getPointer)
			        .orElse(null);
		if(ptr!=null) freeChunkChain(ptr.dereference(this));
	}
	
	public boolean rename(Identifier id, Identifier newId) throws IOException{
		var find=getFilePtrByPath(id);
		if(find.isEmpty()) return false;
		
		var fileRef=find.get();
		
		var split   =identifierIO.splitLast(id);
		var newSplit=identifierIO.splitLast(newId);
		
		//only rename file
		if(split.trail.equals(newSplit.trail)){
			fileRef.set(fileRef.get().withName(newSplit.target));
			return true;
		}
		
		var newParentFind=getFolder(newSplit.trail);
		if(newParentFind.isEmpty()) return false;
		var newParent=newParentFind.get();
		
		var files=newParent.getFiles();
		
		int ptrIndex=files.findIndex(comp->comp.getPath().equals(id));
		//can't move file to folder that already has it
		if(ptrIndex!=-1) return false;
		
		var file=fileRef.get();
		fileRef.delete();
		files.addElement(file);
		return true;
	}
	
	@Override
	public String toString(){
		return "Header{version="+version+'}';
	}
	
	public Optional<FileData> getFileData(FileTag<?> ptr){
		return ptr.getFileID().flatMap(this::getFileData);
	}
	
	public Optional<IOList.Ref<FileEntry>> findFileEntry(FileID fileID){
		return IntStream.range(0, fileIds.size())
		                .filter(i->fileIds.get(i).getId().equals(fileID))
		                .mapToObj(fileIds::makeReference)
		                .findAny();
	}
	
	public Optional<FileData> getFileData(FileID fileID){
		return findFileEntry(fileID).map(ref->new FileData(this, ref));
	}
	
	public void alocEntryData(IOList.Ref<FileEntry> entry, long initialCapacity) throws IOException{
		entry.modify(val->{
			var ref=aloc(initialCapacity, true).reference();
			return val.withData(ref);
		});
	}
	
	private FileID alocFileID(long initialCapacity) throws IOException{
		FileID id=new FileID(fileIds.stream().mapToLong(e->e.getId().getValue()).max().orElse(0)+1);
		
		fileIds.addElement(new FileEntry(id));
		
		if(initialCapacity>0) alocEntryData(fileIds.makeReference(fileIds.size()-1), initialCapacity);
		
		if(LOG_ACTIONS) logChunkAction("NEW FILE", null);
		return id;
	}
	
	public void defragment(){
	
	}
}

