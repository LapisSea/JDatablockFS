package com.lapissea.cfs.objects;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Config.*;
import static com.lapissea.cfs.io.struct.IOStruct.*;

@SuppressWarnings("AutoBoxing")
public class StructLinkedList<T extends IOInstance> extends IOInstance.Contained.SingletonChunk<StructLinkedList<T>> implements IOList<T>{
	
	private static final long LL_SIZE=IOStruct.thisClass().requireKnownSize();
	
	private static final IOStruct                   NODE_TYP     =IOStruct.get(StructLinkedList.Node.class);
	private static final VariableNode<ChunkPointer> NODE_NEXT_VAR=NODE_TYP.getVar(0);
	
	private class Node extends IOInstance.Contained.SingletonChunk<Node>{
		
		private Chunk container;
		
		@Value(index=0, rw=ObjectPointer.AutoSizedNoOffsetIO.class, rwArgs="BYTE")
		public final ObjectPointer<Node> next;
		
		@Value(index=1)
		private IOInstance value;
		
		private Node(Chunk container){
			this();
			setContainer(container);
		}
		
		public Node(){
			super(NODE_TYP);
			next=new ObjectPointer.Struct<>(getSelfConstructor());
		}
		
		@Override
		protected UnsafeFunction<Chunk, Node, IOException> getSelfConstructor(){
			return StructLinkedList.this::tryCacheFetch;
		}
		
		public IOInstance getValue(){
			return value;
		}
		
		@Write
		void writeValue(ContentWriter dest, IOInstance source) throws IOException{
			Objects.requireNonNull(getContainer());
			source.writeStruct(getContainer().cluster, dest);
		}
		
		@Read
		IOInstance readValue(ContentReader source, IOInstance oldVal) throws IOException{
			var val=valConstructor.apply(getContainer());
			val.readStruct(getContainer().cluster, source);
			return val;
		}
		
		@Size
		private long sizeValue(IOInstance value){
			return value.getInstanceSize();
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof StructLinkedList<?>.Node node&&
			       Objects.equals(next, node.next)&&
			       Objects.equals(value, node.value);
		}
		
		@Override
		public int hashCode(){
			int result=1;
			
			result=31*result+(next==null?0:next.hashCode());
			result=31*result+(value==null?0:value.hashCode());
			
			return result;
		}
		
		@Override
		public Chunk getContainer(){
			return container;
		}
		
		void setContainer(Chunk container){
			this.container=container;
		}
		
		void allocContainer(Cluster cluster) throws IOException{
			setContainer(cluster.alloc(getInstanceSize(), solidNodes));
			writeStruct();
		}
		
		void initContainer(Chunk chunk) throws IOException{
			setContainer(chunk);
			readStruct();
		}
		
		void setNext(Node node){
			next.set(node.getContainer().getPtr(), 0);
		}
		
		@Override
		public void writeStruct() throws IOException{
			writeStruct(true);
		}
	}
	
	public static class Builder<T extends IOInstance>{
		private UnsafeSupplier<Chunk, IOException>    containerGetter;
		private boolean                               solidNodes;
		private UnsafeFunction<Chunk, T, IOException> valConstructor;
		
		public Builder<T> withContainer(Chunk chunk){
			containerGetter=()->chunk;
			return this;
		}
		
		public Builder<T> withSolidNodes(boolean solidNodes){
			this.solidNodes=solidNodes;
			return this;
		}
		
		public Builder<T> withElementConstructor(Supplier<T> valConstructor){
			return withElementContextConstructor(c->valConstructor.get());
		}
		
		public Builder<T> withElementContextConstructor(UnsafeFunction<Chunk, T, IOException> valConstructor){
			this.valConstructor=valConstructor;
			return this;
		}
		
		
		public Builder<T> withAllocator(UnsafeLongFunction<Chunk, IOException> allocator){
			containerGetter=()->allocator.apply(LL_SIZE);
			return this;
		}
		
		public Builder<T> withAllocationSource(Cluster cluster){
			return withAllocator(s->cluster.alloc(s, true));
		}
		
