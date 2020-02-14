package com.lapissea.fsf.chunk;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.fsf.exceptions.MalformedFileException;
import com.lapissea.fsf.flags.FlagReader;
import com.lapissea.fsf.flags.FlagWriter;
import com.lapissea.fsf.io.ContentBuffer;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

/*
 *   1            2
 * 3   4                     5
 * |---|---------------------|
 *
 * 1. header
 * 2. body
 * 3. offset (physical start)
 * 4. data start
 * 5. data end (physical end & next physical chunk)
 *
 * Header contains:
 * - flags defining header value sizes and chunk properties
 * - body size (number of bytes chunk can store in body)
 * - used (number of bytes that have user written data)
 * - next (offset to next chunk in chain)
 *
 * */
@SuppressWarnings("AutoBoxing")
public class Chunk{
	
	public static int headerSize(long fileSize, long chunkSize)      { return headerSize(NumberSize.bySize(fileSize), NumberSize.bySize(chunkSize)); }
	
	public static int headerSize(NumberSize nextType, long chunkSize){ return headerSize(nextType, NumberSize.bySize(chunkSize)); }
	
	public static int headerSize(NumberSize nextType, NumberSize bodyType){
		return FLAGS_SIZE.bytes+
		       nextType.bytes+
		       bodyType.bytes*2;
	}
	
	public static long wholeSize(long fileSize, long capacity)      { return wholeSize(NumberSize.bySize(fileSize), capacity); }
	
	public static long wholeSize(NumberSize nextType, long capacity){ return wholeSize(nextType, NumberSize.bySize(capacity), capacity); }
	
	public static long wholeSize(NumberSize nextType, NumberSize bodyType, long capacity){
		return headerSize(nextType, bodyType)+capacity;
	}
	
	public static void init(ContentOutputStream out, NumberSize nextType, int bodySize) throws IOException{ init(out, nextType, bodySize, null); }
	
	public static void init(ContentOutputStream out, NumberSize nextType, long bodySize, UnsafeConsumer<ContentOutputStream, IOException> initContents) throws IOException{
		
		ByteArrayOutputStream ba=null;
		if(initContents!=null){
			ba=new ByteArrayOutputStream();
			ContentOutputStream contents=new ContentOutputStream.Wrapp(ba);
			initContents.accept(contents);
			if(ba.size()>bodySize) bodySize=ba.size();
		}
		
		var c=new Chunk(null, Header.FILE_HEADER_SIZE, nextType, 0, bodySize);
		
		c.init(out, ba==null?null:ba.toByteArray());
		
	}
	
	private static final NumberSize FLAGS_SIZE=NumberSize.SHORT;
	
	
	public static Chunk read(Header header, long offset, ContentInputStream in) throws IOException{
		var flagData=FLAGS_SIZE.read(in);
		var flags   =new FlagReader(flagData, FLAGS_SIZE);

//		var flags=FlagReader.read(in, FLAGS_SIZE);
		
		var nextType=flags.readEnum(NumberSize.class);
		var bodyType=flags.readEnum(NumberSize.class);
		
		var chunkUsed=flags.readBoolBit();
		
		if(DEBUG_VALIDATION){
			if(!flags.checkRestAllOne()){
				var siz=FLAGS_SIZE.bytes*Byte.SIZE;
				var d  =new StringBuilder(siz);
				d.append(Long.toBinaryString(flagData));
				while(d.length()<siz) d.insert(0, "0");
				throw new AssertionError(TextUtil.toString("Invalid chunk header", new ChunkPointer(offset), d.toString()));
			}
		}
		
		long next    =requirePositive(nextType.read(in));
		long used    =requirePositive(bodyType.read(in));
		long dataSize=requirePositive(bodyType.read(in));
		
		return new Chunk(header, offset, nextType, next, bodyType, used, dataSize, chunkUsed);
	}
	
	public static Chunk read(Header header, long offset) throws IOException{
		try(ContentInputStream in=header.source.read(offset)){
			return read(header, offset, in);
		}
	}
	
