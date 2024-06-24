package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.exceptions.MalformedPointer;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;


/**
 * Chunk structure:
 *
 * <pre>
 * . 1            2
 * 3   4                     5
 * |---|---------------------|
 * </pre>
 * <table>
 *   <tr>
 *     <th>No.</th>
 *     <th>Section</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>1.</td>
 *     <td>Header</td>
 *     <td>
 *         Area for the header that contains information about<br/>
 *         the chunk and its body. (next chunk, size, etc...)
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>2.</td>
 *     <td>Body</td>
 *     <td>
 *         Area that holds arbitrary binary data. Typically<br/>
 *         object(s) that are referenced by something else
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>3.</td>
 *     <td>Pointer</td>
 *     <td>
 *         At this point (start of header) is the crucial offset (pointer) <br/>
 *         based on the start of the file. If the header data starts at<br/>
 *         byte 100 then the pointer of a 100 will be able to fetch the<br/>
 *         header and it's data.<br/>
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>4.</td>
 *     <td>Data start</td>
 *     <td>
 *         At this point the header ends and the body of the chunk starts.<br/>
 *         The header itself determines its own size and by effect the data<br/>
 *         start. This size is calculated trough the data within the header.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>5.</td>
 *     <td>Data end</td>
 *     <td>
 *         At this point the body data ends. This point is does not mean actual <br/>
 *         data ends there. There can be extra unused space between the end of <br/>
 *         data (body size) and the actual chunk end. (capacity + header size)
 *     </td>
 *   </tr>
 * </table>
 */
@Struct.NoDefaultConstructor
@StructPipe.Special
public final class Chunk extends IOInstance.Managed<Chunk> implements RandomIO.Creator, DataProvider.Holder, Comparable<Chunk>{
	
	/**
	 * Internal implementation of the {@link StandardStructPipe}. This may be removed in the future as the generated/generic pipes get further optimized
	 */
	private static class OptimizedChunkPipe extends StandardStructPipe<Chunk>{
		
		static{
			if(DEBUG_VALIDATION){
				var check = "bodyNumSize + nextSize 1 byte capacity NS(bodyNumSize): 0-8 bytes size NS(bodyNumSize): 0-8 bytes nextPtr NS(nextSize): 0-8 bytes ";
				var sb    = new StringBuilder(check.length());
				var p     = StandardStructPipe.of(STRUCT, STATE_DONE);
				for(IOField<Chunk, ?> specificField : p.getSpecificFields()){
					sb.append(specificField.getName()).append(' ').append(specificField.getSizeDescriptor()).append(' ');
				}
				var res = sb.toString();
				if(!check.equals(res)){
					throw UtilL.exitWithErrorMsg(check + "\n" + res);
				}
			}
		}
		
		public OptimizedChunkPipe(){
			super(STRUCT, (type, f) -> List.of(
				BitFieldMerger.of(List.of(
					(BitField<Chunk, ?>)f.requireExact(NumberSize.class, "bodyNumSize"),
					(BitField<Chunk, ?>)f.requireExact(NumberSize.class, "nextSize")
				)),
				f.requireExact(long.class, "capacity"),
				f.requireExact(long.class, "size"),
				f.requireExact(ChunkPointer.class, "nextPtr")
			), true);
		}
		
		@Override
		protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<Chunk> ioPool, Chunk instance) throws IOException{
			dest.write(instance.writeHeaderToBB());
		}
		
		@Override
		protected Chunk doRead(VarPool<Chunk> ioPool, DataProvider provider, ContentReader src, Chunk instance, GenericContext genericContext) throws IOException{
			NumberSize bns;
			NumberSize nns;
			try(var f = FlagReader.read(src, NumberSize.BYTE)){
				bns = NumberSize.FLAG_INFO.get((int)f.readBits(3));
				nns = NumberSize.FLAG_INFO.get((int)f.readBits(3));
			}
			instance.bodyNumSize = bns;
			instance.nextSize = nns;
			try{
				instance.setCapacity(bns.read(src));
				instance.setSize(bns.read(src));
				instance.setNextPtr(ChunkPointer.read(nns, src));
			}catch(OutOfBitDepth e){
				throw new IOException(e);
			}
			return instance;
		}
		
