package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.NewObj;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.TypeLink.Check.ArgCheck.RawCheck;
import com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.Rand;

import java.io.IOException;
import java.util.*;

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
		
		boolean red();
		void red(boolean red);
		
		default long getChild(boolean isRight){
			return isRight? right() : left();
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
	
	private static final int RET_BIT   = 0b0001;
	private static final int CONTAINS  = 0b0001;
	private static final int INSERTED  = 0b0011;
	private static final int REMOVED   = 0b0101;
	private static final int POP       = 0b0110;
	private static final int NOT_FOUND = 0b1001;
	
	private static final int INSERT = 0;
	private static final int REMOVE = 1;
	private static final int FIND   = 2;
	
	private int nodeIterAction(long nodePos, Node node, T obj, int action) throws IOException{
		var val = getVal(node);
		
		boolean isRight;
		var     comp = val.compareTo(obj);
		if(comp == 0){
			if(Objects.equals(val, obj)){
				if(action == REMOVE){
					if(nodePos == 0){//root edge case
						var n = Node.of(0, true);
						n.right(0);
						removeLR(-1, n, true);
						return REMOVED;
					}
					
					return POP;
				}else return CONTAINS;
			}
		}
		
		
		if(node.hasChild(isRight = comp<0)){
			var idx = node.getChild(isRight);
			var res = nodeIterAction(idx, node(idx), obj, action);
			if(res == POP){
				try(var ignored = getDataProvider().getSource().openIOTransaction()){
					removeLR(nodePos, node, isRight);
				}
				return REMOVED;
			}
			assert (res&RET_BIT) == 1;
			return res;
		}else{
			if(action != INSERT) return NOT_FOUND;
			try(var ignored = getDataProvider().getSource().openIOTransaction()){
				node.setChild(isRight, addNode(obj, !node.red()));
				updateNode(nodePos, node);
			}
			return INSERTED;
		}
	}
	
	private T getVal(Node node) throws IOException{
		return values.get(node.valueIndex()).val;
	}
	
	private static final class NodeCache{
		private final Node node;
		private       byte age = 1;
		private NodeCache(Node node){ this.node = node; }
	}
	
	private final Map<Long, NodeCache> nodeCache = new HashMap<>();
	private Node node(long nodeIdx) throws IOException{
		var cached = nodeCache.get(nodeIdx);
		if(cached != null){
			if(cached.age<10) cached.age++;
			return cached.node;
		}
		if(nodeCache.size()>1){
			var id = nodeCache.keySet().stream().skip(Rand.i(nodeCache.size())).findAny().orElseThrow();
			var c  = nodeCache.get(id);
			c.age -= 2;
			if(c.age<=0){
				nodeCache.remove(id);
			}
		}
		var val = nodes.get(nodeIdx);
		nodeCache.put(nodeIdx, new NodeCache(val));
		return val;
	}
	private void updateNode(long nodeIdx, Node node) throws IOException{
		nodeCache.remove(nodeIdx);
		nodes.set(nodeIdx, node);
	}
	
	private void swapValues(long a, long b) throws IOException{
		var nodeA = node(a);
		var nodeB = node(b);
		
		var newA = Node.of(nodeB.valueIndex(), nodeB.red());
		newA.left(nodeA.left());
		newA.right(nodeA.right());
		
		var newB = Node.of(nodeA.valueIndex(), nodeA.red());
		newB.left(nodeB.left());
		newB.right(nodeB.right());
		
		updateNode(a, newA);
		updateNode(b, newB);
	}
	
	private void removeLR(long parentPos, Node parent, boolean isRight) throws IOException{
		var childIdx      = parent.getChild(isRight);
		var toRemoveChild = node(childIdx);
		
		var hasL = toRemoveChild.hasLeft();
		var hasR = toRemoveChild.hasRight();
		
		if(hasL != hasR){
			var childChildIdx = toRemoveChild.getChild(hasR);
			if(parentPos == -1){
				popValue(childIdx, toRemoveChild);
				var cc = node(childChildIdx);
				updateNode(0, cc);
				popNode(childChildIdx, cc);
			}else{
				parent.setChild(isRight, childChildIdx);
				popValue(childIdx, toRemoveChild);
			}
		}else{
			if(!hasR){
				if(parentPos == -1){
					nodes.clear();
					nodeCache.clear();
					values.clear();
					return;
				}
				parent.yeetChild(isRight);
				popValue(childIdx, toRemoveChild);
			}else{
				var smallestBiggerParentIdx = childIdx;
				
				var smallestBiggerIdx = toRemoveChild.right();
				var smallestBigger    = node(smallestBiggerIdx);
				var lr                = true;
				
				while(smallestBigger.hasLeft()){
					smallestBiggerParentIdx = smallestBiggerIdx;
					
					smallestBiggerIdx = smallestBigger.left();
					smallestBigger = node(smallestBiggerIdx);
					lr = false;
				}
				
				swapValues(smallestBiggerIdx, childIdx);
				removeLR(smallestBiggerParentIdx, node(smallestBiggerParentIdx), lr);
				return;
			}
		}
		if(parentPos != -1){
			updateNode(parentPos, parent);
		}
	}
	
	private final TreeSet<Long> blankValueIds = new TreeSet<>();
	private void popValue(long nodeIdx, Node node) throws IOException{
		var idx = node.valueIndex();
		
		popNode(nodeIdx, node);
		
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
	private void popNode(long nodeIdx, Node node) throws IOException{
		if(nodeIdx + 1 == nodes.size()){
			nodes.remove(nodeIdx);
			nodeCache.remove(nodeIdx);
			blankNodeIds.remove(nodeIdx);
			
			while(nodes.popLastIf(not(Node::hasValue)).isPresent()){
				blankNodeIds.remove(nodes.size());
				nodeCache.remove(nodes.size());
			}
		}else{
			node.valueIndex(-1);
			node.yeetChild(true);
			node.yeetChild(false);
			
			updateNode(nodeIdx, node);
			if(nodeIdx>0) blankNodeIds.add(nodeIdx);
		}
	}
	
	private long addNode(T value, boolean red) throws IOException{
		long valIdx  = findValueIdx();
		long nodeIdx = findNodeIdx();
		
		var node = Node.of(valIdx, red);
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
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
	
	@Override
	public boolean add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		if(values.isEmpty()){
			addNode(value, false);
			deltaSize(1);
			return true;
		}
		var v = nodeIterAction(0, node(0), value, INSERT) == INSERTED;
		if(v){
			deltaSize(1);
			if(DEBUG_VALIDATION) validate();
		}
		return v;
	}
	
	private void validate() throws IOException{
		if(isEmpty()) return;
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
	public boolean remove(T value) throws IOException{
		if(nodes.isEmpty()) return false;
		var v = nodeIterAction(0, node(0), value, REMOVE) == REMOVED;
		if(v){
			deltaSize(-1);
			if(DEBUG_VALIDATION) validate();
		}
		return v;
	}
	
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		nodes.clear();
		nodeCache.clear();
		values.clear();
		deltaSize(-size());
	}
	
	@Override
	public boolean contains(T value) throws IOException{
		if(nodes.isEmpty()) return false;
		
		var v = nodeIterAction(0, node(0), value, FIND) == CONTAINS;
		return v;
	}
	
	@Override
	public IOIterator<T> iterator(){
		return new IOIterator<>(){
			private long count;
			private List<Node> stack;
			private List<Boolean> doneRight;
			
			@Override
			public boolean hasNext() throws IOException{
				if(stack == null) init();
				return !stack.isEmpty();
			}
			
			private void init() throws IOException{
				var cap = (int)Math.ceil(Math.sqrt(values.size()));
				stack = new ArrayList<>(cap);
				doneRight = new ArrayList<>(cap);
				if(nodes.isEmpty()) return;
				stack.add(node(0));
				doneRight.add(false);
				teleportLeft();
			}
			
			private void teleportLeft() throws IOException{
				Node p;
				while((p = stack.get(stack.size() - 1)).hasLeft()){
					p = node(p.left());
					stack.add(p);
					doneRight.add(false);
				}
			}
			
			private Node nextPtr() throws IOException{
				if(stack == null) init();
				if(stack.isEmpty()) throw new NoSuchElementException();
				while(true){
					var depth = stack.size() - 1;
					var ptr   = stack.get(depth);
					
					if(ptr.hasRight() && !doneRight.get(depth)){
						doneRight.set(depth, true);
						var right = node(ptr.right());
						stack.add(right);
						doneRight.add(false);
						teleportLeft();
						continue;
					}
					stack.remove(depth);
					doneRight.remove(depth);
					return ptr;
				}
			}
			
			@Override
			public T ioNext() throws IOException{
				var ptr = nextPtr();
				count++;
				if(DEBUG_VALIDATION){
					if(count>size()) throw new IllegalStateException(count + " > " + size());
				}
				return getVal(ptr);
			}
		};
	}
}
