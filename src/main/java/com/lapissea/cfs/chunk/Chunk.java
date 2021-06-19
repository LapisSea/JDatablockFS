package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;

public final class Chunk extends IOInstance<Chunk> implements RandomIO.Creator{
	
	private static final Struct<Chunk>     STRUCT=Struct.of(Chunk.class);
	private static final StructPipe<Chunk> PIPE  =ContiguousStructPipe.of(STRUCT);
	
	public static ChunkPointer getPtr(Chunk chunk){
		return chunk==null?null:chunk.getPtr();
	}
	
	public static Chunk readChunk(@NotNull ChunkDataProvider provider, @NotNull ChunkPointer pointer) throws IOException{
		if(provider.getSource().getIOSize()<pointer.add(PIPE.getSizeDescriptor().getMin())) throw new MalformedPointerException(pointer.toString());
		Chunk chunk=new Chunk(provider, pointer);
		chunk.readHeader();
		return chunk;
	}
	
	@IOValue
	private NumberSize bodyNumSize;
	
	@IODependency.NumSize("bodyNumSize")
	@IOValue
	private long capacity;
	
	@IODependency.NumSize("bodyNumSize")
	@IOValue
	private long size;
	
	@IOValue
	private NumberSize nextSize;
	
	@IOValue
	@IODependency.NumSize("nextSize")
	private ChunkPointer nextPtr=ChunkPointer.NULL;
	
	
	@NotNull
	private final ChunkDataProvider provider;
	@NotNull
	private final ChunkPointer      ptr;
	
	
	private int     headerSize;
	private boolean dirty;
	private Chunk   nextCache;
	
	private Chunk(ChunkDataProvider provider, ChunkPointer ptr){
		super(STRUCT);
		this.provider=provider;
		this.ptr=ptr;
	}
	
	public Chunk(@NotNull ChunkDataProvider provider, @NotNull ChunkPointer ptr, @NotNull NumberSize bodyNumSize, long capacity, long size, NumberSize nextSize, ChunkPointer nextPtr){
		this.provider=Objects.requireNonNull(provider);
		this.ptr=Objects.requireNonNull(ptr);
		this.bodyNumSize=Objects.requireNonNull(bodyNumSize);
		this.capacity=capacity;
		this.size=size;
		this.nextSize=Objects.requireNonNull(nextSize);
		setNextPtr(nextPtr);
		
		assert capacity>=size;
		
		calcHeaderSize();
	}
	
	private void calcHeaderSize(){
		headerSize=(int)PIPE.getSizeDescriptor().calcUnknown(this);
	}
	
	private NumberSize calcBodyNumSize(){
		return NumberSize.bySize(Math.max(getCapacity(), getSize()));
	}
	
	public void writeHeader() throws IOException{
		try(var io=clusterIoAtHead()){
			writeHeader(io);
		}
	}
	public void writeHeader(ContentWriter dest) throws IOException{
		dirty=false;
		PIPE.write(dest, this);
	}
	
	public void readHeader() throws IOException{
		try(var io=clusterIoAtHead()){
			readHeader(io);
		}
	}
	public void readHeader(ContentReader src) throws IOException{
		PIPE.read(provider, src, this);
		calcHeaderSize();
	}
	
	
	private RandomIO clusterIoAtHead() throws IOException{
		return getSource().ioAt(getPtr().getValue());
	}
	
	@Override
	public RandomIO io() throws IOException{
		return new ChunkChainIO(this);
	}
	
	public long dataStart(){
		return ptr.add(headerSize);
	}
	public long dataEnd(){
		var start=dataStart();
		var cap  =getCapacity();
		return start+cap;
	}
	
