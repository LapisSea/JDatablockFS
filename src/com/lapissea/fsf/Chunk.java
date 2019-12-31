package com.lapissea.fsf;

import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;

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
public class Chunk{
	
	public static int headerSize(long fileSize, long chunkSize){
		return headerSize(NumberSize.bySize(fileSize), NumberSize.bySize(chunkSize));
	}
	
	public static int headerSize(NumberSize nextType, long chunkSize){
		return headerSize(nextType, NumberSize.bySize(chunkSize));
	}
	
	public static int headerSize(NumberSize nextType, NumberSize bodyType){
		return FLAGS_SIZE.bytes+
		       nextType.bytes+
		       bodyType.bytes*2;
	}
	
	public static long wholeSize(long fileSize, long capacity){
		return wholeSize(NumberSize.bySize(fileSize), capacity);
	}
	
	public static long wholeSize(NumberSize nextType, long capacity){
		return wholeSize(nextType, NumberSize.bySize(capacity), capacity);
	}
	
	public static long wholeSize(NumberSize nextType, NumberSize bodyType, long capacity){
		return headerSize(nextType, bodyType)+capacity;
	}
	
	public static void init(ContentOutputStream out, NumberSize nextType, int bodySize) throws IOException{
		init(out, nextType, bodySize, null);
	}
	
	public static void init(ContentOutputStream out, NumberSize nextType, long bodySize, UnsafeConsumer<ContentOutputStream, IOException> initContents) throws IOException{
		
		ByteArrayOutputStream ba=null;
		if(initContents!=null){
			ba=new ByteArrayOutputStream();
			ContentOutputStream contents=new ContentOutputStream.Wrapp(ba);
			initContents.accept(contents);
			if(ba.size()>bodySize) bodySize=ba.size();
		}
		
		var c=new Chunk(null, 0, nextType, 0, bodySize);
		
		c.init(out, ba==null?null:ba.toByteArray());
		
	}
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	
	public static Chunk read(Header header, long offset, ContentInputStream in) throws IOException{
		var flags=FlagReader.read(in, FLAGS_SIZE);
		
		var nextType=flags.readEnum(NumberSize.class);
		var bodyType=flags.readEnum(NumberSize.class);
		
		var chunkUsed=flags.readBoolBit();
		
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
	
	private static class WriteNode{
		final ByteArrayOutputStream bb;
		final ContentOutputStream   buff;
		
		private WriteNode(ByteArrayOutputStream bb, ContentOutputStream buff){
			this.bb=bb;
			this.buff=buff;
		}
	}
	
	private WeakReference<WriteNode> headerWriteCache=new WeakReference<>(null);
	
	public void saveHeader(ContentOutputStream os) throws IOException{
		var c=headerWriteCache.get();
		if(c==null){
			var bb=new ByteArrayOutputStream(getHeaderSize());
			headerWriteCache=new WeakReference<>(c=new WriteNode(bb, new ContentOutputStream.Wrapp(bb)));
		}
		
		var buff=c.buff;
		
		var flags=new FlagWriter(FLAGS_SIZE);
		
		flags.writeEnum(nextType);
		flags.writeEnum(bodyType);
		
		flags.writeBoolBit(isUsed());
		
		flags.export(buff);
		
		nextType.write(buff, getNext());
		bodyType.write(buff, getSize());
		bodyType.write(buff, getCapacity());
		
		var bb=c.bb;
		if(DEBUG_VALIDATION){
			Assert(getHeaderSize()==bb.size(), getHeaderSize(), bb.size(), this);
		}
		bb.writeTo(os);
		bb.reset();
	}
	
	private static long requirePositive(long val) throws IOException{
		return requireGreaterOrEqual(val, 0);
	}
	
	private static long requireGreaterOrEqual(long val, long limit) throws IOException{
		if(val<0){
			throw new IOException("Malformed file");
		}
		return val;
	}
	
	final Header header;
	
	private boolean used;
	private boolean dirty;
	
	private long offset;
	
	private NumberSize nextType;
	private long       next;
	
	private NumberSize bodyType;
	private long       size;
	private long       capacity;
	
	private ChunkIO io;
	
	Set<Runnable> dependencyInvalidate=new HashSet<>(3);
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, long capacity){
		this(header, offset, nextType, next, NumberSize.bySize(capacity), capacity);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long capacity){
		this(header, offset, nextType, next, bodyType, 0, capacity, true);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long size, long capacity, boolean used){
		try{
			this.header=header;
			this.offset=requirePositive(offset);
			
			this.nextType=Objects.requireNonNull(nextType);
			this.next=requireGreaterOrEqual(next, Header.FILE_HEADER_SIZE);
			
			this.bodyType=Objects.requireNonNull(bodyType);
			this.capacity=requirePositive(capacity);
			this.size=requireGreaterOrEqual(size, capacity);
			
			this.used=used;
		}catch(Exception e){
			throw new IllegalArgumentException(e);
		}
	}
	
	
	public boolean hasNext(){
		return getNext()!=0;
	}
	
