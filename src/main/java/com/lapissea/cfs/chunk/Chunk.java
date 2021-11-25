package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

@SuppressWarnings("unused")
public final class Chunk extends IOInstance<Chunk> implements RandomIO.Creator, DataProvider.Holder, Comparable<Chunk>{
	
	private static final Struct<Chunk>     STRUCT=Struct.of(Chunk.class);
	public static final  StructPipe<Chunk> PIPE  =ContiguousStructPipe.of(STRUCT);
	
	private static final int  CHECK_BYTE_OFF;
	private static final byte CHECK_BIT_MASK;
	
	static{
		if(PIPE.getSpecificFields().get(0) instanceof BitFieldMerger<?> bf){
			var layout=bf.getSafetyBits().orElseThrow();
			
			CHECK_BYTE_OFF=(int)(Utils.bitToByte(layout.usedBits())-1);
			
			int offset=(int)(layout.usedBits()-CHECK_BYTE_OFF*Byte.SIZE);
			CHECK_BIT_MASK=(byte)(BitUtils.makeMask(layout.safetyBits())<<offset);
			
			assert offset+layout.safetyBits()==8;
		}else{
			throw new ShouldNeverHappenError("Update the early check logic!");
		}
	}
	
	
	public static ChunkPointer getPtrNullable(Chunk chunk){
		return chunk==null?null:chunk.getPtr();
	}
	
	public static Chunk readChunk(@NotNull DataProvider provider, @NotNull ChunkPointer pointer) throws IOException{
		if(!earlyCheckChunkAt(provider, pointer)) throw new IOException("Invalid chunk at "+pointer);
		if(provider.getSource().getIOSize()<pointer.add(PIPE.getSizeDescriptor().getMin(WordSpace.BYTE))) throw new MalformedPointerException(pointer+" points outside of available data");
		Chunk chunk=new Chunk(provider, pointer);
		try{
			chunk.readHeader();
		}catch(Exception e){
			throw new MalformedPointerException("No valid chunk at "+pointer, e);
		}
		return chunk;
	}
	
