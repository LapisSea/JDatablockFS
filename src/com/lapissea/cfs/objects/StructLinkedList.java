package com.lapissea.cfs.objects;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.BooleanConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Config.*;
import static com.lapissea.cfs.io.struct.IOStruct.*;

@SuppressWarnings("AutoBoxing")
public class StructLinkedList<T extends IOInstance> extends IOInstance.Contained.SingletonChunk<StructLinkedList<T>> implements IOList<T>{
	
	private static final IOStruct                   NODE_TYP     =IOStruct.get(StructLinkedList.Node.class);
	private static final VariableNode<ChunkPointer> NODE_NEXT_VAR=NODE_TYP.getVar(0);
	
	private class Node extends IOInstance.Contained.SingletonChunk<Node>{
		
		private Chunk container;
		
		@Value(index=0, rw=ObjectPointer.AutoSizedNoOffsetIO.class, rwArgs="BYTE")
		private final ObjectPointer<Node> next;
		
		@Value(index=1)
		private IOInstance value;
		
		public Node(Chunk container){
			this();
			setContainer(container);
		}
		
		public Node(){
			super(NODE_TYP);
			next=new ObjectPointer.Struct<>(getValueConstructor());
		}
		
		@Override
		protected UnsafeFunction<Chunk, Node, IOException> getValueConstructor(){
			return Node::new;
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
	
	private boolean changing;
	private boolean solidNodes=true;
	
	private final UnsafeFunction<Chunk, T, IOException> valConstructor;
	private       Chunk                                 container;
	
	private final Map<Integer, Node> nodeCache=new WeakValueHashMap<>();
	
	public BooleanConsumer changingListener=b->{};
	
	@PrimitiveValue(index=0, defaultSize=NumberSize.INT)
	private int size;
	
	@Value(index=1, rw=ObjectPointer.FixedNoOffsetIO.class)
	private final ObjectPointer<Node> first;
	
	public StructLinkedList(Chunk container, Supplier<T> valConstructor) throws IOException{
		this(container, c->valConstructor.get());
	}
	
	public StructLinkedList(Chunk container, UnsafeFunction<Chunk, T, IOException> valConstructor) throws IOException{
		this.container=container;
		this.valConstructor=valConstructor;
		first=new ObjectPointer.Struct<>(Node::new);
		
		validate();
	}
	
	private Cluster cluster(){
		return container.cluster;
	}
	
	@Override
	public int size(){
		return size;
	}
	
	private Node getNode(int index) throws IOException{
		
		Node cached=nodeCache.get(index);
		if(cached!=null){
			if(DEBUG_VALIDATION){
				checkCache(cached);
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
		
		result.initContainer(resultPtr.getBlock(cluster()));
		
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
		
		if(isChanging()) throw new IllegalStateException("recursive change");
		setChanging(true);
		try{
			
			Node node=getNode(index);
			if(node.value.equals(value)) return;
			
			
			Node sizeCheck=new Node();
			sizeCheck.value=value;
			sizeCheck.next.set(node.next);
			
			var oldVal=node.value;
			node.value=value;
			
			if(sizeCheck.getInstanceSize()>node.getInstanceSize()){
				//????
			}
			
			node.writeStruct();
			
		}finally{
			setChanging(false);
		}
	}
	
	@Override
	public void ensureCapacity(int elementCapacity){ }
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		Runnable popCache=()->{
//			for(var i : nodeCache.keySet().stream().filter(v->v>=index).collect(Collectors.toList())){
//				nodeCache.remove(i);
//			}
			
			var decremented=nodeCache.entrySet()
			                         .stream()
			                         .filter(e->e.getKey()>index)
			                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			nodeCache.remove(index);
			if(!decremented.isEmpty()){
				decremented.forEach((k, v)->nodeCache.remove(k));
//				decremented.forEach((i, n)->nodeCache.put(i-1, n));
			}
		};
		
		Chunk toRemove=null;
		
		if(isChanging()) throw new IllegalStateException("recursive change");
		setChanging(true);
		try{
			if(index==0){
				toRemove=first.getBlock(cluster());
				first.set(getNode(0).next);
				popCache.run();
				addSizeAndSave(-1);
				return;
			}
			
			Node prevNode=getNode(index-1);
			Node node    =getNode(index);
			
			toRemove=node.getContainer();
			
			prevNode.next.set(node.next);
			
			popCache.run();
			
			prevNode.writeStruct();
			
			addSizeAndSave(-1);
		}finally{
			setChanging(false);
			if(toRemove!=null){
				toRemove.freeChaining();
			}
		}
		
	}
	
	private Node fromVal(T value){
		Node node=new Node();
		node.value=value;
		return node;
	}
	
	@Override
	public void addElement(T value) throws IOException{
		Objects.requireNonNull(value);
		validate();
		
		Node newNode=fromVal(value);
		newNode.allocContainer(cluster());
		
		if(isChanging()) throw new IllegalStateException("recursive change");
		setChanging(true);
		try{
			if(isEmpty()){
				first.set(newNode.getSelfPtr());
			}else{
				Node last=getNode(size()-1);
				last.setNext(newNode);
				last.writeStruct();
			}
			addSizeAndSave(1);
		}finally{
			setChanging(false);
		}
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
		
		if(isChanging()) throw new IllegalStateException("recursive change");
		setChanging(true);
		try{
			if(index==0){
				newNode.next.set(first);
				newNode.writeStruct();
				first.set(newNode.getContainer().getPtr(), 0);
			}else{
				int  insertionIndex=index-1;
				Node insertionNode =getNode(insertionIndex);
				
				newNode.next.set(insertionNode.next);
				newNode.writeStruct();
				
				insertionNode.setNext(newNode);
				insertionNode.writeStruct();
			}
			
			addSizeAndSave(1);
		}finally{
			setChanging(false);
		}
	}
	
	@Override
	public void validate() throws IOException{
		if(!DEBUG_VALIDATION) return;
		
		var elementCount=stream().count();
		assert elementCount==size:elementCount+"=="+size();
		
		for(var e : nodeCache.entrySet()){
			Node cached=e.getValue();
			int  index =e.getKey();
			
			if(cached==null) continue;
			assert cached.value!=null:index;
			
			assert index<size:index+"<"+size;
			
			checkCache(cached);
		}
	}
	
	@Override
	public void free() throws IOException{
		clear();
		var d=container;
		container=null;
		d.freeChaining();
	}
	
	private void checkCache(Node node) throws IOException{
		
		Node read=new Node();
		read.initContainer(node.getContainer());
		
		assert read.equals(node):"\n"+TextUtil.toTable("cached/read mismatch", List.of(node, read));
	}
	
	private void addSizeAndSave(int delta) throws IOException{
		size+=delta;
		writeStruct();
		validate();
	}
	
	
	@Override
	public String toString(){
		return stream().map(IOInstance::toShortString).collect(Collectors.joining(", ", "[", "]"));
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
	private void setChanging(boolean changing){
		this.changing=changing;
		changingListener.accept(changing);
	}
}
