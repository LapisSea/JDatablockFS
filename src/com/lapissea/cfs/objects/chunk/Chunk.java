package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.exceptions.IllegalBitValueException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.EnumValue;
import com.lapissea.cfs.io.struct.IOStruct.PrimitiveValue;
import com.lapissea.cfs.io.struct.IOStruct.Set;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.cfs.Config.*;

public class Chunk extends IOStruct.Instance.Contained implements Iterable<Chunk>, RandomIO.Creator{
	private static final IOStruct THIS_TYP=IOStruct.thisClass();
	
	public static Chunk read(Cluster cluster, ChunkPointer ptr) throws IOException{
		var result=new Chunk(cluster, ptr);
		try{
			result.readStruct();
		}catch(IllegalBitValueException e){
//			var data=cluster.getData().ioAt(ptr, io->{
//				return io.readInts1((int)Math.min(4, io.remaining()));
//			});
			
			throw new IOException("Not a chunk at "+ptr/*+"\n"+new MemoryData(data, false).hexdump(ptr+" - "+ptr.addPtr(data.length))*/, e);
		}catch(IOException e){
			throw new IOException("Failed to read chunk at "+ptr, e);
		}
		return result;
	}
	
	public final  Cluster      cluster;
	private final ChunkPointer ptr;
	private       Chunk        nextCache;
	private       boolean      dirty;
	private       long         headerSize=-1;
	
	
	@Value(index=-1)
	private boolean typed;
	
	@Value(index=0)
	private boolean used;
	
	@EnumValue(index=1) private NumberSize bodyNumSize;
	@EnumValue(index=2) private NumberSize nextSize;
	
	
	@PrimitiveValue(index=3, sizeRef="bodyNumSize") private long capacity;
	@PrimitiveValue(index=4, sizeRef="bodyNumSize") private long size;
	
	@Value(index=5, rw=ChunkPointer.WrittenSizeIO.class, rwArgs="nextSize")
	private ChunkPointer nextPtr;
	
	@Set
	public void setNextPtr(ChunkPointer newNextPtr){
		if(Objects.equals(nextPtr, newNextPtr)) return;
		
		if(newNextPtr!=null) nextSize.ensureCanFit(newNextPtr);
		
		nextPtr=newNextPtr;
		nextCache=newNextPtr==null?null:cluster.getChunkCached(newNextPtr);
		markDirty();
	}
	
	private Chunk(@NotNull Cluster cluster, @NotNull ChunkPointer ptr){
		super(THIS_TYP);
		this.cluster=cluster;
		this.ptr=ptr;
	}
	
	public Chunk(@NotNull Cluster cluster, @NotNull ChunkPointer ptr, long capacity, @NotNull NumberSize nextSize){
		this(cluster, ptr, capacity, NumberSize.bySize(capacity), nextSize);
	}
	
	public Chunk(@NotNull Cluster cluster, @NotNull ChunkPointer ptr, long capacity, @NotNull NumberSize bodyNumSize, @NotNull NumberSize nextSize){
		super(THIS_TYP);
		this.cluster=cluster;
		this.ptr=ptr;
		this.bodyNumSize=bodyNumSize;
		this.capacity=capacity;
		this.nextSize=nextSize;
		used=true;
		headerSize=getInstanceSize();
	}
	
	public void initBody(ContentWriter dest) throws IOException{
		byte[] chunk=new byte[(int)Math.min(1024*4, getCapacity())];
		
		long remaining=getCapacity();
		while(remaining>0){
			long toWrite=Math.min(chunk.length, remaining);
			dest.write(chunk, 0, (int)toWrite);
			remaining-=toWrite;
		}
	}
	
	@Override
	public void readStruct(ContentReader in) throws IOException{
		super.readStruct(in);
		headerSize=getInstanceSize();
	}
	
	@Override
	public void writeStruct(ContentWriter out) throws IOException{
		dirty=false;
		super.writeStruct(out);
	}
	@NotNull
	public ChunkPointer getPtr(){ return ptr; }
	
	public boolean isUsed()           { return used; }
	
	public NumberSize getBodyNumSize(){ return bodyNumSize; }
	public long getSize()             { return size; }
	public long getCapacity()         { return capacity; }
	
	public NumberSize getNextSize()   { return nextSize; }
	public ChunkPointer getNextPtr()  { return nextPtr; }
	
	public void setUsed(boolean used){
		if(this.used==used) return;
		this.used=used;
		markDirty();
	}
	public void setCapacity(long newCapacity){
		if(this.capacity==newCapacity) return;
		this.capacity=newCapacity;
		markDirty();
		clampSize(newCapacity);
	}
	
	public void pushSize(long newSize){
		if(newSize>getSize()) setSize(newSize);
	}
	
	public void clampSize(long newSize){
		if(newSize<getSize()) setSize(newSize);
	}
	
	
	public void incrementSize(long amount){
		setSize(getSize()+amount);
	}
	
