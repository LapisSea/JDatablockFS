package com.lapissea.fsf;

import com.lapissea.util.TextUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;

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
	
	public static int headerSize(NumberSize nextType, NumberSize bodyType){
		return FLAGS_SIZE.bytes+
		       nextType.bytes+
		       bodyType.bytes*2;
	}
	
	public static void init(ContentOutputStream out, long offset, NumberSize nextType, int fs) throws IOException{
		new Chunk(null, offset, nextType, fs).init(out);
	}
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	
	public static Chunk read(Header header, long offset) throws IOException{
		try(var in=header.source.read(offset)){
			
			var flags=FlagReader.read(in, FLAGS_SIZE);
			
			var nextType=flags.readEnum(NumberSize.class);
			var bodyType=flags.readEnum(NumberSize.class);
			
			var chunkUsed=flags.readBoolBit();
			
			long next    =requirePositive(nextType.read(in));
			long used    =requirePositive(bodyType.read(in));
			long dataSize=requirePositive(bodyType.read(in));
			
			return new Chunk(header, offset, nextType, next, bodyType, used, dataSize, chunkUsed);
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
		
		flags.writeBoolBit(isChunkUsed());
		
		flags.export(buff);
		
		nextType.write(buff, getNext());
		bodyType.write(buff, getUsed());
		bodyType.write(buff, getDataSize());
		
		var bb=c.bb;
		Assert(getHeaderSize()==bb.size());
		bb.writeTo(os);
		bb.reset();
	}
	
	private static long requirePositive(long val) throws IOException{
		if(val<0){
			throw new IOException("Malformed file");
		}
		return val;
	}
	
	final Header header;
	
	private boolean chunkUsed;
	private boolean dirty;
	
	private long offset;
	
	private NumberSize nextType;
	private long       next;
	
	private NumberSize bodyType;
	private long       used;
	private long       dataSize;
	
	private ChunkIO io;
	
	Set<Runnable> dependencyInvalidate=new HashSet<>(3);
	
	private Chunk(Header header, long offset, NumberSize nextType, long dataSize){
		this(header, offset, nextType, 0, NumberSize.bySize(dataSize), dataSize);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long dataSize){
		this(header, offset, nextType, next, bodyType, 0, dataSize, true);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long used, long dataSize, boolean chunkUsed){
		try{
			this.header=header;
			this.offset=offset;
			
			this.nextType=nextType;
			this.next=next;
			
			this.bodyType=bodyType;
			this.used=used;
			this.dataSize=dataSize;
			
			this.chunkUsed=chunkUsed;
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
		return getDataStart()+getDataSize();
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
		return headerSize(nextType, bodyType)+getDataSize();
	}
	
	public int getHeaderSize(){
		return headerSize(nextType, bodyType);
	}
	
	public void pushUsed(long offset){
		if(offset>getUsed()){
			setUsed(offset);
		}
	}
	
	public void setUsed(long used){
		if(used>getDataSize()) throw new IndexOutOfBoundsException(used+" > "+getDataSize());
		this.used=used;
		dirty=true;
	}
	
	public void clearNext(){
		try{
			setNext(0);
		}catch(BitDepthOutOfSpaceException e){
			throw new RuntimeException(e);//should never happen
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
	
	public void setDataSize(long dataSize) throws BitDepthOutOfSpaceException{
		if(this.dataSize==dataSize) return;
		
		getBodyType().ensureCanFit(dataSize);
		this.dataSize=dataSize;
		dirty=true;
		notifyDependency();
	}
	
	public boolean isChunkUsed(){
		return chunkUsed;
	}
	
	public void setChunkUsed(boolean chunkUsed){
		this.chunkUsed=chunkUsed;
		dirty=true;
	}
	
	public long getOffset(){
		return offset;
	}
	
	public long getUsed(){
		return used;
	}
	
	public long getNext(){
		return next;
	}
	
	public long getDataSize(){
		return dataSize;
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
		
		int result=1;
		
		result=31*result+(header==null?0:header.hashCode());
		result=31*result+Long.hashCode(getOffset());
		
		return result;
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
		saveHeader(os);
		Utils.zeroFill(os, getDataSize());
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
	
	public Chunk moveTo(Chunk newChunk) throws IOException{
		
		Chunk oldReference=new Chunk(header, offset, nextType, next, bodyType, used, dataSize, true);
		
		offset=newChunk.offset;
		nextType=newChunk.nextType;
		bodyType=newChunk.bodyType;
		dataSize=newChunk.dataSize;
		
		try{
			newChunk.setNext(next);
			newChunk.setUsed(used);
		}catch(BitDepthOutOfSpaceException e){
			throw new RuntimeException(e);//should never happen
		}
		
		dirty=true;
		
		saveHeader();
		newChunk.saveHeader();
		
		try(var out=newChunk.io().write(false)){
			try(var in=newChunk.io().read()){
				in.transferTo(out);
			}
		}
		
		notifyDependency();
		
		header.notifyMovement(oldReference.getOffset(), newChunk);
		
		return oldReference;
	}
	
	public void notifyDependency(){
		for(Runnable runnable : dependencyInvalidate){
			runnable.run();
		}
	}
	
	static{
		TextUtil.IN_TABLE_TO_STRINGS.register(Chunk.class, Chunk::toShortString);
	}
	
	@Override
	public String toString(){
		final StringBuilder sb=new StringBuilder("Chunk{");
		
		if(!isChunkUsed()) sb.append("free, ");
		
		sb.append("[")
		  .append(getUsed())
		  .append("/")
		  .append(getDataSize()).append(getBodyType().shotName)
		  .append("]");
		
		sb.append(", @").append(getOffset());
		
		if(hasNext()) sb.append(", next: ").append(getNext()).append(getNextType().shotName);
		
		sb.append('}');
		return sb.toString();
	}
	
	public String toShortString(){
		return getUsed()+"/"+getDataSize()+getBodyType().shotName+"@"+getOffset();
	}
	
}
