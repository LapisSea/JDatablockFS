package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.instancepipe.FieldDependency;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.query.Query;
import com.lapissea.cfs.query.QuerySupport;
import com.lapissea.cfs.type.RuntimeType;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.TypeLink.Check.ArgCheck;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LateInit;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.INSTANCE_MANAGED;
import static com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck.PRIMITIVE;

@SuppressWarnings("unchecked")
public class LinkedIOList<T> extends AbstractUnmanagedIOList<T, LinkedIOList<T>>{
	
	private class LinkedListIterator extends IOListIterator.AbstractIndex<T>{
		
		private IONode<T> node;
		private long      nodeIndex;
		
		public LinkedListIterator(long cursorStart){
			super(cursorStart);
		}
		
		
		private void resetNode() throws IOException{
			node = getHead();
			nodeIndex = 0;
		}
		private void walkNode(long index) throws IOException{
			if(node == null){
				resetNode();
			}
			if(nodeIndex == index) return;
			if(nodeIndex>index){
				resetNode();
			}
			
			node = getNode(node, nodeIndex, index);
			nodeIndex = index;
		}
		
		@Override
		protected T getElement(long index) throws IOException{
			walkNode(index);
			return node.getValue();
		}
		
		@Override
		protected void removeElement(long index) throws IOException{
			if(index>0){
				walkNode(index - 1);
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
				walkNode(index - 1);
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
	
	private static final TypeLink.Check LIST_TYPE_CHECK = new TypeLink.Check(
		LinkedIOList.class,
		ArgCheck.rawAny(PRIMITIVE, INSTANCE_MANAGED)
	);
	
	private static final LateInit.Safe<IOField<?, ?>> HEAD_FIELD = Runner.async(
		() -> Struct.Unmanaged.thisClass().getFields().byName("head").orElseThrow()
	);
	
	@IOValue
	@IONullability(IONullability.Mode.NULLABLE)
	private IONode<T> head;
	
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	private long size;
	
	private final ValueStorage<T> valueStorage;
	private final TypeLink        nodeType;
	
	private final boolean      readOnly;
	private final Map<Long, T> cache;
	
	
	@SuppressWarnings("unchecked")
	public LinkedIOList(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef, LIST_TYPE_CHECK);
		readOnly = getDataProvider().isReadOnly();
		cache = readOnly? new HashMap<>() : null;
		
		nodeType = new TypeLink(
			IONode.class,
			getTypeDef().arg(0)
		);
		
		valueStorage = (ValueStorage<T>)ValueStorage.makeStorage(provider, typeDef.arg(0), getGenerics().argAsContext("T"), new ValueStorage.StorageRule.Default());
		
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	public RuntimeType<T> getElementType(){
		return valueStorage.getType();
	}
	
	private IONode<T> getNode(long index) throws IOException{
		return getNode(getHead(), 0, index);
	}
	private IONode<T> getNode(IONode<T> start, long startIndex, long index) throws IOException{
		var node = start;
		for(long i = startIndex; i<index; i++){
			node = node.getNext();
		}
		return node;
	}
	
	@Override
	public Class<T> elementType(){
		return valueStorage.getType().getType();
	}
	
	@Override
	public long size(){
		return size;
	}
	@Override
	protected void setSize(long size){
		this.size = size;
	}
	
	@Override
	public T get(long index) throws IOException{
		checkSize(index);
		if(readOnly){
			return getCached(index);
		}
		return getNode(index).getValue();
	}
	
	private T getCached(long index) throws IOException{
		if(cache.containsKey(index)){
			return cache.get(index);
		}
		var val = getNode(index).getValue();
		cache.put(index, val);
		return val;
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		checkSize(index);
		getNode(index).setValue(value);
	}
	
	private IONode<T> allocNode(T value, IONode<T> next) throws IOException{
		var mag = OptionalLong.of((next == null? this : next).getReference().getPtr().getValue());
		return IONode.allocValNode(value, next, valueStorage.getSizeDescriptor(), nodeType, getDataProvider(), mag);
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		checkSize(index, 1);
		
		if(index == size()){
			add(value);
			return;
		}
		
		if(index == 0){
			var head = getHead();
			
			setHead(allocNode(value, head));
			deltaSize(1);
			return;
		}
		
		var prevNode = getNode(index - 1);
		
		insertNodeInFrontOf(prevNode, value);
	}
	private void insertNodeInFrontOf(IONode<T> prevNode, T value) throws IOException{
		var       node    = prevNode.getNext();
		IONode<T> newNode = allocNode(value, node);
		prevNode.setNext(newNode);
		deltaSize(1);
	}
	
	@Override
	public void add(T value) throws IOException{
		IONode<T> newNode = allocNode(value, null);
		
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
		
		if(index == 0){
			if(isLast(index)){
				var oldHead = getHead();
				setHead(null);
				oldHead.free();
			}else{
				var oldHead = getHead();
				var newHead = getNode(1);
				setHead(newHead);
				oldHead.setNext(null);
				oldHead.free();
			}
			deltaSize(-1);
		}else{
			popNodeFromPrev(getNode(index - 1));
		}
	}
	
	private void popNodeFromPrev(IONode<T> prevNode) throws IOException{
		var toPop = prevNode.getNext();
		if(toPop == null) return;
		var nextNode = toPop.getNext();
		prevNode.setNext(nextNode);
		deltaSize(-1);
		toPop.setNext(null);
		toPop.free();
	}
	
	private boolean isLast(long index){
		return index == size() - 1;
	}
	
	private IONode<T> getLastNode() throws IOException{
		return getNode(size() - 1);
	}
	
	private IONode<T> getHead() throws IOException{
		if(!readOnly || head == null){
			readManagedField((IOField<LinkedIOList<T>, IONode<T>>)HEAD_FIELD.get());
		}
		return head;
	}
	private void setHead(IONode<T> head) throws IOException{
		this.head = head;
		getDataProvider().getSource().openIOTransaction(() -> {
			writeManagedField((IOField<LinkedIOList<T>, IONode<T>>)HEAD_FIELD.get());
		});
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
		if(readOnly){
			return new IOIterator.Iter<>(){
				LinkedListIterator src;
				long index;
				@Override
				public boolean hasNext(){
					return index<size();
				}
				@Override
				public T ioNext() throws IOException{
					try{
						if(cache.containsKey(index)){
							return cache.get(index);
						}
						if(src == null) src = new LinkedListIterator(index);
						var e = src.getElement(index);
						cache.put(index, e);
						return e;
					}finally{
						index++;
					}
				}
			};
		}
		
		try{
			var head = getHead();
			if(head == null) return IOIterator.Iter.emptyIter();
			return head.valueIterator();
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
		if(count == 0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		T val = getElementType().make();
		
		
		IONode<T> chainStart = null;
		
		for(long i = 0; i<count; i++){
			if(initializer != null){
				initializer.accept(val);
			}
			//inverse order add, reduce chance for fragmentation by providing the next node immediately
			var nextNode = chainStart;
			chainStart = allocNode(val, nextNode);
		}
		
		var last = getLastNode();
		if(last == null){
			setHead(chainStart);
		}else{
			last.setNext(chainStart);
		}
	}
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		
		var head = getHead();
		setHead(null);
		deltaSize(-size());
		
		head.free();
	}
	@Override
	public void requestCapacity(long capacity){ }
	@Override
	public void trim(){ }
	
	@Override
	public long getCapacity(){
		return Long.MAX_VALUE;
	}
	
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "L";
	}
	
	
	@Override
	public Query<T> query(){
		return QuerySupport.of(ListData.of(this, readFields -> {
			var                       size = size();
			FieldDependency.Ticket<?> depTicket;
			if(valueStorage instanceof ValueStorage.InstanceBased<?> i){
				var t = i.depTicket(readFields);
				depTicket = t.fullRead()? null : t;
			}else depTicket = null;
			
			return new QuerySupport.AccessIterator<T>(){
				long cursor;
				
				IOIterator.Iter<IONode<T>> iter;
				long iterCursor;
				
				@SuppressWarnings("rawtypes")
				@Override
				public QuerySupport.Accessor<T> next(){
					if(cursor>=size) return null;
					var index = cursor++;
					return full -> {
						checkSize(index);
						
						if(iter == null || iterCursor>=index + 1){
							var head = getHead();
							if(head == null) iter = IOIterator.Iter.emptyIter();
							else iter = head.iterator();
							iterCursor = 0;
						}
						
						IONode<T> node;
						do{
							node = iter.ioNext();
							iterCursor++;
						}while(iterCursor != index + 1);
						
						if(!full && depTicket != null){
							return node.readValueSelective(depTicket, true);
						}
						return node.getValue();
					};
				}
			};
		}));
	}
	@Override
	public void free(long index){
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.free()
	}
}
