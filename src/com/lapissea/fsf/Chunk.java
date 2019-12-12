package com.lapissea.fsf;

import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

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
 * */
public class Chunk{
	
	public static Chunk read(Header header, long offset) throws IOException{
		try(var in=header.source.read(offset)){
			return read(header, in, offset);
		}
	}
	
	public static int headerSize(long fileSize, long chunkSize){
		return headerSize(NumberSize.getBySize(fileSize), NumberSize.getBySize(chunkSize));
	}
	
	public static int headerSize(NumberSize nextType, NumberSize bodyType){
		return FLAGS_SIZE.bytesPerValue+
		       nextType.bytesPerValue+
		       bodyType.bytesPerValue*2;
	}
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	public static Chunk read(Header header, ContentInputStream in, long offset) throws IOException{
		
		int flags=(int)FLAGS_SIZE.read(in);
		
		var nextType=NumberSize.fromFlags(flags, 6);
		var bodyType=NumberSize.fromFlags(flags, 4);
		
		var chunkUsed=UtilL.checkFlag(flags, 1<<3);
		
		long next    =requirePositive(nextType.read(in));
		long used    =requirePositive(bodyType.read(in));
		long dataSize=requirePositive(bodyType.read(in));
		
		return new Chunk(header, offset, nextType, next, bodyType, used, dataSize, chunkUsed);
	}
	public void saveHeader(ContentOutputStream os) throws IOException{
		
		int flags=0;
		flags=nextType.writeFlag(flags, 6);
		flags=bodyType.writeFlag(flags, 4);
		if(isChunkUsed()) flags|=1<<3;
		
		FLAGS_SIZE.write(os, flags);
		
		nextType.write(os, getNext());
		bodyType.write(os, getUsed());
		bodyType.write(os, getDataSize());
	}
	
	private static long requirePositive(long val) throws IOException{
		if(val<0) throw new IOException("Malformed file");
		return val;
	}
	
	final Header header;
	
	private boolean chunkUsed;
	private boolean dirty;
	
	private final long offset;
	
	private final NumberSize nextType;
	private       long       next;
	
	private final NumberSize bodyType;
	private       long       used;
	private       long       dataSize;
	
	private ChunkIO io;
	
	public Chunk(Header header, long offset, NumberSize nextType, long dataSize){
		this(header, offset, nextType, 0, NumberSize.getBySize(dataSize), dataSize);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long dataSize){
		this(header, offset, nextType, next, bodyType, 0, dataSize, true);
	}
	
	public Chunk(Header header, long offset, NumberSize nextType, long next, NumberSize bodyType, long used, long dataSize, boolean chunkUsed){
		Assert(dataSize>0);
		
		this.header=header;
		this.offset=offset;
		
		this.nextType=nextType;
		this.next=next;
		
		this.bodyType=bodyType;
		this.used=used;
		this.dataSize=dataSize;
		
		this.chunkUsed=chunkUsed;
	}
	
	
	public boolean hasNext(){
		return getNext()!=0;
	}
	
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
		return nextPhysicalOffset() >= header.source.size();
	}
	
	public long nextPhysicalOffset(){
		return getDataStart()+getDataSize();
	}
	
	public Chunk nextPhysical() throws IOException{
		if(isLastPhysical()) return null;
		
		return header.getByOffset(nextPhysicalOffset());
	}
	
	public long wholeSize(){
		return getHeaderSize()+getDataSize();
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
		}catch(BitDepthOutOfSpace bitDepthOutOfSpace){
			bitDepthOutOfSpace.printStackTrace();//should never happen
		}
	}
	
	public void setNext(long next) throws BitDepthOutOfSpace{
		getNextType().ensureCanFit(next);
		this.next=next;
		dirty=true;
	}
	
	public void setDataSize(long dataSize) throws BitDepthOutOfSpace{
		getBodyType().ensureCanFit(dataSize);
		this.dataSize=dataSize;
		dirty=true;
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
		return Objects.hash(header, getOffset());
	}
	
	//////////////////////////////////////////////////////////////////
	
	public void syncHeader() throws IOException{
		if(dirty){
			saveHeader();
		}
	}
	
	public void saveHeader() throws IOException{
		dirty=false;
		try(var out=header.source.write(getOffset())){
			saveHeader(out);
		}
	}
	
	
	public void init(ContentOutputStream os) throws IOException{
		
		saveHeader(os);
		
		var buff     =new byte[(int)Math.min(256, getDataSize())];
		var remaining=getDataSize();
		
		do{
			var toWrite=(int)Math.min(remaining, buff.length);
			remaining-=toWrite;
			
			os.write(buff, 0, toWrite);
			
		}while(remaining>0);
	}
	
	public void chainForwardFree() throws IOException{
		if(!hasNext()) return;
		var chunk=nextChunk();
		clearNext();
		header.freeChunkChain(chunk);
	}
	
	public List<Chunk> makeChain() throws IOException{
		if(!hasNext()) return List.of(this);
		
		List<Chunk> chain=new LinkedList<>();
		
		Chunk link=this;
		do{
			chain.add(link);
			link=link.nextChunk();
		}while(link!=null);
		
		return List.copyOf(chain);
	}
}
