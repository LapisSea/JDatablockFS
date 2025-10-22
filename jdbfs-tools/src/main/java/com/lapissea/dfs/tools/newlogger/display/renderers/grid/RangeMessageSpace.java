package com.lapissea.dfs.tools.newlogger.display.renderers.grid;

import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.util.UtilL;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class RangeMessageSpace{
	
	public sealed interface HoverEffect{
		record None() implements HoverEffect{
			static final None INSTANCE = new None();
		}
		
		record Outline(Color color, float lineWidth) implements HoverEffect{ }
		
	}
	
	public record Message(String message, DrawUtils.Range range, HoverEffect hoverEffect, List<Message> children) implements Comparable<Message>{
		private boolean add(Message message){
			if(!message.range.isWithin(range)){
				return false;
			}
			for(Message child : children){
				if(child.add(message)){
					return true;
				}
			}
			children.removeIf(message::add);
			children.add(message);
			return true;
		}
		
		private String toTreeString(){
			return toTreeString(0);
		}
		
		private String toTreeString(int indent){
			StringBuilder sb = new StringBuilder();
			sb.append("  ".repeat(indent))
			  .append(message)
			  .append(" [")
			  .append(range)
			  .append("]")
			  .append('\n');
			for(Message child : children){
				sb.append(child.toTreeString(indent + 1));
			}
			return sb.toString();
		}
		@Override
		public int compareTo(Message o){
			return Long.compare(range.from(), o.range().from());
		}
		
		public void collect(long p, List<Message> result){
			if(range.from()>p || range.to()<p) return;
			result.add(this);
			for(Message child : children){
				child.collect(p, result);
			}
		}
	}
	
	private final List<Message> roots = new ArrayList<>();
	
	public void add(String message, DrawUtils.Range range){
		add(message, range, HoverEffect.None.INSTANCE);
	}
	public void add(String message, DrawUtils.Range range, HoverEffect hoverEffect){
		var msg = new Message(message, range, hoverEffect, new ArrayList<>());
		
		for(Message root : roots){
			if(root.add(msg)){
				return;
			}
		}
		roots.removeIf(msg::add);
		UtilL.addRemainSorted(roots, msg);
	}
	
	public List<Message> collect(long hoverIndex){
		var result = new ArrayList<Message>();
		for(Message root : roots){
			root.collect(hoverIndex, result);
		}
		return result;
	}
}