		public StructLinkedList<T> build() throws IOException{
			
			Objects.requireNonNull(containerGetter);
			Objects.requireNonNull(valConstructor);
			
			return new StructLinkedList<>(containerGetter.get(), solidNodes, valConstructor);
		}
	}
	
	public static <T extends IOInstance> StructLinkedList<T> build(Consumer<Builder<T>> config) throws IOException{
		Builder<T> b=builder();
		config.accept(b);
		return b.build();
	}
	
	public static <T extends IOInstance> Builder<T> builder(){
		return new Builder<>();
	}
	
	private       boolean changing;
	private final boolean solidNodes;
	
	private final UnsafeFunction<Chunk, T, IOException> valConstructor;
	private       Chunk                                 container;
	
	private final Map<Integer, Node> nodeCache=new WeakValueHashMap<>();
	
	public BooleanConsumer changingListener=b->{};
	
	//@PrimitiveValue(index=0, defaultSize=NumberSize.INT)
	private int size;
	
	@Value(index=0, rw=ObjectPointer.FixedNoOffsetIO.class)
	private final ObjectPointer<Node> first;
	
	private StructLinkedList(Chunk container, boolean solidNodes, UnsafeFunction<Chunk, T, IOException> valConstructor) throws IOException{
		this.container=container;
		this.valConstructor=valConstructor;
		this.solidNodes=solidNodes;
		first=new ObjectPointer.Struct<>(this::tryCacheFetch);
		
		if(container.getSize()>0){
			readStruct();
			
			size=nextWalkCount();
			
			validate();
		}
	}
	
	private Node tryCacheFetch(Chunk chunk){
		return nodeCache.values()
		                .stream()
		                .filter(c->c.getContainer()==chunk)
		                .findAny()
		                .orElseGet(()->new Node(chunk));
	}
	
	private int nextWalkCount() throws IOException{
		int count=0;
		
		Node result=new Node();
		
		ObjectPointer<?> nextOff=new ObjectPointer.Raw();
		
		ObjectPointer<Node> resultPtr =first;
		var                 nextVarOff=NODE_NEXT_VAR.getKnownOffset().getOffset();
		while(resultPtr.hasPtr()){
			nextOff.set(resultPtr).addOffset(nextVarOff);
			
			count++;
			try{
				resultPtr=nextOff.io(cluster(), io->{
					NODE_NEXT_VAR.read(result, cluster(), io);
					return result.next;
				});
			}catch(Throwable e){
				throw new IOException("Failed to read "+NODE_NEXT_VAR+" at "+nextOff, e);
			}
		}
		return count;
	}
	
	private void checkSize() throws IOException{
		int actualSize=nextWalkCount();
		assert actualSize==size:actualSize+" != "+size;
	}
	
	@Override
	protected UnsafeFunction<Chunk, StructLinkedList<T>, IOException> getSelfConstructor(){
		return c->new StructLinkedList<>(c, solidNodes, valConstructor);
	}
	
	private Cluster cluster(){
		return container.cluster;
	}
	
	@Override
	public int size(){
		if(changing){
			return 0;
		}
		return size;
	}
	
	private Node getNode(int index) throws IOException{
		
		Node cached=nodeCache.get(index);
		if(cached!=null){
			if(DEBUG_VALIDATION){
				checkCache(cached, index);
			}
			return cached;
		}
		
		if(index==0){
			
			Node result=new Node();
			
			result.initContainer(first.getBlock(cluster()));
			
			nodeCache.put(0, result);
			
			return result;
		}
		
		int                 start;
		ObjectPointer<Node> startingPtr;
		
		Node prev=nodeCache.get(index-1);
		if(prev!=null){
			start=index;
			startingPtr=prev.next;
		}else{
			Map.Entry<Integer, Node> entry=
				nodeCache.entrySet()
				         .stream()
				         .filter(e->e.getKey()<index)
				         .max(Comparator.comparingInt(Map.Entry::getKey))
				         .orElse(null);
			if(entry==null) entry=new AbstractMap.SimpleEntry<>(0, getNode(0));
			
			start=entry.getKey()+1;
			startingPtr=entry.getValue().next;
		}
		
		Node result=new Node();
		
		ObjectPointer<Node> resultPtr =startingPtr;
		var                 nextVarOff=NODE_NEXT_VAR.getKnownOffset().getOffset();
		for(int i=start;i<index;i++){
			resultPtr=resultPtr.io(cluster(), io->{
				io.skip(nextVarOff);
				NODE_NEXT_VAR.read(result, cluster(), io);
				return result.next;
			});
		}
		
		try{
			result.initContainer(resultPtr.getBlock(cluster()));
		}catch(NullPointerException e){
			checkSize();
			throw e;
		}
		
		nodeCache.put(index, result);
		return result;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public T getElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		return (T)getNode(index).value;
	}
	