		@Override
		protected SizeDescriptor<Chunk> createSizeDescriptor(){
			return SizeDescriptor.Unknown.of(
				WordSpace.BYTE, 1, OptionalLong.of(1 + NumberSize.LARGEST.bytes*3L),
				(ioPool, prov, value) -> {
					return value.calcHeaderSize0();
				}
			);
		}
	}
	
	
	public static final Struct<Chunk>     STRUCT;
	public static final StructPipe<Chunk> PIPE;
	
	static{
		STRUCT = Struct.of(Chunk.class);
		if(ConfigDefs.OPTIMIZED_PIPE_USE_CHUNK.resolveVal()){
			StandardStructPipe.registerSpecialImpl(STRUCT, OptimizedChunkPipe::new);
		}
		PIPE = StandardStructPipe.of(STRUCT);
	}
	
	public static final byte FLAGS_SIZE = 1;
	
	private static final int  CHECK_BYTE_OFF;
	private static final byte CHECK_BIT_MASK;
	
	static{
		if(PIPE.getSpecificFields().getFirst() instanceof BitFieldMerger<?> bf){
			if(bf.getSizeDescriptor().requireFixed(WordSpace.BYTE) != FLAGS_SIZE) throw new AssertionError("flag size not " + FLAGS_SIZE);
			var layout = bf.getSafetyBits().orElseThrow();
			
			CHECK_BYTE_OFF = (int)(BitUtils.bitsToBytes(layout.usedBits()) - 1);
			
			int offset = (int)(layout.usedBits() - CHECK_BYTE_OFF*Byte.SIZE);
			CHECK_BIT_MASK = (byte)(BitUtils.makeMask(layout.safetyBits())<<offset);
			
			if(offset + layout.safetyBits() != 8) throw new AssertionError("not 1 byte");
		}else{
			throw new ShouldNeverHappenError("Update the early check logic!");
		}
	}
	
	public static byte minSafeSize(){
		return FLAGS_SIZE + Long.BYTES;
	}
	
	public static ChunkPointer getPtrNullable(Chunk chunk){
		return chunk == null? ChunkPointer.NULL : chunk.getPtr();
	}
	
	/**
	 * Reads a new chunk at the specified pointer within the data of the provider. This {@link Chunk} instance is stray, if it is
	 * referenced outside a controlled context, then it should be reported to the chunk cache as it is not a "real" chunk until that happens.
	 */
	public static Chunk readChunk(@NotNull DataProvider provider, @NotNull ChunkPointer pointer) throws IOException{
		if(!earlyCheckChunkAt(provider, pointer)) throw new MalformedPointer("Invalid chunk at " + pointer);
		if(provider.getSource().getIOSize()<pointer.add(PIPE.getSizeDescriptor().getMin(WordSpace.BYTE)))
			throw new MalformedPointer(pointer + " points outside of available data");
		Chunk chunk = new Chunk(provider, pointer);
		try{
			chunk.readHeader();
		}catch(Exception e){
			throw new MalformedPointer("No valid chunk at " + pointer, e);
		}
		return chunk;
	}
	
	/**
	 * Quickly checks if a chunk start is possible at all at a certain pointer.
	 */
	public static boolean earlyCheckChunkAt(DataProvider provider, ChunkPointer pointer) throws IOException{
		var src = provider.getSource();
		if(src.getIOSize()<=pointer.getValue()) return false;
		try(var io = src.ioAt(pointer.add(CHECK_BYTE_OFF))){
			return earlyCheckChunkAt(io);
		}
	}
	public static boolean earlyCheckChunkAt(ContentReader reader) throws IOException{
		var flags  = reader.readInt1();
		int masked = flags&CHECK_BIT_MASK;
		return masked == CHECK_BIT_MASK;
	}
	
	@IOValue
	private NumberSize bodyNumSize;
	
	@IOValue
	@IOValue.Unsigned
	@IODependency.NumSize("bodyNumSize")
	private long capacity;
	
	@IOValue
	@IOValue.Unsigned
	@IODependency.NumSize("bodyNumSize")
	private long size;
	
	@IOValue
	private NumberSize nextSize;
	
	@IOValue
	@IODependency.NumSize("nextSize")
	private ChunkPointer nextPtr = ChunkPointer.NULL;
	
	
	@NotNull
	private final DataProvider provider;
	@NotNull
	private final ChunkPointer ptr;
	
	
	private int     headerSize;
	private boolean dirty, reading;
	private Chunk nextCache;
	
	private Chunk(@NotNull DataProvider provider, @NotNull ChunkPointer ptr){
		super(STRUCT);
		this.provider = provider;
		this.ptr = ptr;
	}
	
	public Chunk(@NotNull DataProvider provider, @NotNull ChunkPointer ptr, @NotNull NumberSize bodyNumSize, long capacity, long size, NumberSize nextSize, ChunkPointer nextPtr){
		this.provider = Objects.requireNonNull(provider);
		this.ptr = Objects.requireNonNull(ptr);
		this.bodyNumSize = Objects.requireNonNull(bodyNumSize);
		this.capacity = requireCapacityPositive(capacity);
		this.size = size;
		this.nextSize = Objects.requireNonNull(nextSize);
		try{
			setNextPtr(nextPtr);
		}catch(OutOfBitDepth e){
			throw new RuntimeException(e);
		}
		
		if(size>capacity){
			throw new IllegalArgumentException(size + " > " + capacity);
		}
		
		try{
			bodyNumSize.ensureCanFit(capacity);
		}catch(OutOfBitDepth e){
			throw new IllegalArgumentException("capacity(" + capacity + ") can not fit in to " + bodyNumSize, e);
		}
		
		refreshHeaderSize();
	}
	
	private void refreshHeaderSize(){
		headerSize = calcHeaderSize0();
	}
	private int calcHeaderSize0(){
		return 1 + bodyNumSize.bytes*2 + nextSize.bytes;
	}
	
	/**
	 * Writes the current Chunk header data at header area.
	 */
	public void writeHeader() throws IOException{
		try(var io = sourceIoAtHead()){
			writeHeader(io);
		}
	}
	/**
	 * Writes the current Chunk header data in to the {@link ContentWriter}.
	 */
	public void writeHeader(ContentWriter dest) throws IOException{
		dirty = false;
		dest.write(writeHeaderToBB());
	}
	
	public RandomIO.WriteChunk writeHeaderToBuf() throws IOException{
		byte[] headBytes = writeHeaderToBB();
		return new RandomIO.WriteChunk(getPtr().getValue(), headBytes);
	}
	
	private byte[] writeHeaderToBB() throws IOException{
		var siz = getHeaderSize();
		assert siz == calcHeaderSize0();
		
		var destBuff = new byte[siz];
		
		var bns = getBodyNumSize();
		var nns = getNextSize();

//		new FlagWriter(NumberSize.BYTE)
//			.writeBits(bns.ordinal(), 3)
//			.writeBits(nns.ordinal(), 3)
//			.fillRestAllOne()
//			.export(destBuff, 0);
		destBuff[0] = (byte)(bns.ordinal()|(nns.ordinal()<<3)|(0b11<<6));
		
		bns.write(destBuff, 1, getCapacity());
		bns.write(destBuff, 1 + bns.bytes, getSize());
		nns.write(destBuff, 1 + bns.bytes*2, getNextPtr());
		
		dirty = false;
		return destBuff;
	}
	
	/**
	 * Reads the data from the file in to this chunk.
	 */
	public void readHeader() throws IOException{
		try(var io = sourceIoAtHead()){
			readHeader(io);
		}
	}
	
	/**
	 * Reads the data from the provided {@link ContentReader} in to this chunk.
	 */
	public void readHeader(ContentReader src) throws IOException{
		reading = true;
		try{
			PIPE.read(provider, src, this, null);
		}finally{
			reading = false;
		}
		refreshHeaderSize();
	}
	
	public int getHeaderSize(){
		return headerSize;
	}
	public long dataStart(){
		return ptr.add(getHeaderSize());
	}
	public long dataEnd(){
		var start = dataStart();
		var cap   = getCapacity();
		return start + cap;
	}
	
	private RandomIO sourceIoAtHead() throws IOException{
		return getSource().ioAt(getPtr().getValue());
	}
	
	/**
	 * Creates a new {@link RandomIO} who's available data is provided from this and next chunks (if any).
	 */
	@Override
	public ChunkChainIO io() throws IOException{
		return new ChunkChainIO(this);
	}
	
	@NotNull
	public ChunkPointer getPtr(){
		return ptr;
	}
	
	public void pushSize(long newSize){
		if(newSize>getSize()) setSize(newSize);
	}
	public void clampSize(long newSize){
		if(newSize<getSize()){
			setSize(newSize);
		}
	}
	public void growSizeAndZeroOut(long newSize) throws IOException{
		if(newSize>getCapacity()){
			throw new IllegalArgumentException("newSize(" + newSize + ") is bigger than capacity(" + getCapacity() + ") on " + this);
		}
		
		var oldSize = getSize();
		zeroOutFromTo(oldSize, newSize);
		setSizeUnsafe(newSize);
		
	}
	
	@IOValue
	public long getSize(){
		return size;
	}
	@IOValue
	public void setSize(long newSize){
		forbidReadOnly();
		if(this.size == newSize) return;
		if(newSize>capacity){
			throw new IllegalArgumentException("New size " + newSize + " is bigger than capacity " + capacity);
		}
		
		setSizeUnsafe(newSize);
	}
	
	private void setSizeUnsafe(long newSize){
		markDirty();
		this.size = newSize;
	}
	
	@IOValue
	public long getCapacity(){
		return capacity;
	}
	@IOValue
	public void setCapacity(long newCapacity) throws OutOfBitDepth{
		forbidReadOnly();
		if(this.capacity == newCapacity) return;
		getBodyNumSize().ensureCanFit(newCapacity);
		this.capacity = requireCapacityPositive(newCapacity);
		markDirty();
	}
	
	public boolean setCapacityAndModifyNumSize(long newCapacity){
		if(!clone().setCapacityAndModifyNumSizeInPlace(newCapacity)){
			return false;
		}
		var ok = setCapacityAndModifyNumSizeInPlace(newCapacity);
		if(!ok) throw new ShouldNeverHappenError();
		return true;
	}
	public boolean setCapacityAndModifyNumSizeInPlace(long newCapacity){
		forbidReadOnly();
		if(this.capacity == newCapacity) return true;
		requireCapacityPositive(newCapacity);
		
		var end = dataEnd();
		
		var newNum     = NumberSize.bySize(newCapacity);
		var prevNum    = newNum.prev().orElse(NumberSize.VOID);
		var diff       = newNum.bytes - prevNum.bytes;
		var safeTarget = newCapacity + diff;
		
		bodyNumSize = NumberSize.bySize(safeTarget);
		refreshHeaderSize();
		var growth = newCapacity - capacity;
		var start  = dataStart();
		
		var cap = end - start + growth;
		if(cap<0){
			return false;
		}
		
		if(!bodyNumSize.canFit(cap)){
			bodyNumSize = bodyNumSize.next();
			refreshHeaderSize();
			growth = newCapacity - capacity;
			start = dataStart();
			cap = end - start + growth;
		}
		
		this.capacity = requireCapacityPositive(cap);
		this.size = Math.min(this.size, this.capacity);
		markDirty();
		return true;
	}
	
	private static long requireCapacityPositive(long capacity){
		if(capacity<0){
			throw new IllegalArgumentException("capacity must be positive");
		}
		return capacity;
	}
	
	/**
	 * Should be put before any early termination. If any forbidden function incorrect
	 * state is called, it should fail 100% of the time no matter the input.
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
		return getHeaderSize() + capacity;
	}
	
	@IOValue
	public NumberSize getBodyNumSize(){
		return bodyNumSize;
	}
	public void setBodyNumSize(NumberSize bodyNumSize) throws OutOfBitDepth{
		forbidReadOnly();
		if(this.bodyNumSize == bodyNumSize) return;
		bodyNumSize.ensureCanFit(getCapacity());
		this.bodyNumSize = bodyNumSize;
		markDirty();
		refreshHeaderSize();
	}
	
	public NumberSize getNextSize(){
		return nextSize;
	}
	
	public void setNextSize(NumberSize nextSize) throws OutOfBitDepth{
		forbidReadOnly();
		if(this.nextSize == nextSize) return;
		nextSize.ensureCanFit(getNextPtr());
		
		this.nextSize = nextSize;
		markDirty();
		refreshHeaderSize();
	}
	
	
	public static Predicate<Chunk> sizeFitsPointer(NumberSize size){
		return new Predicate<>(){
			@Override
			public boolean test(Chunk o){
				return size.canFit(o.getPtr());
			}
			@Override
			public String toString(){
				return "Must fit " + size;
			}
		};
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
	public void setNextPtr(ChunkPointer nextPtr) throws OutOfBitDepth{
		forbidReadOnly();
		Objects.requireNonNull(nextPtr);
		if(this.nextPtr.equals(nextPtr)) return;
		if(nextPtr.equals(getPtr())) throw new IllegalArgumentException();
		getNextSize().ensureCanFit(nextPtr);
		this.nextPtr = nextPtr;
		nextCache = null;
		markDirty();
	}
	
	public Chunk requireNext() throws IOException{
		return Objects.requireNonNull(next());
	}
	
	@Nullable
	public Chunk next() throws IOException{
		if(nextCache == null){
			if(!hasNextPtr()) return null;
			nextCache = provider.getChunk(getNextPtr());
		}
		return nextCache;
	}
	
	public void clearAndCompressHeader(){
		try{
			setNextPtr(ChunkPointer.NULL);
			var oldSiz = getHeaderSize();
			setNextSize(NumberSize.VOID);
			var newSiz = getHeaderSize();
			setSize(0);
			try{
				setCapacity(getCapacity() + oldSiz - newSiz);
			}catch(OutOfBitDepth e){
				setBodyNumSize(NumberSize.bySize(getCapacity() + oldSiz - newSiz));
				newSiz = getHeaderSize();
				setCapacity(getCapacity() + oldSiz - newSiz);
			}
		}catch(OutOfBitDepth e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	public void clearNextPtr(){
		try{
			setNextPtr(ChunkPointer.NULL);
		}catch(OutOfBitDepth e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	public Chunk last() throws IOException{
		Chunk ch = this;
		while(ch.hasNextPtr()){
			ch = ch.requireNext();
		}
		return ch;
	}
	
	public List<Chunk> collectNext() throws IOException{
		var len  = chainLength();
		var data = new ArrayList<Chunk>(len);
		
		var ch = this;
		do{
			data.add(ch);
			ch = ch.next();
		}while(ch != null);
		
		return data;
	}
	public Stream<Chunk> streamNext(){
		return Stream.generate(new ChainSupplier(this)).takeWhile(Objects::nonNull);
	}
	public ChainWalker walkNext(){
		return new ChainWalker(this);
	}
	public void addChainTo(Collection<Chunk> dest){
		for(Chunk chunk : walkNext()){
			dest.add(chunk);
		}
	}
	public void addChainToPtr(Collection<ChunkPointer> dest){
		for(Chunk chunk : walkNext()){
			dest.add(chunk.getPtr());
		}
	}
	
	public int chainLength() throws IOException{
		return chainLength(Integer.MAX_VALUE);
	}
	public int chainLength(int maxLen) throws IOException{
		if(maxLen == 0) return 0;
		if(maxLen<0) throw new IllegalArgumentException("maxLen must be positive");
		
		var ch  = this;
		int len = 0;
		do{
			len++;
			if(len>=maxLen) return len;
			ch = ch.next();
		}while(ch != null);
		return len;
	}
	public long chainSize() throws IOException{
		var  ch  = this;
		long sum = 0;
		while(ch != null){
			sum += ch.getSize();
			ch = ch.next();
		}
		return sum;
	}
	public long chainCapacity() throws IOException{
		var  ch  = this;
		long sum = 0;
		while(ch != null){
			sum += ch.getCapacity();
			ch = ch.next();
		}
		return sum;
	}
	
	public void requireReal() throws CacheOutOfSync{
		provider.getChunkCache().requireReal(this);
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
	
	public void zeroOutFromTo(long from, long to) throws IOException{
		
		if(from<0) throw new IllegalArgumentException("from(" + from + ") must be positive");
		if(to<from) throw new IllegalArgumentException("to(" + to + ") must be greater than from(" + from + ")");
		if(from == to) return;
		
		long amount = to - from;
		if(amount>getCapacity()) throw new IOException("Overflow " + (to - from) + " > " + getCapacity());
		
		getSource().ioAt(dataStart() + from, io -> io.fillZero(amount));
	}
	
	public void destroy(boolean zeroSize) throws IOException{
		getSource().ioAt(getPtr().getValue(), io -> io.fillZero(getHeaderSize() + (zeroSize? getSize() : 0)));
		provider.getChunkCache().notifyDestroyed(this);
	}
	
	public void freeChaining() throws IOException{
		provider.validate();
		
		if(!hasNextPtr()){
			provider.getMemoryManager().free(this);
		}else{
			var tofree = collectNext();
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
		return checkLastPhysical()? null : provider.getChunk(ChunkPointer.of(dataEnd()));
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
		dirty = true;
	}
	public boolean dirty(){
		return dirty;
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof Chunk chunk)) return false;
		return capacity == chunk.capacity &&
		       size == chunk.size &&
		       ptr.equals(chunk.ptr) &&
		       bodyNumSize == chunk.bodyNumSize &&
		       nextSize == chunk.nextSize &&
		       Objects.equals(nextPtr, chunk.nextPtr);
	}
	
	@Override
	public int hashCode(){
		return getPtr().hashCode();
	}
	
	public boolean rangeIntersects(long index){
		long start = ptr.getValue();
		long end   = dataEnd();
		return index>=start && index<end;
	}
	
	@Override
	public String toString(){
		var sb     = new StringBuilder(32).append("Chunk{");
		var cached = provider.getChunkCached(getPtr());
		if(cached != this){
			if(cached != null) sb.append("CONFLICTING ");
			else sb.append("NOT_REAL ");
		}else if(dirty){
			sb.append("DIRTY ");
		}
		sb.append(getPtr());
		if(bodyNumSize != NumberSize.VOID) sb.append(" ").append(getSize()).append("/").append(getCapacity()).append(bodyNumSize.shortName);
		if(hasNextPtr()) sb.append(" -> ").append(getNextPtr()).append(nextSize.shortName);
		return sb.append("}").toString();
	}
	@Override
	public String toShortString(){
		var sb = new StringBuilder().append("{").append(getPtr());
		if(bodyNumSize != NumberSize.VOID) sb.append(" ").append(getSize()).append("/").append(getCapacity()).append(bodyNumSize.shortName);
		if(hasNextPtr()) sb.append(" -> ").append(getNextPtr()).append(nextSize.shortName);
		return sb.append("}").toString();
	}
	
	@Override
	public int compareTo(Chunk o){
		return getPtr().compareTo(o.getPtr());
	}
	
	public Chunk findPrev(Chunk first) throws IOException{
		if(first == this) return null;
		
		var last = first;
		for(Chunk chunk : first.requireNext().walkNext()){
			if(chunk == this){
				return last;
			}
			last = chunk;
		}
		return null;
	}
}
