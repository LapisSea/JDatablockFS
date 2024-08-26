package com.lapissea.dfs.type.string;

import com.lapissea.dfs.exceptions.MalformedToStringFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ToStringFormat{
	
	private record Range(int from, int to){
		String substring(String src){
			return src.substring(from, to);
		}
		boolean isEmpty(){
			return from == to;
		}
	}
	
	public static ToStringFragment parse(String format, Set<String> names) throws MalformedToStringFormat{
		List<ToStringFragment> roots = new ArrayList<>();
		
		StringBuilder buff = new StringBuilder();
		Runnable flushBuff = () -> {
			if(buff.isEmpty()) return;
			if(!roots.isEmpty()){
				if(roots.getLast() instanceof ToStringFragment.Literal lit){
					roots.set(roots.size() - 1, new ToStringFragment.Literal(lit.value() + buff));
					buff.setLength(0);
					return;
				}
			}
			roots.add(new ToStringFragment.Literal(buff.toString()));
			buff.setLength(0);
		};
		
		for(int i = 0, l = format.length(); i<l; i++){
			char c = format.charAt(i);
			
			if(c == '!' && !buff.isEmpty()){
				var lm1  = buff.length() - 1;
				var last = buff.charAt(lm1);
				if(last == '!'){
					buff.setLength(lm1);
					flushBuff.run();
					
					var vFrag = readSpecial(format, i + 1);
					
					i += vFrag.value().name.length();
					roots.add(vFrag);
					
					continue;
				}
			}
			if(c == '@'){
				Range rangeOrg = scanWord(format, i + 1);
				
				if(rangeOrg.from == rangeOrg.to){
					buff.append(c);
					continue;
				}
				
				flushBuff.run();
				var                         range = rangeOrg;
				ToStringFragment.FieldValue val;
				while(true){
					var name = range.substring(format);
					if(names.contains(name)){
						val = new ToStringFragment.FieldValue(name);
						break;
					}
					range = new Range(range.from, range.to - 1);
					if(range.isEmpty()){
						throw new MalformedToStringFormat(
							"Invalid toString format! " +
							"Reason: \"" + rangeOrg.substring(format) + "\" is an unknown name" +
							errRange(format, rangeOrg)
						);
					}
				}
				
				i += val.name().length();
				roots.add(val);
				continue;
			}
			if(c == '['){
				flushBuff.run();
				var blockRange = scanBlockRange(format, i + 1);
				try{
					var optData = parse(blockRange.substring(format), names);
					roots.add(new ToStringFragment.OptionalBlock(optData));
				}catch(Throwable e){
					throw new MalformedToStringFormat(
						"Could not parse optional block" +
						errRange(format, blockRange),
						e
					);
				}
				i = blockRange.to;
				continue;
			}
			
			buff.append(c);
		}
		
		flushBuff.run();
		
		return switch(roots.size()){
			case 0 -> new ToStringFragment.NOOP();
			case 1 -> roots.getFirst();
			default -> new ToStringFragment.Concat(List.copyOf(roots));
		};
	}
	
	private static Range scanBlockRange(String format, int start){
		for(int i = start, l = format.length(); i<l; i++){
			var c = format.charAt(i);
			if(c == ']'){
				return new Range(start, i);
			}
		}
		throw new MalformedToStringFormat(
			"illegal string format: " + format + " Opened [ but was not closed" +
			errRange(format, new Range(start, start + 1))
		);
	}
	private static ToStringFragment.SpecialValue readSpecial(String format, int pos){
		var range = scanWord(format, pos);
		while(true){
			var trimmed = format.substring(range.from, range.to);
			var special = ToStringFragment.SpecialValue.of(trimmed);
			if(special.isPresent()){
				return special.get();
			}
			
			range = new Range(range.from, range.to - 1);
			if(range.isEmpty()){
				throw new MalformedToStringFormat("Unrecognised special value");
			}
		}
	}
	private static Range scanWord(String format, int pos){
		for(int i = pos, l = format.length(); i<l; i++){
			var c = format.charAt(i);
			if(Character.isWhitespace(c)){
				return new Range(pos, i);
			}
		}
		return new Range(pos, format.length());
	}
	
	private static String errRange(String format, Range range){
		return "\n\t" + format + "\n\t" + " ".repeat(range.from) + "^".repeat(range.to - range.from);
	}
}
