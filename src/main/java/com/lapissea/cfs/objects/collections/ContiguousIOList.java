package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeLongConsumer;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class ContiguousIOList<T> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>>{
	
	private enum ElementForm{
		INLINE_DIRECT,
		INDIRECT,
		UNMANAGED,
		PRIMITIVE;
	}
	
	private static final TypeLink.Check TYPE_CHECK=new TypeLink.Check(
		ContiguousIOList.class,
		List.of(t->{
			if(IOInstance.isInstance(t)) return;
			if(SupportedPrimitive.isAny(t.getTypeClass(null))) return;
			throw new ClassCastException("not instance or primitive");
		})
	);
	
	private static final FixedContiguousStructPipe<Reference> REF_PIPE=FixedContiguousStructPipe.of(Reference.STRUCT);
	
	@IOValue
	private long size;
	
	private final StructPipe<?> elementPipe;
	private final long          elementSize;
	
	private final RuntimeType<T>     type;
	private final ElementForm        form;
	private final SupportedPrimitive primitive;
	
	private final Map<Long, T> cache;
	
	public ContiguousIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
		cache=readOnly?new HashMap<>():null;
		
		var clazz=typeDef.arg(0).getTypeClass(provider.getTypeDb());
		type=RuntimeType.of((Class<T>)clazz);
		
		if(SupportedPrimitive.isAny(clazz)){
			form=ElementForm.PRIMITIVE;
			primitive=SupportedPrimitive.get(getTypeDef().arg(0).getTypeClass(getDataProvider().getTypeDb())).orElseThrow();
		}else if(!IOInstance.isManaged(clazz)){
			form=ElementForm.UNMANAGED;
			primitive=null;
		}else{
			var canBeFixed=true;
			try{
				FixedContiguousStructPipe.of(Struct.ofUnknown(clazz));
			}catch(MalformedStructLayout e){
				canBeFixed=false;
			}
			form=canBeFixed?ElementForm.INLINE_DIRECT:ElementForm.INDIRECT;
			primitive=null;
		}
		
		elementPipe=switch(form){
			case INLINE_DIRECT -> FixedContiguousStructPipe.of(Struct.ofUnknown(clazz));
			case INDIRECT -> ContiguousStructPipe.of(Struct.ofUnknown(clazz));
			case PRIMITIVE, UNMANAGED -> null;
		};
		
		elementSize=switch(form){
			case INLINE_DIRECT -> elementPipe.getSizeDescriptor().requireFixed(WordSpace.BYTE);
			case INDIRECT -> -1;
			case UNMANAGED -> REF_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
			case PRIMITIVE -> primitive.maxSize.get(WordSpace.BYTE);
		};
		
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
	
	
	private static <T extends IOInstance<T>> FieldAccessor<ContiguousIOList<T>> fieldAccessor(Type elementType, long index){
		return new AbstractFieldAccessor<>(null, elementName(index)){
			@NotNull
			@Override
			public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
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
	private static <T extends IOInstance<T>> IOField<ContiguousIOList<T>, ?> eFieldRefInst(Type elementType, long index){
		var instPipe=ContiguousStructPipe.of((Class<T>)Utils.typeToRaw(elementType));
		return new IOField.Ref.NoIO<ContiguousIOList<T>, T>(fieldAccessor(elementType, index), REF_PIPE.getFixedDescriptor()){
			@Override
			public void setReference(ContiguousIOList<T> instance, Reference newRef){
				try{
					instance.writeReferenceAt(index, newRef);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public Reference getReference(ContiguousIOList<T> instance){
				try{
					return instance.readReferenceAt(index);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public StructPipe<T> getReferencedPipe(ContiguousIOList<T> instance){
				return instPipe;
			}
		};
	}
	
	private static <T extends IOInstance.Unmanaged<T>> IOField<ContiguousIOList<T>, ?> eFieldUnmanagedInst(Type elementType, long index){
		return new IOField.Ref.NoIO<ContiguousIOList<T>, T>(fieldAccessor(elementType, index), REF_PIPE.getFixedDescriptor()){
			@Override
			public void setReference(ContiguousIOList<T> instance, Reference newRef){
				try{
					instance.get(index).notifyReferenceMovement(newRef);
					instance.writeReferenceAt(index, newRef);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public Reference getReference(ContiguousIOList<T> instance){
				try{
					return instance.readReferenceAt(index);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
			@Override
			public StructPipe<T> getReferencedPipe(ContiguousIOList<T> instance){
				try{
					return instance.get(index).getPipe();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		};
	}
	
	private static <T extends IOInstance<T>> IOField<ContiguousIOList<T>, ?> eFieldInst(Type elementType, SizeDescriptor.Fixed<T> desc, long index){
		return new IOField.NoIO<ContiguousIOList<T>, T>(fieldAccessor(elementType, index), SizeDescriptor.Fixed.of(desc));
	}
	
	@NotNull
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listDynamicUnmanagedFields(){
		var typ=getTypeDef().arg(0).generic(getDataProvider().getTypeDb());
		
		LongFunction<IOField<ContiguousIOList<T>, ?>> mapper=switch(form){
			case INLINE_DIRECT -> index->eFieldInst(typ, (SizeDescriptor.Fixed)elementPipe.getSizeDescriptor(), index);
			case INDIRECT -> index->(IOField<ContiguousIOList<T>, ?>)(Object)eFieldRefInst(typ, index);
			case UNMANAGED -> index->(IOField<ContiguousIOList<T>, ?>)(Object)eFieldUnmanagedInst(typ, index);
			case PRIMITIVE -> index->(IOField<ContiguousIOList<T>, ?>)(Object)IOFieldPrimitive.make(fieldAccessor(typ, index));
		};
		
		return LongStream.range(0, size()).mapToObj(mapper);
	}
	
	private long calcElementOffset(long index, long siz){
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		return headSiz+siz*index;
	}
	private long getElementSize(){
		return elementSize;
	}
	
	
	private <I extends IOInstance<I>> void writeEl(ContentWriter io, I value) throws IOException{
		((FixedContiguousStructPipe<I>)elementPipe).write(this, io, value);
	}
	private <I extends IOInstance<I>> I readEl(ContentReader io) throws IOException{
		return ((FixedContiguousStructPipe<I>)elementPipe).readNew(getDataProvider(), io, getGenerics());
	}
	
	private void writeAt(long index, T value) throws IOException{
		switch(form){
			case INLINE_DIRECT -> {
				var inst=(IOInstance)value;
				try(var io=selfIO()){
					var pos=calcElementOffset(index, getElementSize());
					io.skipExact(pos);
					
					writeEl(io, inst);
				}
			}
			case INDIRECT -> {
				var inst=(IOInstance)value;
				var ref =readReferenceAt(index);
				if(ref.isNull()){
					var c=AllocateTicket.withData((StructPipe)elementPipe, getDataProvider(), inst).submit(getDataProvider());
					writeReferenceAt(index, c.getPtr().makeReference());
					return;
				}
				try(var io=ref.io(this)){
					writeEl(io, inst);
				}
			}
			case UNMANAGED -> {
				var inst=(IOInstance.Unmanaged)value;
				var ref =readReferenceAt(index);
				if(!ref.equals(inst.getReference())){
					writeReferenceAt(index, inst.getReference());
				}
			}
			case PRIMITIVE -> {
				try(var io=selfIO()){
					var pos=calcElementOffset(index, getElementSize());
					io.skipExact(pos);
					
					writePrimitive(io, value);
				}
			}
		}
	}
	
	private void writePrimitive(ContentWriter io, T value) throws IOException{
		switch(primitive){
			case DOUBLE -> io.writeFloat8((Double)value);
			case FLOAT -> io.writeFloat4((Float)value);
			case LONG -> io.writeInt8((Long)value);
			case INT -> io.writeInt4((Short)value);
			case SHORT -> io.writeInt2((Integer)value);
			case BYTE -> io.writeInt1((Integer)value);
			case BOOLEAN -> io.writeBoolean((Boolean)value);
		}
	}
	
	private T readAt(long index) throws IOException{
		return switch(form){
			case INLINE_DIRECT -> {
				try(var io=selfIO()){
					var pos=calcElementOffset(index, getElementSize());
					io.skipExact(pos);
					
					yield (T)readEl(io);
				}
			}
			case INDIRECT -> {
				var ref=readReferenceAt(index);
				try(var io=ref.io(this)){
					yield (T)readEl(io);
				}
			}
			case UNMANAGED -> {
				var typ   =getTypeDef().arg(0);
				var struct=Struct.Unmanaged.ofUnknown(typ.getTypeClass(getDataProvider().getTypeDb()));
				
				var ref=readReferenceAt(index);
				
				yield (T)struct.requireUnmanagedConstructor().create(getDataProvider(), ref, typ);
			}
			case PRIMITIVE -> {
				try(var io=selfIO()){
					var pos=calcElementOffset(index, getElementSize());
					io.skipExact(pos);
					
					yield readPrimitive(io);
				}
			}
		};
	}
	
	private T readPrimitive(ContentReader io) throws IOException{
		return switch(primitive){
			case DOUBLE -> (T)(Double)io.readFloat8();
			case FLOAT -> (T)(Float)io.readFloat4();
			case LONG -> (T)(Long)io.readInt8();
			case INT -> (T)(Integer)io.readInt4();
			case SHORT -> (T)(Short)io.readInt2();
			case BYTE -> (T)(Byte)io.readInt1();
			case BOOLEAN -> (T)(Boolean)io.readBoolean();
		};
	}
	
	private Reference readReferenceAt(long index) throws IOException{
		try(var io=selfIO()){
			var pos=calcElementOffset(index, REF_PIPE.getFixedDescriptor().requireFixed(WordSpace.BYTE));
			io.skipExact(pos);
			
			return REF_PIPE.readNew(getDataProvider(), io, getGenerics());
		}
	}
	private void writeReferenceAt(long index, Reference ref) throws IOException{
		try(var io=selfIO()){
			var pos=calcElementOffset(index, REF_PIPE.getFixedDescriptor().requireFixed(WordSpace.BYTE));
			io.skipExact(pos);
			
			REF_PIPE.write(getDataProvider(), io, ref);
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
		
		shift(index, ShiftAction.FORWARD_DUP);
		writeAt(index, value);
		deltaSize(1);
	}
	
	@Override
	public void add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		writeAt(size(), value);
		deltaSize(1);
	}
	
	@Override
	public void addAll(Collection<T> values) throws IOException{
		addMany(values.size(), values.iterator()::next);
	}
	
	private void addMany(long count, UnsafeSupplier<T, IOException> source) throws IOException{
		if(form==ElementForm.INDIRECT||form==ElementForm.UNMANAGED){//TODO
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
			io.setSize(totalPos);
			
			long targetBytes=512;
			long targetCount=Math.min(count, Math.max(1, targetBytes/elSiz));
			
			var targetCap=targetCount*elSiz;
			
			var mem=MemoryData.build().withCapacity((int)targetCap).build();
			try(var buffIo=mem.io()){
				UnsafeLongConsumer<IOException> flush=change->{
					buffIo.setCapacity(buffIo.getPos());
					mem.transferTo(io);
					deltaSize(change);
					buffIo.setPos(0);
				};
				
				long lastI=0;
				long i    =0;
				
				for(long c=0;c<count;c++){
					T value=source.get();
					
					switch(form){
						case INLINE_DIRECT -> writeEl(buffIo, (IOInstance)value);
						case PRIMITIVE -> writePrimitive(buffIo, value);
					}
					
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
		
		shift(index, ShiftAction.SQUASH);
		
		deltaSize(-1);
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(count==0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		T val=getElementType().requireEmptyConstructor().get();
		
		addMany(count, ()->{
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
	
	private enum ShiftAction{
		SQUASH, FORWARD_DUP
	}
	
	private void shift(long index, ShiftAction action) throws IOException{
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			int dummy=switch(action){
				case SQUASH -> {
					for(long i=index;i<size()-1;i++){
						var nextPos=calcElementOffset(i+1, getElementSize());
						io.setPos(nextPos);
						io.readFully(buff);
						
						var pos=calcElementOffset(i, getElementSize());
						io.setPos(pos);
						io.write(buff);
					}
					
					var lastOff=calcElementOffset(size()-1, getElementSize());
					io.setCapacity(lastOff);
					yield 0;
				}
				case FORWARD_DUP -> {
					var lastOff=calcElementOffset(size()+1, getElementSize());
					io.setCapacity(lastOff);
					
					for(long i=index;i<size();i++){
						
						var pos=calcElementOffset(i, getElementSize());
						io.setPos(pos);
						io.readFully(buff);
						
						var nextPos=calcElementOffset(i+1, getElementSize());
						io.setPos(nextPos);
						io.write(buff);
					}
					yield 0;
				}
			};
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
	public RuntimeType<T> getElementType(){
		return type;
	}
	
	@Override
	public void free() throws IOException{
		Set<Chunk> chunks=new HashSet<>();
		var        prov  =getDataProvider();
		
		new MemoryWalker(this).walk(true, ref->{
			if(ref.isNull()) return;
			ref.getPtr().dereference(prov).streamNext().forEach(chunks::add);
		});
		
		prov.getMemoryManager().free(new ArrayList<>(chunks));
	}
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "C";
	}
}
