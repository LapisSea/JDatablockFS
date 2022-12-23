package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.exceptions.MalformedToStringFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ToStringFormat{
	
	public sealed interface ToStringFragment{
		record NOOP() implements ToStringFragment{ }
		
		record Literal(String value) implements ToStringFragment{ }
		
		record Concat(List<ToStringFragment> fragments) implements ToStringFragment{ }
		
		record FieldValue(String name) implements ToStringFragment{ }
		
		record SpecialValue(Value value) implements ToStringFragment{
			SpecialValue(String name){
				this(Arrays.stream(Value.values()).filter(v -> v.name.equalsIgnoreCase(name)).findAny().orElseThrow());
			}
			
			public enum Value{
				CLASS_NAME("className");
				
				public final String name;
				Value(String name){
					this.name = name;
				}
			}
			
		}
		
		record OptionalBlock(ToStringFragment content) implements ToStringFragment{ }
		
		default Stream<ToStringFragment> deep(){
			return switch(this){
				case Concat c -> {
					yield c.fragments.stream().flatMap(ToStringFragment::deep);
				}
				case OptionalBlock o -> {
					yield o.content.deep();
				}
				default -> Stream.of(this);
			};
		}
	}
	
	private record Range(int from, int to){ }
	
	public static ToStringFragment parse(String format, List<String> names){
		List<ToStringFragment> roots = new ArrayList<>();
		
		StringBuilder buff = new StringBuilder();
		Runnable flushBuff = () -> {
			if(buff.length() == 0) return;
			roots.add(new ToStringFragment.Literal(buff.toString()));
			buff.setLength(0);
		};
		
		for(int i = 0; i<format.length(); i++){
			char c = format.charAt(i);
			
			if(c == '!' && buff.length()>=1){
				var last = buff.charAt(buff.length() - 1);
				if(last == '!'){
					buff.setLength(buff.length() - 1);
					flushBuff.run();
					Range            range = scanWord(format, i);
					ToStringFragment val;
					
					IllegalArgumentException org = null;
					while(true){
						try{
							var trimmed = format.substring(range.from, range.to);
							val = new ToStringFragment.SpecialValue(trimmed);
							break;
						}catch(IllegalArgumentException e){
							if(org == null) org = e;
							if(range.from == range.to) throw new MalformedToStringFormat("Unrecognised special value", org);
						}
						range = new Range(range.from, range.to - 1);
					}
					
					i = range.to - 1;
					roots.add(val);
					
					continue;
				}
			}
			if(c == '@'){
				
				Range rangeOrg = scanWord(format, i), range = rangeOrg;
				
				if(range.from == range.to){
					buff.append(c);
					continue;
				}
				
				flushBuff.run();
				
				ToStringFragment val;
				while(true){
					if(range.from == range.to) throw new MalformedToStringFormat("Invalid toString format! Reason: " + format.substring(rangeOrg.from, rangeOrg.to) + " is an unknown name");
					var name = format.substring(range.from, range.to);
					if(names.contains(name)){
						val = new ToStringFragment.FieldValue(name);
						break;
					}
					range = new Range(range.from, range.to - 1);
				}
				
				i = range.to - 1;
				roots.add(val);
				continue;
			}
			if(c == '['){
				flushBuff.run();
				final int from = i + 1, to;
				findTo:
				{
					for(int j = from; j<format.length(); j++){
						c = format.charAt(j);
						if(c == ']'){
							i = to = j;
							break findTo;
						}
					}
					throw new MalformedToStringFormat("illegal string format: " + format + " Opened [ but was not closed");
				}
				
				roots.add(new ToStringFragment.OptionalBlock(parse(format.substring(from, to), names)));
				continue;
			}
			
			buff.append(c);
		}
		
		flushBuff.run();
		
		return switch(roots.size()){
			case 0 -> new ToStringFragment.NOOP();
			case 1 -> roots.get(0);
			default -> new ToStringFragment.Concat(List.copyOf(roots));
		};
	}
	private static Range scanWord(String format, int i){
		char      c;
		final int from = i + 1, to;
		findTo:
		{
			for(int j = from; j<format.length(); j++){
				c = format.charAt(j);
				if(Character.isWhitespace(c)){
					to = j;
					break findTo;
				}
			}
			to = format.length();
		}
		var range = new Range(from, to);
		return range;
	}
}
