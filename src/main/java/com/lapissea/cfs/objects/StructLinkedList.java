package com.lapissea.cfs.objects;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.TypeParser;
import com.lapissea.cfs.conf.AllocateTicket;
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
import com.lapissea.util.function.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;
import static com.lapissea.cfs.io.struct.IOStruct.*;

public class StructLinkedList<T extends IOInstance> extends IOInstance.Contained.SingletonChunk<StructLinkedList<T>> implements IOList<T>{
	
	public static final TypeParser TYPE_PARSER=new TypeParser(){
		
		@Override
		public boolean canParse(Cluster cluster, IOType type){
			return type.getGenericArgs().size()==1&&type.getType().equals(LIST_TYP);
		}
		
		@Override
		public UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type){
			var elType    =type.getGenericArgs().get(0);
			var listConstr=cluster.getTypeParsers().parse(cluster, elType);
			return chunk->build(b->b.withContainer(chunk)
			                        .withElementContextConstructor(listConstr)
			                        .withSolidNodes(elType.getType().getKnownSize().isPresent())
			                   );
		}
	};
	
	private static final IOStruct LIST_TYP=thisClass();
	private static final long     LL_SIZE =LIST_TYP.requireKnownSize();
	
	private static final IOStruct                   NODE_TYP     =IOStruct.get(StructLinkedList.Node.class);
	private static final VariableNode<ChunkPointer> NODE_NEXT_VAR=NODE_TYP.getVar(0);
	
	private class Node extends IOInstance.Contained.SingletonChunk<Node>{
		
		private Chunk container;
		
		@Value(index=0, rw=ObjectPointer.AutoSizedIO.class)
//		@Value(index=0, rw=ObjectPointer.FixedNoOffsetIO.class)
		public final ObjectPointer<Node> next;
		
		@Value(index=1)
		private IOInstance value;
		
		private Node(Chunk container){
			this();
			setContainer(container);
		}
		
		public Node(){
			super(NODE_TYP);
			next=new ObjectPointer.StructCached<>(getSelfConstructor(), StructLinkedList.this::findInCache);
		}
		
		@Override
		protected UnsafeFunction<Chunk, Node, IOException> getSelfConstructor(){
			return StructLinkedList.this::tryCacheFetch;
		}
		
		public IOInstance getValue(){
			return value;
		}
		
		@Write
		private void writeValue(Cluster cluster, ContentWriter dest, IOInstance source) throws IOException{
			source.writeStruct(cluster, dest);
		}
		
		@Read
		private IOInstance readValue(Cluster cluster, ContentReader source, IOInstance oldVal) throws IOException{
			var val=oldVal==null?valConstructor.apply(getContainer()):oldVal;
			val.readStruct(cluster, source);
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
			AllocateTicket.fitTo(this)
			              .shouldDisableResizing(solidNodes)
			              .withDataPopulated(c->{
				              setContainer(c);
				              writeStruct();
				              if(DEBUG_VALIDATION){
					              Node clone=new Node();
					              clone.initContainer(c);
					              assert clone.equals(this):"\n"+clone+"\n"+this;
				              }
			              })
			              .submit(cluster);
		}
		
		void initContainer(Chunk chunk) throws IOException{
			setContainer(chunk);
			readStruct();
		}
		
		void setNextNode(Node node){
			next.set(node.getContainer().getPtr(), 0);
		}
		
		boolean notInCache(){
			return nodeCache.values().stream().noneMatch(e->e==this);
		}
		
		void checkCache(){
			if(!DEBUG_VALIDATION) return;
			if(notInCache()) throw new RuntimeException(this+" "+TextUtil.toString(nodeCache));
		}
		
		@Override
		public void writeStruct() throws IOException{
			writeStruct(true);
		}
		
		@Override
		public String toString(){
			StringBuilder result=new StringBuilder("Node{");
			if(isWritingInstance()) result.append("writing, ");
			if(notInCache()) result.append("invalid, ");
			result.append(container==null?"fake":container.getPtr());
			result.append(" -> ").append(next).append(" value=").append(value).append('}');
			return result.toString();
		}
		
	}
	
	public static class Builder<T extends IOInstance>{
		private UnsafeSupplier<Chunk, IOException>    containerGetter;
		private boolean                               solidNodes=true;
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
		
		
		public Builder<T> withAllocation(Cluster cluster, AllocateTicket ticket){
			containerGetter=()->ticket.withBytes(LL_SIZE)
			                          .withDataPopulated((c, io)->Utils.zeroFill(io::write, LL_SIZE))
			                          .submit(cluster);
			return this;
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
	
	private final Map<Integer, Node> nodeCache=new HashMap<>();//new WeakValueHashMap<>();
	
	public BooleanConsumer changingListener=b->{};
	
	//@PrimitiveValue(index=0, defaultSize=NumberSize.INT)
	private int size;
	
	@Value(index=0, rw=ObjectPointer.FixedNoOffsetIO.class)
	private final ObjectPointer<Node> first=new ObjectPointer.StructCached<>(this::tryCacheFetch, this::findInCache);
	
	private StructLinkedList(Chunk container, boolean solidNodes, UnsafeFunction<Chunk, T, IOException> valConstructor) throws IOException{
		this.container=container;
		this.valConstructor=valConstructor;
		this.solidNodes=solidNodes;
		if(container.getSize()>0){
			readStruct();
			size=nextWalkCount();
			
			validate();
		}
	}
	
	private Node findInCache(Chunk chunk){
		for(Node node : nodeCache.values()){
			if(node.getContainer()==chunk){
				return node;
			}
		}
		return null;
	}
	
	private Node tryCacheFetch(Chunk chunk){
		Node cached=findInCache(chunk);
		if(cached!=null) return cached;
		return new Node(chunk);
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
		if(actualSize!=size)throw new IllegalStateException(actualSize+" != "+size);
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
			if(DEBUG_VALIDATION&&!cached.isWritingInstance()){
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
			modNode(index, n->n.value=value);
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
			
			Node node=getNode(index);
			
			ch=node.getContainer();
			popCache(index);
			modNode(index-1, prevNode->prevNode.next.set(node.next));
			
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
			changingListener.accept(false);
		}catch(Throwable e1){
			String str;
			try{
				str=toString();
			}catch(Throwable e2){
				str="<list>";
			}
			var ne=new IOException("Modification of "+str+" at "+container.getPtr()+" may have caused invalid state", e);
			ne.addSuppressed(e1);
			throw ne;
		}
		
		if(e!=null) throw UtilL.uncheckedThrow(e);
	}
	
	private void modNode(int index, Consumer<Node> modifier) throws IOException{
		Node node=getNode(index);
		if(solidNodes){
			var oldSiz=node.getInstanceSize();
			modifier.accept(node);
			var newSiz=node.getInstanceSize();
			if(oldSiz<newSiz||(oldSiz-newSiz)>cluster().getMinChunkSize()){
				
				Node newNode=new Node();
				newNode.next.set(node.next);
				newNode.value=node.value;
				newNode.allocContainer(cluster());
				
				nodeCache.put(index, newNode);
				if(index==0){
					first.set(newNode.getSelfPtr());
					writeStruct();
				}else{
					modNode(index-1, prevNode->prevNode.setNextNode(newNode));
				}
				node.getContainer().freeChaining();
			}else{
				node.writeStruct();
			}
		}else{
			modifier.accept(node);
			node.writeStruct();
		}
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
	private void requireNonChanging(){
		if(isChanging()) throw new IllegalStateException("recursive change at "+container);
	}
	private void pushChange(){
		requireNonChanging();
		
		this.changing=true;
		changingListener.accept(true);
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
		
		requireNonChanging();
		
		List<Node> nodeChain=toAdd.stream().map(this::fromVal).collect(Collectors.toList());
		
		int size=size();
		for(int i=0;i<nodeChain.size();i++){
			Node node=nodeChain.get(i);
			nodeCache.put(i+size, node);
		}
		
		for(int i=nodeChain.size()-1;i>=1;i--){
			Node prevNode=nodeChain.get(i-1);
			Node node    =nodeChain.get(i);
			node.allocContainer(cluster());
			prevNode.next.set(node.getSelfPtr());
		}
		Node chainStart=nodeChain.get(0);
		chainStart.allocContainer(cluster());
		
		linkToEnd(chainStart, nodeChain.size());
	}
	
	private void linkToEnd(Node newNode, int chainSize) throws IOException{
		changeSession(size->{
			if(size==0){
				first.set(newNode.getSelfPtr());
				writeStruct();
			}else{
				modNode(size-1, last->last.setNextNode(newNode));
			}
			addSize(chainSize);
		});
	}
	
	@Override
	public void addElement(T value) throws IOException{
		Objects.requireNonNull(value);
		requireNonChanging();
		
		Node newNode=fromVal(value);
		nodeCache.put(size, newNode);
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
		
		requireNonChanging();
		
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
				int insertionIndex=index-1;
				
				newNode.next.set(getNode(insertionIndex).next);
				newNode.writeStruct();
				
				modNode(insertionIndex, insertionNode->insertionNode.setNextNode(newNode));
				
			}
			addSize(1);
		});
	}
	
	@Override
	public void validate() throws IOException{
		if(!DEBUG_VALIDATION) return;
		if(container.getSize()>0){
			validateWrittenData();
		}
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
