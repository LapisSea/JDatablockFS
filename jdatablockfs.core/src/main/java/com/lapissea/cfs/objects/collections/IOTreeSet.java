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

import java.io.IOException;
import java.util.*;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class IOTreeSet<T extends Comparable<T>> extends AbstractUnmanagedIOSet<T>{
	
	private interface NodePtr extends IOInstance.Def<NodePtr>{
		
		static String toShortString(NodePtr ptr){
			if(ptr.valueIndex() == -1) return "/";
			
			StringJoiner sj = new StringJoiner(" ", "{", "}");
			sj.add("val = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add(ptr.left() + "L");
			if(ptr.hasRight()) sj.add(ptr.right() + "R");
			return sj.toString();
		}
		static String toString(NodePtr ptr){
			if(ptr.valueIndex() == -1) return "<EMPTY>";
			
			StringJoiner sj = new StringJoiner(", ", "NodePtr{", "}");
			sj.add("value = " + ptr.valueIndex());
			if(ptr.hasLeft()) sj.add("left = " + ptr.left());
			if(ptr.hasRight()) sj.add("right = " + ptr.right());
			return sj.toString();
		}
		
		@VirtualNumSize
		long valueIndex();
		void valueIndex(long valueIndex);
		
		@VirtualNumSize
		long left();
		void left(long left);
		
		@VirtualNumSize
		long right();
		void right(long right);
		
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
		
		static NodePtr of(long index){
			class Constr{
				private static final NewObj<NodePtr> VAL = Struct.of(NodePtr.class).emptyConstructor();
			}
			var ptr = Constr.VAL.make();
			ptr.valueIndex(index);
			ptr.left(-1);
			ptr.right(-1);
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
	private IOList<Val<T>>  values;
	@IOValue
	private IOList<NodePtr> nodes;
	
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
	
	private static final int ADD    = 0;
	private static final int REMOVE = 1;
	private static final int FIND   = 2;
	
	private int nodeIterAction(long nodePos, NodePtr node, T obj, int action) throws IOException{
		var val = getVal(node);
		
		boolean isRight;
		var     comp = val.compareTo(obj);
		if(comp == 0){
			if(Objects.equals(val, obj)){
				if(action == REMOVE){
					if(nodePos == 0){//root edge case
						var n = NodePtr.of(0);
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
			var res = nodeIterAction(idx, nodes.get(idx), obj, action);
			if(res == POP){
				try(var ignored = getDataProvider().getSource().openIOTransaction()){
					removeLR(nodePos, node, isRight);
				}
				return REMOVED;
			}
			assert (res&RET_BIT) == 1;
			return res;
		}else{
			if(action != ADD) return NOT_FOUND;
			try(var ignored = getDataProvider().getSource().openIOTransaction()){
				node.setChild(isRight, addNode(obj));
				nodes.set(nodePos, node);
			}
			return INSERTED;
		}
	}
	private T getVal(NodePtr node) throws IOException{
		return values.get(node.valueIndex()).val;
	}
	
	private void swapValues(long a, long b) throws IOException{
		var nodeA = nodes.get(a);
		var nodeB = nodes.get(b);
		
		var newA = NodePtr.of(nodeB.valueIndex());
		newA.left(nodeA.left());
		newA.right(nodeA.right());
		
		var newB = NodePtr.of(nodeA.valueIndex());
		newB.left(nodeB.left());
		newB.right(nodeB.right());
		
		nodes.set(a, newA);
		nodes.set(b, newB);
	}
	
	private void removeLR(long parentPos, NodePtr parent, boolean isRight) throws IOException{
		var childIdx      = parent.getChild(isRight);
		var toRemoveChild = nodes.get(childIdx);
		
		var hasL = toRemoveChild.hasLeft();
		var hasR = toRemoveChild.hasRight();
		
		if(hasL != hasR){
			var childChildIdx = toRemoveChild.getChild(hasR);
			if(parentPos == -1){
				popValue(childIdx, toRemoveChild);
				var cc = nodes.get(childChildIdx);
				nodes.set(0, cc);
				popNode(childChildIdx, cc);
			}else{
				parent.setChild(isRight, childChildIdx);
				popValue(childIdx, toRemoveChild);
			}
		}else{
			if(!hasR){
				if(parentPos == -1){
					nodes.clear();
					values.clear();
					return;
				}
				parent.yeetChild(isRight);
				popValue(childIdx, toRemoveChild);
			}else{
				var smallestBiggerParentIdx = childIdx;
				
				var smallestBiggerIdx = toRemoveChild.right();
				var smallestBigger    = nodes.get(smallestBiggerIdx);
				var lr                = true;
				
				while(smallestBigger.hasLeft()){
					smallestBiggerParentIdx = smallestBiggerIdx;
					
					smallestBiggerIdx = smallestBigger.left();
					smallestBigger = nodes.get(smallestBiggerIdx);
					lr = false;
				}
				
				swapValues(smallestBiggerIdx, childIdx);
				removeLR(smallestBiggerParentIdx, nodes.get(smallestBiggerParentIdx), lr);
				return;
			}
		}
		if(parentPos != -1){
			nodes.set(parentPos, parent);
		}
	}
	
	private final TreeSet<Long> blankValueIds = new TreeSet<>();
	private void popValue(long nodeIdx, NodePtr node) throws IOException{
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
	private void popNode(long nodeIdx, NodePtr node) throws IOException{
		if(nodeIdx + 1 == nodes.size()){
			nodes.remove(nodeIdx);
			blankNodeIds.remove(nodeIdx);
			while(nodes.popLastIf(v -> v.valueIndex() == -1).isPresent()){
				blankNodeIds.remove(nodes.size());
			}
		}else{
			node.valueIndex(-1);
			node.left(-1);
			node.right(-1);
			
			nodes.set(nodeIdx, node);
			if(nodeIdx>0) blankNodeIds.add(nodeIdx);
		}
	}
	
	private long addNode(T value) throws IOException{
		long valIdx  = findValueIdx();
		long nodeIdx = findNodeIdx();
		
		var node = NodePtr.of(valIdx);
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			if(nodeIdx == nodes.size()) nodes.add(node);
			else nodes.set(nodeIdx, node);
			
			if(valIdx == values.size()) values.add(new Val<>(value));
			else values.set(valIdx, new Val<>(value));
		}
		return nodeIdx;
	}
	
	private long findNodeIdx() throws IOException{
		if(!blankNodeIds.isEmpty()){
			var nodeIdx = blankNodeIds.ceiling(blankNodeIds.first());
			blankNodeIds.remove(nodeIdx);
			assert nodes.get(nodeIdx).valueIndex() == -1;
			return nodeIdx;
		}
		
		long nodesSiz = nodes.size();
		for(int i = 0; i<nodesSiz; i++){
			if(nodes.get(i).valueIndex() == -1){
				return i;
			}
		}
		return nodesSiz;
	}
	
	private long findValueIdx() throws IOException{
		if(!blankValueIds.isEmpty()){
			var valueIdx = blankValueIds.ceiling(blankValueIds.first());
			blankValueIds.remove(valueIdx);
			assert values.get(valueIdx).val == null;
			return valueIdx;
		}
		
		var valSiz = values.size();
		for(int i = 0; i<valSiz; i++){
			if(values.get(i).val == null){
				return i;
			}
		}
		return valSiz;
	}
	
	@Override
	public boolean add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		if(values.isEmpty()){
			addNode(value);
			deltaSize(1);
			return true;
		}
		var v = nodeIterAction(0, nodes.get(0), value, ADD) == INSERTED;
		if(v){
			deltaSize(1);
			if(DEBUG_VALIDATION) validate();
		}
		return v;
	}
	
	private String pad(String s, int len){
		if(s.length()>=len) return s;
		var toAdd = len - s.length();
		var d     = toAdd/2;
		return " ".repeat(d) + s + " ".repeat(toAdd - d);
	}
	
	private void validate() throws IOException{
		if(isEmpty()) return;
		var h = HashSet.<Long>newHashSet((int)nodes.size());
		h.add(0L);
		validate(nodes.get(0), h);
	}
	private void validate(NodePtr nodePtr, Set<Long> ids) throws IOException{
		var val = getVal(nodePtr);
		for(int i = 0; i<2; i++){
			var lr = i == 0;
			if(nodePtr.hasChild(lr)){
				var idx   = nodePtr.getChild(lr);
				var child = nodes.get(idx);
				if(!ids.add(idx)){
					throw new IllegalStateException("duplicate idx " + idx);
				}
				var cVal = getVal(child);
				var c    = val.compareTo(cVal);
				var c2   = lr? -1 : 1;
				if(c != c2){
					throw new IllegalStateException(val + (lr? " > " : " < ") + cVal);
				}
				validate(child, ids);
			}
		}
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		if(nodes.isEmpty()) return false;
		var v = nodeIterAction(0, nodes.get(0), value, REMOVE) == REMOVED;
		if(v){
			deltaSize(-1);
//			print();
			if(DEBUG_VALIDATION) validate();
		}
		return v;
	}
	
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		nodes.clear();
		values.clear();
		deltaSize(-size());
	}
	
	@Override
	public boolean contains(T value) throws IOException{
		if(nodes.isEmpty()) return false;
		
		var v = nodeIterAction(0, nodes.get(0), value, FIND) == CONTAINS;
		return v;
	}
	
	@Override
	public IOIterator<T> iterator(){
		return new IOIterator<>(){
			private long count;
			private List<NodePtr> stack;
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
				stack.add(nodes.get(0));
				doneRight.add(false);
				teleportLeft();
			}
			
			private void teleportLeft() throws IOException{
				NodePtr p;
				while((p = stack.get(stack.size() - 1)).hasLeft()){
					p = nodes.get(p.left());
					stack.add(p);
					doneRight.add(false);
				}
			}
			
			private NodePtr nextPtr() throws IOException{
				if(stack == null) init();
				if(stack.isEmpty()) throw new NoSuchElementException();
				while(true){
					var depth = stack.size() - 1;
					var ptr   = stack.get(depth);
					
					if(ptr.hasRight() && !doneRight.get(depth)){
						doneRight.set(depth, true);
						var right = nodes.get(ptr.right());
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
