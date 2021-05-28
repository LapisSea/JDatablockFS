package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.ChunkIO;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;

public final class Chunk implements IOInterface, IterablePP<Chunk>{
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	private static final long       MIN_HEADER_SIZE;
	
	static{
		Chunk c=new Chunk(null, null);
		c.bodyNumSize=c.calcBodyNumSize();
		c.nextSize=NumberSize.VOID;
		c.calcHeaderSize();
		MIN_HEADER_SIZE=c.headerSize;
	}
	
	public static ChunkPointer getPtr(Chunk chunk){
		return chunk==null?null:chunk.getPtr();
	}
	
	public static Chunk readChunk(@NotNull ChunkDataProvider provider, @NotNull ChunkPointer pointer) throws IOException{
		if(provider.getSource().getSize()<pointer.add(MIN_HEADER_SIZE)) throw new MalformedPointerException(pointer.toString());
		Chunk chunk=new Chunk(provider, pointer);
		chunk.readHeader();
		return chunk;
	}
	
	@NotNull
	private final ChunkDataProvider provider;
	@NotNull
	private final ChunkPointer      ptr;
	
	@NotNull
	private NumberSize bodyNumSize;
	private long       capacity;
	private long       size;
	
	@NotNull
	private NumberSize   nextSize;
	@Nullable
	private ChunkPointer nextPtr;
	
	private int     headerSize;
	private boolean dirty;
	private Chunk   nextCache;
	
	private Chunk(ChunkDataProvider provider, ChunkPointer ptr){
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
		this.nextPtr=nextPtr;
		
		assert capacity>=size;
		
		calcHeaderSize();
	}
	
	private void calcHeaderSize(){
		headerSize=FLAGS_SIZE.bytes+
		           getBodyNumSize().bytes*2+
		           nextSize.bytes;
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
		try(var buff=dest.writeTicket(headerSize).requireExact().submit()){
			var flags=new FlagWriter(FLAGS_SIZE);
			flags.writeEnum(NumberSize.FLAG_INFO, bodyNumSize, false);
			flags.writeEnum(NumberSize.FLAG_INFO, nextSize, false);
			flags.export(buff);
			
			bodyNumSize.write(buff, capacity);
			bodyNumSize.write(buff, size);
			nextSize.write(buff, nextPtr);
		}
	}
	
	public void readHeader() throws IOException{
		try(var io=clusterIoAtHead()){
			readHeader(io);
		}
	}
	public void readHeader(ContentReader src) throws IOException{
		var flags=FlagReader.read(src, FLAGS_SIZE);
		
		bodyNumSize=flags.readEnum(NumberSize.FLAG_INFO, false);
		nextSize=flags.readEnum(NumberSize.FLAG_INFO, false);
		
		capacity=bodyNumSize.read(src);
		size=bodyNumSize.read(src);
		nextPtr=ChunkPointer.read(nextSize, src);
		
		calcHeaderSize();
	}
	
	
	private RandomIO clusterIoAtHead() throws IOException{
		return getSource().ioAt(getPtr().getValue());
	}
	
	@Override
	public RandomIO io() throws IOException{
		return new ChunkIO(this);
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
	@Override
	public long getSize(){
		return size;
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
	
	@Override
	public void setSize(long newSize){
		if(this.size==newSize) return;
		assert newSize<=getCapacity():newSize+" > "+getCapacity();
		
		markDirty();
		this.size=newSize;
	}
	
	@Override
	public long getCapacity(){
		return capacity;
	}
	
	@Override
	public boolean isReadOnly(){
		return provider.isReadOnly();
	}
	public long totalSize(){
		return headerSize+capacity;
	}
	
	public NumberSize getBodyNumSize(){
		return bodyNumSize;
	}
	
	public ChunkDataProvider getProvider(){
		return provider;
	}
	
	public ChunkPointer getNextPtr(){
		return nextPtr;
	}
	
	public void clearNextPtr(){
		if(nextCache==null) return;
		
		nextPtr=null;
		nextCache=null;
		
		markDirty();
	}
	
	public Chunk nextUnsafe(){
		try{
			return next();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	public Chunk next() throws IOException{
		if(nextCache==null){
			if(nextPtr==null) return null;
			nextCache=provider.getChunk(nextPtr);
		}
		return nextCache;
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
	
	public boolean hasNext(){
		return getNextPtr()!=null;
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
		
		getSource().ioAt(dataStart()+from, io->{
			io.fillZero(amount);
		});
	}
	
	public void zeroOutHead() throws IOException{
		getSource().ioAt(getPtr().getValue(), io->{
			io.fillZero(headerSize);
		});
	}
	public void freeChaining() throws IOException{
		provider.validate();
		
		if(!hasNext()){
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
		if(provider.isReadOnly()){
			throw new UnsupportedOperationException();
		}
		dirty=true;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Chunk chunk)) return false;
		return getCapacity()==chunk.getCapacity()&&
		       getSize()==chunk.getSize()&&
		       getPtr().equals(chunk.getPtr())&&
		       getBodyNumSize()==chunk.getBodyNumSize()&&
		       nextSize==chunk.nextSize&&
		       Objects.equals(getNextPtr(), chunk.getNextPtr())&&
		       Objects.equals(provider, chunk.provider);
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
	
	@Override
	public Iterator<Chunk> iterator(){
		return IterablePP.nullTerminated(()->new ChainSupplier(this)).iterator();
	}
}
