package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class LinkedIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<LinkedIOList<T>> implements IOList<T>{
	
	
	private static class Node<T extends IOInstance<T>> extends IOInstance.Unmanaged<Node<T>>{
		
		public Node(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef){
			super(provider, reference, typeDef);
		}
		
		@Override
		public Stream<IOField<Node<T>, ?>> listUnmanagedFields(){
			return Stream.of();
		}
		
		T getValue(){
			throw NotImplementedException.infer();//TODO
		}
		void setValue(T value){
			throw NotImplementedException.infer();//TODO
		}
		Node<T> getNext(){
			throw NotImplementedException.infer();//TODO
		}
		public void setNext(Node<T> next){
			throw NotImplementedException.infer();//TODO
		}
	}
	
	private static final TypeDefinition.Check LIST_TYPE_CHECK=new TypeDefinition.Check(
		LinkedIOList.class,
		List.of(t->{
			if(!IOInstance.isManaged(t)) throw new ClassCastException("not managed");
		})
	);
	
	@IOValue
	private long size;
	
	private final Struct<T>     type;
	private final long          minSizePerElement;
	private final StructPipe<T> elementPipe;
	
	
	@SuppressWarnings("unchecked")
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
	
	private Node<T> getNode(long index){
		var node=readHead();
		for(long i=0;i<index;i++){
			node=node.getNext();
		}
		return node;
	}
	
	@Override
	public T get(long index) throws IOException{
		Objects.checkIndex(index, size());
		return getNode(index).getValue();
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		Objects.checkIndex(index, size());
		getNode(index).setValue(value);
	}
	
	@Override
	public void add(T value) throws IOException{
		var chunk=AllocateTicket.bytes(NumberSize.LARGEST.bytes+switch(elementPipe.getSizeDescriptor()){
			case SizeDescriptor.Fixed<T> f -> f.get();
			case SizeDescriptor.Unknown<T> f -> f.calcUnknown(value);
		}).submit(getChunkProvider());
		var nood=new Node<T>(getChunkProvider(), chunk.getPtr().makeReference(), getTypeDef().arg(0));
		nood.setValue(value);
		
		if(isEmpty()){
			writeHead(nood);
		}else{
			getLastNode().setNext(nood);
		}
	}
	
	@Override
	public Stream<IOField<LinkedIOList<T>, ?>> listUnmanagedFields(){
		return Stream.of();
	}
	
	private Node<T> getLastNode(){
		throw NotImplementedException.infer();//TODO
	}
	private Node<T> readHead(){
		throw NotImplementedException.infer();//TODO
	}
	private void writeHead(Node<T> head) throws IOException{
		throw NotImplementedException.infer();//TODO
	}
}
