package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Stream;

import static java.util.function.Predicate.*;

public class LinkedIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<LinkedIOList<T>> implements IOList<T>{
	
	
	private static class Node<T extends IOInstance<T>> extends IOInstance<Node<T>>{
	
	}
	
	private static final TypeDefinition.Check TYPE_CHECK=new TypeDefinition.Check(
		LinkedIOList.class,
		List.of(not(IOInstance::isManaged))
	);
	
	@IOValue
	private long size;
	
	private final Struct<T>     type;
	private final long          minSizePerElement;
	private final StructPipe<T> elementPipe;
	
	
	public LinkedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
		
		this.type=(Struct<T>)typeDef.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=FixedContiguousStructPipe.of(type);
		minSizePerElement=elementPipe.getSizeDescriptor().getMin();
		
		try(var io=reference.io(provider)){
			if(io.getSize()==0) writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	public long size(){return size;}
	
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
	public Stream<IOField<LinkedIOList<T>, ?>> listUnmanagedFields(){
		return Stream.of(new IOField<LinkedIOList<T>, Node<?>>(new IFieldAccessor<>(){
			@Override
			public Struct<LinkedIOList<T>> getDeclaringStruct(){
				throw new NotImplementedException();
			}
			@NotNull
			@Override
			public String getName(){
				return "head";
			}
			@Override
			public Type getGenericType(){
				return Node.class;
			}
			@Override
			public Object get(LinkedIOList<T> instance){
				return instance.readHead();
			}
			@Override
			public void set(LinkedIOList<T> instance, Object value){
				try{
					instance.writeHead((Node<T>)value);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}){
			@Override
			public SizeDescriptor<LinkedIOList<T>> getSizeDescriptor(){
				throw NotImplementedException.infer();//TODO: implement .getSizeDescriptor()
			}
			@Override
			public List<IOField<LinkedIOList<T>, ?>> write(ChunkDataProvider provider, ContentWriter dest, LinkedIOList<T> instance) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .write()
			}
			@Override
			public void read(ChunkDataProvider provider, ContentReader src, LinkedIOList<T> instance) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .read()
			}
		});
	}
	
	private Node<T> readHead(){
		throw NotImplementedException.infer();
	}
	private void writeHead(Node<T> head) throws IOException{
		throw NotImplementedException.infer();
	}
}