	public void setSize(long newSize){
		if(this.size==newSize) return;
		assert newSize<=getCapacity():newSize+" > "+getCapacity();
		
		this.size=newSize;
		markDirty();
	}
	
	public void syncStruct() throws IOException{
		if(DEBUG_VALIDATION){
			cluster.checkCached(this);
		}
		if(!dirty) return;
		writeStruct();
	}
	
	@Nullable
	public Chunk next() throws IOException{
		if(nextCache==null){
			if(nextPtr==null) return null;
			nextCache=cluster.getChunk(nextPtr);
		}
		return nextCache;
	}
	
	public boolean hasNext(){
		return getNextPtr()!=null;
	}
	
	@Override
	protected RandomIO getStructSourceIO() throws IOException{
		return cluster.getData().io().setPos(getPtr());
	}
	
	@NotNull
	@Override
	public Iterator<Chunk> iterator(){
		var that=this;
		return new Iterator<>(){
			Chunk chunk=that;
			
			@Override
			public boolean hasNext(){
				return chunk!=null;
			}
			
			@Override
			public Chunk next(){
				Chunk c=chunk;
				try{
					chunk=chunk.next();
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
				return c;
			}
		};
	}
	
	public List<Chunk> collectNext(){
		List<Chunk> chain=new LinkedList<>();
		for(Chunk chunk : this){
			chain.add(chunk);
		}
		return chain;
	}
	
	public void freeChaining() throws IOException{
		var tofree=collectNext();
		for(Chunk chunk : tofree){
			chunk.modifyAndSave(ch->ch.setUsed(false));
		}
		
		cluster.free(tofree);
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof Chunk chunk&&
		       isUsed()==chunk.isUsed()&&
		       getCapacity()==chunk.getCapacity()&&
		       getSize()==chunk.getSize()&&
		       getPtr().equals(chunk.getPtr())&&
		       getBodyNumSize()==chunk.getBodyNumSize()&&
		       getNextSize()==chunk.getNextSize()&&
		       Objects.equals(getNextPtr(), chunk.getNextPtr());
	}
	
	@Override
	public int hashCode(){
		
		int result=1;
		
		result=31*result+getPtr().hashCode();
		result=31*result+Boolean.hashCode(isUsed());
		result=31*result+(getBodyNumSize()==null?0:getBodyNumSize().hashCode());
		result=31*result+(getNextSize()==null?0:getNextSize().hashCode());
		result=31*result+Long.hashCode(getCapacity());
		result=31*result+Long.hashCode(getSize());
		result=31*result+(getNextPtr()==null?0:getNextPtr().hashCode());
		
		return result;
	}
	
	public long dataStart(){
		assert headerSize!=-1;
		return headerSize+ptr.getValue();
	}
	
	public long dataEnd(){
		var start=dataStart();
		var cap  =getCapacity();
		return start+cap;
	}
	
	
	public long totalSize(){
		return headerSize+getCapacity();
	}
	
	public long usedDataEnd(){
		return dataStart()+getSize();
	}
	
	public void growBy(long amount) throws IOException{
		cluster.allocTo(this, amount);
	}
	
	public void modifyAndSave(UnsafeConsumer<Chunk, IOException> modifier) throws IOException{
		modifier.accept(this);
		syncStruct();
	}
	
	public void zeroOutCapacity() throws IOException{
		zeroOutFromTo(0, getCapacity());
	}
	
	public void zeroOutFromTo(long from) throws IOException{
		zeroOutFromTo(from, getSize());
	}
	
	public void zeroOutFromTo(long from, long to) throws IOException{
		
		if(from<0) throw new IllegalArgumentException("from("+from+") must be positive");
		if(to<from) throw new IllegalArgumentException("to("+to+") must be greater than from("+from+")");
		if(from==to) return;
		
		long amount=to-from;
		if(amount>getCapacity()) throw new IOException("Overflow "+(to-from)+" > "+getCapacity());
		
		cluster.getData().ioAt(dataStart()+from, io->{
			io.fillZero(amount);
		});
	}
	
	public void zeroOutHead() throws IOException{
		cluster.getData().ioAt(getPtr().getValue(), io->{
			io.fillZero(headerSize);
		});
	}
	
	@Override
	public RandomIO io() throws IOException{
		return new ChunkIO(this);
	}
	
	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder("Chunk{");
		sb.append(ptr);
		sb.append(" ");
		sb.append(getSize());
		sb.append("/");
		sb.append(getCapacity());
		sb.append(getBodyNumSize().shortName);
		if(hasNext()) sb.append(" >> ").append(getNextPtr());
		if(!isUsed()) sb.append(", unused");
		sb.append('}');
		return sb.toString();
	}
	
	@Nullable
	public Chunk nextPhysical() throws IOException{
		return cluster.isLastPhysical(this)?null:cluster.getChunk(new ChunkPointer(dataEnd()));
	}
	
	private void markDirty(){
		dirty=true;
	}
}
