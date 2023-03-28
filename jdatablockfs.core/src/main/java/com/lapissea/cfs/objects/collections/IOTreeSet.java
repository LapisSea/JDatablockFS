package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOTransaction;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.NewObj;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck;
import com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static java.util.function.Predicate.not;

public final class IOTreeSet<T extends Comparable<T>> extends AbstractUnmanagedIOSet<T>{
	
	private interface Node extends IOInstance.Def<Node>{
		
		static String toShortString(Node ptr){
			if(!ptr.hasValue()) return "/";
			
			StringJoiner sj = new StringJoiner(" ", "{", "}");
			sj.add("val = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add(ptr.left() + "L");
			if(ptr.hasRight()) sj.add(ptr.right() + "R");
			sj.add(ptr.red()? "r" : "b");
			return sj.toString();
		}
		static String toString(Node ptr){
			if(!ptr.hasValue()) return "<EMPTY>";
			
			StringJoiner sj = new StringJoiner(", ", "NodePtr{", "}");
			sj.add("value = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add("left = " + ptr.left());
			if(ptr.hasRight()) sj.add("right = " + ptr.right());
			sj.add(ptr.red()? "red" : "black");
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
		
		boolean red();
		void red(boolean red);
		
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
		
		static Node of(long index, boolean red, long left, long right){
			class Constr{
				private static final NewObj<Node> VAL = Struct.of(Node.class).emptyConstructor();
			}
			var ptr = Constr.VAL.make();
			ptr.valueIndex(index);
			ptr.red(red);
			ptr.left(left);
			ptr.right(right);
			return ptr;
		}
		static Node of(long index, boolean red){
			return of(index, red, -1, -1);
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
	
	private static final TypeLink.Check TYPE_CHECK = new TypeLink.Check(
		IOTreeSet.class,
		RawCheck.of(Comparable.class::isAssignableFrom, "is not Comparable").arg()
	);
	
	public IOTreeSet(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef.argCount() == 0? typeDef.withArgs(TypeLink.of(Object.class)) : typeDef, TYPE_CHECK);
		
		if(isSelfDataEmpty()){
			allocateNulls();
			writeManagedFields();
		}
		
		readManagedFields();
	}
	
	private record IndexedNode(long idx, Node node){
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
			this.node = node;
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
			assert nodes.get(nodeIdx).equals(cached.node);
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
		assert nodeIdx != 0 || !node.red() : "Root is red";
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
	private void setAndUpdateColor(IndexedNode node, boolean red) throws IOException{
		if(node.node.red() == red) return;
		node.node.red(false);
		updateNode(node);
	}
	
	private void removeLR(NodePointer ptr) throws IOException{
		var toRemove = ptr.child(this);
		
		var hasL = toRemove.node.hasLeft();
		var hasR = toRemove.node.hasRight();
		
		if(hasL != hasR){
			if(ptr.idx == -1){
				var childChild = toRemove.node.getChild(hasR, this);
				childChild.node.red(false);
				gcNodeValue(toRemove);
				updateNode(0, childChild.node.clone());
				gcNode(childChild);
			}else{
				ptr.setChild(toRemove.node.getChild(hasR));
				gcNodeValue(toRemove);
			}
		}else{
			if(!hasR){
				if(ptr.idx == -1){
					gcNodeValue(toRemove);
					return;
				}
				ptr.node.yeetChild(ptr.relation);
				gcNodeValue(toRemove);
			}else{
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
				return;
			}
		}
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
			
			while(nodes.popLastIf(not(Node::hasValue)).isPresent()){
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
			
			if(valIdx == values.size()) values.add(new Val<>(value));
			else values.set(valIdx, new Val<>(value));
		}
		return nodeIdx;
	}
	
	private long findNodeIdx() throws IOException{
		if(!blankNodeIds.isEmpty()){
			var nodeIdx = popMinIdx(blankNodeIds);
//			assert !nodes.get(nodeIdx).hasValue();
			return nodeIdx;
		}
		
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
		if(!blankValueIds.isEmpty()){
			var idx = popMinIdx(blankValueIds);
//			assert values.get(idx).val == null;
			return idx;
		}
		
		
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
			var read = nodes.get(e.getKey());
			if(e.getValue().node.equals(read)) continue;
			throw new IllegalStateException("cache desync " + e);
		}
		
		var root = nodes.get(0);
		if(root.red()){
			throw new IllegalStateException("Root is not black!");
		}
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
			if(node.red() && child.red()){
				throw new IllegalStateException("A node is red and its " + (lr? "right" : "left") + " child is also red\n" +
				                                "Node:  " + node + "\n" +
				                                "Child: " + child);
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
			repairRedBlack(parent);
			deltaSize(1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	private void repairRedBlack(NodePointer parent) throws IOException{
		if(!parent.node.red()) return;
		
		NodePointer grandparent = findNodeParent(parent);
		if(grandparent == null){
			setAndUpdateColor(parent.index(), false);
			return;
		}
		
		var uncle = grandparent.otherChild(this);
		
		if(uncle != null && !uncle.node.red()){
			setAndUpdateColor(parent.index(), false);
			setAndUpdateColor(grandparent.index(), true);
			setAndUpdateColor(uncle, false);
			repairRedBlack(grandparent);
			return;
		}
		
		if(!grandparent.relation){
			if(parent.relation){
				setAndUpdateColor(parent.child(this), false);
				setAndUpdateColor(grandparent.index(), true);
				var newP = rotateLeft(parent.index());
				rotateRight(findNodeParent(newP).index());
			}else{
				setAndUpdateColor(parent.index(), false);
				setAndUpdateColor(grandparent.index(), true);
				rotateRight(grandparent.index());
			}
		}else{
			if(!parent.relation){
				setAndUpdateColor(parent.child(this), false);
				setAndUpdateColor(grandparent.index(), true);
				var newP = rotateRight(parent.index());
				rotateLeft(findNodeParent(newP).index());
			}else{
				setAndUpdateColor(parent.index(), false);
				setAndUpdateColor(grandparent.index(), true);
				rotateLeft(grandparent.index());
			}
		}
	}
	
	private IndexedNode rotateLeft(IndexedNode iNode) throws IOException{
		var node       = iNode.node;
		var parent     = findNodeParent(iNode);
		var rightChild = node.right(this);
		
		node.right(rightChild.node.left());
		if(parent != null){
			rightChild.node.left(iNode.idx);
			
			updateNode(iNode);
			updateNode(rightChild);
			
			parent.setChild(rightChild);
			updateNode(parent.index());
		}else{
			rightChild.node.left(rightChild.idx);
			
			updateNode(0, rightChild.node);
			updateNode(rightChild.idx, node);
		}
		return rightChild;
	}
	
	private IndexedNode rotateRight(IndexedNode iNode) throws IOException{
		var node      = iNode.node;
		var parent    = findNodeParent(iNode);
		var leftChild = node.left(this);
		
		node.left(leftChild.node.right());
		if(parent != null){
			leftChild.node.right(iNode.idx);
			
			updateNode(iNode);
			updateNode(leftChild);
			
			parent.setChild(leftChild);
			updateNode(parent.index());
		}else{
			leftChild.node.right(leftChild.idx);
			
			updateNode(0, leftChild.node);
			updateNode(leftChild.idx, node);
		}
		return leftChild;
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
		nodes.clear();
		values.clear();
		nodeCache.clear();
		blankNodeIds.clear();
		blankValueIds.clear();
		deltaSize(-size());
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
