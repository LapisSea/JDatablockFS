package com.lapissea.jorth.lang.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class CharJoin implements CharSequence{
	
	private final List<? extends CharSequence> data;
	private final int                          size;
	
	private CharSequence lastAccessed;
	private int          lastStart, lastEnd;
	
	public CharJoin(CharSequence... data){
		this(Arrays.asList(data));
	}
	public CharJoin(List<? extends CharSequence> data){
		this.data = data;
		int sum = 0;
		for(var part : data){
			sum += part.length();
		}
		this.size = sum;
		
		if(!data.isEmpty()){
			lastAccessed = data.getFirst();
			lastEnd = lastAccessed.length();
		}
	}
	
	@Override
	public int length(){
		return size;
	}
	@Override
	public char charAt(int index){
		if(lastStart>index || lastEnd<=index){
			calc(index);
		}
		return lastAccessed.charAt(index - lastStart);
	}
	private void calc(int index){
		int off = 0;
		for(var datum : data){
			int start = off;
			int size  = datum.length();
			int end   = start + size;
			
			if(start<=index && end>index){
				lastAccessed = datum;
				lastStart = start;
				lastEnd = end;
				return;
			}
			
			off += size;
		}
		throw new IndexOutOfBoundsException(index + "");
	}
	
	@Override
	public CharSequence subSequence(int start, int end){
		Objects.checkFromToIndex(start, end, length());
		
		int off    = 0;
		var result = new ArrayList<CharSequence>();
		for(var part : data){
			int startPart = off;
			int size      = part.length();
			int endPart   = startPart + size;
			
			if(endPart<start){
				off += size;
				continue;
			}
			if(startPart>=end) break;
			
			int startOff = start - startPart;
			int endOff   = size + end - endPart;
			if(startOff == 0 && endOff == size) return part;
			
			result.add(CharSubview.of(part, Math.max(startOff, 0), Math.min(endOff, size)));
			
			off += size;
		}
		if(result.size() == 1) return result.getFirst();
		return new CharJoin(result);
	}
	
	@Override
	public IntStream chars(){
		return data.stream().flatMapToInt(CharSequence::chars);
	}
	
	@Override
	public String toString(){
		var sb = new StringBuilder(length());
		data.forEach(sb::append);
		return sb.toString();
	}
}
