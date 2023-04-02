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
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotNull;
import guru.nidi.graphviz.attribute.Color;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Factory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static java.util.function.Predicate.not;

/**
 * A red black binary tree implementation as an on disk data structure with a
 * custom memory reference and allocation system to reference node relations.<br>
 * Big thanks to <a href="https://www.happycoders.eu/algorithms/red-black-tree-java/">
 * Red-Black Tree (Fully Explained, with Java Code) - happycoders.eu</a>
 * for providing a detailed and friendly explanation on the data structure
 * and the specifics of the balancing logic.
 *
 * @param <T>
 */
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
		default boolean black(){
			return !red();
		}
		
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
	
	private record IndexedNode(long idx, @NotNull Node node){
		private IndexedNode{
			Objects.requireNonNull(node);
		}
		@Override
		public String toString(){
			return node + " @" + idx;
		}
		boolean red()  { return node.red(); }
		boolean black(){ return node.black(); }
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
		assert nodeIdx != 0 || node.black() : "Root is red";
		nodeCache.put(nodeIdx, new NodeCache(node, (byte)3));
		nodes.set(nodeIdx, node);
		try{
			renderGraph();
		}catch(Throwable e){
			LogUtil.println("fail graph");
		}
	}
	
	private void swapValues(IndexedNode a, IndexedNode b) throws IOException{
		var aVal = a.node.valueIndex();
		a.node.valueIndex(b.node.valueIndex());
		b.node.valueIndex(aVal);
		updateNode(a);
		updateNode(b);
	}
	private void setAndUpdateColor(IndexedNode node, boolean red) throws IOException{
		var n = node.node;
		if(n.red() == red) return;
		n.red(red);
		updateNode(node);
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
			childChild.node.red(false);
			gcNodeValue(toRemove);
			updateNode(0, childChild.node.clone());
			gcNode(childChild);
			return;
		}
		
		ptr.setChild(childChild);
		gcNodeValue(toRemove);
		updateNode(ptr.index());
		
		if(childChild.black()){
			fixRedBlackPropertiesAfterDelete(childChild);
		}
	}
	private void fixRedBlackPropertiesAfterDelete(IndexedNode movedDown) throws IOException{
		var parent = findNodeParent(movedDown);
		if(parent == null) return;
		var sibling = parent.otherChild(this);
		if(sibling == null) return;
		
		if(sibling.red()){
			setAndUpdateColor(sibling, false);
			setAndUpdateColor(parent.index(), true);
			
			rotate(parent.index(), parent.relation);
			
			parent = findNodeParent(movedDown);
			Objects.requireNonNull(parent);
			sibling = parent.otherChild(this);
		}
		
		var l = sibling.node.left(this);
		var r = sibling.node.right(this);
		if(l != null && l.black() && r != null && r.black()){
			setAndUpdateColor(sibling, true);
			
			var parentIdx = parent.index();
			if(parentIdx.red()){
				setAndUpdateColor(parentIdx, false);
			}else{
				fixRedBlackPropertiesAfterDelete(parentIdx);
			}
		}
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
			repairRedBlackInsert(parent);
			deltaSize(1);
		}
		if(DEBUG_VALIDATION) validate();
		return true;
	}
	
	private void repairRedBlackInsert(NodePointer parent) throws IOException{
		if(parent.node.black()) return;
		
		NodePointer grandparent = findNodeParent(parent);
		if(grandparent == null){
			setAndUpdateColor(parent.index(), false);
			return;
		}
		
		var uncle = grandparent.otherChild(this);
		
		if(uncle != null && uncle.node.black()){
			setAndUpdateColor(parent.index(), false);
			if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
			setAndUpdateColor(uncle, false);
			repairRedBlackInsert(grandparent);
			return;
		}
		
		if(!grandparent.relation){
			if(parent.relation){
				setAndUpdateColor(parent.child(this), false);
				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
				var newP = rotateLeft(parent.index());
				rotateRight(findNodeParent(newP).index());
			}else{
				setAndUpdateColor(parent.index(), false);
				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
				rotateRight(grandparent.index());
			}
		}else{
			if(!parent.relation){
				var newParent = rotateRight(parent.index());
				rotateLeft(grandparent.index());
				setAndUpdateColor(newParent, false);
				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
			}else{
				LogUtil.println(parent.index());
				var newGp = rotateLeft(grandparent.index());
				var oldGP = newGp.node.getChild(false, this);
				
				LogUtil.println(newGp);
				setAndUpdateColor(newGp, false);
				setAndUpdateColor(oldGP, true);
			}

//			if(!parent.relation){
//				renderGraph();
//				setAndUpdateColor(parent.child(this), false);
//				renderGraph();
//				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
//				renderGraph();
//				var newP = rotateRight(parent.index());
//				renderGraph();
//				LogUtil.println(getVal(newP.node));
//				rotateLeft(findNodeParent(newP).index());
//				renderGraph();
//				int i = 0;
//			}else{
//				setAndUpdateColor(parent.index(), false);
//				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
//				rotateLeft(grandparent.index());
//			}
//			if(!parent.relation){
//				setAndUpdateColor(parent.child(this), false);
//				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
//				var newP = rotateRight(parent.index());
//				LogUtil.println(getVal(newP.node));
//				rotateLeft(findNodeParent(newP).index());
//			}else{
//				setAndUpdateColor(parent.index(), false);
//				if(grandparent.idx>0) setAndUpdateColor(grandparent.index(), true);
//				rotateLeft(grandparent.index());
//			}
		}
	}
	
	String nodeStr(Node node) throws IOException{
		return getVal(node) + " @ " + node.valueIndex();
	}
	
	void feedGraph(Node node, List<guru.nidi.graphviz.model.Node> graph) throws IOException{
		
		var n = Factory.node(nodeStr(node));
		if(node.red()) n = n.with(Color.RED);
		for(int i = 0; i<2; i++){
			var b = i == 0;
			if(!node.hasChild(b)) continue;
			var ch = nodes.get(node.getChild(b));
			n = n.link(Factory.node(nodeStr(ch)));
			feedGraph(ch, graph);
		}
		graph.add(n);
	}
	
	private void renderGraph(){
		try{
			List<guru.nidi.graphviz.model.Node> graph = new ArrayList<>();
			if(size()>0){
				feedGraph(node(0), graph);
				graph.add(Factory.node("<ROOT>").link(nodeStr(node(0))));
			}
			var f  = new File("deb.png");
			var f2 = new File("deb2.png");
			Graphviz.fromGraph(Factory.graph("tree").with(graph)).width(300).render(Format.PNG).toFile(f2);
			f.delete();
			f2.renameTo(f);
//			LogUtil.println(f.getAbsolutePath());
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	
	private IndexedNode rotate(IndexedNode iNode, boolean right) throws IOException{
		if(right) return rotateRight(iNode);
		else return rotateLeft(iNode);
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
			return rightChild;
		}else{
			rightChild.node.left(rightChild.idx);
			
			swapMemValues(node, rightChild.node);
			node.red(false);
			updateNode(rightChild);
			updateNode(iNode);
			return iNode;
		}
	}
	
	private static void swapMemValues(Node node, Node nodeB){
		var l   = node.left();
		var r   = node.right();
		var v   = node.valueIndex();
		var red = node.red();
		
		node.left(nodeB.left());
		node.right(nodeB.right());
		node.valueIndex(nodeB.valueIndex());
		node.red(nodeB.red());
		
		nodeB.left(l);
		nodeB.right(r);
		nodeB.valueIndex(v);
		nodeB.red(red);
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
			return leftChild;
		}else{
			leftChild.node.right(leftChild.idx);
			
			swapMemValues(node, leftChild.node);
			
			updateNode(leftChild);
			updateNode(iNode);
			return iNode;
		}
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