	public ChunkPointer getPtr(){
		return ptr;
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
	
	@IOValue
	public long getSize(){
		return size;
	}
	@IOValue
	public void setSize(long newSize){
		forbidReadOnly();
		if(this.size==newSize) return;
		assert newSize<=getCapacity():newSize+" > "+getCapacity();
		
		markDirty();
		this.size=newSize;
	}
	
	@IOValue
	public long getCapacity(){
		return capacity;
	}
	@IOValue
	public void setCapacity(long newCapacity){
		forbidReadOnly();
		if(this.capacity==newCapacity) return;
		this.capacity=newCapacity;
		markDirty();
	}
	
	public void forbidReadOnly(){
		if(isReadOnly()){
			throw new UnsupportedOperationException();
		}
	}
	public boolean isReadOnly(){
		return provider.isReadOnly();
	}
	
	
	public long totalSize(){
		return headerSize+capacity;
	}
	
	@IOValue
	public NumberSize getBodyNumSize(){
		return bodyNumSize;
	}
	
	public ChunkDataProvider getProvider(){
		return provider;
	}
	
	@NotNull
	@IOValue
	public ChunkPointer getNextPtr(){
		return nextPtr;
	}
	
	@IOValue
	private void setNextPtr(ChunkPointer nextPtr){
		forbidReadOnly();
		Objects.requireNonNull(nextPtr);
		if(this.nextPtr.equals(nextPtr)) return;
		this.nextPtr=nextPtr;
		nextCache=null;
		markDirty();
	}
	
	public Chunk next() throws IOException{
		if(nextCache==null){
			if(!hasNextPtr()) return null;
			nextCache=provider.getChunk(getNextPtr());
		}
		return nextCache;
	}
	
	public void clearNextPtr(){
		setNextPtr(ChunkPointer.NULL);
	}
	
	public Chunk nextUnsafe(){
		try{
			return next();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	
	public List<Chunk> collectNext(){
		return streamNext().collect(Collectors.toList());
	}
	public Stream<Chunk> streamNext(){
		return Stream.generate(new ChainSupplier(this)).takeWhile(Objects::nonNull);
	}
	
	public void requireReal() throws DesyncedCacheException{
		var cached=provider.getChunkCache().get(getPtr());
		if(this!=cached) throw new DesyncedCacheException(this, cached);
	}
	
	public boolean hasNextPtr(){
		return !getNextPtr().isNull();
	}
	
	public void modifyAndSave(UnsafeConsumer<Chunk, IOException> modifier) throws IOException{
		if(DEBUG_VALIDATION){
			requireReal();
		}
		modifier.accept(this);
		syncStruct();
	}
	
	public void syncStruct() throws IOException{
		if(DEBUG_VALIDATION){
			requireReal();
		}
		if(!dirty) return;
		writeHeader();
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
		
		getSource().ioAt(dataStart()+from, io->io.fillZero(amount));
	}
	
	public void zeroOutHead() throws IOException{
		getSource().ioAt(getPtr().getValue(), io->io.fillZero(headerSize));
	}
	public void freeChaining() throws IOException{
		provider.validate();
		
		if(!hasNextPtr()){
			provider.getMemoryManager().free(this);
		}else{
			var tofree=collectNext();
			provider.getMemoryManager().free(tofree);
		}
		
	}
	
	public void growBy(Chunk firstChunk, long amount) throws IOException{
		provider.getMemoryManager().allocTo(firstChunk, this, amount);
	}
	
	private IOInterface getSource(){
		return provider.getSource();
	}
	
	public boolean checkLastPhysical() throws IOException{
		return provider.isLastPhysical(this);
	}
	
	@Nullable
	public Chunk nextPhysical() throws IOException{
		return checkLastPhysical()?null:provider.getChunk(ChunkPointer.of(dataEnd()));
	}
	
	private void markDirty(){
		forbidReadOnly();
		dirty=true;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Chunk chunk)) return false;
		return capacity==chunk.capacity&&
		       size==chunk.size&&
		       ptr.equals(chunk.ptr)&&
		       bodyNumSize==chunk.bodyNumSize&&
		       nextSize==chunk.nextSize&&
		       Objects.equals(nextPtr, chunk.nextPtr);
	}
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+getPtr().hashCode();
		result=31*result+bodyNumSize.hashCode();
		result=31*result+Long.hashCode(getCapacity());
		result=31*result+Long.hashCode(getSize());
		result=31*result+nextSize.hashCode();
		result=31*result+Objects.hashCode(getNextPtr());
		
		return result;
	}
}
