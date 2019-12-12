package com.lapissea.fsf;

import java.io.IOException;

public class LongVal extends FileObject implements Comparable<LongVal>{
	
	public long value;
	
	public LongVal(){
	}
	
	@Override
	public void read(ContentInputStream dest) throws IOException{
		value=dest.readLong();
	}
	
	public LongVal(long value){
		this.value=value;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		dest.writeLong(value);
	}
	
	@Override
	public int length(){
		return Long.BYTES;
	}
	
	@Override
	public int compareTo(LongVal o){
		return Long.compare(value, o.value);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof LongVal)) return false;
		LongVal longVal=(LongVal)o;
		return value==longVal.value;
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(value);
	}
}
