package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

public class ContiguousIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<ContiguousIOList<T>> implements IOList<T>{
	
	@IOValue
	private int size;
	
	private final Struct<T>     type;
	private final long          sizePerElement;
	private final StructPipe<T> elementPipe;
	
	public ContiguousIOList(ChunkDataProvider provider, Reference reference, Struct<T> type) throws IOException{
		super(provider, reference);
		this.type=type;
		this.elementPipe=ContiguousStructPipe.of(type);
		sizePerElement=elementPipe.getSizeDescriptor().fixed().orElseThrow();
		
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
			
			return elementPipe.readNew(io);
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
