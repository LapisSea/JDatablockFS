package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public class LinkedIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<ContiguousIOList<T>> implements IOList<T>{
	
	private static final TypeDefinition.Check TYPE_CHECK=new TypeDefinition.Check(
		LinkedIOList.class,
		List.of(not(IOInstance::isManaged))
	);
	
	public LinkedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef){
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
	}
	
	
	@Override
	public long size(){
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.size()
	}
	@Override
	public T get(long index) throws IOException{
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.get()
	}
	@Override
	public void set(long index, T value) throws IOException{
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.set()
	}
	@Override
	public void add(T value) throws IOException{
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.add()
	}
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listUnmanagedFields(){
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.listUnmanagedFields()
	}
}
