package com.lapissea.cfs;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.IOMap;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;

public class StringTree extends IOInstance implements IOMap<Integer, String>, Iterable<String>{
	
	private static class LeafResolved{
		private int    index;
		private String value;
		
		public void set(int index, String value){
			this.index=index;
			this.value=value;
		}
		
		public int getIndex(){
			return index;
		}
		
		public String getValue(){
			return value;
		}
	}
	
	
	private static class Leaf extends IOInstance implements Comparable<Leaf>{
		private static final Leaf[] NO_LEAVES=new Leaf[0];
		
		@IOStruct.Value(index=0, rw=AutoText.StringIO.class)
		public String data;
		
		@IOStruct.ArrayValue(index=1, rw=IOInstance.AutoRW.class)
		private Leaf[] leavesArr;
		
		public List<Leaf> leaves;
		
		private int eIndex=-1;
		
		public Leaf(String data, int eIndex){
			this.data=data;
			this.eIndex=eIndex;
		}
		
		public Leaf(String data){
			this.data=data;
		}
		
		@IOStruct.Get
		private Leaf[] getLeaves(){
			return leaves==null?NO_LEAVES:leaves.toArray(Leaf[]::new);
		}
		
		@IOStruct.Set
		private void setLeaves(Leaf[] newLeaves){
			if(newLeaves.length==0) leaves=null;
			else{
				leaves=new ArrayList<>(newLeaves.length+1);
				leaves.addAll(Arrays.asList(newLeaves));
			}
		}
		
		private static int commonStart(String l, String r){
			
			int max=Math.min(l.length(), r.length());
			
			int i=0;
			for(;i<max;i++){
				if(l.charAt(i)!=r.charAt(i)) break;
			}
			return i;
		}
		
		private boolean midInsert(int index, String right, int elementIndex){
			String lData=data.substring(0, index);
			String rData=data.substring(index);
			
			if(!rData.isEmpty()){
				var rLeaf=new Leaf(rData);
				rLeaf.leaves=leaves;
				rLeaf.eIndex=this.eIndex;
				this.eIndex=-1;
				leaves=null;
				addLeaf(rLeaf);
			}
			if(!right.isEmpty()){
				addLeaf(new Leaf(right, elementIndex));
				elementIndex=eIndex;
			}
			if(!data.equals(lData)){
				data=lData;
				eIndex=elementIndex;
			}
			return true;
		}
		private boolean insertAt(int index, String right, int elementIndex){
			if(right.isEmpty()) return false;
			
			if(noLeaves()){
				if(index==0){
					if(data.isEmpty()){
						data=right;
						this.eIndex=elementIndex;
						return true;
					}
					
					addLeaf(new Leaf(data));
					addLeaf(new Leaf(right));
					data="";
					return true;
				}
				
				midInsert(index, right, elementIndex);
				return true;
			}
			
			if(index==data.length()){
				for(Leaf leaf : leaves){
					int start=commonStart(leaf.data, right);
					if(start==0) continue;
					String innerRight=right.substring(start);
					if(innerRight.isEmpty()) return leaf.midInsert(start, "", elementIndex);
					else return leaf.insertAt(start, innerRight, elementIndex);
				}
				addLeaf(new Leaf(right, elementIndex));
				return true;
			}
			
			return midInsert(index, right, elementIndex);
		}
		
		public boolean add(int elementIndex, String string){
			int start=commonStart(data, string);
			return insertAt(start, string.substring(start), elementIndex);
		}
		
		private boolean noLeaves(){
			return leaves==null||leaves.isEmpty();
		}
		
		private void addLeaf(Leaf leaf){
			if(leaves==null) leaves=new ArrayList<>(2);
			UtilL.addRemainSorted(leaves, leaf);
//			leaves.add(leaf);
		}
		
		
		public Iterator<String> iterator(String left){
			return new Iterator<>(){
				final Iterator<LeafResolved> iter=iterator(left, new LeafResolved());
				@Override
				public boolean hasNext(){
					return iter.hasNext();
				}
				@Override
				public String next(){
					return iter.next().getValue();
				}
			};
		}
		public Iterator<LeafResolved> iterator(String left, LeafResolved dest){
			return new Iterator<>(){
				int cursor=eIndex==-1?0:-1;
				Iterator<LeafResolved> inner;
				
				LeafResolved next;
				
				private LeafResolved getNext(){
					if(next==null) next=calcNext();
					return next;
				}
				
				private LeafResolved calcNext(){
					if(cursor==-1){
						cursor++;
						dest.set(eIndex, left+data);
						return dest;
					}
					
					if(inner!=null){
						if(inner.hasNext()){
							return inner.next();
						}else{
							inner=null;
						}
					}
					
					if(leaves==null||cursor>=leaves.size()) return null;
					
					Leaf leaf=leaves.get(cursor++);
					inner=leaf.iterator(left+data, dest);
					
					return calcNext();
				}
				
				@Override
				public boolean hasNext(){
					return getNext()!=null;
				}
				@Override
				public LeafResolved next(){
					LeafResolved val=getNext();
					next=null;
					return val;
				}
			};
		}
		@Override
		public String toString(){
			return data+(noLeaves()?"":leaves.stream().map(Object::toString).collect(Collectors.joining(", ", "(", ")")));
		}
		
		public String debStr(){
			StringBuilder sb=new StringBuilder();
			sb.append(data);
			if(!noLeaves()){
				String empty=" ".repeat(data.length());
				for(Leaf leaf : leaves){
					sb.append("\n");
					sb.append(Arrays.stream(leaf.debStr().split("\n"))
					                .map(s->empty+s)
					                .collect(Collectors.joining("\n")));
				}
			}
			return sb.toString();
		}
		@Override
		public int compareTo(Leaf o){
			return data.compareTo(o.data);
		}
		
		@Override
		public boolean equals(Object o){
			return this==o||
			       o instanceof Leaf leaf&&
			       eIndex==leaf.eIndex&&
			       Objects.equals(data, leaf.data)&&
			       Objects.equals(leaves, leaf.leaves);
		}
		@Override
		public int hashCode(){
			return Objects.hash(data, leaves, eIndex);
		}
	}
	
	@IOStruct.Value(index=0, rw=IOInstance.AutoRW.class)
	public Leaf root=new Leaf("");
	
	private int nextId;
	
	public void addValue(String value){
		putValue(nextId, value);
	}
	
	@Override
	public Iterator<String> iterator(){
		return root.iterator("");
	}
	
	@Override
	public String getValue(Integer key){
		return getValue(key.intValue());
	}
	public String getValue(int key){
		var iter=root.iterator("", new LeafResolved());
		while(iter.hasNext()){
			var next=iter.next();
			if(next.getIndex()==key) return next.getValue();
		}
		return null;
	}
	
	@Override
	public void putValue(Integer key, String value){
		putValue(key.intValue(), value);
	}
	
	public void putValue(int key, String value){
		Objects.requireNonNull(value);
		if(root.add(key, value)) nextId++;
		
		if(DEBUG_VALIDATION){
			int checkSize=0;
			for(String s : this){
				checkSize++;
			}
			if(nextId!=checkSize) throw new IllegalStateException(key+" "+value);
		}
	}
	
	@Override
	public void clear(){
		nextId=0;
		root=new Leaf("");
	}
}
