package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

public abstract class AbstractUnmanagedIOList<T extends IOInstance<T>, SELF extends AbstractUnmanagedIOList<T, SELF>> extends IOInstance.Unmanaged<SELF> implements IOList<T>{
	
	@IOValue
	private long size;
	
	private final IOField<SELF, ?> sizeField=getThisStruct().getFields().byName("size").orElseThrow();
	
	public AbstractUnmanagedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef, TypeDefinition.Check check){super(provider, reference, typeDef, check);}
	public AbstractUnmanagedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef)                            {super(provider, reference, typeDef);}
	
	public abstract Struct<T> getElementType();
	
	@Override
	public boolean equals(Object o){
		if(this==o){
			return true;
		}
		if(!(o instanceof IOList<?> that)){
			return false;
		}
		
		var siz=size();
		if(siz!=that.size()){
			return false;
		}
		
		if(that instanceof AbstractUnmanagedIOList<?, ?> ioL&&
		   !getElementType().equals(ioL.getElementType())){
			return false;
		}
		
		var iThis=iterator();
		var iThat=that.iterator();
		
		for(long i=0;i<siz;i++){
			var vThis=iThis.next();
			var vThat=iThat.next();
			
			if(!vThis.equals(vThat)){
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+Long.hashCode(size());
		result=31*result+getElementType().hashCode();
		return result;
	}
	
	@NotNull
	protected String getStringPrefix(){return "";}
	
	@Override
	public String toString(){
		return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", getStringPrefix()+"[", "]"));
	}
	@Override
	public String toShortString(){
		return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		throw new UnsupportedOperationException();
	}
	@Override
	public void add(T value) throws IOException{
		throw new UnsupportedOperationException();
	}
	@Override
	public void remove(long index) throws IOException{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		T val=getElementType().requireEmptyConstructor().get();
		if(initializer!=null){
			initializer.accept(val);
		}
		add(val);
		return val;
	}
	
	@Override
	public long size(){
		return size;
	}
	
	protected void deltaSize(long delta) throws IOException{
		this.size+=delta;
		writeManagedField(sizeField);
	}
	
	protected final void checkSize(long index){
		checkSize(index, 0);
	}
	protected final void checkSize(long index, long sizeMod){
		Objects.checkIndex(index, size());
	}
}