	@SuppressWarnings("AutoBoxing")
	public Chunk nextChunk() throws IOException{
		if(!hasNext()) return null;
		return header.getByOffset(getNext());
	}
	
	public ChunkIO io(){
		if(io==null) io=new ChunkIO(this);
		return io;
	}
	
	
	public long getDataStart(){
		return getOffset()+getHeaderSize();
	}
	
	public boolean isLastPhysical() throws IOException{
		return nextPhysicalOffset() >= header.source.getSize();
	}
	
	public long nextPhysicalOffset(){
		return getDataStart()+getCapacity();
	}
	
	@SuppressWarnings("AutoBoxing")
	public Chunk nextPhysical() throws IOException{
		if(isLastPhysical()) return null;
		
		return header.getByOffset(nextPhysicalOffset());
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
		dirty=true;
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
	
	public void setNext(long next) throws BitDepthOutOfSpaceException{
		if(this.next==next) return;
		
		getNextType().ensureCanFit(next);
		this.next=next;
		dirty=true;
		
		notifyDependency();
	}
	
	public void setCapacity(long capacity) throws BitDepthOutOfSpaceException{
		if(this.capacity==capacity) return;
		
		getBodyType().ensureCanFit(capacity);
		this.capacity=capacity;
		dirty=true;
		notifyDependency();
	}
	
	public boolean isUsed(){
		return used;
	}
	
	public void setUsed(boolean used){
		if(this.used==used) return;
		this.used=used;
		dirty=true;
	}
	
	public long getOffset(){
		return offset;
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
		Chunk chunk=(Chunk)o;
		return getOffset()==chunk.getOffset()&&
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
		if(!hasNext()) return;
		var chunk=nextChunk();
		clearNext();
		syncHeader();
		header.freeChunkChain(chunk);
	}
	
	public List<Chunk> loadWholeChain() throws IOException{
		if(!hasNext()) return List.of(this);
		
		List<Chunk> chain=new LinkedList<>();
		
		Chunk link=this;
		do{
			chain.add(link);
			link=link.nextChunk();
		}while(link!=null);
		
		return List.copyOf(chain);
	}
	
	public boolean overlaps(long start, long end) throws IOException{
		long a=getOffset(), b=nextPhysicalOffset();
		return Math.max(start, a)-Math.min(end, b)<(a-b)+(start-end);
	}
	
	public void moveToAndFreeOld(Chunk newChunk) throws IOException{
		var old=new Chunk(header, getOffset(), getNextType(), 0, getBodyType(), size, getCapacity(), true);
		moveTo(newChunk);
		header.freeChunk(old);
	}
	
	public void moveTo(Chunk newChunk) throws IOException{
		byte[] oldData;
		String oldChunk;
		
		if(DEBUG_VALIDATION){
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
		headerWriteCache.clear();
		
		var oldOffset=offset;
		
		offset=newChunk.offset;
		nextType=newChunk.nextType;
		bodyType=newChunk.bodyType;
		capacity=newChunk.capacity;
		dirty=true;
		
		try{
			newChunk.setNext(next);
			newChunk.setSize(size);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		
		
		saveHeader();
		newChunk.saveHeader();
		
		notifyDependency();
		
		header.notifyMovement(oldOffset, newChunk.getOffset());
		
		if(DEBUG_VALIDATION){
			var newData=io().readAll();
			if(!Arrays.equals(oldData, newData)){
				throw new AssertionError(TextUtil.toString(loadWholeChain())+"\n"+
				                         oldChunk+"\n"+
				                         TextUtil.toString(this)+"\n"+
				                         TextUtil.toString(oldData)+"\n"+
				                         TextUtil.toString(newData));
			}
		}
	}
	
	public void notifyDependency(){
		for(Runnable runnable : dependencyInvalidate){
			runnable.run();
		}
	}
	
	@Override
	public String toString(){
		final StringBuilder sb=new StringBuilder("Chunk{");
		
		if(!isUsed()) sb.append("free, ");
		
		sb.append("[")
		  .append(getSize())
		  .append("/")
		  .append(getCapacity()).append(getBodyType().shotName)
		  .append("]");
		
		sb.append(", @").append(getOffset());
		
		if(hasNext()) sb.append(", next: ").append(getNext()).append(getNextType().shotName);
		
		sb.append('}');
		return sb.toString();
	}
	
	public String toShortString(){
		StringBuilder b=new StringBuilder();
		if(!isUsed()) b.append("free ");
		b.append(getSize());
		b.append("/");
		b.append(getCapacity()).append(getBodyType().shotName);
		b.append("@").append(getOffset());

//		if(hasNext()) b.append(">>").append(getNext()).append(getNextType().shotName);
		return b.toString();
	}
	
}
