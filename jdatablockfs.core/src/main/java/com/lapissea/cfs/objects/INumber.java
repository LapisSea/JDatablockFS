package com.lapissea.cfs.objects;

public interface INumber{
	
	interface Mutable extends INumber{
		void setValue(long value);
		
		default void setValue(INumber value){
			setValue(value.getValue());
		}
	}
	
	default int getValueInt(){
		return Math.toIntExact(getValue());
	}
	
	long getValue();
	
	default boolean equals(long value){
		return getValue()==value;
	}
	
	default int compareTo(long o){
		return Long.compare(getValue(), o);
	}
	
}
