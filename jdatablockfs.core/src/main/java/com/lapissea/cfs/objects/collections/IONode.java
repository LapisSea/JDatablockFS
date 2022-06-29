package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.annotations.IOValueUnmanaged;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class IONode<T> extends IOInstance.Unmanaged<IONode<T>> implements IterablePP<IONode<T>>{
	
	private static class LinkedValueIterator<T> implements IOList.IOIterator.Iter<T>{
		
		private final Iter<IONode<T>> nodes;
		
		private LinkedValueIterator(IONode<T> node){
			nodes=node.iterator();
		}
		
		@Override
		public boolean hasNext(){
			return nodes.hasNext();
		}
		
		@Override
		public T ioNext() throws IOException{
			var node=nodes.next();
			return node.getValue();
		}
		@Override
		public void ioRemove() throws IOException{
			nodes.ioRemove();
		}
	}
	
	private static final List<Annotation> ANNOTATIONS=List.of(
		IOFieldTools.makeAnnotation(IOType.Dynamic.class, Map.of()),
		IOFieldTools.makeAnnotation(IONullability.class, Map.of("value", IONullability.Mode.NULLABLE))
	);
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	@IOValueUnmanaged(index=0)
	private static <T> IOField<IONode<T>, Object> makeValField(){
		var valueAccessor=new AbstractFieldAccessor<IONode<T>>(null, "value"){
			@NotNull
			@Override
			public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
				return ANNOTATIONS.stream().filter(a->UtilL.instanceOfObj(a, annotationClass)).map(a->(T1)a).findAny();
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return Object.class;
			}
			@Override
			public T get(Struct.Pool<IONode<T>> ioPool, IONode<T> instance){
				try{
					return instance.getValue();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public void set(Struct.Pool<IONode<T>> ioPool, IONode<T> instance, Object value){
				try{
					if(value!=null){
						var arg=instance.getTypeDef().arg(0);
						if(!UtilL.instanceOfObj(value, arg.getTypeClass(instance.getDataProvider().getTypeDb()))) throw new ClassCastException(arg+" not compatible with "+value);
					}
					
					instance.setValue((T)value);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		};
		
		SizeDescriptor<IONode<T>> valDesc=SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst)->{
			var val=valueAccessor.get(ioPool, inst);
			if(val==null) return 0;
			var siz=inst.valueStorage.inlineSize();
			if(inst.valueStorage instanceof ValueStorage.Instance iStor){
				try{
					return iStor.getPipe().calcUnknownSize(prov, (IOInstance)inst.getValue(), WordSpace.BYTE);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			return siz;
		});
		
		return new IOField.NoIO<>(valueAccessor, valDesc);
	}
	
	@IOValueUnmanaged(index=1)
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> IOField<IONode<T>, IONode<T>> makeNextField(){
		var nextAccessor=new AbstractFieldAccessor<IONode<T>>(null, "next"){
			@NotNull
			@Override
			public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
				if(annotationClass!=IONullability.class) return Optional.empty();
				return (Optional<T1>)Optional.of(ANNOTATIONS.get(1));
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				if(genericContext==null) return IONode.class;
				throw new NotImplementedException();
			}
			@Override
			public Object get(Struct.Pool<IONode<T>> ioPool, IONode<T> instance){
				try{
					return instance.getNext();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public void set(Struct.Pool<IONode<T>> ioPool, IONode<T> instance, Object value){
				try{
					instance.setNext((IONode<T>)value);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		};
		
		var next=new IOField.Ref.NoIO<IONode<T>, IONode<T>>(nextAccessor, SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, NumberSize.LARGEST.optionalBytesLong, (ioPool, prov, node)->node.nextSize.bytes)){
			@Override
			public void setReference(IONode<T> instance, Reference newRef){
				if(newRef.getOffset()!=0) throw new NotImplementedException();
				try{
					instance.setNextRaw(newRef.getPtr());
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public Reference getReference(IONode<T> instance){
				ChunkPointer next;
				try{
					next=instance.getNextPtr();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				return next.isNull()?new Reference():next.makeReference();
			}
			@Override
			public StructPipe<IONode<T>> getReferencedPipe(IONode<T> instance){
				return instance.getPipe();
			}
		};
		next.initLateData(FieldSet.of(List.of(getNextSizeField())));
		
		return next;
	}
	
	private static final TypeLink.Check NODE_TYPE_CHECK=new TypeLink.Check(
		IONode.class,
		List.of(t->{
			var c=t.getTypeClass(null);
			if(SupportedPrimitive.isAny(c)) return;
			if(!IOInstance.isManaged(c)) throw new ClassCastException("not managed");
			if(Modifier.isAbstract(c.getModifiers())) throw new ClassCastException(c+" is abstract");
		})
	);
	
	private static final IOField<?, NumberSize> NEXT_SIZE_FIELD         =Struct.thisClass().getFields().requireExact(NumberSize.class, "nextSize");
	private static final int                    NEXT_SIZE_FIELD_MIN_SIZE=Math.toIntExact(getNextSizeField().getSizeDescriptor().getMin(WordSpace.BYTE));
	
	private static NumberSize calcOptimalNextSize(DataProvider provider) throws IOException{
		return NumberSize.bySize(provider.getSource().getIOSize()).next();
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static <T> IONode<T> allocValNode(T value, IONode<T> next, BasicSizeDescriptor<T, ?> sizeDescriptor, TypeLink nodeType, DataProvider provider, OptionalLong positionMagnet) throws IOException{
		int nextBytes;
		if(next!=null) nextBytes=NumberSize.bySize(next.getReference().getPtr()).bytes;
		else nextBytes=calcOptimalNextSize(provider).bytes;
		
		var bytes=1+nextBytes+switch(sizeDescriptor){
			case SizeDescriptor.Fixed<?> f -> f.get(WordSpace.BYTE);
			case SizeDescriptor.Unknown f -> f.calcUnknown(((IOInstance<?>)value).getThisStruct().allocVirtualVarPool(IO), provider, value, WordSpace.BYTE);
			case BasicSizeDescriptor<T, ?> b -> b.calcUnknown(null, provider, value, WordSpace.BYTE);
		};
		
		try(var ignored=provider.getSource().openIOTransaction()){
			var chunk=AllocateTicket.bytes(bytes).withPositionMagnet(positionMagnet).submit(provider);
			return new IONode<>(provider, chunk.getPtr().makeReference(), nodeType, value, next);
		}
	}
	
	private final ValueStorage<T> valueStorage;
	
	@IOValue
	private NumberSize nextSize;
	
	public IONode(DataProvider provider, Reference reference, TypeLink typeDef, T val, IONode<T> next) throws IOException{
		this(provider, reference, typeDef);
		
		var newSiz=calcOptimalNextSize(provider);
		if(newSiz.greaterThan(nextSize)){
			nextSize=newSiz;
			writeManagedFields();
		}
		
		if(next!=null) setNext(next);
		if(val!=null) setValue(val);
	}
	
	public IONode(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef, NODE_TYPE_CHECK);
		
		var magnetProvider=provider.withRouter(t->t.withPositionMagnet(t.positionMagnet().orElse(getReference().getPtr().getValue())));
		
		//noinspection unchecked
		valueStorage=(ValueStorage<T>)ValueStorage.makeStorage(magnetProvider, typeDef.arg(0), getGenerics(), false);
		
		if(isSelfDataEmpty()){
			nextSize=calcOptimalNextSize(provider);
			if(!readOnly) writeManagedFields();
		}else{
			readManagedFields();
		}
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof IONode<?> that)) return false;
		
		if(!this.getReference().equals(that.getReference())){
			return false;
		}
		if(!this.getTypeDef().equals(that.getTypeDef())){
			return false;
		}
		
		try{
			if(!this.getNextPtr().equals(that.getNextPtr())){
				return false;
			}
			if(this.hasValue()!=that.hasValue()){
				return false;
			}
			var v1=this.getValue();
			var v2=that.getValue();
			return Objects.equals(v1, v2);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public int hashCode(){
		return getReference().hashCode();
	}
	
	private void ensureNextSpace(RandomIO io) throws IOException{
		var valueStart=valueStart();
		var skipped   =io.skip(valueStart);
		var toWrite   =valueStart-skipped;
		Utils.zeroFill(io::write, toWrite);
		io.setPos(0);
	}
	
	private T       valueCache;
	private boolean valueRead;
	
	T getValue() throws IOException{
		if(readOnly){
			if(!valueRead){
				valueRead=true;
				valueCache=readValue();
			}
			return valueCache;
		}
		return readValue();
	}
	
	private T readValue() throws IOException{
		
		try(var io=selfIO()){
			var s=io.getSize();
			if(s==0) return null;
			if(s<NEXT_SIZE_FIELD_MIN_SIZE) return null;
			if(DEBUG_VALIDATION){
				assert getPipe().getSpecificFields().get(0).equals(getNextSizeField());
			}
			nextSize=FlagReader.readSingle(io, NumberSize.FLAG_INFO);

//				if(DEBUG_VALIDATION){
//					requireNonFreed();
//				}
			
			long toSkip =nextSize.bytes;
			long skipped=0;
			while(skipped<toSkip){
				long remaining   =toSkip-skipped;
				var  skippedChunk=io.skip(remaining);
				if(skippedChunk==0){
					return null;
				}
				skipped+=skippedChunk;
			}
			if(io.remaining()==0){
				return null;
			}
			return valueStorage.readNew(io);
		}catch(IOException e){
			throw new IOException("failed to get value on "+this.getReference().addOffset(valueStart()).infoString(getDataProvider()), e);
		}
	}
	
	private void requireNonFreed() throws IOException{
		var ch   =this.getReference().getPtr();
		var frees=getDataProvider().getMemoryManager().getFreeChunks();
		if(frees.contains(ch)){
			throw new RuntimeException(frees+" "+ch);
		}
	}
	
	boolean hasValue() throws IOException{
		var nextStart=nextStart();
		try(var io=this.getReference().io(this)){
			if(io.remaining()<nextStart){
				return false;
			}
			io.skipExact(nextStart);
			if(io.remaining()<nextSize.bytes){
				return false;
			}
			io.skipExact(nextSize.bytes);
			assert valueStart()==io.getPos();
			return io.remaining()!=0;
		}
	}
	
	void setValue(T value) throws IOException{
		if(readOnly){
			throw new UnsupportedOperationException();
		}
		try(var io=this.getReference().io(this)){
			ensureNextSpace(io);
			io.skipExact(valueStart());
			if(value!=null){
				valueStorage.write(io, value);
			}
			io.trim();
		}
	}
	
	private ChunkPointer nextPtrCache;
	private boolean      nextPtrRead;
	private ChunkPointer getNextPtr() throws IOException{
		if(readOnly){
			if(!nextPtrRead){
				nextPtrRead=true;
				nextPtrCache=readNextPtr();
			}
			return nextPtrCache;
		}
		return readNextPtr();
	}
	private ChunkPointer readNextPtr() throws IOException{
		readManagedFields();
		ChunkPointer chunk;
		try(var io=getReference().io(this)){
			var start=nextStart();
			if(io.remaining()<=start){
				return ChunkPointer.NULL;
			}
			io.skipExact(start);
			chunk=ChunkPointer.read(nextSize, io);
		}
		return chunk;
	}
	
	private IONode<T> nextCache;
	private boolean   nextRead;
	
	IONode<T> getNext() throws IOException{
		if(readOnly){
			if(!nextRead){
				nextRead=true;
				nextCache=readNext();
			}
			return nextCache;
		}
		return readNext();
	}
	
	public boolean hasNext() throws IOException{
		return !getNextPtr().isNull();
	}
	
	private IONode<T> readNext() throws IOException{
		
		var ptr=getNextPtr();
		if(ptr.isNull()) return null;
		
		return new IONode<>(getDataProvider(), new Reference(ptr, 0), getTypeDef());
	}
	
	private long nextStart(){
		IOField<IONode<T>, NumberSize> f=getNextSizeField();
		return switch(f.getSizeDescriptor()){
			case SizeDescriptor.Unknown<IONode<T>> u -> u.calcUnknown(getPipe().makeIOPool(), getDataProvider(), this, WordSpace.BYTE);
			case SizeDescriptor.Fixed<IONode<T>> fixed -> fixed.get(WordSpace.BYTE);
		};
	}
	
	@SuppressWarnings("unchecked")
	private static <T> IOField<IONode<T>, NumberSize> getNextSizeField(){
		return (IOField<IONode<T>, NumberSize>)NEXT_SIZE_FIELD;
	}
	
	private long valueStart(){
		return nextStart()+nextSize.bytes;
	}
	
	public void setNext(IONode<T> next) throws IOException{
		ChunkPointer ptr;
		if(next==null) ptr=ChunkPointer.NULL;
		else ptr=next.getReference().getPtr();
		
		setNextRaw(ptr);
	}
	private void setNextRaw(ChunkPointer ptr) throws IOException{
		var oldPtr=getNextPtr();
		if(oldPtr.equals(ptr)){
			return;
		}
		
		var newSiz=NumberSize.bySize(ptr);
		if(newSiz.greaterThan(nextSize)){
			var val =getValue();
			var grow=newSiz.bytes-nextSize.bytes;
			nextSize=newSiz;
			getReference().withContext(this).io(io->io.ensureCapacity(io.getCapacity()+grow));
			try(var ignored=getDataProvider().getSource().openIOTransaction()){
				writeManagedFields();
				setValue(val);
			}
		}
		
		try(var io=getReference().io(this)){
			io.skipExact(nextStart());
			nextSize.write(io, ptr);
		}
	}
	
	@Override
	public String toShortString(){
		String  val;
		boolean valCorrupted=false;
		try{
			val=Utils.toShortString(getValue());
		}catch(Throwable e){
			val="CORRUPTED: "+e;
			valCorrupted=true;
		}
		var result=new StringBuilder().append("{").append(Utils.toShortString(val));
		try{
			var next=getNextPtr();
			if(!next.isNull()){
				result.append(" -> ").append(next);
			}
		}catch(Throwable e){
			if(!valCorrupted){
				result.append(" -> ").append("CORRUPTED: ").append(e);
			}
		}
		
		return result.append("}").toString();
	}
	@Override
	public String toString(){
		return this.getClass().getSimpleName()+toShortString();
	}
	
	private static class NodeIterator<T> implements IOList.IOIterator.Iter<IONode<T>>{
		
		private IONode<T>   node;
		private IOException e;
		
		private NodeIterator(IONode<T> node){
			this.node=node;
		}
		
		@Override
		public boolean hasNext(){
			return node!=null;
		}
		
		@Override
		public IONode<T> ioNext() throws IOException{
			
			if(e!=null){
				throw e;
			}
			
			IONode<T> next;
			try{
				next=node.getNext();
			}catch(IOException e){
				this.e=e;
				next=null;
			}
			
			var current=node;
			node=next;
			return current;
		}
	}
	@Override
	public final IOList.IOIterator.Iter<IONode<T>> iterator(){
		return new NodeIterator<>(this);
	}
	public final IOList.IOIterator.Iter<T> valueIterator(){
		return new LinkedValueIterator<>(this);
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public boolean readValueField(T dest, IOField<?, ?> field) throws IOException{
		if(DEBUG_VALIDATION){
			var struct=field.getAccessor().getDeclaringStruct();
			if(!UtilL.instanceOf(dest.getClass(), struct.getType())){
				throw new ClassCastException(dest.getClass()+" is not compatible with "+field);
			}
			if(!(valueStorage instanceof ValueStorage.InstanceBased)){
				throw new UnsupportedOperationException("Node with type of "+valueStorage.getType().getType()+" is not a valid instance");
			}
		}
		if(!hasValue()) return false;
		var based=(ValueStorage.InstanceBased)valueStorage;
		try(var io=this.getReference().io(this)){
			io.skipExact(valueStart());
			based.readSingle(io, (IOInstance)dest, field);
		}
		return true;
	}
}
