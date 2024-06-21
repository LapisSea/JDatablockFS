package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class PrefixTree extends UnmanagedIOSet<String>{
	
	@Def.Order({"value", "children", "real", "nullMark"})
	private interface Node extends Def<Node>{
		@IONullability(DEFAULT_IF_NULL)
		String value();
		void value(String value);
		
		@IONullability(DEFAULT_IF_NULL)
		int[] children();
		void children(int[] children);
		
		boolean real();
		void real(boolean real);
		
		boolean nullMark();
		
		default boolean isParent(){
			return children().length>0;
		}
		default boolean isSingleParent(){
			return children().length == 1;
		}
		
		MethodHandle CTOR = Def.dataConstructor(Node.class);
		static Node of(String value, int[] children, boolean real){
			return of(value, children, real, false);
		}
		static Node of(String value, int[] children, boolean real, boolean isNull){
			try{ return (Node)CTOR.invoke(value, children, real, isNull); }catch(Throwable e){ throw new RuntimeException(e); }
		}
	}
	
	private static final TypeCheck TYPE_CHECK = new TypeCheck(PrefixTree.class);
	
	@IOValue
	@IONullability(NULLABLE)
	private ContiguousIOList<Node> nodes;
	@IOValue
	private boolean hasNullVal, hasEmptyVal;
	
	public PrefixTree(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef, TYPE_CHECK);
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		readManagedFields();
	}
	
	private Node getNode(long index) throws IOException{
		return nodes.get(index).clone();
	}
	private void writeNode(long index, Node node) throws IOException{
		nodes.set(index, node.clone());
	}
	
	private BitSet nullCache;
	
	private void deleteNode(int childIndex) throws IOException{
		writeNode(childIndex, Node.of("", new int[0], false, true));
		nullCache().set(childIndex);
		while(getNode(nodes.size() - 1).nullMark()){
			popNode();
		}
	}
	private BitSet nullCache(){
		var v = nullCache;
		if(v == null){
			v = nullCache = new BitSet();
			if(nodes != null){
				for(var n : nodes.enumerate().filtered(l -> l.val().nullMark())){
					v.set(n.index());
				}
			}
		}
		return v;
	}
	
	private void popNode() throws IOException{
		var pos = nodes.size() - 1;
		nodes.remove(pos);
		nullCache().clear((int)pos);
	}
	
	private int allocNode(String value, int[] children, boolean real) throws IOException{
		long li    = nodes.size();
		int  index = (int)li;
		if(index != li) throw new OutOfMemoryError("Too many nodes");
		
		var node = Node.of(value, children, real);
		var nv   = nodes.enumerateL().firstMatching(l -> l.val().nullMark()).mapToLong(IterablePP.LdxValue::index);
		if(nv.isPresent()){
			var i = nv.getAsLong();
			writeNode(i, node);
			return Math.toIntExact(i);
		}else{
			nodes.add(node);
		}
		return index;
	}
	
	@Override
	public boolean add(String value) throws IOException{
		if(value == null){
			if(hasNullVal){
				return false;
			}
			hasNullVal = true;
			deltaSizeDirty(1);
			writeManagedFields();
			return true;
		}else if(value.isEmpty()){
			if(hasEmptyVal){
				return false;
			}
			hasEmptyVal = true;
			deltaSizeDirty(1);
			writeManagedFields();
			return true;
		}
		
		requireNodes();
		
		try(var ignore = getDataProvider().getSource().openIOTransaction()){
			var root = getNode(0);
			var res  = insert(value, 0, root);
			if((res&IO_MASK) == IO_SAVE_NODE){
				writeNode(0, root);
			}
			
			return switch(res&ACTION_MASK){
				case ACTION_NEW_VAL -> {
					deltaSize(1);
					yield true;
				}
				case ACTION_VAL_EXISTS -> false;
				case ACTION_FAILED -> {
					
					//need to uproot root
					var nodeVal       = root.value();
					int matchingChars = countMatchingChars(value, 0, nodeVal);
					var v1            = nodeVal.substring(0, matchingChars);
					var v2            = nodeVal.substring(matchingChars);
					var movedRoot     = allocNode(v2, root.children(), root.real());
					
					var valuePt2  = value.substring(matchingChars);
					var valueNode = allocNode(valuePt2, new int[0], true);
					
					var newRoot = Node.of(v1, new int[]{movedRoot, valueNode}, false);
					writeNode(0, newRoot);
					deltaSize(1);
					yield true;
				}
				default -> {
					throw new IllegalStateException("Unexpected value: " + (res&ACTION_MASK));
				}
			};
		}finally{
			validate();
		}
	}
	
	private void requireNodes() throws IOException{
		if(nodes != null) return;
		allocateNulls();
		writeManagedFields();
		nodes.addNew();
	}
	
	private static final int
		ACTION_NEW_VAL     = 0b0001,
		ACTION_VAL_EXISTS  = 0b0010,
		ACTION_FAILED      = 0b0011,
		ACTION_VAL_REMOVED = 0b0100,
		ACTION_POP_ME      = 0b0101,
		ACTION_MASK        = 0b0111,
		IO_SAVE_NODE       = 0b1000,
		IO_MASK            = 0b1000;
	
	
	private int insert(String value, int pos, Node node) throws IOException{
		var nodeVal = node.value();
		
		if(!value.regionMatches(pos, nodeVal, 0, nodeVal.length())){
			int matchingChars = countMatchingChars(value, pos, nodeVal);
			if(matchingChars>0){
				var v1 = nodeVal.substring(0, matchingChars);
				var v2 = nodeVal.substring(matchingChars);
				var nn = allocNode(v2, node.children(), node.real());
				
				var valuePt2 = value.substring(pos + matchingChars);
				if(valuePt2.isEmpty()){
					node.children(new int[]{nn});
					node.value(v1);
					node.real(true);
					return ACTION_NEW_VAL|IO_SAVE_NODE;
				}
				
				var nnCh = allocNode(valuePt2, new int[0], true);
				
				node.children(new int[]{nn, nnCh});
				node.value(v1);
				node.real(false);
				return ACTION_NEW_VAL|IO_SAVE_NODE;
			}
			return ACTION_FAILED;
		}
		
		var newPos = pos + nodeVal.length();
		if(newPos>value.length()){
			return ACTION_FAILED;
		}
		if(newPos == value.length()){
			if(node.real()){
				return ACTION_VAL_EXISTS;
			}
			node.real(true);
			return ACTION_NEW_VAL|IO_SAVE_NODE;
		}
		
		int[] children = node.children();
		for(int i = 0; i<children.length; i++){
			var childIndex = children[i];
			var child      = getNode(childIndex);
			var res        = insert(value, newPos, child);
			
			if((res&IO_MASK) == IO_SAVE_NODE){
				writeNode(childIndex, child);
			}
			switch(res&ACTION_MASK){
				case ACTION_NEW_VAL -> {
					
					if((res&IO_MASK) == IO_SAVE_NODE){
						if(!(node.real() && child.real()) && child.value().isEmpty()){
							deleteNode(childIndex);
							node.real(node.real() || child.real());
							node.children(join(removeChild(node.children(), i), child.children()));
							return ACTION_NEW_VAL|IO_SAVE_NODE;
						}
						for(int child2Id : removeChild(node.children(), i)){
							var child2 = getNode(child2Id);
							if(!child2.value().equals(child.value())) continue;
							deleteNode(childIndex);
							child2.real(child2.real() || child.real());
							child2.children(join(child2.children(), child.children()));
							writeNode(child2Id, child2);
						}
					}
					
					return ACTION_NEW_VAL;
				}
				case ACTION_VAL_EXISTS -> { return ACTION_VAL_EXISTS; }
				case ACTION_FAILED -> { continue; }
				default -> throw new NotImplementedException((res&ACTION_MASK) + "");
			}
		}
		
		var valuePart = value.substring(newPos);
		if(!node.real() && !node.isParent()){
			node.value(valuePart);
			node.real(true);
			return ACTION_NEW_VAL|IO_SAVE_NODE;
		}
		var nn = allocNode(valuePart, new int[0], true);
		node.children(addChild(node.children(), nn));
		return ACTION_NEW_VAL|IO_SAVE_NODE;
	}
	
	private static int countMatchingChars(String value, int pos, String nodeVal){
		int count = 0;
		while(nodeVal.length()>count && value.length()>pos + count &&
		      nodeVal.charAt(count) == value.charAt(pos + count)){
			count++;
		}
		return count;
	}
	private int[] addChild(int[] children, int newChild){
		var res = Arrays.copyOf(children, children.length + 1);
		res[children.length] = newChild;
		return res;
	}
	public static int[] join(int[] a, int[] b){
		int[] result = new int[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}
	
	@Override
	public boolean remove(String value) throws IOException{
		if(value == null){
			if(!hasNullVal) return false;
			hasNullVal = false;
			deltaSizeDirty(-1);
			writeManagedFields();
			return true;
		}else if(value.isEmpty()){
			if(!hasEmptyVal) return false;
			hasEmptyVal = false;
			deltaSizeDirty(-1);
			writeManagedFields();
			return true;
		}
		
		if(nodes == null) return false;
		
		try(var ignore = getDataProvider().getSource().openIOTransaction()){
			var root = getNode(0);
			int res  = remove(value, 0, root);
			
			if((res&IO_MASK) == IO_SAVE_NODE){
				while(!root.real() && root.children().length == 1){
					var ch = getNode(root.children()[0]);
					deleteNode(root.children()[0]);
					root.value(root.value() + ch.value());
					root.children(ch.children());
					root.real(ch.real());
				}
				writeNode(0, root);
			}
			
			return switch(res&ACTION_MASK){
				case ACTION_FAILED -> false;
				case ACTION_VAL_REMOVED -> {
					deltaSize(-1);
					yield true;
				}
				case ACTION_POP_ME -> {
					writeNode(0, Node.of("", new int[0], false));
					if(root.real()){
						deltaSize(-1);
					}
					yield true;
				}
				default -> throw new IllegalStateException("Illegal action: " + (res&ACTION_MASK));
			};
		}finally{
			validate();
		}
	}
	
	private int remove(String value, int pos, Node node) throws IOException{
		var val = node.value();
		if(!value.regionMatches(pos, val, 0, val.length())){
			return ACTION_FAILED;
		}
		
		var newPos = pos + val.length();
		if(newPos>value.length()) return ACTION_FAILED;
		
		if(node.real() && value.length() == newPos){
			if(node.isParent()){
				if(node.isSingleParent()){
					mergeSingleChildInToEmpty(node);
				}else{
					node.real(false);
				}
				return ACTION_VAL_REMOVED|IO_SAVE_NODE;
			}
			return ACTION_POP_ME;
		}
		
		for(int i = 0; i<node.children().length; i++){
			var childIndex = node.children()[i];
			var child      = getNode(childIndex);
			var res        = remove(value, newPos, child);
			
			if((res&IO_MASK) == IO_SAVE_NODE){
				writeNode(childIndex, child);
			}
			
			switch(res&ACTION_MASK){
				case ACTION_FAILED -> { }
				case ACTION_VAL_REMOVED -> {
					if(!node.real() && node.isSingleParent()){
						mergeSingleChildInToEmpty(node);
						return IO_SAVE_NODE|ACTION_VAL_REMOVED;
					}
					return ACTION_VAL_REMOVED;
				}
				case ACTION_POP_ME -> {
					deleteNode(childIndex);
					node.children(removeChild(node.children(), i));
					if(!node.real()){
						if(!node.isParent()){
							return ACTION_POP_ME;
						}
						if(node.isSingleParent()){
							mergeSingleChildInToEmpty(node);
							return IO_SAVE_NODE|ACTION_VAL_REMOVED;
						}
					}
					return IO_SAVE_NODE|ACTION_VAL_REMOVED;
				}
				default -> throw new IllegalStateException("Illegal action: " + (res&ACTION_MASK));
			}
		}
		
		return ACTION_FAILED;
	}
	private void mergeSingleChildInToEmpty(Node node) throws IOException{
		var chId = node.children()[0];
		var ch   = getNode(chId);
		deleteNode(chId);
		node.value(node.value() + ch.value());
		node.children(ch.children());
		node.real(ch.real());
	}
	
	private static int[] removeChild(int[] children, int childIndex){
		int[] res = new int[children.length - 1];
		System.arraycopy(children, 0, res, 0, childIndex);
		System.arraycopy(children, childIndex + 1, res, childIndex, children.length - childIndex - 1);
		return res;
	}
	
	@Override
	public void clear() throws IOException{
		var oldNodes = nodes;
		nodes = null;
		hasEmptyVal = hasNullVal = false;
		setSizeDirty(0);
		writeManagedFields();
		if(oldNodes != null) oldNodes.free();
	}
	
	@Override
	public IOIterator<String> iterator(){
		return new IOIterator<>(){
			private static class NodeWalk{
				final String wholeVal;
				final Node   node;
				int pos;
				NodeWalk(String wholeVal, Node node){
					this.wholeVal = wholeVal;
					this.node = node;
					pos = node.real()? -1 : 0;
				}
			}
			
			boolean didNull, didEmpty;
			ArrayList<NodeWalk> nodeIters;
			String              next;
			boolean             hasNext;
			
			private void computeNext() throws IOException{
				if(!didNull){
					if(!didEmpty){
						didEmpty = true;
						if(hasEmptyVal){
							setNext("");
							return;
						}
					}
					didNull = true;
					if(hasNullVal){
						setNext(null);
						return;
					}
				}
				if(nodeIters == null){
					if(nodes == null) return;
					nodeIters = new ArrayList<>(8);
					var root = getNode(0);
					nodeIters.add(new NodeWalk(root.value(), root));
				}
				while(!nodeIters.isEmpty()){
					var walk     = nodeIters.getLast();
					var children = walk.node.children();
					var pos      = walk.pos;
					if(pos == children.length){
						nodeIters.removeLast();
						continue;
					}
					try{
						if(pos == -1){
							setNext(walk.wholeVal);
							return;
						}
						var childIndex = children[walk.pos];
						var child = getNode(childIndex);
						
						nodeIters.add(new NodeWalk(walk.wholeVal + child.value(), child));
					}finally{
						walk.pos++;
					}
				}
				
			}
			private void setNext(String value){
				assert !hasNext;
				next = value;
				hasNext = true;
			}
			
			
			@Override
			public boolean hasNext() throws IOException{
				if(!hasNext) computeNext();
				return hasNext;
			}
			@Override
			public String ioNext() throws IOException{
				if(!hasNext) computeNext();
				if(!hasNext) throw new NoSuchElementException();
				hasNext = false;
				return next;
			}
		};
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{ }
	
	public String toVis() throws IOException{
		if(nodes == null) return "";
		return toVis(getNode(0));
	}
	private String toVis(Node node) throws IOException{
		var bb  = " ".repeat(node.value().length()) + "|";
		var res = new StringBuilder(node.value().isEmpty()? "_" : node.value());
		if(node.isParent() && !node.value().isEmpty()) res.append('~');
		res.append("\n");
		for(int c : node.children()){
			var ch = getNode(c);
			for(String re : toVis(ch).split("\n")){
				if(re.isEmpty()) continue;
				res.append(bb).append(re).append('\n');
			}
		}
		return res.toString();
	}
	
	private void validate() throws IOException{
		if(DEBUG_VALIDATION && nodes == null) return;
		try{
			validate(getNode(0), getNode(0).value());
		}catch(Throwable e){
			e.addSuppressed(new RuntimeException("Vis:\n" + toVis()));
			throw e;
		}
	}
	private void validate(Node node, String path) throws IOException{
		var chs = chs(node);
		if(node.children().length == 1 && !node.real()){
			throw new IllegalStateException("Not real parent with 1 child: " + path);
		}
		var values = HashSet.<String>newHashSet(chs.size());
		for(Node ch : chs){
			if(node.nullMark()){
				throw new IllegalStateException("null child: " + path);
			}
			if(ch.value().isEmpty()){
				throw new IllegalStateException("Empty child: " + path);
			}
			if(!ch.real() && !ch.isParent()){
				throw new IllegalStateException("Dangling child: " + path + " -> " + ch.value());
			}
			if(!values.add(ch.value())){
				throw new IllegalStateException("Multiple children with name: \"" + ch.value() + "\" " + path);
			}
		}
		for(Node ch : chs){
			validate(ch, path + " -> " + ch.value());
		}
	}
	
	private List<Node> chs(Node node) throws IOException{
		var res = new ArrayList<Node>();
		for(int child : node.children()){
			res.add(getNode(child));
		}
		return res;
	}
}
