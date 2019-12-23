package com.lapissea.fsf;

import java.io.IOException;

public class LongFileBacked extends FileObject implements Comparable<LongFileBacked>{
	
	public long value;
	
	public LongFileBacked(){
		this(-1);
	}
	
	@Override
	public void read(ContentInputStream dest) throws IOException{
		value=dest.readLong();
	}
	
	public LongFileBacked(long value){
		this.value=value;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		dest.writeLong(value);
	}
	
	@Override
	public long length(){
		return Long.BYTES;
	}
	
	@Override
	public int compareTo(LongFileBacked o){
		return Long.compare(value, o.value);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof LongFileBacked)) return false;
		var other=(LongFileBacked)o;
		return value==other.value;
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(value);
	}
	
	@Override
	public String toString(){
		return Long.toString(value);
	}
}
