package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

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

//		static Node of(String value, int[] children){ return Def.of(Node.class, value, children); }
	}
	
	private static final TypeCheck TYPE_CHECK = new TypeCheck(PrefixTree.class);
	
	@IOValue
	private ContiguousIOList<Node> nodes;
	@IOValue
	private boolean                hasNullVal;
	
	public PrefixTree(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef, TYPE_CHECK);
		if(isSelfDataEmpty()){
			getDataProvider().getSource().openIOTransaction(() -> {
				allocateNulls();
				writeManagedFields();
				nodes.addNew();
			});
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
		
		var root = nodes.get(0);
		var res  = insert(value, 0, root);
		if((res&INSERT_IO_MASK) == INSERT_SAVE_NODE){
			nodes.set(0, root);
		}
		LogUtil.println(this);
		return switch(res&INSERT_MASK){
			case INSERT_NEW -> {
				deltaSize(1);
				yield true;
			}
			case INSERT_EXISTS -> false;
			case INSERT_NO -> {
				throw new ShouldNeverHappenError("Refused??");
			}
			default -> {
				throw new IllegalStateException("Unexpected value: " + (res&INSERT_MASK));
			}
		};
	}
	private record nn(Node node, int index){ }
	
	private nn allocNode(String value, int[] children, boolean real) throws IOException{
		long li    = nodes.size();
		int  index = (int)li;
		if(index != li) throw new OutOfMemoryError("Too many nodes");
		
		var node = IOInstance.Def.of(Node.class);
		node.value(value);
		node.children(children);
		node.real(real);
		
		nodes.add(node);
		return new nn(node, index);
	}
	
	private static final int
		INSERT_NEW    = 0b000,
		INSERT_EXISTS = 0b001,
		INSERT_NO     = 0b010,
		INSERT_MASK   = 0b011;
	private static final int
		INSERT_SAVE_NODE = 0b100,
		INSERT_IO_MASK   = 0b100;
	
	
	private int insert(String value, int pos, Node node) throws IOException{
		var nodeVal = node.value();
		
		if(!value.regionMatches(pos, nodeVal, 0, nodeVal.length())){
			int matchingChars = 0;
			while(nodeVal.length()>matchingChars && value.length()>pos + matchingChars &&
			      nodeVal.charAt(matchingChars) == value.charAt(pos + matchingChars)){
				matchingChars++;
			}
			if(matchingChars>0 || value.length() == 0){
				var v1 = nodeVal.substring(0, matchingChars);
				var v2 = nodeVal.substring(matchingChars);
				var nn = allocNode(v2, node.children(), node.real());
				
				var valuePt2 = value.substring(pos + matchingChars);
				var nnCh     = allocNode(valuePt2, new int[0], true);
				
				node.children(new int[]{nn.index, nnCh.index});
				node.value(v1);
				node.real(false);
				return INSERT_NEW|INSERT_SAVE_NODE;
			}
			return INSERT_NO;
		}
		
		var newPos = pos + nodeVal.length();
		if(newPos == value.length()){
			return INSERT_EXISTS;
		}
		
		for(var childIndex : node.children()){
			var child = nodes.get(childIndex);
			var res   = insert(value, newPos, child);
			if((res&INSERT_IO_MASK) == INSERT_SAVE_NODE){
				nodes.set(childIndex, child);
			}
			switch(res&INSERT_MASK){
				case INSERT_NEW -> { return INSERT_NEW; }
				case INSERT_EXISTS -> { return INSERT_EXISTS; }
				case INSERT_NO -> { }
				default -> throw new NotImplementedException((res&INSERT_MASK) + "");
			}
		}
		
		if(!node.real()){
			var valuePart = value.substring(newPos);
			if(nodeVal.isEmpty()){
				node.value(valuePart);
				node.real(true);
				return INSERT_NEW|INSERT_SAVE_NODE;
			}
			var nn = allocNode(valuePart, new int[0], true);
			node.children(addChild(node.children(), nn.index));
			return INSERT_NEW|INSERT_SAVE_NODE;
		}
		
		throw new NotImplementedException();
	}
	private int[] addChild(int[] children, int newChild){
		var res = Arrays.copyOf(children, children.length + 1);
		res[children.length] = newChild;
		return res;
	}
	
	@Override
	public boolean remove(String value) throws IOException{
		throw NotImplementedException.infer();//TODO: implement PrefixTree.remove()
	}
	@Override
	public void clear() throws IOException{
		throw NotImplementedException.infer();//TODO: implement PrefixTree.clear()
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
	public void requestCapacity(long capacity) throws IOException{
		throw NotImplementedException.infer();//TODO: implement PrefixTree.requestCapacity()
	}
}