	@Override
	public void setElement(int index, T value) throws IOException{
		Objects.checkIndex(index, size());
		Objects.requireNonNull(value);
		
		Node node=getNode(index);
		if(node.value.equals(value)) return;
		
		changeSession(size->{
			
			Node sizeCheck=fromVal(value);
			sizeCheck.next.set(node.next);
			
			var oldVal=node.value;
			node.value=value;
			
			if(sizeCheck.getInstanceSize()>node.getInstanceSize()){
				//????
				int i=0;
			}
			
			node.writeStruct();
		});
	}
	
	@Override
	public void ensureCapacity(int elementCapacity){ }
	
	private void popCache(int index){
//		for(var i : nodeCache.keySet().stream().filter(v->v>=index).collect(Collectors.toList())){
//			nodeCache.remove(i);
//		}
		
		var decremented=nodeCache.entrySet()
		                         .stream()
		                         .filter(e->e.getKey()>index)
		                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		nodeCache.remove(index);
		if(!decremented.isEmpty()){
			decremented.forEach((k, v)->nodeCache.remove(k));
//			decremented.forEach((i, n)->nodeCache.put(i-1, n));
		}
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		
		Chunk toRemove=changeSession(size->{
			Chunk ch;
			if(index==0){
				ch=first.getBlock(cluster());
				first.set(getNode(0).next);
				popCache(0);
				writeStruct();
				addSize(-1);
				return ch;
			}
			
			Node prevNode=getNode(index-1);
			Node node    =getNode(index);
			
			ch=node.getContainer();
			
			prevNode.next.set(node.next);
			
			popCache(index);
			
			prevNode.writeStruct();
			
			addSize(-1);
			
			return ch;
		});
		
		if(toRemove!=null){
			toRemove.freeChaining();
		}
	}
	
	private Node fromVal(T value){
		Node node=new Node();
		node.value=value;
		return node;
	}
	
	private void popChange(Throwable e) throws IOException{
		try{
			this.changing=false;
			changingListener.accept(changing);
		}catch(Throwable e1){
			String str;
			try{
				str=toString();
			}catch(Throwable e2){
				str="<list>";
			}
			var ne=new IOException("Modification of "+str+" may have caused invalid state", e);
			ne.addSuppressed(e1);
			throw ne;
		}
		
		if(e!=null) throw UtilL.uncheckedThrow(e);
	}
	
	private void changeSession(UnsafeIntConsumer<IOException> action) throws IOException{
		Throwable e   =null;
		int       size=size();
		pushChange();
		try{
			action.accept(size);
			return;
		}catch(Throwable t){
			e=t;
		}finally{
			popChange(e);
		}
		throw new ShouldNeverHappenError();
	}
	
	private <R> R changeSession(UnsafeIntFunction<R, IOException> action) throws IOException{
		Throwable e   =null;
		int       size=size();
		pushChange();
		try{
			return action.apply(size);
		}catch(Throwable t){
			e=t;
		}finally{
			popChange(e);
		}
		throw new ShouldNeverHappenError();
	}
	