	public void saveHeader(ContentOutputStream os) throws IOException{
		if(headerWriteCache==null) headerWriteCache=new ContentBuffer(getHeaderSize());
		try(var buff=headerWriteCache.session(os)){
			
			var flags=new FlagWriter(FLAGS_SIZE);
			
			flags.writeEnum(nextType);
			flags.writeEnum(bodyType);
			
			flags.writeBoolBit(isUsed());
			
			flags.fillRestAllOne();
			
			flags.export(buff);
			
			nextType.write(buff, getNext());
			bodyType.write(buff, getSize());
			bodyType.write(buff, getCapacity());
			
			if(DEBUG_VALIDATION){
				Assert(getHeaderSize()==buff.size(), getHeaderSize(), buff.size(), this);
			}
		}
		
	}
	
	private static long requirePositive(long val) throws MalformedFileException{
		return requireGreaterOrEqual(val, 0);
	}
	
	private static long requireLesserOrEqual(long val, long limit) throws MalformedFileException{
		if(val>limit) throw new MalformedFileException(val+">"+limit);
		return val;
	}
	
	private static long requireGreaterOrEqual(long val, long limit) throws MalformedFileException{
		if(val<limit) throw new MalformedFileException(val+"<"+limit);
		return val;
	}
	
	private static int counter;
	
	
	public final transient Header header;
	
	private boolean used;
	private boolean dirty;
	
	private long offset;
	
	private NumberSize nextType;
	private long       next;
	
	private NumberSize bodyType;
	private long       size;
	private long       capacity;
	
	private ChunkIO io;
	
	private ContentBuffer headerWriteCache;
	
	private ChunkPointer referenceCache;
	
	public transient       Throwable lastMod;
	public final transient Throwable madeAt=new Throwable((counter++)+" "+LocalDateTime.now().toString());
	
	public transient List<Runnable> dependencyInvalidate=new ArrayList<>();
	
	public Chunk(Header header, ChunkPointer ptr, NumberSize nextType, long next, long capacity) throws MalformedFileException{
		this(header, ptr.getValue(), nextType, next, capacity);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, long capacity) throws MalformedFileException{
		this(header, offset, nextType, next, NumberSize.bySize(capacity), capacity);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long capacity) throws MalformedFileException{
		this(header, offset, nextType, next, bodyType, 0, capacity, true);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long size, long capacity, boolean used) throws MalformedFileException{
		
		this.header=header;
		
		setOffset(offset);
		
		this.nextType=Objects.requireNonNull(nextType);
		this.next=next==0?0:requireGreaterOrEqual(next, Header.FILE_HEADER_SIZE);
		
		this.bodyType=bodyType.requireNonVoid();
		
		this.capacity=requireGreaterOrEqual(capacity, 1);
		
		this.size=requireLesserOrEqual(size, capacity);
		
		this.used=used;
		
	}
	
	private void markDirty(){
		dirty=true;
		lastMod=new Throwable(this.toString());
	}
	
	public boolean hasNext(){
		return getNext()!=0;
	}
	
	public Chunk nextChunk() throws IOException{
		if(!hasNext()) return null;
		return header.getChunk(new ChunkPointer(getNext()));
	}
	
	public ChunkIO io(){
		if(io==null) io=new ChunkIO(this);
		return io;
	}
	
	
	public long getDataStart(){
		return getOffset()+getHeaderSize();
	}
	
	public boolean isLastPhysical() throws IOException{
		return nextPhysicalOffset()==header.source.getSize();
	}
	
	public long nextPhysicalOffset(){
		return getDataStart()+getCapacity();
	}
	
	public Chunk nextPhysical() throws IOException{
		if(isLastPhysical()) return null;
		
		return header.getChunk(new ChunkPointer(nextPhysicalOffset()));
	}
	
	public long wholeSize(){
		return wholeSize(nextType, bodyType);
	}
	
	public long wholeSize(long fileSize){
		return wholeSize(NumberSize.bySize(fileSize), bodyType);
	}
	
	public long wholeSize(NumberSize nextType, NumberSize bodyType){
		return wholeSize(nextType, bodyType, getCapacity());
	}
	
	public int getHeaderSize(){
		return headerSize(nextType, bodyType);
	}
	
	public void pushSize(long offset){
		if(offset>getSize()){
			setSize(offset);
		}
	}
	
