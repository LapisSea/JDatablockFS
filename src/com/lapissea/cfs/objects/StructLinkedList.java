package com.lapissea.cfs.objects;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Config.*;
import static com.lapissea.cfs.io.struct.IOStruct.*;

@SuppressWarnings("AutoBoxing")
public class StructLinkedList<T extends IOStruct.Instance> extends IOStruct.Instance.Contained implements IOList<T>{
	
	private static class Node extends IOStruct.Instance.Contained{
		
		@SuppressWarnings("unchecked")
		private static final VariableNode<ChunkPointer> NEXT_VAR=(VariableNode<ChunkPointer>)(VariableNode<?>)IOStruct.thisClass().variables.get(0);
		
		private final UnsafeFunction<Chunk, ? extends IOStruct.Instance, IOException> constructor;
		Chunk container;
		
		@Value(index=1, rw=ChunkPointer.AutoSizedIO.class, rwArgs="BYTE")
		ChunkPointer next;
		
		@Value(index=2)
		IOStruct.Instance value;
		
		public Node(UnsafeFunction<Chunk, ? extends IOStruct.Instance, IOException> constructor){
			this.constructor=constructor;
		}
		
		@Write
		void writeValue(ContentWriter dest, IOStruct.Instance source) throws IOException{
			
			var off=getStructOffset()+calcVarOffset(2).getOffset();
			if(DEBUG_VALIDATION){
				if(dest instanceof RandomIO io){
					assert off==io.getPos():off+" == "+io.getPos();
				}
			}
			
			source.writeStruct(dest, off);
		}
		
		@Read
		IOStruct.Instance readValue(ContentReader source, IOStruct.Instance oldVal) throws IOException{
			var val=constructor.apply(container);
			
			var off=getStructOffset()+calcVarOffset(2).getOffset();
			if(DEBUG_VALIDATION){
				if(source instanceof RandomIO io){
					assert off==io.getPos():off+" == "+io.getPos();
				}
			}
			
			val.readStruct(source, off);
			return val;
		}
		
		@Size
		private long sizeValue(IOStruct.Instance value){
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
		protected RandomIO getStructSourceIO() throws IOException{
			return container.io();
		}
		
		void allocContainer(Cluster cluster) throws IOException{
			container=cluster.allocWrite(this);
		}
		
		void initContainer(Chunk chunk) throws IOException{
			container=chunk;
			readStruct();
		}
		
		void setNext(Node node){
			next=node.container.getPtr();
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
	
	@PrimitiveValue(index=0, defaultSize=NumberSize.INT)
	private int size;
	
	@Value(index=1, rw=ChunkPointer.AutoSizedIO.class)
	private ChunkPointer first;
	
	public StructLinkedList(Chunk container, Supplier<T> constructor){
		this(container, c->constructor.get());
	}
	public StructLinkedList(Chunk container, UnsafeFunction<Chunk, T, IOException> constructor){
		this.container=container;
		this.constructor=constructor;
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
			Chunk target=first.dereference(cluster());
			
			Node result=new Node(constructor);
			
			result.initContainer(target);
			
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
				                   Node.NEXT_VAR.read(result, io);
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
		
		if(changing) throw new IllegalStateException("recursive change");
		changing=true;
		try{
			
			Node node=getNode(index);
			if(node.value.equals(value)) return;
			
			
			node.value=value;
			node.writeStruct();
			
		}finally{
			changing=false;
		}
	}
	
	@Override
	public void ensureCapacity(int elementCapacity) throws IOException{ }
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		Runnable popCache=()->{
			for(var i : nodeCache.keySet().stream().filter(v->v>=index).collect(Collectors.toList())){
				nodeCache.remove(i);
			}
//			nodeCache.remove(index);
//			var decremented=nodeCache.entrySet().stream().filter(e->e.getKey()>index).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
//			decremented.forEach((k, v)->nodeCache.remove(k));
		};
		
		Chunk toRemove=null;
		
		if(changing) throw new IllegalStateException("recursive change");
		changing=true;
		try{
			if(index==0){
				toRemove=first.dereference(cluster());
				first=getNode(0).next;
				popCache.run();
				addSizeAndSave(-1);
				return;
			}
			
			Node prevNode=getNode(index-1);
			Node node    =getNode(index);
			
			toRemove=node.container;
			
			prevNode.next=node.next;
			
			popCache.run();
			
			prevNode.writeStruct();
			
			addSizeAndSave(-1);
		}finally{
			changing=false;
			if(toRemove!=null){
				toRemove.freeChaining();
			}
		}
		
	}
	
	@Override
	public void addElement(int index, T value) throws IOException{
		Objects.checkIndex(index, size()+1);
		Objects.requireNonNull(value);
		
		Node newNode=new Node(constructor);
		newNode.value=value;
		
		newNode.next=new ChunkPointer(cluster().getData().getSize());
		newNode.allocContainer(cluster());
		
		
		if(changing) throw new IllegalStateException("recursive change");
		changing=true;
		try{
			if(index==0){
				newNode.next=first;
				newNode.writeStruct();
				first=newNode.container.getPtr();
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
			changing=false;
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
		
		Node read=new Node(constructor);
		read.initContainer(node.container);
		
		assert read.equals(node):"\n"+TextUtil.toTable("cached/read mismatch", List.of(node, read));
	}
	
	private void addSizeAndSave(int delta) throws IOException{
		size+=delta;
		writeStruct();
		validate();
	}
	
	
	@Override
	protected RandomIO getStructSourceIO() throws IOException{
		return container.io();
	}
	
	@Override
	public String toString(){
		return stream().map(Instance::toShortString).collect(Collectors.joining(", ", "[", "]"));
	}
	public boolean isChanging(){
		return changing;
	}
	
	@Override
	public void clear() throws IOException{
		List<Chunk> tofree=new ArrayList<>();
		for(int i=0;i<size();i++){
			tofree.addAll(getNode(i).container.collectNext());
		}
		
		size=0;
		first=null;
		writeStruct();
		
		for(Chunk chunk : tofree){
			chunk.modifyAndSave(ch->{
				ch.setSize(0);
				ch.setUsed(false);
			});
		}
		
		cluster().free(tofree);
		
		validate();
	}
}
