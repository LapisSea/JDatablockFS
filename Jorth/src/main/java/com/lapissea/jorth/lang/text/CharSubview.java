package com.lapissea.jorth.lang.text;

import java.util.Objects;
import java.util.stream.IntStream;

public class CharSubview implements CharSequence{
	
	public static CharSequence of(CharSequence data, int start, int end){
		if(start == end) return "";
		if(start == 0 && end == data.length()) return data;
		if(data instanceof CharSubview sub) return sub.subSequence(start, end);
		return new CharSubview(data, start, end);
	}
	
	private final CharSequence parent;
	private final int          start;
	private final int          end;
	
	private String strCache;
	
	public CharSubview(CharSequence parent, int start, int end){
		Objects.checkFromToIndex(start, end, parent.length());
		this.parent = parent;
		this.start = start;
		this.end = end;
	}
	
	@Override
	public int length(){
		return end - start;
	}
	
	@Override
	public char charAt(int index){
		return parent.charAt(index + start);
	}
	
	@Override
	public CharSequence subSequence(int start, int end){
		if(start == 0 && end == length()) return this;
		return new CharSubview(parent, this.start + start, this.start + end);
	}
	
	@Override
	public IntStream chars(){
		return parent.chars().skip(start).limit(length());
	}
	
	@Override
	public String toString(){
		if(strCache == null) strCache = parent.subSequence(start, end).toString();
		return strCache;
	}
}
