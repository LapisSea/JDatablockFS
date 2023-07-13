package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChainWalker;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkBuilder;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.OutOfBitDepth;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.ValueStorage.StorageRule;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.BaseFixedStructPipe;
import com.lapissea.cfs.io.instancepipe.FieldDependency;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.query.Query;
import com.lapissea.cfs.query.QuerySupport;
import com.lapissea.cfs.type.CommandSet;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.NewObj;
import com.lapissea.cfs.type.RuntimeType;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.access.TypeFlag;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeLongConsumer;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.config.GlobalConfig.BATCH_BYTES;
import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.INSTANCE;
import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.PRIMITIVE;
import static com.lapissea.util.UtilL.Assert;

@SuppressWarnings("unchecked")
public final class ContiguousIOList<T> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>> implements RandomAccess{
	
	private static final TypeLink.Check TYPE_CHECK = new TypeLink.Check(
		ContiguousIOList.class,
		TypeLink.Check.ArgCheck.rawAny(PRIMITIVE, INSTANCE, TypeLink.Check.ArgCheck.RawCheck.of(c -> c == String.class, "is not a string"))
	);
	
	@IOValue
	@IOValue.Unsigned
	private long size;
	
	@IOValue
	private List<NumberSize> varyingBuffer;
	
	private       ValueStorage<T> storage;
	private       int             headSize;
	private final Map<Long, T>    cache;
	
	
	public ContiguousIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef, TYPE_CHECK);
		cache = readOnly? new ConcurrentHashMap<>() : null;
		
		//read data needed for proper function such as number of elements and varying sizes
		if(!isSelfDataEmpty()){
			readManagedFields();
		}
		
		var ptrSize = NumberSize.bySize(getDataProvider().getSource().getIOSize());
		
		var rec = VaryingSize.Provider.record((max, ptr, id) -> {
			NumberSize num;
			if(varyingBuffer != null){
				num = varyingBuffer.get(id);
			}else{
				num = ptr? ptrSize : NumberSize.VOID;
			}
			return max.min(num);
		});
		
		this.storage = makeValueStorage(rec, typeDef.arg(0));
		
		Assert(this.storage.inlineSize() != -1);
		
		if(!readOnly && isSelfDataEmpty()){
			varyingBuffer = rec.export();
			writeManagedFields();
		}
		calcHead();
	}
	
	private void calcHead(){
		headSize = (int)calcInstanceSize(WordSpace.BYTE);
	}
	
	private DataProvider makeMagnetProvider(){
		return getDataProvider().withRouter(t -> {
			if(t.positionMagnet().isPresent()) return t;
			return t.withPositionMagnet(getReference().getPtr().getValue());
		});
	}
	
	@Override
	public <VT extends IOInstance<VT>> StructPipe<VT> getFieldPipe(IOField<ContiguousIOList<T>, VT> unmanagedField, VT fieldValue){
		return (StructPipe<VT>)switch(storage){
			case ValueStorage.FixedInstance<?> fixedInstance -> fixedInstance.getPipe();
			case ValueStorage.UnmanagedInstance<?> unmanagedInstance -> ((Unmanaged<?>)fieldValue).getPipe();
			case ValueStorage.FixedReferenceInstance<?> fixedReferencedInstance -> Reference.fixedPipe();
			case ValueStorage.FixedReferenceSealedInstance<?> ignored -> throw new UnsupportedOperationException();
			case ValueStorage.Instance<?> ignored -> throw new UnsupportedOperationException();
			case ValueStorage.FixedReferenceString ignored -> throw new UnsupportedOperationException();
			case ValueStorage.InlineString ignored -> throw new UnsupportedOperationException();
			case ValueStorage.Primitive<?> ignored -> throw new UnsupportedOperationException();
			case ValueStorage.UnknownIDObject unknownIDObject -> throw new NotImplementedException();
			case ValueStorage.SealedInstance<?> instance -> throw new NotImplementedException();
		};
	}
	
	private static class IndexAccessor<T> extends AbstractFieldAccessor<ContiguousIOList<T>>{
		
		private static final IONullability NULLABLE = IOFieldTools.makeNullabilityAnn(IONullability.Mode.NULLABLE);
		
		private final Type     elementType;
		private final Class<?> rawelementType;
		private       long     index;
		private final boolean  nullable;
		private final int      typeID;
		
		protected IndexAccessor(Type elementType, boolean nullable){
			super(null, "");
			this.elementType = elementType;
			rawelementType = Utils.typeToRaw(elementType);
			this.index = -1;
			this.nullable = nullable;
			typeID = TypeFlag.getId(Utils.typeToRaw(elementType));
		}
		
		@NotNull
		@Override
		public String getName(){
			return elementName(index);
		}
		@NotNull
		@Override
		public <F extends Annotation> Optional<F> getAnnotation(Class<F> annotationClass){
			if(nullable && annotationClass == IONullability.class){
				return Optional.of((F)NULLABLE);
			}
			return Optional.empty();
		}
		
		@Override
		public Type getGenericType(GenericContext genericContext){
			return elementType;
		}
		@Override
		public Class<?> getType(){
			return rawelementType;
		}
		
		@Override
		public int getTypeID(){
			return typeID;
		}
		
		@Override
		public T get(VarPool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance){
			return instance.getUnsafe(index);
		}
		@Override
		public void set(VarPool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance, Object value){
			try{
				instance.set(index, (T)value);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
	}
	
	private static class UnmanagedField<T extends IOInstance.Unmanaged<T>> extends RefField.NoIO<ContiguousIOList<T>, T>{
		
		private       long                           index;
		private final BaseFixedStructPipe<Reference> refPipe;
		
		public UnmanagedField(FieldAccessor<ContiguousIOList<T>> accessor, long index, ValueStorage.UnmanagedInstance<T> storage){
			super(accessor, SizeDescriptor.Fixed.of(storage.getSizeDescriptor()));
			this.index = index;
			refPipe = (BaseFixedStructPipe<Reference>)storage.getRefPipe();
		}
		
		@Override
		public StructPipe<T> getReferencedPipe(ContiguousIOList<T> instance){
			try{
				return instance.get(index).getPipe();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public void setReference(ContiguousIOList<T> instance, Reference newRef){
			try{
				try(var io = instance.ioAtElement(index)){
					refPipe.write(instance.getDataProvider(), io, newRef);
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public Reference getReference(ContiguousIOList<T> instance){
			try{
				try(var io = instance.ioAtElement(index)){
					return refPipe.readNew(instance.getDataProvider(), io, null);
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
	}
	
	private static String elementName(long index){
		return "Element[" + index + "]";
	}
	
	@NotNull
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listDynamicUnmanagedFields(){
		var typeDatabase = getDataProvider().getTypeDb();
		var genericType  = getTypeDef().genericArg(0, typeDatabase);
		var unmanaged    = storage instanceof ValueStorage.UnmanagedInstance<?> u? u : null;
		
		
		//TODO: index and object reusing hackery may cause problems? Investigate when appropriate
		//if(unmanaged != null){
		//	return LongStream.range(0, size()).mapToObj(
		//		index -> {
		//			var f = new UnmanagedField<>(new IndexAccessor<>(genericType, index, true), index, unmanaged);
		//			return (IOField<ContiguousIOList<T>, ?>)(Object)f;
		//		}
		//	);
		//}
		//return LongStream.range(0, size()).mapToObj(
		//	index -> storage.field(new IndexAccessor<>(genericType, index, false), () -> ioAtElement(index))
		//);
		
		
		if(unmanaged != null){
			var indexAccessor = new IndexAccessor<>(genericType, true);
			//noinspection rawtypes
			var f = new UnmanagedField(indexAccessor, -1, unmanaged);
			return LongStream.range(0, size()).mapToObj(
				index -> {
					indexAccessor.index = index;
					f.index = index;
					return f;
				}
			);
		}
		
		var ioAt = new UnsafeSupplier<RandomIO, IOException>(){
			private long index;
			private RandomIO io;
			@Override
			public RandomIO get() throws IOException{
				if(io == null) io = selfIO();
				io.setPos(calcElementOffset(index));
				return io;
			}
		};
		
		var indexAccessor = new IndexAccessor<T>(genericType, false);
		var indexField    = storage.field(indexAccessor, ioAt);
		
		return LongStream.range(0, size()).mapToObj(index -> {
			indexAccessor.index = index;
			ioAt.index = index;
			return indexField;
		});
	}
	
	private static final CommandSet END_SET  = CommandSet.builder(CommandSet.Builder::endFlow);
	private static final CommandSet PREF_SET = CommandSet.builder(b -> {
		b.potentialReference();
		b.endFlow();
	});
	private static final CommandSet REFF_SET = CommandSet.builder(b -> {
		b.referenceField();
		b.endFlow();
	});
	
	@Override
	public CommandSet.CmdReader getUnmanagedReferenceWalkCommands(){
		if(isEmpty()) return END_SET.reader();
		return switch(storage){
			case ValueStorage.Primitive<?> __ -> END_SET.reader();
			case ValueStorage.FixedInstance<?> stor -> {
				var struct = stor.getPipe().getType();
				if(!struct.getCanHavePointers()) yield END_SET.reader();
				yield new CommandSet.RepeaterEnd(PREF_SET, size());
			}
			case ValueStorage.UnmanagedInstance<?> __ -> new CommandSet.RepeaterEnd(REFF_SET, size());
			case ValueStorage.FixedReferenceInstance<?> __ -> new CommandSet.RepeaterEnd(REFF_SET, size());
			case ValueStorage.InlineString __ -> END_SET.reader();
			case ValueStorage.Instance<?> stor -> {
				var struct = stor.getPipe().getType();
				if(!struct.getCanHavePointers()) yield END_SET.reader();
				yield new CommandSet.RepeaterEnd(PREF_SET, size());
			}
			case ValueStorage.FixedReferenceString __ -> new CommandSet.RepeaterEnd(PREF_SET, size());
			case ValueStorage.UnknownIDObject unknownIDObject -> throw new NotImplementedException();
			case ValueStorage.SealedInstance<?> stor -> {
				if(!stor.getCanHavePointers()) yield END_SET.reader();
				yield new CommandSet.RepeaterEnd(PREF_SET, size());
			}
			case ValueStorage.FixedReferenceSealedInstance<?> stor -> new CommandSet.RepeaterEnd(PREF_SET, size());
		};
	}
	
	private long calcElementOffset(long index){
		return calcElementOffset(index, getElementSize());
	}
	private long calcElementOffset(long index, long siz){
		return headSize + siz*index;
	}
	private long getElementSize(){
		return storage.inlineSize();
	}
	
	
	private RandomIO ioAtElement(long index) throws IOException{
		var io = selfIO();
		try{
			var pos = calcElementOffset(index);
			io.skipExact(pos);
		}catch(Throwable e){
			io.close();
			throw e;
		}
		return io;
	}
	
	private void writeAt(long index, T value) throws IOException{
		try(var io = ioAtElement(index)){
			storage.write(io, value);
		}catch(VaryingSize.TooSmall e){
			growVaryingSizes(e.tooSmallIdMap);
			try(var io = ioAtElement(index)){
				storage.write(io, value);
			}
		}
	}
	
	private <Inline> void growVaryingSizes(Map<VaryingSize, NumberSize> tooSmallIdMap) throws IOException{
		var newBuffer = new ArrayList<>(varyingBuffer);
		tooSmallIdMap.forEach((v, s) -> newBuffer.set(v.getId(), s));
		var newVarying = List.copyOf(newBuffer);
		
		var newStorage = makeValueStorage(VaryingSize.Provider.repeat(newVarying), getTypeDef().arg(0));
		
		var oldVal = ValueStorage.RefStorage.<T, Inline>of(storage);
		var newVal = ValueStorage.RefStorage.<T, Inline>of(newStorage);
		
		//fail on recurse
		storage = null;
		
		var oldElemenSize = oldVal.inlineSize();
		var newElemenSize = newVal.inlineSize();
		var headSiz       = calcInstanceSize(WordSpace.BYTE);
		
		long newSize = headSiz + size()*newElemenSize;
		
		Inline            zeroSize       = null;
		Map<Long, Inline> forwardBackup  = new HashMap<>();
		long              forwardCounter = 0;
		
		selfIO(io -> io.ensureCapacity(newSize));
		
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			
			varyingBuffer = newVarying;
			writeManagedFields();
			
			try(var io = getReference().addOffset(headSiz).io(getDataProvider())){
				
				for(long i = 0; i<size(); i++){
					var newDataStart = i*newElemenSize;
					var newDataEnd   = newDataStart + newElemenSize;
					
					if(oldElemenSize == 0){
						if(zeroSize == null){
							io.setPos(0);
							zeroSize = oldVal.readInline(io);
							forwardCounter++;
						}
					}else{
						while(forwardCounter*oldElemenSize<newDataEnd && forwardCounter<size()){
							io.setPos(forwardCounter*oldElemenSize);
							forwardBackup.put(forwardCounter, oldVal.readInline(io));
							forwardCounter++;
						}
					}
					
					io.setPos(newDataStart);
					var el = oldElemenSize == 0? zeroSize : forwardBackup.remove(i);
					newVal.writeInline(io, el);
				}
			}
		}
		storage = newStorage;
		calcHead();
	}
	private ValueStorage<T> makeValueStorage(VaryingSize.Provider varying, TypeLink typeDef){
		return (ValueStorage<T>)ValueStorage.makeStorage(
			makeMagnetProvider(), typeDef, getGenerics().argAsContext("T"),
			new StorageRule.VariableFixed(varying)
		);
	}
	
	private T readAt(long index) throws IOException{
		try(var io = ioAtElement(index)){
			return storage.readNew(io);
		}
	}
	
	@Override
	public Class<T> elementType(){
		return storage.getType().getType();
	}
	
	@Override
	public long size(){
		return size;
	}
	
	@Override
	protected void setSize(long size){
		this.size = size;
	}
	
	@Override
	public T get(long index) throws IOException{
		checkSize(index);
		if(readOnly){
			return getCached(index);
		}
		return readAt(index);
	}
	
	private T getCached(long index) throws IOException{
		if(cache.containsKey(index)){
			return cache.get(index);
		}
		var val = readAt(index);
		cache.put(index, val);
		return val;
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		checkSize(index);
		writeAt(index, value);
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		checkSize(index, 1);
		if(index == size()){
			add(value);
			return;
		}
		
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			forwardDup(index);
			writeAt(index, value);
			deltaSize(1);
		}
	}
	
	@Override
	public void add(T value) throws IOException{
		defragData(1);
		writeAt(size(), value);
		deltaSize(1);
	}
	
	@Override
	public void addAll(Collection<T> values) throws IOException{
		if(values.isEmpty()) return;
		addMany(values.size(), values.iterator()::next);
	}
	
	private void addMany(long count, UnsafeSupplier<T, IOException> source) throws IOException{
		if(count == 0) return;
		if(count == 1){
			add(source.get());
			return;
		}
		if(storage instanceof ValueStorage.UnmanagedInstance){//TODO is this necessary? Test and maybe remove
			requestCapacity(size() + count);
			for(long i = 0; i<count; i++){
				add(source.get());
			}
			return;
		}
		defragData(count);
		
		try(var io = selfIO()){
			var pos = calcElementOffset(size());
			io.skipExact(pos);
			var elSiz    = getElementSize();
			var totalPos = pos + count*elSiz;
			io.ensureCapacity(totalPos);
			
			long targetBytes = Math.min(BATCH_BYTES, elSiz*count);
			long targetCount = elSiz == 0? count : Math.min(count, Math.max(1, targetBytes/elSiz));
			
			var targetCap = targetCount*elSiz;
			
			var mem = MemoryData.builder().withCapacity((int)targetCap).withUsedLength(0).build();
			try(var buffIo = mem.io()){
				UnsafeLongConsumer<IOException> flush = change -> {
					mem.transferTo(io);
					deltaSize(change);
					buffIo.setSize(0);
				};
				
				long lastI = 0;
				long i     = 0;
				
				List<T> bufferedElements = new ArrayList<>();
				
				for(long c = 0; c<count; c++){
					T value = source.get();
					bufferedElements.add(value);
					
					try{
						storage.write(buffIo, value);
					}catch(VaryingSize.TooSmall e){
						var change = i - lastI;
						if(change>0) flush.accept(change);
						
						growVaryingSizes(e.tooSmallIdMap);
						
						addAll(bufferedElements);
						addMany(count - c - 1, source);
						
						return;
					}
					
					i++;
					var s = buffIo.getPos();
					if(s>=targetCap){
						bufferedElements.clear();
						var change = i - lastI;
						lastI = i;
						flush.accept(change);
					}
				}
				
				if(buffIo.getPos()>0){
					var change = i - lastI;
					flush.accept(change);
				}
			}
		}
	}
	
	@Override
	public void remove(long index) throws IOException{
		checkSize(index);
		
		var size = size();
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			deltaSize(-1);
			squash(index, size);
		}
		
		var lastOff = calcElementOffset(size - 1);
		try(var io = selfIO()){
			io.setCapacity(lastOff);
		}
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(count == 0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		NewObj<T> ctr = storage instanceof ValueStorage.UnmanagedInstance? () -> null : getElementType().emptyConstructor();
		
		addMany(count, () -> {
			T val = ctr.make();
			if(initializer != null){
				initializer.accept(val);
			}
			return val;
		});
	}
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		var s = size();
		deltaSize(-s);
		
		if(storage.needsRemoval()){
			List<ChunkPointer> toFree = new ArrayList<>();
			try(var io = selfIO()){
				for(long i = 0; i<s; i++){
					io.setPos(calcElementOffset(i));
					toFree.addAll(storage.notifyRemoval(io, false));
				}
			}
			getDataProvider().getMemoryManager().freeChains(toFree);
		}
		try(var io = selfIO()){
			io.setCapacity(calcElementOffset(0));
		}
	}
	
	private void forwardDup(long index) throws IOException{
		defragData(1);
		
		try(var io = selfIO()){
			var  size              = size();
			long remainingElements = size - index;
			
			var elementSize = Math.toIntExact(getElementSize());
			var maxChunk    = Math.max(1, (int)Math.min(remainingElements, BATCH_BYTES/elementSize));
			
			byte[] buff = new byte[elementSize*maxChunk];
			
			var lastOff = calcElementOffset(size + 1);
			io.setCapacity(lastOff);
			
			for(long i = size; i>index; ){
				var stepSize = Math.min(maxChunk, (int)Math.max(0, i - index));
				i -= stepSize;
				var buffSize = stepSize*elementSize;
				
				var pos = calcElementOffset(i);
				io.setPos(pos);
				io.readFully(buff, 0, buffSize);
				
				var nextPos = calcElementOffset(i + 1);
				io.setPos(nextPos);
				io.write(buff, 0, buffSize);
			}
		}
	}
	
	private void defragData(long extraSlots) throws IOException{
		defragData(getReference().getPtr().dereference(getDataProvider()), extraSlots, 8);
	}
	private void defragData(Chunk ch, long extraSlots, long max) throws IOException{
		if(max<=2) return;
		var nextCount = ch.chainLength(max + 1);
		if(nextCount<max) return;
		if(nextCount == max){
			var cap = 0L;
			for(Chunk chunk : new ChainWalker(ch)){
				cap += chunk.hasNextPtr()? chunk.getSize() : chunk.getCapacity();
			}
			var neededCap = calcElementOffset(size() + extraSlots);
			if(cap>=neededCap){
				return;
			}
		}
		
		record Point(Chunk target, Chunk realloc){ }
		
		Point point;
		{
			
			Point optimal    = null;
			Chunk mergePoint = ch.requireNext();
			int   steps      = 0;
			while(mergePoint.chainLength(3) == 3){
				if(steps>=max) break;
				steps++;
				var next = mergePoint.requireNext();
				if(mergePoint.getCapacity()>next.getCapacity()*2){
					optimal = new Point(mergePoint, next);
				}
				mergePoint = next;
			}
			if(optimal == null){
				optimal = new Point(ch, ch.requireNext());
			}
			point = optimal;
		}
		
		var forwardCap = point.realloc.chainCapacity();
		
		var extra = extraSlots*getElementSize();
		
		var newNext = AllocateTicket.bytes(forwardCap + extra)
		                            .withPositionMagnet(point.target)
		                            .withDataPopulated((prov, io) -> {
			                            try(var ioSrc = point.realloc.io()){
				                            ioSrc.transferTo(io);
			                            }
		                            })
		                            .withApproval(Chunk.sizeFitsPointer(point.target.getNextSize()))
		                            .withExplicitNextSize(Optional.of(NumberSize.bySize(getDataProvider().getSource().getIOSize())))
		                            .submit(getDataProvider());
		if(newNext == null){
			defragData(point.realloc, extraSlots, max - 1);
			return;
		}
		
		try{
			point.target.setNextPtr(newNext.getPtr());
		}catch(OutOfBitDepth e){
			throw new RuntimeException(e);
		}
		point.target.syncStruct();
		
		getDataProvider().getMemoryManager().free(point.realloc.collectNext());
	}
	
	private void squash(long index, long size) throws IOException{
		try(var io = selfIO()){
			if(storage.needsRemoval()){
				io.setPos(calcElementOffset(index));
				notifySingleFree(io, false);
			}
			
			long sm1 = size - 1;
			if(sm1 == index) return;
			long remainingElements = sm1 - index;
			
			var siz      = Math.toIntExact(getElementSize());
			var maxChunk = Math.max(1, (int)Math.min(remainingElements, BATCH_BYTES/siz));
			
			byte[] buff = new byte[siz*maxChunk];
			
			for(long i = index; i<sm1; i += maxChunk){
				var buffSize = (int)Math.min(sm1 - i, maxChunk)*siz;
				var nextPos  = calcElementOffset(i + 1);
				io.setPos(nextPos);
				io.readFully(buff, 0, buffSize);
				
				var pos = calcElementOffset(i);
				io.setPos(pos);
				io.write(buff, 0, buffSize);
			}
		}
	}
	private void notifySingleFree(RandomIO io, boolean dereferenceWrite) throws IOException{
		getDataProvider().getMemoryManager().freeChains(storage.notifyRemoval(io, dereferenceWrite));
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		var cap = calcElementOffset(capacity);
		try(var io = selfIO()){
			io.ensureCapacity(cap);
		}
	}
	@Override
	public void trim() throws IOException{
		try(var io = selfIO()){
			io.setCapacity(calcElementOffset(size()));
		}
		
		long siz, cap;
		try(var io = selfIO()){
			cap = io.getCapacity();
			siz = io.getSize();
		}
		
		var totalFree = cap - siz;
		
		var prov = getDataProvider();
		
		var endRef  = getReference().addOffset(siz);
		var ptr     = ChunkPointer.of(endRef.calcGlobalOffset(prov));
		var builder = new ChunkBuilder(prov, ptr).withCapacity(totalFree);
		
		var ch         = builder.create();
		var headerSize = ch.getHeaderSize();
		if(headerSize>=totalFree) return;
		
		Chunk chRem   = getReference().getPtr().dereference(prov);
		var   sizeRem = siz;
		while(chRem.hasNextPtr()){
			sizeRem -= chRem.getSize();
			chRem = chRem.requireNext();
		}
		if(chRem.dataStart() + sizeRem != ptr.getValue()){
			throw new IllegalStateException(chRem.dataStart() + sizeRem + " " + ptr.getValue());
		}
		
		try{
			chRem.setCapacity(sizeRem);
			ch.setCapacity(totalFree - headerSize);
		}catch(OutOfBitDepth e){
			throw new ShouldNeverHappenError(e);
		}
		
		try(var ignored = prov.getSource().openIOTransaction()){
			ch.writeHeader();
			chRem.writeHeader();
		}
		
		prov.getMemoryManager().free(prov.getChunk(ptr));
	}
	
	@Override
	public long getCapacity() throws IOException{
		long size;
		try(var io = selfIO()){
			size = io.getCapacity();
		}
		var headSiz = calcInstanceSize(WordSpace.BYTE);
		var eSiz    = getElementSize();
		if(eSiz == 0) return size();
		return (size - headSiz)/eSiz;
	}
	
	@Override
	public Query<T> query(){
		return QuerySupport.of(ListData.of(this, readFields -> {
			var                       size = size();
			FieldDependency.Ticket<?> depTicket;
			if(storage instanceof ValueStorage.InstanceBased<?> i){
				depTicket = i.depTicket(readFields);
			}else depTicket = null;
			
			return new QuerySupport.AccessIterator<T>(){
				long cursor;
				
				@SuppressWarnings("rawtypes")
				@Override
				public QuerySupport.Accessor<T> next(){
					if(cursor>=size) return null;
					var index = cursor++;
					return full -> {
						checkSize(index);
						if(readOnly){
							return getCached(index);
						}
						try(var io = ioAtElement(index)){
							if(!full && depTicket != null && storage instanceof ValueStorage.InstanceBased i){
								return (T)i.readNewSelective(io, depTicket, true);
							}
							return storage.readNew(io);
						}
					};
				}
			};
		}));
	}
	
	@Override
	public void free(long index) throws IOException{
		checkSize(index);
		if(!storage.needsRemoval()) return;
		
		
		try(var io = ioAtElement(index)){
			notifySingleFree(io, true);
		}
	}
	
	@Override
	public RuntimeType<T> getElementType(){
		return storage.getType();
	}
	
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "C";
	}
}
