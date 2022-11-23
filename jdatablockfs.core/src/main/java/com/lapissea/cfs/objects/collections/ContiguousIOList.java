package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.*;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.ValueStorage.StorageRule;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FieldDependency;
import com.lapissea.cfs.io.instancepipe.FixedStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.query.Query;
import com.lapissea.cfs.query.QuerySupport;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.access.TypeFlag;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeLongConsumer;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.BATCH_BYTES;
import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.INSTANCE;
import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.PRIMITIVE;
import static com.lapissea.util.UtilL.Assert;

@SuppressWarnings("unchecked")
public final class ContiguousIOList<T> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>> implements RandomAccess{
	
	private static final TypeLink.Check TYPE_CHECK=new TypeLink.Check(
		ContiguousIOList.class,
		TypeLink.Check.ArgCheck.rawAny(PRIMITIVE, INSTANCE)
	);
	
	@IOValue
	@IOValue.Unsigned
	private long size;
	
	@IOValue
	private List<NumberSize> varyingBuffer;
	
	private ValueStorage<T> storage;
	
	private final Map<Long, T> cache;
	
	public ContiguousIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef, TYPE_CHECK);
		cache=readOnly?new HashMap<>():null;
		
		//read data needed for proper function such as number of elements and varying sizes
		if(!isSelfDataEmpty()){
			readManagedFields();
		}
		
		var ptrSize=NumberSize.bySize(getDataProvider().getSource().getIOSize());
		
		var rec=VaryingSize.Provider.record((max, ptr, id)->{
			NumberSize num;
			if(varyingBuffer!=null){
				num=varyingBuffer.get(id);
			}else{
				num=ptr?ptrSize:NumberSize.VOID;
			}
			return max.min(num);
		});
		
		this.storage=(ValueStorage<T>)ValueStorage.makeStorage(makeMagnetProvider(), typeDef.arg(0), getGenerics(), new StorageRule.VariableFixed(rec));
		
		Assert(this.storage.inlineSize()!=-1);
		
		if(!readOnly&&isSelfDataEmpty()){
			varyingBuffer=rec.export();
			writeManagedFields();
		}
	}
	private DataProvider makeMagnetProvider(){
		return getDataProvider().withRouter(t->t.withPositionMagnet(t.positionMagnet().orElse(getReference().getPtr().getValue())));
	}
	
	@Override
	public <VT extends IOInstance<VT>> StructPipe<VT> getFieldPipe(IOField<ContiguousIOList<T>, VT> unmanagedField, VT fieldValue){
		return (StructPipe<VT>)switch(storage){
			case ValueStorage.FixedInstance<?> fixedInstance -> fixedInstance.getPipe();
			case ValueStorage.UnmanagedInstance<?> unmanagedInstance -> ((IOInstance.Unmanaged<?>)fieldValue).getPipe();
			case ValueStorage.FixedReferencedInstance<?> fixedReferencedInstance -> Reference.FIXED_PIPE;
			case ValueStorage.Instance<?> ignored -> throw new UnsupportedOperationException();
			case ValueStorage.FixedReferenceString ignored -> throw new UnsupportedOperationException();
			case ValueStorage.InlineString ignored -> throw new UnsupportedOperationException();
			case ValueStorage.Primitive<?> ignored -> throw new UnsupportedOperationException();
		};
	}
	
	private static <T> FieldAccessor<ContiguousIOList<T>> fieldAccessor(Type elementType, long index){
		return new AbstractFieldAccessor<>(null, ""){
			private String lazyName;
			private final int typeID=TypeFlag.getId(Utils.typeToRaw(elementType));
			@NotNull
			@Override
			public String getName(){
				if(lazyName==null){
					lazyName=elementName(index);
				}
				return lazyName;
			}
			@NotNull
			@Override
			public <F extends Annotation> Optional<F> getAnnotation(Class<F> annotationClass){
				return Optional.empty();
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return elementType;
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
		};
	}
	private static String elementName(long index){
		return "Element["+index+"]";
	}
	
	private static final FixedStructPipe<Reference> REF_PIPE=FixedStructPipe.of(Reference.STRUCT);
	private static <T extends IOInstance.Unmanaged<T>> IOField<ContiguousIOList<T>, ?> eFieldUnmanagedInst(Type elementType, long index){
		return new RefField.NoIO<ContiguousIOList<T>, T>(fieldAccessor(elementType, index), REF_PIPE.getFixedDescriptor()){
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
					try(var io=instance.ioAtElement(index)){
						REF_PIPE.write(instance.getDataProvider(), io, newRef);
					}
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
			@Override
			public Reference getReference(ContiguousIOList<T> instance){
				try{
					try(var io=instance.ioAtElement(index)){
						return REF_PIPE.readNew(instance.getDataProvider(), io, instance.getGenerics());
					}
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		};
	}
	
	@NotNull
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listDynamicUnmanagedFields(){
		var typeDatabase=getDataProvider().getTypeDb();
		var genericType =getTypeDef().genericArg(0, typeDatabase);
		var unmanaged   =storage instanceof ValueStorage.UnmanagedInstance;
		return LongStream.range(0, size()).mapToObj(index->{
			if(unmanaged){
				return (IOField<ContiguousIOList<T>, ?>)(Object)eFieldUnmanagedInst(genericType, index);
			}
			return storage.field(fieldAccessor(genericType, index), ()->ioAtElement(index));
		});
	}
	
	private static final CommandSet END_SET =CommandSet.builder(CommandSet.Builder::endFlow);
	private static final CommandSet PREF_SET=CommandSet.builder(b->{
		b.potentialReference();
		b.endFlow();
	});
	private static final CommandSet REFF_SET=CommandSet.builder(b->{
		b.referenceField();
		b.endFlow();
	});
	
	@Override
	public CommandSet.CmdReader getUnmanagedReferenceWalkCommands(){
		if(isEmpty()) return END_SET.reader();
		return switch(storage){
			case ValueStorage.Primitive<?> __ -> END_SET.reader();
			case ValueStorage.FixedInstance<?> stor -> {
				var struct=stor.getPipe().getType();
				if(!struct.getCanHavePointers()) yield END_SET.reader();
				yield new CommandSet.RepeaterEnd(PREF_SET, size());
			}
			case ValueStorage.UnmanagedInstance<?> __ -> new CommandSet.RepeaterEnd(REFF_SET, size());
			case ValueStorage.FixedReferencedInstance<?> __ -> new CommandSet.RepeaterEnd(REFF_SET, size());
			case ValueStorage.InlineString __ -> END_SET.reader();
			case ValueStorage.Instance<?> stor -> {
				var struct=stor.getPipe().getType();
				if(!struct.getCanHavePointers()) yield END_SET.reader();
				yield new CommandSet.RepeaterEnd(PREF_SET, size());
			}
			case ValueStorage.FixedReferenceString __ -> new CommandSet.RepeaterEnd(PREF_SET, size());
		};
	}
	
	private long calcElementOffset(long index){
		return calcElementOffset(index, getElementSize());
	}
	private long calcElementOffset(long index, long siz){
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		return headSiz+siz*index;
	}
	private long getElementSize(){
		return storage.inlineSize();
	}
	
	
	private RandomIO ioAtElement(long index) throws IOException{
		var io=selfIO();
		try{
			var pos=calcElementOffset(index);
			io.skipExact(pos);
		}catch(Throwable e){
			io.close();
			throw e;
		}
		return io;
	}
	
	private void writeAt(long index, T value) throws IOException{
		try(var io=ioAtElement(index)){
			storage.write(io, value);
		}catch(VaryingSize.TooSmall e){
			growVaryingSizes(e.tooSmallIdMap);
			try(var io=ioAtElement(index)){
				storage.write(io, value);
			}
		}
	}
	
	private void growVaryingSizes(Map<VaryingSize, NumberSize> tooSmallIdMap) throws IOException{
		var newBuffer=new ArrayList<>(varyingBuffer);
		tooSmallIdMap.forEach((v, s)->newBuffer.set(v.getId(), s));
		var newVarying=List.copyOf(newBuffer);
		
		var oldStorage=storage;
		var newStorage=(ValueStorage<T>)ValueStorage.makeStorage(
			makeMagnetProvider(), getTypeDef().arg(0), getGenerics(),
			new StorageRule.VariableFixed(VaryingSize.Provider.repeat(newVarying))
		);
		
		//fail on recurse
		storage=null;
		
		var oldElemenSize=oldStorage.inlineSize();
		var newElemenSize=newStorage.inlineSize();
		var headSiz      =calcInstanceSize(WordSpace.BYTE);
		
		long newSize=headSiz+size()*newElemenSize;
		
		T            zeroSize      =null;
		Map<Long, T> forwardBackup =new HashMap<>();
		long         forwardCounter=0;
		
		getReference().io(getDataProvider(), io->io.ensureCapacity(newSize));
		
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			
			varyingBuffer=newVarying;
			writeManagedFields();
			
			try(var io=getReference().addOffset(headSiz).io(getDataProvider())){
				
				for(long i=0;i<size();i++){
					var newDataStart=i*newElemenSize;
					var newDataEnd  =newDataStart+newElemenSize;
					
					if(oldElemenSize==0){
						if(zeroSize==null){
							io.setPos(0);
							zeroSize=oldStorage.readNew(io);
							forwardCounter++;
						}
					}else{
						while(forwardCounter*oldElemenSize<newDataEnd&&forwardCounter<size()){
							io.setPos(forwardCounter*oldElemenSize);
							forwardBackup.put(forwardCounter, oldStorage.readNew(io));
							forwardCounter++;
						}
					}
					
					io.setPos(newDataStart);
					var el=oldElemenSize==0?zeroSize:forwardBackup.remove(i);
					newStorage.write(io, el);
				}
			}
		}
		storage=newStorage;
	}
	
	private T readAt(long index) throws IOException{
		try(var io=ioAtElement(index)){
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
		this.size=size;
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
		var val=readAt(index);
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
		if(index==size()){
			add(value);
			return;
		}
		
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			forwardDup(index);
			writeAt(index, value);
			deltaSize(1);
		}
	}
	
	@Override
	public void add(T value) throws IOException{
		Objects.requireNonNull(value);
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
		if(count==0) return;
		if(count==1){
			add(source.get());
			return;
		}
		if(storage instanceof ValueStorage.UnmanagedInstance){//TODO is this necessary? Test and maybe remove
			requestCapacity(size()+count);
			for(long i=0;i<count;i++){
				add(source.get());
			}
			return;
		}
		defragData(count);
		
		try(var io=selfIO()){
			var pos=calcElementOffset(size());
			io.skipExact(pos);
			var elSiz   =getElementSize();
			var totalPos=pos+count*elSiz;
			io.ensureCapacity(totalPos);
			
			long targetBytes=Math.min(BATCH_BYTES, elSiz*count);
			long targetCount=elSiz==0?count:Math.min(count, Math.max(1, targetBytes/elSiz));
			
			var targetCap=targetCount*elSiz;
			
			var mem=MemoryData.builder().withCapacity((int)targetCap).withUsedLength(0).build();
			try(var buffIo=mem.io()){
				UnsafeLongConsumer<IOException> flush=change->{
					mem.transferTo(io);
					deltaSize(change);
					buffIo.setSize(0);
				};
				
				long lastI=0;
				long i    =0;
				
				List<T> bufferedElements=new ArrayList<>();
				
				for(long c=0;c<count;c++){
					T value=source.get();
					bufferedElements.add(value);
					
					try{
						storage.write(buffIo, value);
					}catch(VaryingSize.TooSmall e){
						var change=i-lastI;
						if(change>0) flush.accept(change);
						
						growVaryingSizes(e.tooSmallIdMap);
						
						addAll(bufferedElements);
						addMany(count-c-1, source);
						
						return;
					}
					
					i++;
					var s=buffIo.getPos();
					if(s>=targetCap){
						bufferedElements.clear();
						var change=i-lastI;
						lastI=i;
						flush.accept(change);
					}
				}
				
				if(buffIo.getPos()>0){
					var change=i-lastI;
					flush.accept(change);
				}
			}
		}
	}
	
	@Override
	public void remove(long index) throws IOException{
		checkSize(index);
		
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			var size=size();
			deltaSize(-1);
			squash(index, size);
		}
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(count==0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		var ctr=getElementType().emptyConstructor();
		
		addMany(count, ()->{
			T val=ctr.make();
			if(initializer!=null){
				initializer.accept(val);
			}
			return val;
		});
	}
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		deltaSize(-size());
		try(var io=selfIO()){
			io.setCapacity(calcElementOffset(0));
		}
	}
	
	private void forwardDup(long index) throws IOException{
		defragData(1);
		
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			var lastOff=calcElementOffset(size()+1);
			io.setCapacity(lastOff);
			
			for(long i=size()-1;i>=index;i--){
				
				var pos=calcElementOffset(i);
				io.setPos(pos);
				io.readFully(buff);
				
				var nextPos=calcElementOffset(i+1);
				io.setPos(nextPos);
				io.write(buff);
			}
		}
	}
	
	private void defragData(long extraSlots) throws IOException{
		defragData(getReference().getPtr().dereference(getDataProvider()), extraSlots, 8);
	}
	private void defragData(Chunk ch, long extraSlots, long max) throws IOException{
		if(max<=2) return;
		var nextCount=ch.streamNext().limit(max+1).count();
		if(nextCount<max) return;
		if(nextCount==max){
			var cap=0L;
			for(Chunk chunk : new ChainWalker(ch)){
				cap+=chunk.hasNextPtr()?chunk.getSize():chunk.getCapacity();
			}
			var neededCap=calcElementOffset(size()+extraSlots);
			if(cap>=neededCap){
				return;
			}
		}
		
		record Point(Chunk target, Chunk realloc){}
		
		Point point;
		{
			
			Point optimal   =null;
			Chunk mergePoint=ch.requireNext();
			int   steps     =0;
			while(mergePoint.streamNext().limit(3).count()==3){
				if(steps>=max) break;
				steps++;
				var next=mergePoint.requireNext();
				if(mergePoint.getCapacity()>next.getCapacity()*2){
					optimal=new Point(mergePoint, next);
				}
				mergePoint=next;
			}
			if(optimal==null){
				optimal=new Point(ch, ch.requireNext());
			}
			point=optimal;
		}
		
		var forwardCap=point.realloc.streamNext().mapToLong(Chunk::getCapacity).sum();
		
		var extra=extraSlots*getElementSize();
		
		var newNext=AllocateTicket.bytes(forwardCap+extra)
		                          .withPositionMagnet(point.target)
		                          .withDataPopulated((prov, io)->{
			                          try(var ioSrc=point.realloc.io()){
				                          ioSrc.transferTo(io);
			                          }
		                          })
		                          .withApproval(Chunk.sizeFitsPointer(point.target.getNextSize()))
		                          .withExplicitNextSize(Optional.of(NumberSize.bySize(getDataProvider().getSource().getIOSize())))
		                          .submit(getDataProvider());
		if(newNext==null){
			defragData(point.realloc, extraSlots, max-1);
			return;
		}
		
		try{
			point.target.setNextPtr(newNext.getPtr());
		}catch(BitDepthOutOfSpaceException e){
			throw new RuntimeException(e);
		}
		point.target.syncStruct();
		
		getDataProvider().getMemoryManager().free(point.realloc.collectNext());
	}
	
	private void squash(long index, long size) throws IOException{
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			for(long i=index;i<size-1;i++){
				var nextPos=calcElementOffset(i+1);
				io.setPos(nextPos);
				io.readFully(buff);
				
				var pos=calcElementOffset(i);
				io.setPos(pos);
				io.write(buff);
			}
			
			var lastOff=calcElementOffset(size-1);
			io.setCapacity(lastOff);
		}
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		var cap=calcElementOffset(capacity);
		try(var io=selfIO()){
			io.ensureCapacity(cap);
		}
	}
	@Override
	public void trim() throws IOException{
		try(var io=selfIO()){
			io.setCapacity(calcElementOffset(size()));
		}
		
		long siz, cap;
		try(var io=selfIO()){
			cap=io.getCapacity();
			siz=io.getSize();
		}
		
		var totalFree=cap-siz;
		
		var prov=getDataProvider();
		
		var endRef =getReference().addOffset(siz);
		var ptr    =ChunkPointer.of(endRef.calcGlobalOffset(prov));
		var builder=new ChunkBuilder(prov, ptr).withCapacity(totalFree);
		
		var ch        =builder.create();
		var headerSize=ch.getHeaderSize();
		if(headerSize>=totalFree) return;
		
		Chunk chRem  =getReference().getPtr().dereference(prov);
		var   sizeRem=siz;
		while(chRem.hasNextPtr()){
			sizeRem-=chRem.getSize();
			chRem=chRem.requireNext();
		}
		if(chRem.dataStart()+sizeRem!=ptr.getValue()){
			throw new IllegalStateException(chRem.dataStart()+sizeRem+" "+ptr.getValue());
		}
		
		try{
			chRem.setCapacity(sizeRem);
			ch.setCapacity(totalFree-headerSize);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		ch.writeHeader();
		chRem.writeHeader();
		
		prov.getMemoryManager().free(prov.getChunk(ptr));
	}
	
	@Override
	public long getCapacity() throws IOException{
		long size;
		try(var io=selfIO()){
			size=io.getCapacity();
		}
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		var eSiz   =getElementSize();
		if(eSiz==0) return size();
		return (size-headSiz)/eSiz;
	}
	
	@Override
	public Query<T> query(){
		return QuerySupport.of(ListData.of(this, readFields->{
			var                       size=size();
			FieldDependency.Ticket<?> depTicket;
			if(storage instanceof ValueStorage.InstanceBased<?> i){
				depTicket=i.depTicket(readFields);
			}else depTicket=null;
			
			return new QuerySupport.AccessIterator<T>(){
				long cursor;
				
				@SuppressWarnings("rawtypes")
				@Override
				public QuerySupport.Accessor<T> next(){
					if(cursor>=size) return null;
					var index=cursor++;
					return full->{
						checkSize(index);
						if(readOnly){
							return getCached(index);
						}
						try(var io=ioAtElement(index)){
							if(!full&&depTicket!=null&&storage instanceof ValueStorage.InstanceBased i){
								return (T)i.readNewSelective(io, depTicket);
							}
							return storage.readNew(io);
						}
					};
				}
			};
		}));
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
