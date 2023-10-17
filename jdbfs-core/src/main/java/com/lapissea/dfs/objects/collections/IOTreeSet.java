package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.chunk.DataProvider;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.NewObj;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.TypeCheck.ArgCheck.RawCheck;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class IOTreeSet<T extends Comparable<T>> extends AbstractUnmanagedIOSet<T>{
	
	private interface Node extends IOInstance.Def<Node>{
		
		static String toShortString(Node ptr){
			if(!ptr.hasValue()) return "/";
			
			StringJoiner sj = new StringJoiner(" ", "{", "}");
			sj.add("val = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add(ptr.left() + "L");
			if(ptr.hasRight()) sj.add(ptr.right() + "R");
			return sj.toString();
		}
		static String toString(Node ptr){
			if(!ptr.hasValue()) return "<EMPTY>";
			
			StringJoiner sj = new StringJoiner(", ", "NodePtr{", "}");
			sj.add("value = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add("left = " + ptr.left());
			if(ptr.hasRight()) sj.add("right = " + ptr.right());
			return sj.toString();
		}
		
		@VirtualNumSize
		long valueIndex();
		void valueIndex(long valueIndex);
		
		@VirtualNumSize(name = "lrSize")
		long left();
		void left(long left);
		
		@VirtualNumSize(name = "lrSize")
		long right();
		void right(long right);
		
		default IndexedNode left(IOTreeSet<?> set) throws IOException { return hasLeft()? new IndexedNode(left(), set.node(left())) : null; }
		default IndexedNode right(IOTreeSet<?> set) throws IOException{ return hasRight()? new IndexedNode(right(), set.node(right())) : null; }
		
		default long getChild(boolean isRight){
			return isRight? right() : left();
		}
		default IndexedNode getChild(boolean isRight, IOTreeSet<?> set) throws IOException{
			return isRight? right(set) : left(set);
		}
		
		
		default void setChild(boolean isRight, long idx){
			if(isRight) right(idx);
			else left(idx);
		}
		default void yeetChild(boolean isRight){
			setChild(isRight, -1);
		}
		default void yeetValue(){
			valueIndex(-1);
		}
		
		default boolean hasLeft() { return left() != -1; }
		default boolean hasRight(){ return right() != -1; }
		default boolean hasChild(boolean isRight){
			return getChild(isRight) != -1;
		}
		
		default boolean hasValue(){
			return valueIndex() != -1;
		}
		
		static Node of(long index, long left, long right){
			class Constr{
				private static final NewObj<Node> VAL = Struct.of(Node.class).emptyConstructor();
			}
			var ptr = Constr.VAL.make();
			ptr.valueIndex(index);
			ptr.left(left);
			ptr.right(right);
			return ptr;
		}
		static Node of(long index, boolean red){
			return of(index, -1, -1);
		}
	}
	
	private static final class Val<T> extends IOInstance.Managed<Val<T>>{
		@IOValue
		@IOValue.Generic
		@IONullability(NULLABLE)
		@IOValue.Reference
		private T val;
		
		public Val(){ }
		public Val(T val){
			this.val = val;
		}
		
		@Override
		public String toShortString(){
			return val == null? "<EMPTY>" : Utils.toShortString(val);
		}
	}
	
	@IOValue
	private IOList<Val<T>> values;
	@IOValue
	private IOList<Node>   nodes;
	
	private static final TypeCheck TYPE_CHECK = new TypeCheck(
		IOTreeSet.class,
		RawCheck.of(Comparable.class::isAssignableFrom, "is not Comparable").arg()
	);
	
	public IOTreeSet(DataProvider provider, Reference reference, IOType typeDef) throws IOException{
		super(provider, reference, ((IOType.RawAndArg)typeDef).withDefaultArgs(IOType.of(Object.class)));
		
		if(isSelfDataEmpty()){
			allocateNulls();
			writeManagedFields();
		}
		
		readManagedFields();
	}
	
	private record IndexedNode(long idx, @NotNull Node node){
		private IndexedNode{
			Objects.requireNonNull(node);
		}
		@Override
		public String toString(){
			return node + " @" + idx;
		}
	}
	
	private record NodePointer(long idx, Node node, boolean relation){
		boolean hasChild(){ return node.hasChild(relation); }
		long child()      { return node.getChild(relation); }
		IndexedNode child(IOTreeSet<?> set) throws IOException{
			return node.getChild(relation, set);
		}
		IndexedNode otherChild(IOTreeSet<?> set) throws IOException{
			return node.getChild(!relation, set);
		}
		IndexedNode index(){
			return new IndexedNode(idx, node);
		}
		void setChild(IndexedNode node){ setChild(node.idx); }
		void setChild(long idx)        { node.setChild(relation, idx); }
		@Override
		public String toString(){
			return node + " @" + idx + " " + (relation? "R" : "L");
		}
	}
	
	private NodePointer findParent(T obj) throws IOException{
		Node    parent    = null, node = node(0);
		long    parentPos = -1, nodePos = 0;
		boolean relation  = true;
		
		while(true){
			var val = getVal(node);
			
			var comp = val.compareTo(obj);
			if(comp == 0){
				if(Objects.equals(val, obj)){
					if(parent == null){
						parent = Node.of(0, true);
						parent.right(0);
					}
					return new NodePointer(parentPos, parent, relation);
				}
			}
			
			boolean isRight = comp<0;
			
			if(!node.hasChild(isRight)){
				return new NodePointer(nodePos, node, isRight);
			}
			
			parentPos = nodePos;
			parent = node;
			nodePos = node.getChild(isRight);
			node = node(nodePos);
			relation = isRight;
		}
	}
	
	private T getVal(Node node) throws IOException{
		return values.get(node.valueIndex()).val;
	}
	
	private static final class NodeCache{
		private final Node node;
		private       byte age = 1;
		private NodeCache(Node node){ this.node = node.clone(); }
		public NodeCache(Node node, byte age){
			this.node = node.clone();
			this.age = age;
		}
		void makeOlder(){
			if(age<10) age++;
		}
		@Override
		public String toString(){
			return age + " " + node;
		}
	}
	
	private final Map<Long, NodeCache> nodeCache = new HashMap<>();
	private Node node(long nodeIdx) throws IOException{
		var cached = nodeCache.get(nodeIdx);
		if(cached != null){
			if(DEBUG_VALIDATION) IOFieldTools.requireFieldsEquals(nodes.get(nodeIdx), cached.node);
			cached.makeOlder();
			return cached.node.clone();
		}
		nodeCache.entrySet().stream().skip((nodeIdx + nodeCache.size())%Math.max(1, nodeCache.size())).findAny()
		         .filter(e -> (e.getValue().age -= 2)<=0).map(Map.Entry::getKey).ifPresent(nodeCache::remove);
		
		var val = nodes.get(nodeIdx);
		nodeCache.put(nodeIdx, new NodeCache(val));
		return val;
	}
	
	private void updateNode(IndexedNode node) throws IOException{
		updateNode(node.idx, node.node);
	}
	private void updateNode(long nodeIdx, Node node) throws IOException{
		nodeCache.put(nodeIdx, new NodeCache(node, (byte)3));
		nodes.set(nodeIdx, node);
	}
	
	private void swapValues(IndexedNode a, IndexedNode b) throws IOException{
		var aVal = a.node.valueIndex();
		a.node.valueIndex(b.node.valueIndex());
		b.node.valueIndex(aVal);
		updateNode(a);
		updateNode(b);
	}
	
	private void removeLR(NodePointer ptr) throws IOException{
		var toRemove = ptr.child(this);
		
		var hasL = toRemove.node.hasLeft();
		var hasR = toRemove.node.hasRight();
		
		if(!hasL && !hasR){
			justRemove(ptr, toRemove);
			return;
		}
		
		if(hasL != hasR){
			removeAndMoveDownTheChild(ptr, toRemove, hasR);
			return;
		}
		
		swapWithLeafAndPop(toRemove);
	}
	
	private void swapWithLeafAndPop(IndexedNode toRemove) throws IOException{
		var sParent  = toRemove;
		var smallest = sParent.node.right(this);
		var lr       = true;
		
		while(smallest.node.hasLeft()){
			sParent = smallest;
			smallest = smallest.node.left(this);
			lr = false;
		}
		
		swapValues(smallest, toRemove);
		removeLR(new NodePointer(sParent.idx, sParent.node, lr));
	}
	
	private void removeAndMoveDownTheChild(NodePointer ptr, IndexedNode toRemove, boolean hasR) throws IOException{
		var childChild = toRemove.node.getChild(hasR, this);
		if(ptr.idx == -1){
			gcNodeValue(toRemove);
			updateNode(0, childChild.node.clone());
			gcNode(childChild);
			return;
		}
		
		ptr.setChild(childChild);
		gcNodeValue(toRemove);
		updateNode(ptr.index());
		
	}
	
	private void justRemove(NodePointer ptr, IndexedNode toRemove) throws IOException{
		
		if(ptr.idx == -1){
			gcNodeValue(toRemove);
			return;
		}
		
		ptr.node.yeetChild(ptr.relation);
		gcNodeValue(toRemove);
		
		
		if(ptr.idx != -1){
			updateNode(ptr.index());
		}
	}
	
	private void gcNodeValue(IndexedNode node) throws IOException{
		gcValue(node);
		gcNode(node);
	}
	
	private final TreeSet<Long> blankValueIds = new TreeSet<>();
	private       boolean       blankValueIdsScanned;
	private void gcValue(IndexedNode node) throws IOException{
		var idx = node.node.valueIndex();
		if(idx + 1 == values.size()){
			values.remove(idx);
			blankValueIds.remove(idx);
			while(values.popLastIf(v -> v.val == null).isPresent()){
				blankValueIds.remove(values.size());
			}
		}else{
			values.free(idx);
			if(idx>0) blankValueIds.add(idx);
		}
	}
	
	private final TreeSet<Long> blankNodeIds = new TreeSet<>();
	private       boolean       blankNodeIdsScanned;
	private void gcNode(IndexedNode node) throws IOException{
		if(node.idx + 1 == nodes.size()){
			nodes.remove(node.idx);
			nodeCache.remove(node.idx);
			blankNodeIds.remove(node.idx);
			
			while(nodes.popLastIf(n -> !n.hasValue()).isPresent()){
				blankNodeIds.remove(nodes.size());
				nodeCache.remove(nodes.size());
			}
		}else{
			node.node.yeetValue();
			node.node.yeetChild(true);
			node.node.yeetChild(false);
			
			updateNode(node.idx, node.node);
			if(node.idx>0) blankNodeIds.add(node.idx);
		}
	}
	
	private long addNode(T value, boolean red) throws IOException{
		long valIdx  = findValueIdx();
		long nodeIdx = findNodeIdx();
		
		var node = Node.of(valIdx, red);
		try(var ignored = transaction()){
			if(nodeIdx == nodes.size()){
				nodeCache.put(nodes.size(), new NodeCache(node));
				nodes.add(node);
			}else updateNode(nodeIdx, node);
			
			if(valIdx == values.size()){
				values.add(new Val<>(value));
			}else{
				values.set(valIdx, new Val<>(value));
			}
		}
		return nodeIdx;
	}
	
	private long findNodeIdx() throws IOException{
		if(blankNodeIds.isEmpty()){
			return scanNodeIds();
		}
		return popMinIdx(blankNodeIds);
	}
	
	private long scanNodeIds() throws IOException{
		long nodesSiz = nodes.size();
		long idx      = -1;
		if(!blankNodeIdsScanned){
			for(long i = 0; i<nodesSiz; i++){
				if(!nodes.get(i).hasValue()){
					if(idx == -1) idx = i;
					else blankNodeIds.add(i);
				}
			}
			blankNodeIdsScanned = true;
		}
		
		return idx == -1? nodesSiz : idx;
	}
	
	private long findValueIdx() throws IOException{
		if(blankValueIds.isEmpty()){
			return scanValueIds();
		}
		return popMinIdx(blankValueIds);
	}
	
	private long scanValueIds() throws IOException{
		long idx    = -1;
		var  valSiz = values.size();
		
		if(!blankValueIdsScanned){
			for(long i = 0; i<valSiz; i++){
				if(values.get(i).val == null){
					if(idx == -1) idx = i;
					else blankValueIds.add(i);
				}
			}
			blankValueIdsScanned = true;
		}
		
		return idx == -1? valSiz : idx;
	}
	
	private long popMinIdx(TreeSet<Long> set){
		var idx = set.ceiling(set.first());
		Objects.requireNonNull(idx);
		set.remove(idx);
		return idx;
	}
	
	private IOTransaction transaction(){
		return getDataProvider().getSource().openIOTransaction();
	}
	
	private void validate() throws IOException{
		if(isEmpty()) return;
		var nodes = this.nodes.stream().toList();
		
		for(var e : nodeCache.entrySet()){
			var cached = e.getValue().node;
			var read   = nodes.get(Math.toIntExact(e.getKey()));
			IOFieldTools.requireFieldsEquals(cached, read, "Cache desync");
		}
		
		var root = nodes.get(0);
		if(!root.hasValue()){
			throw new IllegalStateException("Root has no value!");
		}
		
		var h = HashSet.<Long>newHashSet(nodes.size());
		h.add(0L);
		var depth = validate(0, nodes, root, h);
//		if(Rand.b(0.01F)) LogUtil.println(depth, (int)Math.ceil(Math.log(size())));
	}
	private int validate(int depth, List<Node> nodes, Node node, Set<Long> ids) throws IOException{
		var val     = getVal(node);
		int chDepth = depth;
		for(int i = 0; i<2; i++){
			var lr = i == 0;
			if(!node.hasChild(lr)) continue;
			
			var idx   = node.getChild(lr);
			var child = nodes.get((int)idx);
			if(!ids.add(idx)){
				throw new IllegalStateException("duplicate idx " + idx);
			}
			var cVal = getVal(child);
			var c    = val.compareTo(cVal);
			var c2   = lr? -1 : 1;
			if(c != c2){
				throw new IllegalStateException(val + (lr? " > " : " < ") + cVal);
			}
			chDepth = Math.max(chDepth, validate(depth + 1, nodes, child, ids));
		}
		return chDepth;
	}
	
	
	@Override
	public boolean add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		if(nodes.isEmpty()){
			addNode(value, false);
			deltaSize(1);
			return true;
		}
		var parent = findParent(value);
		if(parent.node.hasChild(parent.relation)) return false;
		
		try(var ignored = transaction()){
			parent.setChild(addNode(value, true));
			updateNode(parent.index());
			deltaSize(1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	private NodePointer findNodeParent(NodePointer node) throws IOException{
		return findNodeParent(node.index());
	}
	private NodePointer findNodeParent(IndexedNode node) throws IOException{
		var idx = node.idx;
		if(idx == 0) return null;
		
		for(var e : nodeCache.entrySet()){
			var cache = e.getValue();
			var cNode = cache.node;
			var isR   = cNode.right() == idx;
			if(isR || cNode.left() == idx){
				cache.makeOlder();
				return new NodePointer(e.getKey(), cNode, isR);
			}
		}
		
		return findParentByValue(node);
	}
	
	private NodePointer findParentByValue(IndexedNode node) throws IOException{
		var val = getVal(node.node);
		var res = findParent(val);
		assert res.child() == node.idx;
		return res;
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		if(nodes.isEmpty() || value == null) return false;
		
		var res = findParent(value);
		if(!res.node.hasChild(res.relation)) return false;
		
		var toRemove = res.child(this);
		
		if(!toRemove.node.hasLeft() && !toRemove.node.hasRight()){
		
		}
		
		try(var ignored = transaction()){
			removeLR(res);
			deltaSize(-1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		
		nodeCache.clear();
		blankNodeIds.clear();
		blankValueIds.clear();
		
		zeroSize();
		nodes.clear();
		values.clear();
	}
	
	@Override
	public boolean contains(T value) throws IOException{
		if(nodes.isEmpty() || value == null) return false;
		return findParent(value).hasChild();
	}
	
	@Override
	public IOIterator<T> iterator(){
		
		var src = values.iterator();
		return new IOIterator<>(){
			private T val;
			@Override
			public boolean hasNext() throws IOException{
				if(val == null) next();
				return val != null;
			}
			private void next() throws IOException{
				while(src.hasNext()){
					val = src.ioNext().val;
					if(val != null) return;
				}
			}
			@Override
			public T ioNext() throws IOException{
				if(val == null) next();
				var v = val;
				val = null;
				return v;
			}
		};
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		nodes.requestCapacity(capacity);
		values.requestCapacity(capacity);
	}
}
