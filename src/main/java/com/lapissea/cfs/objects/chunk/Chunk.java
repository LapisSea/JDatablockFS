package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.exceptions.IllegalBitValueException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.EnumValue;
import com.lapissea.cfs.io.struct.IOStruct.PrimitiveValue;
import com.lapissea.cfs.io.struct.IOStruct.Set;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.cfs.Config.*;

public class Chunk extends IOInstance.Contained implements Iterable<Chunk>, RandomIO.Creator{
	private static final IOStruct THIS_TYP=IOStruct.thisClass();
	
	public static Chunk read(Cluster cluster, ChunkPointer ptr) throws IOException{
		var result=new Chunk(cluster, ptr);
		try{
			result.readStruct();
		}catch(IllegalBitValueException e){
			throw new IOException("Not a chunk at "+ptr, e);
		}catch(IOException e){
			throw new IOException("Failed to read chunk at "+ptr, e);
		}
		return result;
	}
	
	public final Cluster      cluster;
	private      ChunkPointer ptr;
	private      Chunk        nextCache;
	private      boolean      dirty;
	private      long         headerSize=-1;
	private      boolean      reading;
	
	
	@PrimitiveValue(index=0)
	private boolean userData;
	
	@PrimitiveValue(index=1)
	private boolean used;
	
	@EnumValue(index=2)
	private NumberSize bodyNumSize;
	@EnumValue(index=3)
	private NumberSize nextSize;
	
	
	@PrimitiveValue(index=4, sizeRef="bodyNumSize")
	private long capacity;
	@PrimitiveValue(index=5, sizeRef="bodyNumSize")
	private long size;
	
	@Value(index=6, rw=ChunkPointer.WrittenSizeIO.class, rwArgs="nextSize")
	private ChunkPointer nextPtr;
	
	public void clearNextPtr(){
		if(nextCache==null) return;
		
		nextPtr=null;
		nextCache=null;
		
		markDirty();
	}
	
	public void setNext(@NotNull Chunk chunk) throws BitDepthOutOfSpaceException{
		Objects.requireNonNull(chunk);
		setNextPtr(chunk.getPtr());
	}
	