	private void pushChange(){
		if(isChanging()) throw new IllegalStateException("recursive change");
		
		this.changing=true;
		changingListener.accept(changing);
	}
	
	
	@Override
	public void addElements(IOList<T> toAdd) throws IOException{
		switch(toAdd.size()){
		case 0:
			return;
		case 1:
			addElement(toAdd.getElement(0));
			return;
		}
		
		if(toAdd.anyMatches(Objects::isNull)) throw new IllegalArgumentException(toAdd+" contains null value(s)!");
		
		List<Node> nodeChain=toAdd.stream().map(this::fromVal).collect(Collectors.toList());
		
		for(int i=nodeChain.size()-1;i>=1;i--){
			Node prevNode=nodeChain.get(i-1);
			Node node    =nodeChain.get(i);
			
			node.allocContainer(cluster());
			prevNode.next.set(node.getSelfPtr());
		}
		Node chainStart=nodeChain.get(0);
		chainStart.allocContainer(cluster());
		
		int size=size();
		linkToEnd(chainStart, nodeChain.size());
		for(int i=1;i<nodeChain.size();i++){
			Node node=nodeChain.get(i);
			nodeCache.put(i+size, node);
		}
	}
	
	private void linkToEnd(Node newNode, int chainSize) throws IOException{
		changeSession(size->{
			if(size==0){
				first.set(newNode.getSelfPtr());
				writeStruct();
			}else{
				Node last=getNode(size-1);
				last.setNext(newNode);
				last.writeStruct();
			}
			nodeCache.put(size, newNode);
			addSize(chainSize);
		});
	}
	
	@Override
	public void addElement(T value) throws IOException{
		Objects.requireNonNull(value);
		
		Node newNode=fromVal(value);
		newNode.allocContainer(cluster());
		
		linkToEnd(newNode, 1);
	}
	
	
	@Override
	public void addElement(int index, T value) throws IOException{
		if(index==size()){
			addElement(value);
			return;
		}
		
		Objects.checkIndex(index, size()+1);
		Objects.requireNonNull(value);
		
		validate();
		
		Node newNode=fromVal(value);
		
		newNode.next.set(new ChunkPointer(cluster().getData().getSize()), 0);
		newNode.allocContainer(cluster());
		
		validate();
		
		
		changeSession(size->{
			if(index==0){
				newNode.next.set(first);
				newNode.writeStruct();
				first.set(newNode.getContainer().getPtr(), 0);
				writeStruct();
			}else{
				int  insertionIndex=index-1;
				Node insertionNode =getNode(insertionIndex);
				
				newNode.next.set(insertionNode.next);
				newNode.writeStruct();
				
				insertionNode.setNext(newNode);
				insertionNode.writeStruct();
			}
			addSize(1);
		});
	}
	
	@Override
	public void validate() throws IOException{
		if(!DEBUG_VALIDATION) return;
		validateWrittenData();
		checkSize();
		
		for(var e : nodeCache.entrySet()){
			Node cached=e.getValue();
			int  index =e.getKey();
			
			if(cached==null) continue;
			assert cached.value!=null:index;
			
			assert index<size:index+"<"+size;
			
			checkCache(cached, index);
		}
	}
	
	@Override
	public void free() throws IOException{
		clear();
		var d=container;
		container=null;
		d.freeChaining();
	}
	
	private void checkCache(Node node, int index) throws IOException{
		
		Node read=new Node();
		read.initContainer(node.getContainer());
		
		if(!read.equals(node)) throw new IOException("\n"+TextUtil.toTable("cached/read mismatch of at "+index+" from "+container.getPtr(), List.of(node, read)));
	}
	
	private void addSize(int delta) throws IOException{
		size+=delta;
		validate();
	}
	
	
	@Override
	public String toString(){
		return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
	}
	
	@Override
	public void clear() throws IOException{
		List<Chunk> toFree=new ArrayList<>();
		for(int i=0;i<size();i++){
			toFree.addAll(getNode(i).getContainer().collectNext());
		}
		
		size=0;
		first.unset();
		nodeCache.clear();
		writeStruct();
		
		for(Chunk chunk : toFree){
			chunk.modifyAndSave(ch->{
				ch.setSize(0);
				ch.setUsed(false);
			});
		}
		
		cluster().free(toFree);
		
		
		validate();
	}
	
	@Override
	public Chunk getContainer(){
		return container;
	}
	
	private boolean isChanging(){
		return changing;
	}
	
}