	public void setSize(long size){
		if(this.size==size) return;
		
		if(size>getCapacity()) throw new IndexOutOfBoundsException(size+" > "+getCapacity());
		this.size=size;
		markDirty();
	}
	
	public void clearNext(){
		try{
			setNext(0);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	public void setNext(Chunk next) throws BitDepthOutOfSpaceException{
		setNext(next.getOffset());
	}
	
	public void setNext(ChunkPointer next) throws BitDepthOutOfSpaceException{
		setNext(next.getValue());
	}
	
	public void setNext(long next) throws BitDepthOutOfSpaceException{
		if(this.next==next) return;
		
		if(DEBUG_VALIDATION) Assert(getNextType()!=NumberSize.VOID);
		
		getNextType().ensureCanFit(next);
		this.next=next;
		markDirty();
		
		notifyDependency();
	}
	
	public void setCapacity(long capacity) throws BitDepthOutOfSpaceException{
		if(this.capacity==capacity) return;
		
		getBodyType().ensureCanFit(capacity);
		Assert(capacity>0);
		
		this.capacity=capacity;
		markDirty();
		notifyDependency();
	}
	
	public boolean isUsed(){
		return used;
	}
	
	public void setUsed(boolean used){
		if(this.used==used) return;
		
		this.used=used;
		markDirty();
	}
	
	public long getOffset(){
		return offset;
	}
	
	public void setOffset(long offset){
		if(this.offset==offset) return;
		this.offset=offset;
		referenceCache=null;
	}
	
	
	public long getSize(){
		return size;
	}
	
	public long getNext(){
		return next;
	}
	
	public long getCapacity(){
		return capacity;
	}
	
	public NumberSize getBodyType(){
		return bodyType;
	}
	
	public NumberSize getNextType(){
		return nextType;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Chunk)) return false;
		return equals((Chunk)o);
	}
	
