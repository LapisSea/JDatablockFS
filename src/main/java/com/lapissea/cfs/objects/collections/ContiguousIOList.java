package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ContiguousIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<ContiguousIOList<T>> implements IOList<T>{
	
	
	private static final TypeDefinition.Check TYPE_CHECK=new TypeDefinition.Check(
		ContiguousIOList.class,
		List.of(c->UtilL.instanceOf(c, IOInstance.class)&&!UtilL.instanceOf(c, IOInstance.Unmanaged.class))
	);
	
	@IOValue
	private int size;
	
	private final Struct<T>     type;
	private final long          sizePerElement;
	private final StructPipe<T> elementPipe;
	
	public ContiguousIOList(ChunkDataProvider provider, Reference reference, TypeDefinition data) throws IOException{
		super(provider, reference);
		TYPE_CHECK.ensureValid(data);
		this.type=(Struct<T>)data.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=FixedContiguousStructPipe.of(type);
		sizePerElement=elementPipe.getSizeDescriptor().getFixed().orElseThrow();
		
		try(var io=reference.io(provider)){
			if(io.getSize()==0) writeManagedFields();
		}
		readManagedFields();
	}
	
	@Override
	public long size(){
		return size;
	}
	
	private void writeAt(long index, T value) throws IOException{
		try(var io=selfIO()){
			var pos    =calcSize()+sizePerElement*index;
			var skipped=io.skip(pos);
			if(skipped!=pos) throw new IOException();
			
			elementPipe.write(io, value);
		}
	}
	private T readAt(long index) throws IOException{
		try(var io=selfIO()){
			var pos    =calcSize()+sizePerElement*index;
			var skipped=io.skip(pos);
			if(skipped!=pos) throw new IOException();
			
			return elementPipe.readNew(getProvider(), io);
		}
	}
	
	@Override
	public T get(long index) throws IOException{
		Objects.checkIndex(index, size);
		return readAt(index);
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		Objects.checkIndex(index, size);
		writeAt(index, value);
	}
	
	@Override
	public void add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		writeAt(size, value);
		size++;
		writeManagedFields();
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof ContiguousIOList<?> that)) return false;
		
		if(size!=that.size) return false;
		if(!type.equals(that.type)) return false;
		
		for(long i=0;i<size;i++){
			try{
				if(!get(i).equals(that.get(i))) return false;
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		return true;
	}
	
	@Override
	public String toString(){
		return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
	}
}
