package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeLongConsumer;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class ContiguousIOList<T> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>> implements RandomAccess{
	
	private static final TypeLink.Check TYPE_CHECK=new TypeLink.Check(
		ContiguousIOList.class,
		List.of(t->{
			if(IOInstance.isInstance(t)) return;
			if(SupportedPrimitive.isAny(t.getTypeClass(null))) return;
			throw new ClassCastException("not instance or primitive");
		})
	);
	
	@IOValue
	private long size;
	
	private final ValueStorage<T> storage;
	
	private final Map<Long, T> cache;
	
	public ContiguousIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
		cache=readOnly?new HashMap<>():null;
		
		var magnetProvider=provider.withRouter(t->t.withPositionMagnet(t.positionMagnet().orElse(getReference().getPtr().getValue())));
		
		this.storage=(ValueStorage<T>)ValueStorage.makeStorage(magnetProvider, typeDef.arg(0), getGenerics(), true);
		
		if(!readOnly&&isSelfDataEmpty()){
			writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	protected StructPipe<ContiguousIOList<T>> newPipe(){
		return FixedContiguousStructPipe.of(getThisStruct());
	}
	
	
	private static <T> FieldAccessor<ContiguousIOList<T>> fieldAccessor(Type elementType, long index){
		return new AbstractFieldAccessor<>(null, ""){
			private String lazyName;
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
			public T get(Struct.Pool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance){
				return instance.getUnsafe(index);
			}
			@Override
			public void set(Struct.Pool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance, Object value){
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
	
	private static final FixedContiguousStructPipe<Reference> REF_PIPE=FixedContiguousStructPipe.of(Reference.STRUCT);
	private static <T extends IOInstance.Unmanaged<T>> IOField<ContiguousIOList<T>, ?> eFieldUnmanagedInst(Type elementType, long index){
		return new IOField.Ref.NoIO<ContiguousIOList<T>, T>(fieldAccessor(elementType, index), REF_PIPE.getFixedDescriptor()){
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
		var typ      =getTypeDef().arg(0).generic(getDataProvider().getTypeDb());
		var unmanaged=storage instanceof ValueStorage.UnmanagedInstance;
		return LongStream.range(0, size()).mapToObj(index->{
			if(unmanaged){
				return (IOField<ContiguousIOList<T>, ?>)(Object)eFieldUnmanagedInst(typ, index);
			}
			return storage.field(fieldAccessor(typ, index), ()->ioAtElement(index));
		});
	}
	
	private long calcElementOffset(long index, long siz){
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		return headSiz+siz*index;
	}
	private long getElementSize(){
		return storage.inlineSize();
	}
	
	
	protected RandomIO ioAtElement(long index) throws IOException{
		var io=selfIO();
		try{
			var pos=calcElementOffset(index, getElementSize());
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
		}
	}
	
	private T readAt(long index) throws IOException{
		try(var io=ioAtElement(index)){
			return storage.readNew(io);
		}
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
			if(cache.containsKey(index)){
				return cache.get(index);
			}
			var val=readAt(index);
			cache.put(index, val);
			return val;
		}
		return readAt(index);
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
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			writeAt(size(), value);
			deltaSize(1);
		}
	}
	
	@Override
	public void addAll(Collection<T> values) throws IOException{
		addMany(values.size(), values.iterator()::next);
	}
	
	private void addMany(long count, UnsafeSupplier<T, IOException> source) throws IOException{
		if(storage instanceof ValueStorage.UnmanagedInstance){//TODO is this necessary? Test and maybe remove
			requestCapacity(size()+count);
			for(long i=0;i<count;i++){
				add(source.get());
			}
			return;
		}
		
		try(var io=selfIO()){
			var pos=calcElementOffset(size(), getElementSize());
			io.skipExact(pos);
			var elSiz   =getElementSize();
			var totalPos=pos+count*elSiz;
			io.ensureCapacity(totalPos);
			
			long targetBytes=Math.min(1024, elSiz*count);
			long targetCount=Math.min(count, Math.max(1, targetBytes/elSiz));
			
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
				
				for(long c=0;c<count;c++){
					T value=source.get();
					
					storage.write(buffIo, value);
					
					i++;
					var s=buffIo.getPos();
					if(s>=targetCap){
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
			T val=ctr.get();
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
			io.setCapacity(calcElementOffset(0, getElementSize()));
		}
	}
	
	private void forwardDup(long index) throws IOException{
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			var lastOff=calcElementOffset(size()+1, getElementSize());
			io.setCapacity(lastOff);
			
			for(long i=size()-1;i>=index;i--){
				
				var pos=calcElementOffset(i, getElementSize());
				io.setPos(pos);
				io.readFully(buff);
				
				var nextPos=calcElementOffset(i+1, getElementSize());
				io.setPos(nextPos);
				io.write(buff);
			}
		}
	}
	
	private void squash(long index, long size) throws IOException{
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			for(long i=index;i<size-1;i++){
				var nextPos=calcElementOffset(i+1, getElementSize());
				io.setPos(nextPos);
				io.readFully(buff);
				
				var pos=calcElementOffset(i, getElementSize());
				io.setPos(pos);
				io.write(buff);
			}
			
			var lastOff=calcElementOffset(size-1, getElementSize());
			io.setCapacity(lastOff);
		}
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		try(var io=selfIO()){
			var cap=calcElementOffset(capacity, getElementSize());
			io.ensureCapacity(cap);
		}
	}
	@Override
	public long getCapacity() throws IOException{
		long size;
		try(var io=selfIO()){
			size=io.getCapacity();
		}
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		var eSiz   =getElementSize();
		return (size-headSiz)/eSiz;
	}
	
	@Override
	public RuntimeType<T> getElementType(){
		return storage.getType();
	}
	
	private boolean freed;
	
	@Override
	public void free() throws IOException{
		Set<Chunk> chunks=new HashSet<>();
		var        prov  =getDataProvider();
		
		new MemoryWalker(this).walk(true, ref->{
			if(ref.isNull()) return;
			ref.getPtr().dereference(prov).streamNext().forEach(chunks::add);
		});
		
		prov.getMemoryManager().free(chunks);
		freed=true;
	}
	@Override
	public boolean isFreed(){
		return freed;
	}
	
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "C";
	}
}
