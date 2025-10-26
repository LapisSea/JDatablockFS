package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.DrawUtils.Rect;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class RectSet{
	
	private static final int THRESHOLD = 16;
	
	private record Node(Rect rect, ArrayList<Node> fakes, ArrayList<Rect> leafs){
		@Override
		public String toString(){
			return "Node";
		}
		
		private static final Comparator<Rect> X_COMPARE = Comparator.comparingDouble(r -> r.x() + r.width()/2);
		private static final Comparator<Rect> Y_COMPARE = Comparator.comparingDouble(r -> r.y() + r.height()/2);
		
		private record Split(List<Rect> l1, List<Rect> l2){ }
		private Split doSplit(Collection<Rect> elements){
			var totalArea = union(elements);
			
			var all = elements instanceof ArrayList<Rect> r? r : new ArrayList<>(elements);
			all.sort(totalArea.width()>totalArea.height()? X_COMPARE : Y_COMPARE);
			
			int mid = elements.size()/2;
			return new Split(
				all.subList(0, mid),
				all.subList(mid, elements.size())
			);
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
			return DrawUtils.Rect.ofFromTo(minX, minY, maxX, maxY);
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
				var newFake = process();
				if(newFake.overlaps(test)){
					return true;
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
				var flat = new ArrayList<Rect>(count);
				for(var r : leafsSet) flat.addAll(r);
				if(count<THRESHOLD){
					fakes.add(new Node(union(flat), new ArrayList<>(), flat));
				}else{
					var split = doSplit(flat);
					fakes.add(toFake(split.l1));
					fakes.add(toFake(split.l2));
				}
				for(Node fake : fakes){
					fake.leafs.clear();
				}
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
		
		private Node process(){
			var  split = doSplit(leafs);
			Node f1    = toFake(split.l1);
			Node f2    = toFake(split.l2);
			var  res   = new Node(f1.rect.union(f2.rect), new ArrayList<>(List.of(f1, f2)), new ArrayList<>());
			fakes.add(res);
			leafs.clear();
			return res;
		}
		private Node toFake(List<Rect> l){
			return new Node(union(l), new ArrayList<>(), new ArrayList<>(l));
		}
		
		boolean add(Rect add){
			if(rect != null && !add.isWithin(rect)){
				return false;
			}
			for(Node fake : fakes){
				if(fake.add(add)){
					return true;
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
