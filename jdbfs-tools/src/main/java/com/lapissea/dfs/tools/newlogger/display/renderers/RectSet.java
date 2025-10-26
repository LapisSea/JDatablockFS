package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawUtils.Rect;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.PPCollection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

public class RectSet{
	
	private static final int THRESHOLD = 16;
	
	private record Node(Rect rect, List<Node> fakes, List<Rect> leafs){
		@Override
		public String toString(){
			return "Node";
		}
		
		private record Split(List<Rect> l1, List<Rect> l2){ }
		private Split doSplit(Collection<Rect> elements){
			return doSortSplit(elements);
		}
		private Split doSortSplit(Collection<Rect> elements){
			var totalArea = union(elements);
			
			var all = elements instanceof List<Rect> r? r : new ArrayList<>(elements);
			
			if(totalArea.width()>totalArea.height()){
				all.sort(Comparator.comparingDouble(r -> r.x() + r.width()/2));
			}else{
				all.sort(Comparator.comparingDouble(r -> r.y() + r.height()/2));
			}
			int mid = elements.size()/2;
			var l1  = new ArrayList<>(all.subList(0, mid));
			var l2  = new ArrayList<>(all.subList(mid, elements.size()));
			return new Split(l1, l2);
		}
		
		private static Rect union(Iterable<Rect> nodes){
			float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			for(Rect r : nodes){
				minX = Math.min(minX, r.x());
				minY = Math.min(minY, r.y());
				maxX = Math.max(maxX, r.xTo());
				maxY = Math.max(maxY, r.yTo());
			}
			return new Rect(minX, minY, maxX - minX, maxY - minY);
		}
		
		public boolean overlaps(Rect test){
			if(rect != null && !rect.overlaps(test)){
				return false;
			}
			if(fakes.size()>=THRESHOLD){
				reflow();
			}
			for(Node fake : fakes){
				if(fake.overlaps(test)){
					return true;
				}
			}
			
			if(leafs.size()>=THRESHOLD){
				process();
				for(Node fake : fakes){
					if(fake.overlaps(test)){
						return true;
					}
				}
			}
			
			for(var leaf : leafs){
				if(leaf.overlaps(test)){
					return true;
				}
			}
			
			return false;
		}
		
		private void reflow(){
			leafy:
			{
				List<List<Rect>> leafsSet = new ArrayList<>(fakes.size());
				int              count    = 0;
				for(Node fake : fakes){
					if(!fake.leafs.isEmpty()){
						leafsSet.add(fake.leafs);
						count += fake.leafs.size();
					}
				}
				if(count == 0) break leafy;
				//noinspection unchecked
				var flat = new PPCollection<Rect>(Iters.concat(leafsSet.toArray(List[]::new)), OptionalInt.of(count));
				if(count<THRESHOLD){
					fakes.add(toFake(flat.toModList()));
				}else{
					var split = doSplit(flat);
					fakes.add(toFake(split.l1));
					fakes.add(toFake(split.l2));
				}
				for(Node fake : fakes) fake.leafs.clear();
			}
			Map<Rect, Node> lookup = HashMap.newHashMap(fakes.size());
			for(Node fake : fakes){
				lookup.put(fake.rect, fake);
			}
			var split = doSplit(lookup.keySet());
			var l1    = new ArrayList<Node>(split.l1.size());
			var l2    = new ArrayList<Node>(split.l2.size());
			var u1    = union(split.l1);
			for(Rect r : split.l1){
				l1.add(Objects.requireNonNull(lookup.get(r)));
			}
			var u2 = union(split.l2);
			for(Rect r : split.l2){
				l2.add(Objects.requireNonNull(lookup.get(r)));
			}
			fakes.clear();
			fakes.add(new Node(u1, l1, new ArrayList<>()));
			fakes.add(new Node(u2, l2, new ArrayList<>()));
		}
		
		private void process(){
			var split   = doSplit(leafs);
			var f1      = toFake(split.l1);
			var f2      = toFake(split.l2);
			var newFake = new Node(f1.rect.union(f2.rect), new ArrayList<>(), new ArrayList<>());
			newFake.fakes.add(f1);
			newFake.fakes.add(f2);
			fakes.add(newFake);
			leafs.clear();
		}
		private Node toFake(List<Rect> l){
			return new Node(union(l), new ArrayList<>(), new ArrayList<>(l));
		}
		
		boolean add(Rect add){
			if(rect != null && !add.isWithin(rect)){
				return false;
			}
			for(Node fake : fakes){
				if(add.isWithin(fake.rect)){
					if(fake.add(add)){
						return true;
					}
				}
			}
			leafs.add(add);
			return true;
		}
		
		void all(Consumer<Rect> res){
//			if(leafs.size()>THRESHOLD){
//				process();
//			}
			if(rect != null) res.accept(rect);
			for(Rect leaf : leafs){
				res.accept(leaf);
			}
			for(Node child : fakes){
				child.all(res);
			}
		}
	}
	
	private final Node root = new Node(null, new ArrayList<>(), new ArrayList<>());
	
	public void add(Rect rect){
		root.add(rect);
	}
	public boolean overlaps(Rect rect){
		return root.overlaps(rect);
	}
	
	public IterablePP<Rect> all(){
		List<Rect> res = new ArrayList<>();
		root.all(res::add);
		return Iters.from(res);
	}
}
