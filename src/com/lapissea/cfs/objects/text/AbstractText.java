package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.Value;

public abstract class AbstractText extends IOInstance implements CharSequence{
	
	@Value(index=10)
	protected String data;
	
	protected abstract int charCount();
	protected abstract int byteCount();
	
	@Override
	public abstract String toString();
	
	
	@Override
	public int length(){
		return data.length();
	}
	
	@Override
	public CharSequence subSequence(int start, int end){
		return data.subSequence(start, end);
	}
	
	@Override
	public char charAt(int index){
		return data.charAt(index);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof AutoText text)) return false;
		return data.equals(text.data);
	}
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	
	public String getData(){
		return data;
	}
}
