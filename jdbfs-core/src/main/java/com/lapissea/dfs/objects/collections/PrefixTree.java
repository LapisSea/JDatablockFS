package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class PrefixTree extends UnmanagedIOSet<String>{
	
	@Def.Order({"value", "children", "real"})
	private interface Node extends Def<Node>{
		@IONullability(DEFAULT_IF_NULL)
		String value();
		void value(String value);
		
		@IONullability(DEFAULT_IF_NULL)
		int[] children();
		void children(int[] children);
		
		boolean real();
		void real(boolean real);
		
		MethodHandle CTOR = Def.dataConstructor(Node.class);
		static Node of(String value, int[] children, boolean real){
			try{ return (Node)CTOR.invoke(value, children, real); }catch(Throwable e){ throw new RuntimeException(e); }
		}
	}
	
	private static final TypeCheck TYPE_CHECK = new TypeCheck(PrefixTree.class);
	
	@IOValue
	@IONullability(NULLABLE)
	private ContiguousIOList<Node> nodes;
	@IOValue
	private boolean                hasNullVal;
	
	public PrefixTree(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef, TYPE_CHECK);
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		readManagedFields();
	}
	
	@Override
	public boolean add(String value) throws IOException{
		if(value == null){
			if(hasNullVal){
				return false;
			}
			hasNullVal = true;
			getDataProvider().getSource().openIOTransaction(() -> {
				deltaSize(1);
				writeManagedFields();
			});
			return true;
		}
		
		requireNodes();
		var root = nodes.get(0);
		var res  = insert(value, 0, root);
		if((res&IO_MASK) == IO_SAVE_NODE){
			nodes.set(0, root);
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
				
				var newRoot = Node.of(v1, new int[]{movedRoot.index, valueNode.index}, false);
				nodes.set(0, newRoot);
				deltaSize(1);
				yield true;
			}
			default -> {
				throw new IllegalStateException("Unexpected value: " + (res&ACTION_MASK));
			}
		};
	}
	
	private void requireNodes() throws IOException{
		allocateNulls();
		writeManagedFields();
		nodes.addNew();
	}
	
	private record nn(Node node, int index){ }
	
	private nn allocNode(String value, int[] children, boolean real) throws IOException{
		long li    = nodes.size();
		int  index = (int)li;
		if(index != li) throw new OutOfMemoryError("Too many nodes");
		
		var node = Node.of(value, children, real);
		
		nodes.add(node);
		return new nn(node, index);
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
			if(matchingChars>0 || (value.length() == 0 && node.children().length != 0)){
				var v1 = nodeVal.substring(0, matchingChars);
				var v2 = nodeVal.substring(matchingChars);
				var nn = allocNode(v2, node.children(), node.real());
				
				var valuePt2 = value.substring(pos + matchingChars);
				var nnCh     = allocNode(valuePt2, new int[0], true);
				
				node.children(new int[]{nn.index, nnCh.index});
				node.value(v1);
				node.real(false);
				return ACTION_NEW_VAL|IO_SAVE_NODE;
			}
			return ACTION_FAILED;
		}
		
		var newPos = pos + nodeVal.length();
		if(newPos == value.length()){
			return ACTION_VAL_EXISTS;
		}
		
		for(var childIndex : node.children()){
			var child = nodes.get(childIndex);
			var res   = insert(value, newPos, child);
			if((res&IO_MASK) == IO_SAVE_NODE){
				nodes.set(childIndex, child);
			}
			switch(res&ACTION_MASK){
				case ACTION_NEW_VAL -> { return ACTION_NEW_VAL; }
				case ACTION_VAL_EXISTS -> { return ACTION_VAL_EXISTS; }
				case ACTION_FAILED -> { continue; }
				default -> throw new NotImplementedException((res&ACTION_MASK) + "");
			}
		}
		
		if(!node.real()){
			var valuePart = value.substring(newPos);
			if(nodeVal.isEmpty()){
				node.value(valuePart);
				node.real(true);
				return ACTION_NEW_VAL|IO_SAVE_NODE;
			}
			var nn = allocNode(valuePart, new int[0], true);
			node.children(addChild(node.children(), nn.index));
			return ACTION_NEW_VAL|IO_SAVE_NODE;
		}
		
		throw new NotImplementedException();
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
	
	@Override
	public boolean remove(String value) throws IOException{
		if(value == null){
			if(!hasNullVal) return false;
			hasNullVal = false;
			deltaSizeDirty(-1);
			writeManagedFields();
			return true;
		}
		
		if(nodes == null) return false;
		var root = nodes.get(0);
		int res  = remove(value, 0, root);
		
		if((res&IO_MASK) == IO_SAVE_NODE){
			nodes.set(0, root);
		}
		
		return switch(res&ACTION_MASK){
			case ACTION_FAILED -> false;
			case ACTION_VAL_REMOVED -> {
				deltaSize(-1);
				yield true;
			}
			case ACTION_POP_ME -> {
				nodes.set(0, Node.of("", new int[0], false));
				if(root.real()){
					deltaSize(-1);
				}
				yield true;
			}
			default -> throw new IllegalStateException("Illegal action: " + (res&ACTION_MASK));
		};
	}
	
	private int remove(String value, int pos, Node node) throws IOException{
		var val = node.value();
		if(!value.regionMatches(pos, val, 0, val.length())){
			return ACTION_FAILED;
		}
		
		var newPos = pos + val.length();
		if(node.real() && value.length() == newPos){
			return ACTION_POP_ME;
		}
		
		var children = node.children();
		for(int i = 0; i<children.length; i++){
			var childIndex = children[i];
			var child      = nodes.get(childIndex);
			var res        = remove(value, newPos, child);
			
			if((res&IO_MASK) == IO_SAVE_NODE){
				nodes.set(childIndex, child);
			}
			
			switch(res&ACTION_MASK){
				case ACTION_FAILED -> { }
				case ACTION_VAL_REMOVED -> { return ACTION_VAL_REMOVED; }
				case ACTION_POP_ME -> {
					node.children(removeChild(children, i));
					nodes.remove(childIndex);
					return IO_SAVE_NODE|ACTION_VAL_REMOVED;
				}
				default -> throw new IllegalStateException("Illegal action: " + (res&ACTION_MASK));
			}
		}
		
		return ACTION_FAILED;
	}
	
	private int[] removeChild(int[] children, int childIndex){
		int[] res = new int[children.length - 1];
		System.arraycopy(children, 0, res, 0, childIndex);
		System.arraycopy(children, childIndex + 1, res, childIndex, children.length - childIndex - 1);
		return res;
	}
	
	@Override
	public void clear() throws IOException{
		var oldNodes = nodes;
		nodes = null;
		hasNullVal = false;
		setSizeDirty(0);
		writeManagedFields();
		oldNodes.free();
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
			
			boolean             didNull;
			ArrayList<NodeWalk> nodeIters;
			String              next;
			boolean             hasNext;
			
			private void computeNext() throws IOException{
				if(!didNull){
					didNull = true;
					if(hasNullVal){
						setNext(null);
						return;
					}
				}
				if(nodeIters == null){
					if(nodes == null) return;
					nodeIters = new ArrayList<>(8);
					var root = nodes.get(0);
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
						var child      = nodes.get(childIndex);
						
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
}
