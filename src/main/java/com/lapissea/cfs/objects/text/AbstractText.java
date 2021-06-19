package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public abstract class AbstractText<SELF extends AbstractText<SELF>> extends IOInstance<SELF> implements CharSequence{
	
	protected String data;
	
	@IOValue
	private byte[] getTextBytes(){
		return null;
	}
	@IOValue
	private void setTextBytes(byte[] data){
	}
	
	protected abstract int charCount();
	
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