	public static boolean earlyCheckChunkAt(DataProvider provider, ChunkPointer pointer) throws IOException{
		try(var io=provider.getSource().ioAt(pointer.add(CHECK_BYTE_OFF))){
			return earlyCheckChunkAt(io);
		}
	}
	public static boolean earlyCheckChunkAt(ContentReader reader) throws IOException{
		var flags =reader.readInt1();
		int masked=flags&CHECK_BIT_MASK;
		return masked==CHECK_BIT_MASK;
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
	private final DataProvider provider;
	@NotNull
	private final ChunkPointer ptr;
	
	
	private int     headerSize;
	private boolean dirty, reading;
	private Chunk nextCache;
	
	private Chunk(@NotNull DataProvider provider, @NotNull ChunkPointer ptr){
		super(STRUCT);
		this.provider=provider;
		this.ptr=ptr;
	}
	
	public Chunk(@NotNull DataProvider provider, @NotNull ChunkPointer ptr, @NotNull NumberSize bodyNumSize, long capacity, long size, NumberSize nextSize, ChunkPointer nextPtr){
		this.provider=Objects.requireNonNull(provider);
		this.ptr=Objects.requireNonNull(ptr);
		this.bodyNumSize=Objects.requireNonNull(bodyNumSize);
		this.capacity=capacity;
		this.size=size;
		this.nextSize=Objects.requireNonNull(nextSize);
		try{
			setNextPtr(nextPtr);
		}catch(BitDepthOutOfSpaceException e){
			throw new RuntimeException(e);
		}
		
		assert capacity>=size;
		try{
			bodyNumSize.ensureCanFit(capacity);
		}catch(BitDepthOutOfSpaceException e){
			throw new IllegalArgumentException("capacity("+capacity+") can not fit in to "+bodyNumSize, e);
		}
		
		calcHeaderSize();
	}
	
	private void calcHeaderSize(){
		headerSize=(int)PIPE.calcUnknownSize(getDataProvider(), this, WordSpace.BYTE);
	}
	
	public void writeHeader() throws IOException{
		try(var io=clusterIoAtHead()){
			writeHeader(io);
		}
	}
	public void writeHeader(ContentWriter dest) throws IOException{
		dirty=false;
		PIPE.write(provider, dest, this);
	}
	
	public void readHeader() throws IOException{
		try(var io=clusterIoAtHead()){
			readHeader(io);
		}
	}
	public void readHeader(ContentReader src) throws IOException{
		reading=true;
		try{
			PIPE.read(provider, src, this, null);
		}finally{
			reading=false;
		}
		calcHeaderSize();
	}
	public int getHeaderSize(){
		return headerSize;
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
	
	@NotNull
	public ChunkPointer getPtr(){
		return ptr;
	}
	
	
	public void pushSize(long newSize) throws BitDepthOutOfSpaceException{
		if(newSize>getSize()) setSize(newSize);
	}
	public void clampSize(long newSize){
		if(newSize<getSize()){
			try{
				setSize(newSize);
			}catch(BitDepthOutOfSpaceException e){
				/*
				 * The size only gets smaller. This can theoretically happen if the chunk is in a corrupt state.
				 * */
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	public void growSizeAndZeroOut(long newSize) throws IOException{
		if(newSize>getCapacity()){
			throw new IllegalArgumentException("newSize("+newSize+") is bigger than capacity("+getCapacity()+") on "+this);
		}
		
		var oldSize=getSize();
		zeroOutFromTo(oldSize, newSize);
		setSizeUnsafe(newSize);
		
	}
	
	public void sizeSetZero(){
		try{
			setSize(0);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	@IOValue
	public long getSize(){
		return size;
	}
	@IOValue
	public void setSize(long newSize) throws BitDepthOutOfSpaceException{
		forbidReadOnly();
		if(this.size==newSize) return;
		assert newSize<=getCapacity():newSize+" > "+getCapacity();
		getBodyNumSize().ensureCanFit(newSize);
		
		setSizeUnsafe(newSize);
	}
	
	private void setSizeUnsafe(long newSize){
		markDirty();
		this.size=newSize;
	}
	
	@IOValue
	public long getCapacity(){
		return capacity;
	}
	@IOValue
	public void setCapacity(long newCapacity) throws BitDepthOutOfSpaceException{
		forbidReadOnly();
		if(this.capacity==newCapacity) return;
		getBodyNumSize().ensureCanFit(newCapacity);
		this.capacity=newCapacity;
		markDirty();
	}
	
	public void setCapacityAndModifyNumSize(long newCapacity){
		forbidReadOnly();
		if(this.capacity==newCapacity) return;
		
		var end=dataEnd();
		
		var newNum    =NumberSize.bySize(newCapacity);
		var prevNum   =newNum.prev();
		var diff      =newNum.bytes-prevNum.bytes;
		var safeTarget=newCapacity+diff;
		
		bodyNumSize=NumberSize.bySize(safeTarget);
		calcHeaderSize();
		var growth=newCapacity-capacity;
		var start =dataStart();
		
		var cap=end-start+growth;
		if(!bodyNumSize.canFit(cap)){
			bodyNumSize=bodyNumSize.next();
			calcHeaderSize();
			growth=newCapacity-capacity;
			start=dataStart();
			cap=end-start+growth;
		}
		
		this.capacity=cap;
		this.size=Math.min(this.size, this.capacity);
		markDirty();
	}
	
	/**
	 * Should be put before any early termination. If any forbidden function in
	 * correct state is called, it should fail 100% of the time no matter the input.
	 */
	private void forbidReadOnly(){
		if(reading) return;
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
	
	public NumberSize getNextSize(){
		return nextSize;
	}
	
	@Override
	public DataProvider getDataProvider(){
		return provider;
	}
	
	@NotNull
	@IOValue
	public ChunkPointer getNextPtr(){
		return nextPtr;
	}
	
	@IOValue
	public void setNextPtr(ChunkPointer nextPtr) throws BitDepthOutOfSpaceException{
		forbidReadOnly();
		Objects.requireNonNull(nextPtr);
		if(this.nextPtr.equals(nextPtr)) return;
		getNextSize().ensureCanFit(nextPtr);
		this.nextPtr=nextPtr;
		nextCache=null;
		markDirty();
	}
	
	public Optional<Chunk> nextOpt() throws IOException{
		return Optional.ofNullable(next());
	}
	
	@Nullable
	public Chunk next() throws IOException{
		if(nextCache==null){
			if(!hasNextPtr()) return null;
			nextCache=provider.getChunk(getNextPtr());
		}
		return nextCache;
	}
	
	public void clearNextPtr(){
		try{
			setNextPtr(ChunkPointer.NULL);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
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
	
	public void zeroOutFromTo(long from, long to) throws IOException{
		
		if(from<0) throw new IllegalArgumentException("from("+from+") must be positive");
		if(to<from) throw new IllegalArgumentException("to("+to+") must be greater than from("+from+")");
		if(from==to) return;
		
		long amount=to-from;
		if(amount>getCapacity()) throw new IOException("Overflow "+(to-from)+" > "+getCapacity());
		
		getSource().ioAt(dataStart()+from, io->io.fillZero(amount));
	}
	
	public void destroy(boolean zeroSize) throws IOException{
		getSource().ioAt(getPtr().getValue(), io->io.fillZero(getHeaderSize()+(zeroSize?getSize():0)));
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
	
	public boolean isNextPhysical(ChunkPointer other){
		return other.equals(dataEnd());
	}
	public boolean isNextPhysical(Chunk other){
		return isNextPhysical(other.getPtr());
	}
	
	public IterablePP<Chunk> chunksAhead(){
		return new PhysicalChunkWalker(this);
	}
	
	private void markDirty(){
		forbidReadOnly();
		if(reading) return;
		dirty=true;
	}
	public boolean dirty(){
		return dirty;
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
	
	public boolean rangeIntersects(long index){
		long start=ptr.getValue();
		long end  =dataEnd();
		return index>=start&&index<end;
	}
	
	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder().append("Chunk{").append(getPtr()).append(" ").append(getSize()).append("/").append(getCapacity()).append(bodyNumSize.shortName);
		if(hasNextPtr()){
			sb.append(" -> ").append(getNextPtr()).append(nextSize.shortName);
		}
		return sb.append("}").toString();
	}
	@Override
	public String toShortString(){
		StringBuilder sb=new StringBuilder().append("{").append(getPtr()).append(" ").append(getSize()).append("/").append(getCapacity()).append(bodyNumSize.shortName);
		if(hasNextPtr()){
			sb.append(" -> ").append(getNextPtr()).append(nextSize.shortName);
		}
		return sb.append("}").toString();
	}
	
	@Override
	public int compareTo(Chunk o){
		return getPtr().compareTo(o.getPtr());
	}
	
	public long chainSize() throws IOException{
		try(var io=io()){
			return io.getSize();
		}
	}
}
