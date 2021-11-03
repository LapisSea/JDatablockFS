package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChainWalker;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class LinkedIOList<T extends IOInstance<T>> extends AbstractUnmanagedIOList<T, LinkedIOList<T>>{
	
	
	public static class Node<T extends IOInstance<T>> extends IOInstance.Unmanaged<Node<T>> implements IterablePP<Node<T>>{
		
		private static final TypeDefinition.Check NODE_TYPE_CHECK=new TypeDefinition.Check(
			LinkedIOList.Node.class,
			List.of(t->{
				var c=t.getTypeClass();
				if(!IOInstance.isManaged(c)) throw new ClassCastException("not managed");
				if(Modifier.isAbstract(c.getModifiers())) throw new ClassCastException(c+" is abstract");
			})
		);
		
		private static final NumberSize SIZE_VAL_SIZE=NumberSize.LARGEST;
		
		public static <T extends IOInstance<T>> Node<T> allocValNode(T value, Node<T> next, SizeDescriptor<T> sizeDescriptor, TypeDefinition nodeType, ChunkDataProvider provider) throws IOException{
			var chunk=AllocateTicket.bytes(SIZE_VAL_SIZE.bytes+switch(sizeDescriptor){
				case SizeDescriptor.Fixed<T> f -> f.get(WordSpace.BYTE);
				case SizeDescriptor.Unknown<T> f -> f.calcUnknown(value, WordSpace.BYTE);
			}).submit(provider);
			return new Node<>(provider, chunk.getPtr().makeReference(), nodeType, value, next);
		}
		
		private final StructPipe<T> valuePipe;
		
		public Node(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef, T val, Node<T> next) throws IOException{
			this(provider, reference, typeDef);
			if(next!=null) setNext(next);
			if(val!=null) setValue(val);
		}
		
		public Node(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef){
			super(provider, reference, typeDef, NODE_TYPE_CHECK);
			
			var type=(Struct<T>)typeDef.argAsStruct(0);
			type.requireEmptyConstructor();
			this.valuePipe=StructPipe.of(getPipe().getClass(), type);
		}
		private void ensureNextSpace(RandomIO io) throws IOException{
			var skipped=io.skip(SIZE_VAL_SIZE.bytes);
			var toWrite=SIZE_VAL_SIZE.bytes-skipped;
			Utils.zeroFill(io::write, toWrite);
			io.setPos(0);
		}
		
		@Override
		public Stream<IOField<Node<T>, ?>> listUnmanagedFields(){
			var that=this;
			
			var valueAccessor=new AbstractFieldAccessor<Node<T>>(null, "value"){
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
			
			var nextAccessor=new AbstractFieldAccessor<Node<T>>(null, "next"){
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
			};
			
			SizeDescriptor<Node<T>> valDesc;
			{
				var desc =valuePipe.getSizeDescriptor();
				if(desc.hasFixed()) valDesc=SizeDescriptor.Fixed.of(desc);
				else valDesc=new SizeDescriptor.Unknown<>(desc.getWordSpace(), desc.getMin(), desc.getMax(), inst->{
					var val=valueAccessor.get(inst);
					if(val==null) return 0;
					return desc.calcUnknown(val);
				});
			}
			
			return Stream.of(
				new IOField.Ref.NoIO<Node<T>, Node<T>>(nextAccessor, SizeDescriptor.Fixed.of(SIZE_VAL_SIZE.bytes)){
					@Override
					public Reference getReference(Node<T> instance){
						ChunkPointer next;
						try{
							next=readNextPtr();
						}catch(IOException e){
							throw new RuntimeException();
						}
						return next.isNull()?new Reference():next.makeReference();
					}
					@Override
					public StructPipe<Node<T>> getReferencedPipe(Node<T> instance){
						return getPipe();
					}
				},
				new IOField.NoIO<Node<T>, T>(valueAccessor, valDesc));
		}
		
		T getValue() throws IOException{
			try(var io=this.getReference().io(this)){
				if(io.getSize()<SIZE_VAL_SIZE.bytes){
					return null;
				}
				io.skipExact(SIZE_VAL_SIZE.bytes);
				if(io.remaining()==0){
					return null;
				}
				return valuePipe.readNew(getChunkProvider(), io, getGenerics());
			}
		}
		boolean hasValue() throws IOException{
			try(var io=this.getReference().io(this)){
				if(io.getSize()<SIZE_VAL_SIZE.bytes){
					return false;
				}
				io.skipExact(SIZE_VAL_SIZE.bytes);
				return io.remaining()!=0;
			}
		}
		
		void setValue(T value) throws IOException{
			try(var io=this.getReference().io(this)){
				ensureNextSpace(io);
				io.skipExact(SIZE_VAL_SIZE.bytes);
				if(value!=null){
					valuePipe.write(this, io, value);
				}
				io.trim();
			}
		}
		private ChunkPointer readNextPtr() throws IOException{
			ChunkPointer chunk;
			try(var io=getReference().io(this)){
				if(io.remaining()==0){
					return ChunkPointer.NULL;
				}
				chunk=ChunkPointer.read(SIZE_VAL_SIZE, io);
			}
			return chunk;
		}
		
		Node<T> getNext() throws IOException{
			var ptr=readNextPtr();
			if(ptr.isNull()) return null;
			
			return new Node<>(getChunkProvider(), new Reference(ptr, 0), getTypeDef());
		}
		
		public void setNext(Node<T> next) throws IOException{
			try(var io=getReference().io(this)){
				ChunkPointer ptr;
				if(next==null) ptr=ChunkPointer.NULL;
				else ptr=next.getReference().getPtr();
				SIZE_VAL_SIZE.write(io, ptr);
			}
		}
		@Override
		public String toShortString(){
			try{
				var val   =getValue();
				var result=new StringBuilder().append("{").append(val==null?null:val.toShortString());
				
				var next=readNextPtr();
				if(!next.isNull()){
					result.append(" -> ").append(next);
				}
				return result.append("}").toString();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		@Override
		public String toString(){
			return "Node"+toShortString();
		}
		
		private static class NodeIterator<T extends IOInstance<T>> implements IOIterator.Iter<Node<T>>{
			
			private Node<T>     node;
			private IOException e;
			
			private NodeIterator(Node<T> node){
				this.node=node;
			}
			
			@Override
			public boolean hasNext(){
				return node!=null;
			}
			
			@Override
			public Node<T> ioNext() throws IOException{
				
				if(e!=null){
					throw e;
				}
				
				Node<T> next;
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
		public final IOIterator.Iter<Node<T>> iterator(){
			return new NodeIterator<>(this);
		}
		public final IOIterator.Iter<T> valueIterator(){
			return new LinkedValueIterator<>(this);
		}
	}
	
	private static class LinkedValueIterator<T extends IOInstance<T>> implements IOIterator.Iter<T>{
		
		private final Iter<Node<T>> nodes;
		
		private LinkedValueIterator(Node<T> node){
			if(node==null){
				nodes=Utils.emptyIter();
			}else{
				nodes=node.iterator();
			}
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
	}
	
	private class LinkedListIterator extends IOListIterator.AbstractIndex<T>{
		
		private Node<T> node;
		private long    nodeIndex;
		
		public LinkedListIterator(long cursorStart){
			super(cursorStart);
		}
		
		
		private void resetNode() throws IOException{
			node=getHead();
			nodeIndex=0;
		}
		private void walkNode(long index) throws IOException{
			if(node==null){
				resetNode();
			}
			if(nodeIndex==index) return;
			if(nodeIndex>index){
				resetNode();
			}
			
			node=getNode(node, nodeIndex, index);
			nodeIndex=index;
		}
		
		@Override
		protected T getElement(long index) throws IOException{
			walkNode(index);
			return node.getValue();
		}
		
		@Override
		protected void removeElement(long index) throws IOException{
			if(index>0){
				walkNode(index-1);
				popNodeFromPrev(node);
			}else{
				LinkedIOList.this.remove(index);
				resetNode();
			}
		}
		
		@Override
		protected void setElement(long index, T value) throws IOException{
			walkNode(index);
			node.setValue(value);
		}
		@Override
		protected void addElement(long index, T value) throws IOException{
			if(index>0){
				walkNode(index-1);
				insertNodeInFrontOf(node, value);
			}else{
				LinkedIOList.this.add(index, value);
				resetNode();
			}
		}
		@Override
		protected long getSize(){
			return LinkedIOList.this.size();
		}
	}
	
	private static final TypeDefinition.Check LIST_TYPE_CHECK=new TypeDefinition.Check(
		LinkedIOList.class,
		List.of(t->{
			if(!IOInstance.isManaged(t)) throw new ClassCastException("not managed");
		})
	);
	
	private final IOField<LinkedIOList<T>, Node<T>> headField=(IOField<LinkedIOList<T>, Node<T>>)Struct.Unmanaged.thisClass().getFields().byName("head").orElseThrow();
	
	@IOValue
	@IONullability(IONullability.Mode.NULLABLE)
	private Node<T> head;
	
	private final StructPipe<T> elementPipe;
	
	
	@SuppressWarnings("unchecked")
	public LinkedIOList(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef, LIST_TYPE_CHECK);
		
		var type=(Struct<T>)typeDef.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=StructPipe.of(this.getPipe().getClass(), type);
		
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	public Struct<T> getElementType(){
		return elementPipe.getType();
	}
	
	private Node<T> getNode(long index) throws IOException{
		return getNode(getHead(), 0, index);
	}
	private Node<T> getNode(Node<T> start, long startIndex, long index) throws IOException{
		var node=start;
		for(long i=startIndex;i<index;i++){
			node=node.getNext();
		}
		return node;
	}
	
	@Override
	public T get(long index) throws IOException{
		checkSize(index);
		return getNode(index).getValue();
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		checkSize(index);
		getNode(index).setValue(value);
	}
	
	private TypeDefinition nodeType(){
		return new TypeDefinition(
			LinkedIOList.Node.class,
			getTypeDef().arg(0)
		);
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		checkSize(index, 1);
		
		if(index==size()){
			add(value);
			return;
		}
		
		if(index==0){
			var head=getHead();
			setHead(Node.allocValNode(value, head, elementPipe.getSizeDescriptor(), nodeType(), getChunkProvider()));
			return;
		}
		
		var prevNode=getNode(index-1);
		
		insertNodeInFrontOf(prevNode, value);
	}
	private void insertNodeInFrontOf(Node<T> prevNode, T value) throws IOException{
		var     node   =prevNode.getNext();
		Node<T> newNode=Node.allocValNode(value, node, elementPipe.getSizeDescriptor(), nodeType(), getChunkProvider());
		prevNode.setNext(newNode);
		deltaSize(1);
	}
	
	@Override
	public void add(T value) throws IOException{
		Node<T> newNode=Node.allocValNode(value, null, elementPipe.getSizeDescriptor(), nodeType(), getChunkProvider());
		
		if(isEmpty()){
			setHead(newNode);
		}else{
			getLastNode().setNext(newNode);
		}
		
		deltaSize(1);
	}
	
	@Override
	public void remove(long index) throws IOException{
		checkSize(index);
		
		if(index==0){
			if(isLast(index)){
				setHead(null);
			}else{
				var newHead=getNode(1);
				setHead(newHead);
			}
			deltaSize(-1);
		}else{
			popNodeFromPrev(getNode(index-1));
		}
	}
	
	private void popNodeFromPrev(Node<T> prevNode) throws IOException{
		var toPop=prevNode.getNext();
		if(toPop==null) return;
		var nextNode=toPop.getNext();
		
		prevNode.setNext(nextNode);
		deltaSize(-1);
	}
	
	private boolean isLast(long index){
		return index==size()-1;
	}
	
	private Node<T> getLastNode() throws IOException{
		return getNode(size()-1);
	}
	
	private Node<T> getHead() throws IOException{
		readManagedField(headField);
		return head;
	}
	private void setHead(Node<T> head) throws IOException{
		this.head=head;
		writeManagedField(headField);
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
		try{
			return new LinkedValueIterator<>(getHead());
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		return new LinkedListIterator(startIndex);
	}
	
	@Override
	public void free() throws IOException{
		
		List<Chunk> chunks=new ArrayList<>(Math.toIntExact(size()+1));
		
		UnsafeConsumer<IOInstance.Unmanaged<?>, IOException> yum=inst->{
			var c=inst.getReference()
			          .getPtr()
			          .dereference(getChunkProvider());
			
			for(Chunk chunk : new ChainWalker(c)){
				chunks.add(chunk);
			}
		};
		
		var node=getHead();
		
		do{
			yum.accept(node);
		}while((node=node.getNext())!=null);
		
		yum.accept(this);
		
		getChunkProvider()
			.getMemoryManager()
			.free(chunks);
	}
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "L";
	}
}
