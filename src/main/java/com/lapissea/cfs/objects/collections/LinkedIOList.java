package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class LinkedIOList<T extends IOInstance<T>> extends IOInstance.Unmanaged<LinkedIOList<T>> implements IOList<T>{
	
	
	private static class Node<T extends IOInstance<T>> extends IOInstance.Unmanaged<Node<T>>{
		
		private static final TypeDefinition.Check NODE_TYPE_CHECK=new TypeDefinition.Check(
			LinkedIOList.Node.class,
			List.of(t->{
				var c=t.getTypeClass();
				if(!IOInstance.isManaged(c)) throw new ClassCastException("not managed");
				if(Modifier.isAbstract(c.getModifiers())) throw new ClassCastException(c+" is abstract");
			})
		);
		
		private static final NumberSize SIZE_VAL_SIZE=NumberSize.LARGEST;
		
		private final StructPipe<T> valuePipe;
		
		public Node(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
			super(provider, reference, typeDef, NODE_TYPE_CHECK);
			
			var type=(Struct<T>)typeDef.argAsStruct(0);
			type.requireEmptyConstructor();
			this.valuePipe=ContiguousStructPipe.of(type);
			
			try(var io=this.getReference().io(this)){
				var skipped=io.skip(SIZE_VAL_SIZE.bytes);
				var toWrite=SIZE_VAL_SIZE.bytes-skipped;
				Utils.zeroFill(io::write, toWrite);
			}
		}
		
		@Override
		public Stream<IOField<Node<T>, ?>> listUnmanagedFields(){
			var that=this;
			
			var valueAccessor=new IFieldAccessor<Node<T>>(){
				@Override
				public Struct<Node<T>> getDeclaringStruct(){
					throw NotImplementedException.infer();//TODO: implement .getDeclaringStruct()
				}
				@NotNull
				@Override
				public String getName(){
					return "value";
				}
				@Override
				public Type getGenericType(GenericContext genericContext){
					return that.getTypeDef().arg(0).generic();
				}
				@Override
				public T get(Node<T> instance){
					try{
						return instance.getValue();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public void set(Node<T> instance, Object value){
					throw NotImplementedException.infer();//TODO: implement .set()
				}
			};
			
			return Stream.of(new IOField<Node<T>, T>(valueAccessor){
				@Override
				public SizeDescriptor<Node<T>> getSizeDescriptor(){
					var desc=valuePipe.getSizeDescriptor();
					{
						var fixed=desc.getFixed();
						if(fixed.isPresent()) return SizeDescriptor.Fixed.of(desc.getWordSpace(), fixed.getAsLong());
					}
					
					return new SizeDescriptor.Unknown<>(desc.getWordSpace(), desc.getMin(), desc.getMax()){
						@Override
						public long calcUnknown(Node<T> instance){
							return desc.calcUnknown(valueAccessor.get(instance));
						}
					};
				}
				@Override
				public List<IOField<Node<T>, ?>> write(ChunkDataProvider provider, ContentWriter dest, Node<T> instance) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .write()
				}
				@Override
				public void read(ChunkDataProvider provider, ContentReader src, Node<T> instance, GenericContext genericContext) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .read()
				}
				@Override
				public void skipRead(ChunkDataProvider provider, ContentReader src, Node<T> instance, GenericContext genericContext) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .skipRead()
				}
			}, new IOField<Node<T>, Node<T>>(new IFieldAccessor<>(){
				@Override
				public Struct<Node<T>> getDeclaringStruct(){
					throw NotImplementedException.infer();//TODO: implement .getDeclaringStruct()
				}
				@NotNull
				@Override
				public String getName(){
					return "next";
				}
				@Override
				public Type getGenericType(GenericContext genericContext){
					return that.getTypeDef().generic();
				}
				@Override
				public Object get(Node<T> instance){
					try{
						return getNext();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public void set(Node<T> instance, Object value){
					try{
						instance.setNext((Node<T>)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			}){
				@Override
				public SizeDescriptor<Node<T>> getSizeDescriptor(){
					return SizeDescriptor.Fixed.of(SIZE_VAL_SIZE.bytes);
				}
				@Override
				public List<IOField<Node<T>, ?>> write(ChunkDataProvider provider, ContentWriter dest, Node<T> instance) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .write()
				}
				@Override
				public void read(ChunkDataProvider provider, ContentReader src, Node<T> instance, GenericContext genericContext) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .read()
				}
				@Override
				public void skipRead(ChunkDataProvider provider, ContentReader src, Node<T> instance, GenericContext genericContext) throws IOException{
					throw NotImplementedException.infer();//TODO: implement .skipRead()
				}
			});
		}
		
		T getValue() throws IOException{
			try(var io=this.getReference().io(this)){
				io.skipExact(SIZE_VAL_SIZE.bytes);
				if(io.remaining()==0){
					return null;
				}
				return valuePipe.readNew(getChunkProvider(), io, getGenerics());
			}
		}
		
		void setValue(T value) throws IOException{
			try(var io=this.getReference().io(this)){
				io.skipExact(SIZE_VAL_SIZE.bytes);
				if(value!=null){
					valuePipe.write(this, io, value);
				}
				io.trim();
			}
		}
		
		Node<T> getNext() throws IOException{
			ChunkPointer chunk;
			try(var io=getReference().io(this)){
				if(io.remaining()==0){
					return null;
				}
				chunk=ChunkPointer.read(SIZE_VAL_SIZE, io);
			}
			if(chunk.isNull()) return null;
			
			return new Node<>(getChunkProvider(), new Reference(chunk, 0), getTypeDef());
		}
		public void setNext(Node<T> next) throws IOException{
			try(var io=getReference().io(this)){
				ChunkPointer ptr;
				if(next==null) ptr=ChunkPointer.NULL;
				else ptr=next.getReference().getPtr();
				SIZE_VAL_SIZE.write(io, ptr);
			}
		}
	}
	
	private static final TypeDefinition.Check LIST_TYPE_CHECK=new TypeDefinition.Check(
		LinkedIOList.class,
		List.of(t->{
			if(!IOInstance.isManaged(t)) throw new ClassCastException("not managed");
		})
	);
	
	private final IOField<LinkedIOList<T>, Node<T>> headField=(IOField<LinkedIOList<T>, Node<T>>)Struct.Unmanaged.thisClass().getFields().byName("head").orElseThrow();
	private final IOField<LinkedIOList<T>, Long>    sizeField=(IOField<LinkedIOList<T>, Long>)Struct.Unmanaged.thisClass().getFields().byName("size").orElseThrow();
	
	@IOValue
	private long size;
	
	@IOValue
	@IONullability(IONullability.Mode.NULLABLE)
	private Node<T> head;
	
	private final StructPipe<T> elementPipe;
	
	
	@SuppressWarnings("unchecked")
	public LinkedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef, LIST_TYPE_CHECK);
		
		var type=(Struct<T>)typeDef.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=ContiguousStructPipe.of(type);
		
		try(var io=reference.io(provider)){
			if(io.getSize()==0){
				writeManagedFields();
			}
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	public long size(){return size;}
	
	private Node<T> getNode(long index) throws IOException{
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
	
	private TypeDefinition nodeType(){
		return new TypeDefinition(
			LinkedIOList.Node.class,
			getTypeDef().arg(0)
		);
	}
	
	@Override
	public void add(T value) throws IOException{
		var chunk=AllocateTicket.bytes(NumberSize.LARGEST.bytes+switch(elementPipe.getSizeDescriptor()){
			case SizeDescriptor.Fixed<T> f -> f.get();
			case SizeDescriptor.Unknown<T> f -> f.calcUnknown(value);
		}).submit(getChunkProvider());
		var nood=new Node<T>(getChunkProvider(), chunk.getPtr().makeReference(), nodeType());
		nood.setValue(value);
		
		if(isEmpty()){
			writeHead(nood);
		}else{
			getLastNode().setNext(nood);
		}
		
		size++;
		writeManagedField(sizeField);
	}
	
	@Override
	public Stream<IOField<LinkedIOList<T>, ?>> listUnmanagedFields(){
		return Stream.of();
	}
	
	private Node<T> getLastNode() throws IOException{
		return getNode(size()-1);
	}
	
	private Node<T> readHead() throws IOException{
		readManagedField(headField);
		if(head==null){
			allocateNulls(getChunkProvider());
		}
		return head;
	}
	private void writeHead(Node<T> head) throws IOException{
		this.head=head;
		writeManagedField(headField);
	}
}
