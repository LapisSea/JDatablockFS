package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
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
import com.lapissea.cfs.type.field.annotations.IOValueUnmanaged;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

@SuppressWarnings("unchecked")
public class LinkedIOList<T extends IOInstance<T>> extends AbstractUnmanagedIOList<T, LinkedIOList<T>>{
	
	
	public static class Node<T extends IOInstance<T>> extends IOInstance.Unmanaged<Node<T>> implements IterablePP<Node<T>>{
		
		@IOValueUnmanaged
		private static <T extends IOInstance<T>> IOField<Node<T>, ?> makeValField(){
			var valueAccessor=new AbstractFieldAccessor<Node<T>>(null, "value"){
				@Override
				public Type getGenericType(GenericContext genericContext){
					return Object.class;
				}
				@Override
				public T get(Struct.Pool<Node<T>> ioPool, Node<T> instance){
					try{
						return instance.getValue();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public void set(Struct.Pool<Node<T>> ioPool, Node<T> instance, Object value){
					try{
						if(value!=null){
							var arg=instance.getTypeDef().arg(0);
							if(!UtilL.instanceOf(value, arg.getTypeClass(instance.getDataProvider().getTypeDb()))) throw new ClassCastException(arg+" not compatible with "+value);
						}
						
						instance.setValue((T)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			};
			
			SizeDescriptor<Node<T>> valDesc=new SizeDescriptor.Unknown<>(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst)->{
				var val=valueAccessor.get(ioPool, inst);
				if(val==null) return 0;
				return inst.valuePipe.calcUnknownSize(prov, val, WordSpace.BYTE);
			});
			
			return new IOField.NoIO<>(valueAccessor, valDesc);
		}
		
		@IOValueUnmanaged
		private IOField<Node<T>, ?> makeNextField(){
			var nextAccessor=new AbstractFieldAccessor<Node<T>>(null, "next"){
				@Override
				public Type getGenericType(GenericContext genericContext){
					return getTypeDef().generic(null);
				}
				@Override
				public Object get(Struct.Pool<Node<T>> ioPool, Node<T> instance){
					try{
						return getNext();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public void set(Struct.Pool<Node<T>> ioPool, Node<T> instance, Object value){
					try{
						instance.setNext((Node<T>)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			};
			
			var next=new IOField.Ref.NoIO<Node<T>, Node<T>>(nextAccessor, new SizeDescriptor.Unknown<>(WordSpace.BYTE, 0, NumberSize.LARGEST.optionalBytesLong, (ioPool, prov, node)->node.nextSize.bytes)){
				@Override
				public void setReference(Node<T> instance, Reference newRef){
					if(newRef.getOffset()!=0) throw new NotImplementedException();
					try{
						instance.setNextRaw(newRef.getPtr());
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public Reference getReference(Node<T> instance){
					ChunkPointer next;
					try{
						next=readNextPtr();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					return next.isNull()?new Reference():next.makeReference();
				}
				@Override
				public StructPipe<Node<T>> getReferencedPipe(Node<T> instance){
					return getPipe();
				}
			};
			next.initLateData(FieldSet.of(List.of(getNextSizeField())), Stream.of());
			
			return next;
			
		}
		
		private static final TypeLink.Check NODE_TYPE_CHECK=new TypeLink.Check(
			LinkedIOList.Node.class,
			List.of(t->{
				var c=t.getTypeClass(null);
				if(!IOInstance.isManaged(c)) throw new ClassCastException("not managed");
				if(Modifier.isAbstract(c.getModifiers())) throw new ClassCastException(c+" is abstract");
			})
		);
		
		private static NumberSize calcOptimalNextSize(DataProvider provider) throws IOException{
			return NumberSize.bySize(provider.getSource().getIOSize());
		}
		public static <T extends IOInstance<T>> Node<T> allocValNode(T value, Node<T> next, SizeDescriptor<T> sizeDescriptor, TypeLink nodeType, DataProvider provider) throws IOException{
			int nextBytes;
			if(next!=null) nextBytes=NumberSize.bySize(next.getReference().getPtr()).bytes;
			else nextBytes=calcOptimalNextSize(provider).bytes;
			
			var bytes=1+nextBytes+switch(sizeDescriptor){
				case SizeDescriptor.Fixed<T> f -> f.get(WordSpace.BYTE);
				case SizeDescriptor.Unknown<T> f -> f.calcUnknown(value.getThisStruct().allocVirtualVarPool(IO), provider, value, WordSpace.BYTE);
			};
			
			try(var ignored=provider.getSource().openIOTransaction()){
				var chunk=AllocateTicket.bytes(bytes).submit(provider);
				return new Node<>(provider, chunk.getPtr().makeReference(), nodeType, value, next);
			}
		}
		
		private final StructPipe<T> valuePipe;
		
		@IOValue
		private NumberSize nextSize;
		
		public Node(DataProvider provider, Reference reference, TypeLink typeDef, T val, Node<T> next) throws IOException{
			this(provider, reference, typeDef);
			
			var newSiz=calcOptimalNextSize(provider);
			if(newSiz.greaterThan(nextSize)){
				nextSize=newSiz;
				writeManagedFields();
			}
			
			if(next!=null) setNext(next);
			if(val!=null) setValue(val);
		}
		
		public Node(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
			super(provider, reference, typeDef, NODE_TYPE_CHECK);
			
			var type=(Struct<T>)typeDef.argAsStruct(0);
			type.requireEmptyConstructor();
			this.valuePipe=StructPipe.of(getPipe().getClass(), type);
			
			if(isSelfDataEmpty()){
				nextSize=calcOptimalNextSize(provider);
				writeManagedFields();
			}else{
				readManagedFields();
			}
		}
		
		@NotNull
		@Override
		public Stream<IOField<Node<T>, ?>> listDynamicUnmanagedFields(){
			return Stream.of(makeNextField(), makeValField());
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			if(!(o instanceof Node<?> other)) return false;
			
			if(!getReference().equals(other.getReference())){
				return false;
			}
			if(!getTypeDef().equals(other.getTypeDef())){
				return false;
			}
			
			try{
				if(!readNextPtr().equals(other.readNextPtr())){
					return false;
				}
				if(hasValue()!=other.hasValue()){
					return false;
				}
				if(!Objects.equals(getValue(), other.getValue())){
					return false;
				}
				return true;
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
		
		T getValue() throws IOException{
			readManagedFields();
			
			var start=valueStart();
			
			var ch   =this.getReference().getPtr();
			var frees=getDataProvider().getMemoryManager().getFreeChunks();
			
			if(frees.contains(ch)){
				throw new RuntimeException(frees+" "+ch);
			}
			try(var io=this.getReference().io(this)){
				if(io.getSize()<start){
					return null;
				}
				io.skipExact(start);
				if(io.remaining()==0){
					return null;
				}
				return valuePipe.readNew(getDataProvider(), io, getGenerics());
			}catch(Throwable e){
				throw new IOException("failed to get value on "+this.getReference().addOffset(start).infoString(getDataProvider()), e);
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
			try(var io=this.getReference().io(this)){
				ensureNextSpace(io);
				io.skipExact(valueStart());
				if(value!=null){
					if(DEBUG_VALIDATION){
						var size=valuePipe.calcUnknownSize(getDataProvider(), value, WordSpace.BYTE);
						try(var buff=io.writeTicket(size).requireExact().submit()){
							valuePipe.write(getDataProvider(), buff, value);
						}
					}else{
						valuePipe.write(this, io, value);
					}
				}
				io.trim();
			}
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
		
		Node<T> getNext() throws IOException{
			var ptr=readNextPtr();
			if(ptr.isNull()) return null;
			
			return new Node<>(getDataProvider(), new Reference(ptr, 0), getTypeDef());
		}
		
		private long nextStart(){
			IOField<Node<T>, NumberSize> field=getNextSizeField();
			var                          desc =field.getSizeDescriptor();
			return desc.calcUnknown(getPipe().makeIOPool(), getDataProvider(), this, WordSpace.BYTE);
		}
		
		private IOField<Node<T>, NumberSize> getNextSizeField(){
			return getPipe().getSpecificFields().requireExact(NumberSize.class, "nextSize");
		}
		
		private long valueStart(){
			return nextStart()+nextSize.bytes;
		}
		
		public void setNext(Node<T> next) throws IOException{
			ChunkPointer ptr;
			if(next==null) ptr=ChunkPointer.NULL;
			else ptr=next.getReference().getPtr();
			
			setNextRaw(ptr);
		}
		private void setNextRaw(ChunkPointer ptr) throws IOException{
			
			var newSiz=NumberSize.bySize(ptr);
			if(newSiz.greaterThan(nextSize)){
				var val =getValue();
				var grow=newSiz.bytes-nextSize.bytes;
				nextSize=newSiz;
				getReference().withContext(this).io(io->io.ensureCapacity(io.getCapacity()+grow));
				writeManagedFields();
				setValue(val);
			}
			
			try(var io=getReference().io(this)){
				io.skipExact(nextStart());
				nextSize.write(io, ptr);
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
		@Override
		public void ioRemove() throws IOException{
			nodes.ioRemove();
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
	
	private static final TypeLink.Check LIST_TYPE_CHECK=new TypeLink.Check(
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
	public LinkedIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
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
	
	private TypeLink nodeType(){
		return new TypeLink(
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
			setHead(Node.allocValNode(value, head, elementPipe.getSizeDescriptor(), nodeType(), getDataProvider()));
			deltaSize(1);
			return;
		}
		
		var prevNode=getNode(index-1);
		
		insertNodeInFrontOf(prevNode, value);
	}
	private void insertNodeInFrontOf(Node<T> prevNode, T value) throws IOException{
		var     node   =prevNode.getNext();
		Node<T> newNode=Node.allocValNode(value, node, elementPipe.getSizeDescriptor(), nodeType(), getDataProvider());
		prevNode.setNext(newNode);
		deltaSize(1);
	}
	
	@Override
	public void add(T value) throws IOException{
		Node<T> newNode=Node.allocValNode(value, null, elementPipe.getSizeDescriptor(), nodeType(), getDataProvider());
		
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
				var oldHead=getHead();
				setHead(null);
				freeUnmanaged(oldHead);
			}else{
				var oldHead=getHead();
				var newHead=getNode(1);
				setHead(newHead);
				oldHead.setNext(null);
				freeUnmanaged(oldHead);
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
		toPop.setNext(null);
		freeUnmanaged(toPop);
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
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			writeManagedField(headField);
		}
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
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(count==0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		T   val=getElementType().requireEmptyConstructor().get();
		var typ=nodeType();
		
		
		Node<T> chainStart=null;
		
		for(long i=0;i<count;i++){
			if(initializer!=null){
				initializer.accept(val);
			}
			//inverse order add, reduce chance for fragmentation by providing next node immediately
			var nextNode=chainStart;
			chainStart=Node.allocValNode(val, nextNode, elementPipe.getSizeDescriptor(), typ, getDataProvider());
		}
		
		var last=getLastNode();
		if(last==null){
			setHead(chainStart);
		}else{
			last.setNext(chainStart);
		}
	}
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		
		var head=getHead();
		setHead(null);
		deltaSize(-size());
		
		freeUnmanaged(head);
	}
	
	private <U extends IOInstance.Unmanaged<U>> void freeUnmanaged(U val) throws IOException{
		Set<Chunk> chunks=new HashSet<>();
		
		new MemoryWalker(val).walk(true, ref->{
			if(ref.isNull()){
				return;
			}
			ref.getPtr().dereference(getDataProvider()).streamNext().forEach(chunks::add);
		});
		
		getDataProvider()
			.getMemoryManager()
			.free(chunks);
	}
	
	@Override
	public void free() throws IOException{
		freeUnmanaged(this);
	}
	
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "L";
	}
}