	public boolean equals(Chunk chunk){
		return chunk!=null&&
		       getOffset()==chunk.getOffset()&&
		       getNext()==chunk.getNext()&&
		       getCapacity()==chunk.getCapacity()&&
		       getSize()==chunk.getSize()&&
		       getBodyType()==chunk.getBodyType()&&
		       getNextType()==chunk.getNextType()&&
		       Objects.equals(header, chunk.header);
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getOffset());
	}
	
	//////////////////////////////////////////////////////////////////
	
	public void syncHeader() throws IOException{
		if(dirty){
			saveHeader();
		}
	}
	
	public void saveHeader() throws IOException{
		dirty=false;
		try(var out=header.source.write(getOffset(), false)){
			saveHeader(out);
		}
		
		if(DEBUG_VALIDATION){
			var read=read(header, offset);
			Assert(equals(read), this, read);
		}
	}
	
	
	public void init(ContentOutputStream os) throws IOException{
		init(os, null);
	}
	
	public void init(ContentOutputStream os, byte[] initialData) throws IOException{
		if(initialData!=null){
			setSize(initialData.length);
		}
		
		saveHeader(os);
		
		var toZeroOut=getCapacity();
		if(initialData!=null){
			os.write(initialData);
			toZeroOut-=initialData.length;
		}
		Utils.zeroFill(os, toZeroOut);
	}
	
	public void chainForwardFree() throws IOException{
		if(!hasNext()){
			syncHeader();
			return;
		}
		
		var chunk=nextChunk();
		clearNext();
		syncHeader();
		header.freeChunkChain(chunk);
	}
	
	public List<Chunk> collectWholeChain() throws IOException{
		if(!hasNext()){
			if(DEBUG_VALIDATION) checkCaching();
			return List.of(this);
		}
		
		List<Chunk> chain=new LinkedList<>();
		walkOverWholeChain(chain::add);
		return List.copyOf(chain);
	}
	
	public void calculateWholeChainSizes(long[] totalSize, long[] totalCapacity) throws IOException{
		walkOverWholeChain(chunk->{
			totalSize[0]+=chunk.getSize();
			totalCapacity[0]+=chunk.getCapacity();
		});
	}
	
	public void shit(){
		List<StringBuilder> sbs=shit(new StringBuilder());
	}
	
	public <T> List<T> shit(T dummyT){
		List<T> ts=new ArrayList<>();
		for(int i=0;i<10;i++){
			try{
				ts.add(((Class<T>)dummyT.getClass()).getConstructor().newInstance());
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		return ts;
	}
	
	@NotNull
	public Chunk findFirstInChain(@NotNull Predicate<Chunk> check) throws IOException{
		Objects.requireNonNull(check);
		
		Chunk[] result={null};
		walkOverChain(chunk->{
			if(check.test(chunk)){
				result[0]=chunk;
				return true;
			}
			return false;
		});
		return result[0];
	}
	
	@Nullable
	public Chunk findLastInChain(@NotNull Predicate<Chunk> check) throws IOException{
		Objects.requireNonNull(check);
		
		Chunk[] result={null};
		walkOverWholeChain(chunk->{
			if(check.test(chunk)) result[0]=chunk;
		});
		return result[0];
	}
	
	
	public void walkOverWholeChain(@NotNull Consumer<Chunk> consumer) throws IOException{
		Objects.requireNonNull(consumer);
		
		walkOverChain(chunk->{
			consumer.accept(chunk);
			return false;
		});
	}
	
	public void walkOverChain(@NotNull Predicate<Chunk> walker) throws IOException{
		Objects.requireNonNull(walker);
		
		Chunk link=this;
		while(true){
			if(DEBUG_VALIDATION) link.checkCaching();
			
			if(walker.test(link)) return;
			
			var next=link.nextChunk();
			if(next==null) return;
			link=next;
		}
	}
	
	public boolean overlaps(Chunk other){
		return overlaps(other.getOffset(), other.nextPhysicalOffset());
	}
	
	public boolean overlaps(long start, long end){
		Assert(start<end);
		
		long thisStart=getOffset(), thisEnd=nextPhysicalOffset();
		
		if(start<=thisStart){
			return end>thisStart;
		}
		
		return start<thisEnd;
	}
	
	public void moveToAndFreeOld(Chunk newChunk) throws IOException{
		
		if(DEBUG_VALIDATION) header.validateFile();
		
		var oldPtr=reference();
		
		moveTo(newChunk);
		
		var old=header.getChunk(oldPtr);

//		old.clearNext();
//		old.setSize(0);
//		old.syncHeader();
		
		header.freeChunk(old);
		
		if(DEBUG_VALIDATION) header.validateFile();
	}
	
	public void transparentChainRestart(Chunk newChunk) throws IOException{
		transparentChainRestart(newChunk, false);
	}
	
	public void transparentChainRestart(Chunk newChunk, boolean freeOld) throws IOException{
		
		if(DEBUG_VALIDATION) header.validateFile();
		
		var oldPtr=reference();
		
		setOffset(newChunk.offset);
		nextType=newChunk.nextType;
		bodyType=newChunk.bodyType;
		capacity=newChunk.capacity;
		size=newChunk.size;
		next=newChunk.next;
		
		
		notifyDependency();
		
		header.notifyMovement(oldPtr, this);
		
		var old=header.getChunk(oldPtr);
		old.io().setSize(0);
		
		if(freeOld){
			header.freeChunkChain(old);
		}else{
			Chunk last=findFirstInChain(chunk->!chunk.hasNext());
			
			try{
				last.setNext(old);
				last.syncHeader();
			}catch(BitDepthOutOfSpaceException e){
				throw new NotImplementedException();//TODO
			}
		}
		
		if(DEBUG_VALIDATION) header.validateFile();
	}
	
	public void moveTo(Chunk newChunk) throws IOException{
		var oldPtr=reference();
		
		byte[] oldData;
		String oldChunk;
		
		if(DEBUG_VALIDATION){
			Assert(isUsed(), this);
			header.validateFile();
			Assert(!this.equals(newChunk), "Trying to move chunk into itself", this, newChunk);
			Assert(!overlaps(newChunk.getOffset(), newChunk.getOffset()+newChunk.wholeSize()), "Overlapping chunks", this, newChunk);
			Assert(getSize()<=newChunk.getCapacity(), getSize()+" can't fit in to "+newChunk.getCapacity());
			
			oldData=io().readAll();
			oldChunk=TextUtil.toString(this);
		}
		
		try(var out=newChunk.io().write(false)){
			try(var in=this.io().read()){
				var buf=new byte[(int)Math.min(2048, getSize())];
				
				var remaining=getSize();
				
				while(remaining>0){
					int read=in.read(buf, 0, (int)Math.min(remaining, buf.length));
					out.write(buf, 0, read);
					remaining-=read;
				}
			}
		}
		
		io=null;
		headerWriteCache=null;
		
		setOffset(newChunk.offset);
		nextType=newChunk.nextType;
		bodyType=newChunk.bodyType;
		capacity=newChunk.capacity;
		markDirty();
		
		try{
			newChunk.setNext(next);
			newChunk.setSize(size);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		
		
		saveHeader();
		newChunk.saveHeader();
		
		notifyDependency();
		
		header.notifyMovement(oldPtr, this);
		
		if(DEBUG_VALIDATION){
			checkCaching();
			var old=header.getByOffsetCached(oldPtr);
			
			var newData=io().readAll();
			if(!Arrays.equals(oldData, newData)){
				throw new AssertionError(TextUtil.toString(collectWholeChain())+"\n"+
				                         oldChunk+"\n"+
				                         TextUtil.toString(this)+"\n"+
				                         TextUtil.toString(oldData)+"\n"+
				                         TextUtil.toString(newData)+"\n"+
				                         TextUtil.toTable(oldData, newData));
			}
			
			header.validateFile();
		}
	}
	
	public void notifyDependency(){
		for(Runnable runnable : dependencyInvalidate.toArray(Runnable[]::new)){
			runnable.run();
		}
	}
	
	@Override
	public String toString(){
		final StringBuilder sb=new StringBuilder("Chunk{");
		
		if(!isUsed()) sb.append("free, ");
		if(isDirty()) sb.append("dirty, ");
		
		sb.append("[")
		  .append(getSize())
		  .append("/")
		  .append(getCapacity()).append(getBodyType().shortName)
		  .append("]");
		
		sb.append(", @").append(getOffset());
		
		if(hasNext()) sb.append(", next: ").append(getNext()).append(getNextType().shortName);
		
		sb.append('}');
		return sb.toString();
	}
	
	public String toShortString(){
		StringBuilder b=new StringBuilder();
		if(!isUsed()) b.append("free ");
		b.append(getSize());
		b.append("/");
		b.append(getCapacity()).append(getBodyType().shortName);
		b.append("@").append(getOffset());
		return b.toString();
	}
	
	public String toTableString(){
		StringBuilder b=new StringBuilder();
		if(!isUsed()) b.append("free ");
		if(isDirty()) b.append("dirty ");
		
		b.append(getSize());
		b.append("/");
		b.append(getCapacity()).append(getBodyType().shortName);
		b.append("@").append(getOffset());
		
		if(hasNext()) b.append(" -> @").append(getNext()).append(getNextType().shortName);
		return b.toString();
	}
	
	public boolean isDirty(){
		return dirty;
	}
	
	public void checkCaching() throws IOException{
		var cached=header.getChunk(reference());
		if(cached!=this){
			Function<Chunk, String> toStr=ch->ch+" "+System.identityHashCode(ch)+"\n"+
			                                  ch.madeAt.toString()+"\n"+
			                                  Arrays.stream(ch.madeAt.getStackTrace())
			                                        .map(Objects::toString)
			                                        .collect(Collectors.joining("\n"));
			
			throw new AssertionError("\n"+
			                         toStr.apply(this)+"\n\n"+
			                         toStr.apply(cached)+"\n"+
			                         TextUtil.toTable(this, cached)
			);
		}
	}
	
	public void nukeChunk() throws IOException{
		int siz;
//		if(DEBUG_VALIDATION) siz=Math.toIntExact(wholeSize());
//		else
		siz=FLAGS_SIZE.bytes;
		
		header.source.write(getOffset(), false, new byte[siz]);
	}
	
	public ChunkPointer reference(){
		if(referenceCache==null) referenceCache=new ChunkPointer(this);
		return referenceCache;
	}
	
	public ChunkLink link(){
		return new ChunkLink(this);
	}
	
	public void clearSize() throws IOException{
		setSize(0);
		syncHeader();
	}
}
