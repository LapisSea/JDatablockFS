package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.io.ValueStorage;
import com.lapissea.dfs.io.instancepipe.FieldDependency;
import com.lapissea.dfs.query.Queries;
import com.lapissea.dfs.query.Query;
import com.lapissea.dfs.query.QueryFields;
import com.lapissea.dfs.query.QueryableData;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.RuntimeType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.TypeCheck.ArgCheck;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static com.lapissea.dfs.type.TypeCheck.ArgCheck.RawCheck.INSTANCE_MANAGED;
import static com.lapissea.dfs.type.TypeCheck.ArgCheck.RawCheck.PRIMITIVE;

@SuppressWarnings("unchecked")
public class LinkedIOList<T> extends UnmanagedIOList<T, LinkedIOList<T>>{
	
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
	
	private static final TypeCheck LIST_TYPE_CHECK = new TypeCheck(
		LinkedIOList.class,
		ArgCheck.rawAny(PRIMITIVE, INSTANCE_MANAGED)
	);
	
	private static final CompletableFuture<IOField<?, ?>> HEAD_FIELD = Runner.async(
		() -> Struct.Unmanaged.of(LinkedIOList.class).getFields().requireByName("head")
	);
	
	@IOValue
	@IONullability(IONullability.Mode.NULLABLE)
	private IONode<T> head;
	
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	private long size;
	
	private final ValueStorage<T> valueStorage;
	private final IOType          nodeType;
	
	@SuppressWarnings("unchecked")
	public LinkedIOList(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef, LIST_TYPE_CHECK);
		
		nodeType = ((IOType.RawAndArg)typeDef).withRaw(IONode.class);
		
		valueStorage = (ValueStorage<T>)ValueStorage.makeStorage(
			provider, IOType.getArg(typeDef, 0),
			getGenerics().argAsContext("T"), new ValueStorage.StorageRule.Default()
		);
		
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
		return getNode(index).getValue();
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		checkSize(index);
		getNode(index).setValue(value);
	}
	
	private IONode<T> allocNode(T value, IONode<T> next) throws IOException{
		var mag = OptionalLong.of((next == null? this : next).getPointer().getValue());
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
			readManagedField((IOField<LinkedIOList<T>, IONode<T>>)HEAD_FIELD.join());
		}
		return head;
	}
	private void setHead(IONode<T> head) throws IOException{
		this.head = head;
		getDataProvider().getSource().openIOTransaction(() -> {
			writeManagedField((IOField<LinkedIOList<T>, IONode<T>>)HEAD_FIELD.join());
		});
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
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
	public void free(long index){
		throw NotImplementedException.infer();//TODO: implement LinkedIOList.free()
	}
	
	private final class QSource implements QueryableData.QuerySource<T>{
		
		private final FieldDependency.Ticket<?> depTicket;
		
		private final IOIterator<IONode<T>> iter;
		private       IONode<T>             node;
		
		public QSource(QueryFields queryFields) throws IOException{
			if(!queryFields.isEmpty() && valueStorage instanceof ValueStorage.InstanceBased<?> i){
				var t = i.depTicket(queryFields.set());
				depTicket = t.fullRead()? null : t;
			}else depTicket = null;
			
			var head = getHead();
			if(head == null) iter = IOIterator.Iter.emptyIter();
			else iter = head.iterator();
		}
		
		@Override
		public boolean step() throws IOException{
			if(!iter.hasNext()) return false;
			node = iter.ioNext();
			return true;
		}
		@Override
		public T fullEntry() throws IOException{
			return node.getValue();
		}
		@Override
		public T fieldEntry() throws IOException{
			if(depTicket == null){
				return node.getValue();
			}
			var t = node.readValueSelective(depTicket, true);
			return (T)t.val();
		}
		@Override
		public void close() throws IOException{
			node = null;
		}
	}
	
	@Override
	public Query<T> query(){
		return new Queries.All<>(QSource::new);
	}
}