	@Set
	public void setNextPtr(@Nullable ChunkPointer newNextPtr) throws BitDepthOutOfSpaceException{
		if(newNextPtr==null){
			clearNextPtr();
			return;
		}
		
		if(Objects.equals(nextPtr, newNextPtr)) return;
		
		getNextSize().ensureCanFit(newNextPtr);
		
		markDirty();
		nextPtr=newNextPtr;
		nextCache=cluster.getChunkCached(newNextPtr);
		
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
		headerSize=super.getInstanceSize();
		
		dirty=false;
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
	
	public boolean isUserData(){
		return userData;
	}
	
	public void markAsUser(){
		if(userData) return;
		markDirty();
		userData=true;
	}
	
	public void clearUserMark(){
		if(!userData) return;
		this.userData=false;
		markDirty();
	}
	
	@Override
	public long getInstanceSize(){
		return headerSize;
	}
	
	@Override
	public void readStruct(Cluster cluster, ContentReader in) throws IOException{
		try{
			reading=true;
			super.readStruct(cluster, in);
			headerSize=super.getInstanceSize();
			dirty=false;
		}finally{
			reading=false;
		}
	}
	
	@Override
	public void writeStruct(Cluster cluster, ContentWriter out) throws IOException{
		
		dirty=false;
		super.writeStruct(cluster, out);
	}
	
	@NotNull
	public ChunkPointer getPtr(){ return ptr; }
	
	public boolean isUsed()           { return used; }
	
	public NumberSize getBodyNumSize(){ return bodyNumSize; }
	
	public long getSize()             { return size; }
	
	public long getCapacity()         { return capacity; }
	
	public NumberSize getNextSize()   { return nextSize; }
	
	public ChunkPointer getNextPtr()  { return nextPtr; }
	
	public void setBodyNumSize(NumberSize bodyNumSize){
		if(this.bodyNumSize==bodyNumSize) return;
		this.bodyNumSize=bodyNumSize;
		markDirty();
		headerSize=super.getInstanceSize();
	}
	
	public void setNextSize(NumberSize nextSize){
		if(this.nextSize==nextSize) return;
		this.nextSize=nextSize;
		markDirty();
		headerSize=super.getInstanceSize();
	}
	
	public void setUsed(boolean used){
		if(this.used==used) return;
		markDirty();
		this.used=used;
	}
	
	/**
	 * @return if capacity was set successfully
	 */
	public boolean trySetCapacity(long newCapacity){
		if(this.capacity==newCapacity) return true;
		
		if(!getBodyNumSize().canFit(newCapacity)) return false;
		
		setCapacity0(newCapacity);
		return true;
	}
	
	public void setCapacityConfident(long newCapacity){
		try{
			setCapacity(newCapacity);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	public void setCapacity(long newCapacity) throws BitDepthOutOfSpaceException{
		if(this.capacity==newCapacity) return;
		
		getBodyNumSize().ensureCanFit(newCapacity);
		
		setCapacity0(newCapacity);
	}
	
	private void setCapacity0(long newCapacity){
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
		
		markDirty();
		this.size=newSize;
	}
	
	public void requireReal(){
		cluster.checkCached(this);
	}
	
	public void syncStruct() throws IOException{
		if(DEBUG_VALIDATION){
			requireReal();
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
	
	@Override
	protected Cluster getSourceCluster() throws IOException{
		return cluster;
	}
	
	@NotNull
	@Override
	public Iterator<Chunk> iterator(){
		return new Iterator<>(){
			Chunk chunk=Chunk.this;
			
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
	
	public List<Chunk> collectNext() throws IOException{
		List<Chunk> chain=new LinkedList<>();
		for(Chunk chunk : this){
			chain.add(chunk);
		}
		return chain;
	}
	
	public void freeChaining() throws IOException{
		cluster.validate();
		
		if(!hasNext()){
			modifyAndSave(ch->ch.setUsed(false));
			cluster.free(this);
		}else{
			var tofree=collectNext();
			for(Chunk chunk : tofree){
				chunk.modifyAndSave(ch->ch.setUsed(false));
			}
			cluster.free(tofree);
		}
		
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
		return getPtr().hashCode();
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
	
	public void growBy(Chunk firstChunk, long amount) throws IOException{
		cluster.allocTo(firstChunk, this, amount);
	}
	
	public void modifyAndSave(UnsafeConsumer<Chunk, IOException> modifier) throws IOException{
		if(DEBUG_VALIDATION){
			requireReal();
		}
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
		return getClass().getSimpleName()+toShortString();
	}
	
	public String toShortString(){
		StringBuilder sb=new StringBuilder("{");
		sb.append(ptr);
		sb.append(" ");
		sb.append(getSize());
		sb.append("/");
		sb.append(getCapacity());
		sb.append(getBodyNumSize().shortName);
		if(hasNext()) sb.append(" >> ").append(getNextPtr());
		if(!isUsed()) sb.append(", unused");
		
		Chunk cached=cluster.getChunkCached(getPtr());
		if(cached==null) sb.append(", fake");
		else if(cached!=this) sb.append(", INVALID");
		
		sb.append('}');
		return sb.toString();
	}
	
	@Nullable
	public Chunk nextPhysical() throws IOException{
		return cluster.isLastPhysical(this)?null:cluster.getChunk(new ChunkPointer(dataEnd()));
	}
	
	private void markDirty(){
		if(reading) return;
		if(cluster.isReadOnly()){
			throw new UnsupportedOperationException();
		}
		dirty=true;
	}
	
	public void setLocation(ChunkPointer ptr){
		this.ptr=Objects.requireNonNull(ptr);
	}
	
	private class PhysicalIterable implements Iterable<Chunk>{
		
		@NotNull
		@Override
		public Iterator<Chunk> iterator(){
			return new Iterator<>(){
				Chunk ch=Chunk.this;
				
				@Override
				public boolean hasNext(){
					return ch!=null;
				}
				
				@Override
				public Chunk next(){
					Chunk c=ch;
					try{
						ch=c.nextPhysical();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					return c;
				}
			};
		}
	}
	
	public Iterable<Chunk> physicalIterator(){
		return new PhysicalIterable();
	}
	
	public Chunk fakeCopy(){
		Chunk chunk=new Chunk(cluster, ptr, capacity, bodyNumSize, nextSize);
		
		chunk.ptr=this.ptr;
		chunk.nextCache=this.nextCache;
		chunk.headerSize=this.headerSize;
		chunk.userData=this.userData;
		chunk.used=this.used;
		chunk.bodyNumSize=this.bodyNumSize;
		chunk.nextSize=this.nextSize;
		chunk.capacity=this.capacity;
		chunk.size=this.size;
		return chunk;
	}
}
