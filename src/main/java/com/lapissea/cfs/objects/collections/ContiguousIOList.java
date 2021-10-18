package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class ContiguousIOList<T extends IOInstance<T>> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>>{
	
	private static final TypeDefinition.Check TYPE_CHECK=new TypeDefinition.Check(
		ContiguousIOList.class,
		List.of(t->{
			if(!IOInstance.isManaged(t)) throw new ClassCastException("not a managed "+IOInstance.class.getSimpleName());
		})
	);
	
	private final long          sizePerElement;
	private final StructPipe<T> elementPipe;
	
	public ContiguousIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
		var type=(Struct<T>)typeDef.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=FixedContiguousStructPipe.of(type);
		var desc=elementPipe.getSizeDescriptor();
		sizePerElement=desc.toBytes(desc.getFixed()).orElseThrow();
		
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listUnmanagedFields(){
		SizeDescriptor<ContiguousIOList<T>> descriptor=new SizeDescriptor.Fixed<>(sizePerElement);
		return LongStream.range(0, size()).mapToObj(index->{
			return new IOField.NoIO<>(new AbstractFieldAccessor<>(null, "ArrayElement["+index+"]"){
				@Override
				public Type getGenericType(GenericContext genericContext){
					return getTypeDef().arg(0).generic();
				}
				@Override
				public Object get(ContiguousIOList<T> instance){
					return ContiguousIOList.this.getUnsafe(index);
				}
				@Override
				public void set(ContiguousIOList<T> instance, Object value){
					try{
						ContiguousIOList.this.set(index, (T)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			}, descriptor);
		});
	}
	
	private long calcElementOffset(long index){
		var siz=calcSize();
		return siz+sizePerElement*index;
	}
	private void writeAt(long index, T value) throws IOException{
		try(var io=selfIO()){
			var pos    =calcElementOffset(index);
			var skipped=io.skip(pos);
			if(skipped!=pos) throw new IOException();
			
			elementPipe.write(this, io, value);
		}
	}
	private T readAt(long index) throws IOException{
		try(var io=selfIO()){
			var pos=calcElementOffset(index);
			io.skipExact(pos);
			
			return elementPipe.readNew(getChunkProvider(), io, getGenerics());
		}
	}
	
	@Override
	public T get(long index) throws IOException{
		checkSize(index);
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
	public void remove(long index) throws IOException{
		checkSize(index);
		
		shift(index, ShiftAction.SQUASH);
		
		deltaSize(-1);
	}
	
	private enum ShiftAction{
		SQUASH, FORWARD_DUP
	}
	
	private void shift(long index, ShiftAction action) throws IOException{
		try(var io=selfIO()){
			byte[] buff=new byte[Math.toIntExact(sizePerElement)];
			
			int dummy=switch(action){
				case SQUASH -> {
					for(long i=index;i<size()-1;i++){
						var nextPos=calcElementOffset(i+1);
						io.setPos(nextPos);
						io.readFully(buff);
						
						var pos=calcElementOffset(i);
						io.setPos(pos);
						io.write(buff);
					}
					
					var lastOff=calcElementOffset(size()-1);
					io.setCapacity(lastOff);
					yield 0;
				}
				case FORWARD_DUP -> {
					var lastOff=calcElementOffset(size()+1);
					io.setCapacity(lastOff);
					
					for(long i=index;i<size();i++){
						
						var pos=calcElementOffset(i);
						io.setPos(pos);
						io.readFully(buff);
						
						var nextPos=calcElementOffset(i+1);
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
			var cap=calcElementOffset(capacity);
			io.ensureCapacity(cap);
		}
	}
	
	@Override
	public Struct<T> getElementType(){
		return elementPipe.getType();
	}
	
	@Override
	public void free() throws IOException{
		Set<Chunk> chunks=new HashSet<>();
		var        prov  =getChunkProvider();
		
		new MemoryWalker().walk(prov, this, getReference(), getPipe(),
		                        ref->ref.getPtr().dereference(prov).streamNext().forEach(chunks::add));
		
		getReference().getPtr().dereference(prov).streamNext().forEach(chunks::add);
		
		LogUtil.println(chunks);
		
		prov.getMemoryManager().free(new ArrayList<>(chunks));
	}
}
