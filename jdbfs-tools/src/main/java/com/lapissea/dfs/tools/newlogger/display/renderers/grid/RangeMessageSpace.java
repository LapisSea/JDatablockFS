package com.lapissea.dfs.tools.newlogger.display.renderers.grid;

import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RangeMessageSpace{
	
	public sealed interface HoverEffect{
		record None() implements HoverEffect{ }
		
		None NONE = new None();
		
		record Outline(Color color, float lineWidth) implements HoverEffect{ }
		
	}
	
	private record Node(Range range, List<Node> children, Message val) implements Comparable<Node>{
		private Node(Message val)             { this(val.range, new ArrayList<>(), val); }
		private Node(Range range, Message val){ this(range, new ArrayList<>(), val); }
		@Override
		public int compareTo(Node o){ return Long.compare(range.from(), o.range().from()); }
		
		private void all(List<Message> dst){
			if(val != null) dst.add(val);
			for(Node child : children){
				child.all(dst);
			}
		}
		
		private boolean add(Node node){
			if(!node.range.isWithin(range)){
				return false;
			}
			
			relax();
			for(var branch : children){
				if(branch.add(node)){
					return true;
				}
			}
			children.removeIf(node::add);
			UtilL.addRemainSorted(children, node);
			return true;
		}
		
		private void relax(){
			if(children.size()>=6){
				doRelax();
			}
		}
		private void doRelax(){
			var ch = new ArrayList<Message>();
			for(Node child : children){
				child.all(ch);
			}
			Collections.sort(ch);
			
			var split = ch.size()/2;
			var l1    = new ArrayList<>(ch.subList(0, split));
			var l2    = new ArrayList<>(ch.subList(split, ch.size()));
			
			children.clear();
			children.add(new Node(calcRange(l1), Iters.from(l1).map(Node::new).toModList(), null));
			children.add(new Node(calcRange(l2), Iters.from(l2).map(Node::new).toModList(), null));
			for(Node child : children){
				child.relax();
			}
		}
		
		private Range calcRange(ArrayList<Message> l1){
			return new Range(
				Iters.from(l1).map(Message::range).mapToLong(Range::from).min(0),
				Iters.from(l1).map(Message::range).mapToLong(Range::to).max(0)
			);
		}
		
		public int collect(long p, List<Message> result){
			if(range.from()>p || range.to()<=p) return 1;
			if(val != null){
				result.add(val);
			}
			var count = 0;
			for(var child : children){
				count += child.collect(p, result);
			}
			return count;
		}
		
		private String toTreeString(){
			return toTreeString(0);
		}
		private String toTreeString(int indent){
			StringBuilder sb = new StringBuilder();
			sb.append("  ".repeat(indent))
			  .append(val == null? "" : val.message)
			  .append(" [")
			  .append(range)
			  .append("]")
			  .append('\n');
			for(var child : children){
				sb.append(child.toTreeString(indent + 1));
			}
			return sb.toString();
		}
	}
	
	public record Message(String message, Range range, HoverEffect hoverEffect) implements Comparable<Message>{
		public Message{
			Objects.requireNonNull(message);
			Objects.requireNonNull(range);
			Objects.requireNonNull(hoverEffect);
		}
		@Override
		public int compareTo(Message o){
			return Long.compare(range.from(), o.range().from());
		}
	}
	
	private final Node root = new Node(new Range(Long.MIN_VALUE, Long.MAX_VALUE), null);
	
	public void add(String message, Range range){
		add(message, range, HoverEffect.NONE);
	}
	public void add(String message, Range range, HoverEffect hoverEffect){
		var msg = new Message(message, range, hoverEffect);
		if(!root.add(new Node(range, msg))){
			throw new ShouldNeverHappenError("Must be able to add to root");
		}
	}
	
	public List<Message> collect(long hoverIndex){
		var result = new ArrayList<Message>();
		var c      = root.collect(hoverIndex, result);
//		LogUtil.println(root.toTreeString());
//		LogUtil.println(c);
		return result;
	}
}
