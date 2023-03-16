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
import com.lapissea.util.ZeroArrays;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
		
		default IndexedNode left(IOTreeSet<?> set) throws IOException { return new IndexedNode(left(), hasLeft()? set.node(left()) : null); }
		default IndexedNode right(IOTreeSet<?> set) throws IOException{ return new IndexedNode(right(), hasRight()? set.node(right()) : null); }
		
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
		
		default boolean hasLeft() { return left() != -1; }
		default boolean hasRight(){ return right() != -1; }
		default boolean hasChild(boolean isRight){
			return getChild(isRight) != -1;
		}
		
		default boolean hasValue(){
			return valueIndex() != -1;
		}
		
		static Node of(long index, boolean red){
			class Constr{
				private static final NewObj<Node> VAL = Struct.of(Node.class).emptyConstructor();
			}
			var ptr = Constr.VAL.make();
			ptr.valueIndex(index);
			ptr.red(red);
			ptr.yeetChild(true);
			ptr.yeetChild(false);
			return ptr;
		}
	}
	
	private static final class Val<T> extends IOInstance.Managed<Val<T>>{
		@IOValue
		@IOValue.Generic
		@IONullability(NULLABLE)
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
	
	private record IndexedNode(long idx, Node node){ }
	
	private record NodeResult(long parentIdx, Node parent, boolean relation){ }
	
	private NodeResult findParent(T obj) throws IOException{
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
					return new NodeResult(parentPos, parent, relation);
				}
			}
			
			boolean isRight = comp<0;
			
			if(!node.hasChild(isRight)){
				return new NodeResult(nodePos, node, isRight);
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
	}
	
	private final Map<Long, NodeCache> nodeCache = new HashMap<>();
	private Node node(long nodeIdx) throws IOException{
		var cached = nodeCache.get(nodeIdx);
		if(cached != null){
			if(cached.age<10) cached.age++;
			return cached.node.clone();
		}
		nodeCache.entrySet().stream().skip((nodeIdx + nodeCache.size())%nodeCache.size()).findAny()
		         .filter(e -> (e.getValue().age -= 2)<=0).map(Map.Entry::getKey).ifPresent(nodeCache::remove);
		
		var val = nodes.get(nodeIdx);
		nodeCache.put(nodeIdx, new NodeCache(val));
		return val;
	}
	private void updateNode(long nodeIdx, Node node) throws IOException{
		nodeCache.put(nodeIdx, new NodeCache(node));
		nodes.set(nodeIdx, node);
	}
	
	private void swapValues(IndexedNode a, IndexedNode b) throws IOException{
		var newA = Node.of(b.node.valueIndex(), b.node.red());
		newA.left(a.node.left());
		newA.right(a.node.right());
		
		var newB = Node.of(a.node.valueIndex(), a.node.red());
		newB.left(b.node.left());
		newB.right(b.node.right());
		
		updateNode(a.idx, newA);
		updateNode(b.idx, newB);
	}
	
	private void removeLR(long parentPos, Node parent, boolean isRight) throws IOException{
		var toRemove = parent.getChild(isRight, this);
		
		var hasL = toRemove.node.hasLeft();
		var hasR = toRemove.node.hasRight();
		
		if(hasL != hasR){
			if(parentPos == -1){
				var childChild = toRemove.node.getChild(hasR, this);
				popValue(toRemove);
				updateNode(0, childChild.node);
				popNode(childChild);
			}else{
				parent.setChild(isRight, toRemove.node.getChild(hasR));
				popValue(toRemove);
			}
		}else{
			if(!hasR){
				if(parentPos == -1){
					popValue(toRemove);
					return;
				}
				parent.yeetChild(isRight);
				popValue(toRemove);
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
				removeLR(sParent.idx, node(sParent.idx), lr);
				return;
			}
		}
		if(parentPos != -1){
			updateNode(parentPos, parent);
		}
	}
	
	private final TreeSet<Long> blankValueIds = new TreeSet<>();
	private void popValue(IndexedNode node) throws IOException{
		var idx = node.node.valueIndex();
		
		popNode(node);
		
		if(idx + 1 == values.size()){
			values.remove(idx);
			blankValueIds.remove(idx);
			while(values.popLastIf(v -> v.val == null).isPresent()){
				blankValueIds.remove(values.size());
			}
		}else{
			values.set(idx, new Val<>());
			if(idx>0) blankValueIds.add(idx);
		}
	}
	
	private final TreeSet<Long> blankNodeIds = new TreeSet<>();
	private void popNode(IndexedNode node) throws IOException{
		if(node.idx + 1 == nodes.size()){
			nodes.remove(node.idx);
			nodeCache.remove(node.idx);
			blankNodeIds.remove(node.idx);
			
			while(nodes.popLastIf(not(Node::hasValue)).isPresent()){
				blankNodeIds.remove(nodes.size());
				nodeCache.remove(nodes.size());
			}
		}else{
			node.node.valueIndex(-1);
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
			assert !nodes.get(nodeIdx).hasValue();
			return nodeIdx;
		}
		
		long nodesSiz = nodes.size();
		for(int i = 0; i<nodesSiz; i++){
			if(!node(i).hasValue()){
				return i;
			}
		}
		return nodesSiz;
	}
	
	private long findValueIdx() throws IOException{
		if(!blankValueIds.isEmpty()){
			var idx = popMinIdx(blankValueIds);
			assert values.get(idx).val == null;
			return idx;
		}
		
		var valSiz = values.size();
		for(int i = 0; i<valSiz; i++){
			if(values.get(i).val == null){
				return i;
			}
		}
		return valSiz;
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
		for(var e : nodeCache.entrySet()){
			var read = nodes.get(e.getKey());
			if(e.getValue().node.equals(read)) continue;
			throw new RuntimeException("cache desync " + e);
		}
		
		var h = HashSet.<Long>newHashSet((int)nodes.size());
		h.add(0L);
		var depth = validate(0, node(0), h);
//		LogUtil.println(depth, Math.log(size()));
	}
	private int validate(int depth, Node node, Set<Long> ids) throws IOException{
		var val = getVal(node);
		for(int i = 0; i<2; i++){
			var lr = i == 0;
			if(node.hasChild(lr)){
				var idx   = node.getChild(lr);
				var child = node(idx);
				if(!ids.add(idx)){
					throw new IllegalStateException("duplicate idx " + idx);
				}
				var cVal = getVal(child);
				var c    = val.compareTo(cVal);
				var c2   = lr? -1 : 1;
				if(c != c2){
					throw new IllegalStateException(val + (lr? " > " : " < ") + cVal);
				}
				depth = Math.max(depth, validate(depth + 1, child, ids));
			}
		}
		return depth;
	}
	
	
	@Override
	public boolean add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		if(nodes.isEmpty()){
			addNode(value, false);
			deltaSize(1);
			return true;
		}
		
		var res = findParent(value);
		if(res.parent.hasChild(res.relation)) return false;
		
		try(var ignored = transaction()){
			var newNode = addNode(value, !res.parent.red());
			res.parent.setChild(res.relation, newNode);
			updateNode(res.parentIdx, res.parent);
			deltaSize(1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		if(nodes.isEmpty() || value == null) return false;
		
		var res = findParent(value);
		if(!res.parent.hasChild(res.relation)) return false;
		
		try(var ignored = transaction()){
			removeLR(res.parentIdx, res.parent, res.relation);
			deltaSize(-1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		nodes.clear();
		nodeCache.clear();
		blankNodeIds.clear();
		values.clear();
		blankValueIds.clear();
		deltaSize(-size());
	}
	
	@Override
	public boolean contains(T value) throws IOException{
		if(nodes.isEmpty() || value == null) return false;
		var res = findParent(value);
		return res.parent.hasChild(res.relation);
	}
	
	@Override
	public IOIterator<T> iterator(){
		final class TreeIterator implements IOIterator<T>{
			private List<Node> stack;
			private boolean[]  doneRight = ZeroArrays.ZERO_BOOLEAN;
			
			private boolean doneRightGet(int depth){ return depth<doneRight.length && doneRight[depth]; }
			private void doneRightRemove(int depth){ if(depth<doneRight.length) doneRight[depth] = false; }
			private void doneRightSet(int depth){
				if(depth>=doneRight.length) doneRight = Arrays.copyOf(
					doneRight, Math.max(stack.size(), doneRight.length == 0? cap() : doneRight.length*2));
				doneRight[depth] = true;
			}
			
			@Override
			public boolean hasNext() throws IOException{
				if(stack == null) init();
				return !stack.isEmpty();
			}
			
			private void init() throws IOException{
				stack = new ArrayList<>(cap());
				if(nodes.isEmpty()) return;
				stack.add(node(0));
				teleportLeft();
			}
			
			private int cap(){
				return (int)Math.ceil(Math.sqrt(values.size()));
			}
			
			private void teleportLeft() throws IOException{
				Node p = stack.get(stack.size() - 1);
				while(p.hasLeft()){
					stack.add(p = node(p.left()));
				}
			}
			
			private Node nextPtr() throws IOException{
				while(true){
					var depth = stack.size() - 1;
					var ptr   = stack.get(depth);
					
					if(ptr.hasRight() && !doneRightGet(depth)){
						doneRightSet(depth);
						stack.add(node(ptr.right()));
						teleportLeft();
						continue;
					}
					stack.remove(depth);
					doneRightRemove(depth);
					return ptr;
				}
			}
			
			@Override
			public T ioNext() throws IOException{
				if(stack == null) init();
				if(stack.isEmpty()) throw new NoSuchElementException();
				return getVal(nextPtr());
			}
		}
		
		return new TreeIterator();
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		nodes.requestCapacity(capacity);
		values.requestCapacity(capacity);
	}
}
