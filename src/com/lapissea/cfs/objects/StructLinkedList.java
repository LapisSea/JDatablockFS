package com.lapissea.cfs.objects;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.RandomIO;
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
public class StructLinkedList<T extends IOInstance> extends IOInstance.Contained.SingletonChunk implements IOList<T>{
	
	private static class Node extends IOInstance.Contained.SingletonChunk{
		
		@SuppressWarnings("unchecked")
		private static final VariableNode<ChunkPointer> NEXT_VAR=(VariableNode<ChunkPointer>)(VariableNode<?>)IOStruct.thisClass().variables.get(0);
		
		private final UnsafeFunction<Chunk, ? extends IOInstance, IOException> constructor;
		private       Chunk                                                    container;
		
		@Value(index=1, rw=ChunkPointer.AutoSizedIO.class, rwArgs="BYTE")
		ChunkPointer next;
		
		@Value(index=2)
		IOInstance value;
		
		public Node(UnsafeFunction<Chunk, ? extends IOInstance, IOException> constructor){
			this.constructor=constructor;
		}
		
		@Write
		void writeValue(ContentWriter dest, IOInstance source) throws IOException{
			Objects.requireNonNull(getContainer());
			
			var off=getStructOffset()+calcVarOffset(2).getOffset();
			if(DEBUG_VALIDATION){
				if(dest instanceof RandomIO io){
					assert off==io.getPos():off+" == "+io.getPos();
				}
			}
			
			source.writeStruct(getContainer().cluster, dest, off);
		}
		
		@Read
		IOInstance readValue(ContentReader source, IOInstance oldVal) throws IOException{
			var val=constructor.apply(getContainer());
			
			var off=getStructOffset()+calcVarOffset(2).getOffset();
			if(DEBUG_VALIDATION){
				if(source instanceof RandomIO io){
					assert off==io.getGlobalPos():this+" "+off+" == "+io.getGlobalPos();
				}
			}
			
			val.readStruct(getContainer().cluster, source, off);
			return val;
		}
		
		@Size
		private long sizeValue(IOInstance value){
			return value.getInstanceSize();
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof Node node&&
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
			setStructOffset(container.getPtr());
		}
		
		void allocContainer(Cluster cluster) throws IOException{
			setContainer(cluster.alloc(getInstanceSize()));
			writeStruct();
		}
		
		void initContainer(Chunk chunk) throws IOException{
			setContainer(chunk);
			readStruct();
		}
		
		void setNext(Node node){
			next=node.getContainer().getPtr();
		}
		
		@Override
		public void writeStruct() throws IOException{
			writeStruct(true);
		}
	}
	
	private boolean changing;
	
	private final UnsafeFunction<Chunk, T, IOException> constructor;
	private       Chunk                                 container;
	
	private final Map<Integer, Node> nodeCache=new WeakValueHashMap<>();
	
	public BooleanConsumer changingListener=b->{};
	
	@PrimitiveValue(index=0, defaultSize=NumberSize.INT)
	private int size;
	
	@Value(index=1, rw=ObjectPointer.FixedIO.class)
	private final ObjectPointer<Node> first;
	
	public StructLinkedList(Chunk container, Supplier<T> constructor) throws IOException{
		this(container, c->constructor.get());
	}
	
	public StructLinkedList(Chunk container, UnsafeFunction<Chunk, T, IOException> constructor) throws IOException{
		super(container.dataStart());
		
		this.container=container;
		this.constructor=constructor;
		first=new ObjectPointer.Struct<>(()->new Node(constructor));
		
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
			
			Node result=new Node(constructor);
			
			result.initContainer(first.getBlock(cluster()));
			
			nodeCache.put(0, result);
			
			return result;
		}
		
		int          start;
		ChunkPointer startingPtr;
		
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
		
		Node result=new Node(constructor);
		
		ChunkPointer resultPtr=startingPtr;
		
		for(int i=start;i<index;i++){
			resultPtr=resultPtr.dereference(cluster())
			                   .ioAt(Node.NEXT_VAR.getKnownOffset().getOffset(), io->{
				                   Node.NEXT_VAR.read(result, cluster(), io);
				                   return result.next;
			                   });
		}
		
		result.initContainer(resultPtr.dereference(cluster()));
		
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
			
			
			node.value=value;
			node.writeStruct();
			
		}finally{
			setChanging(false);
		}
	}
	
	@Override
	public void ensureCapacity(int elementCapacity) throws IOException{ }
	
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
				first.set(getNode(0).next, 0);
				popCache.run();
				addSizeAndSave(-1);
				return;
			}
			
			Node prevNode=getNode(index-1);
			Node node    =getNode(index);
			
			toRemove=node.getContainer();
			
			prevNode.next=node.next;
			
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
	
	@Override
	protected void setStructOffset(long structOffset){
		if(container!=null){
			assert container.dataStart()==structOffset:container.dataStart()+" "+structOffset;
		}
		super.setStructOffset(structOffset);
	}
	
	private Node fromVal(T value){
		Node node=new Node(constructor);
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
				first.set(newNode.getContainer().getPtr(), 0);
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
		
		newNode.next=new ChunkPointer(cluster().getData().getSize());
		newNode.allocContainer(cluster());
		
		validate();
		
		if(isChanging()) throw new IllegalStateException("recursive change");
		setChanging(true);
		try{
			if(index==0){
				newNode.next=first.getDataBlock();
				newNode.writeStruct();
				first.set(newNode.getContainer().getPtr(), 0);
			}else{
				int  insertionIndex=index-1;
				Node insertionNode =getNode(insertionIndex);
				
				newNode.next=insertionNode.next;
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
		
		assert container.dataStart()==getStructOffset():container.dataStart()+" "+getStructOffset();
		
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
		
		Node read=new Node(constructor);
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
